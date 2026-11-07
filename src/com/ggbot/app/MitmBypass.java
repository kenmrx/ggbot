package com.ggbot.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 解密失败者的「自动直通」名单。
 *
 * 证书固定（certificate pinning）的站点，再怎么签证书也过不了它自己的校验，
 * 客户端会在握手阶段直接断开。这类连接如果一直解密，用户看到的是应用白屏、
 * 加载失败，而且换一张证书、重装 CA 都没用——因为问题不在我们签得对不对。
 *
 * 早先的做法是让用户自己去解密名单里把域名删掉，问题是：用户根本不知道
 * 刚才那个白屏的应用连了哪个域名。所以这里做成自动的——
 *
 *   同一域名在十分钟内失败两次 → 暂停解密十分钟，DNS 直接回真实地址
 *   十分钟后自动重新尝试；如果站点升级了、证书不再固定，就恢复解密
 *
 * 阈值取两次而不是一次：单次失败很可能是网络抖动、或者应用自己主动放弃，
 * 因为一次抖动就永久放弃解密，等于白白放走十分钟的广告。
 */
public final class MitmBypass {

    /** 连续失败多少次才判定为「解不开」。 */
    static final int THRESHOLD = 2;
    /** 失败计数的有效窗口：超过这个时间的旧失败不算数。 */
    static final long WINDOW_MS = 10 * 60 * 1000L;
    /** 直通持续时间，到期后自动重试。 */
    static final long BYPASS_MS = 10 * 60 * 1000L;
    /** 名单上限，防止长时间运行后无限增长。 */
    private static final int MAX_HOSTS = 256;

    static final class Entry {
        int fails;
        long lastFail;
        long until;
    }

    private static final Map<String, Entry> map = new HashMap<>();

    /** 测试注入用的假时钟；0 表示走真实时间。 */
    public static volatile long clock;

    static long now() {
        return clock != 0 ? clock : System.currentTimeMillis();
    }

    private MitmBypass() {
    }

    /** 记录一次客户端侧握手失败。 */
    public static synchronized void failed(String host) {
        if (host == null || host.isEmpty()) return;
        final long t = now();
        Entry e = map.get(host);
        if (e == null) {
            if (map.size() >= MAX_HOSTS) prune(t);
            e = new Entry();
            map.put(host, e);
        }
        if (t - e.lastFail > WINDOW_MS) e.fails = 0;
        e.fails++;
        e.lastFail = t;
        if (e.fails >= THRESHOLD) {
            e.until = t + BYPASS_MS;
            e.fails = 0;
            Diag.push(host + " · 证书校验未通过，暂停解密十分钟", "mitm");
        }
    }

    /** 解密成功一次就把计数清掉，说明这个站点其实能解。 */
    public static synchronized void ok(String host) {
        if (host == null) return;
        Entry e = map.get(host);
        if (e == null) return;
        if (e.until > now()) return;   // 直通期内不解密，谈不上成功
        map.remove(host);
    }

    /** 当前是否应当跳过解密。 */
    public static synchronized boolean bypassed(String host) {
        if (host == null || host.isEmpty()) return false;
        Entry e = map.get(host);
        if (e == null) return false;
        final long t = now();
        if (e.until > t) return true;
        if (e.until != 0) {
            // 直通到期：清掉记录，让下一次连接重新尝试解密
            map.remove(host);
        }
        return false;
    }

    /** 正在直通中的域名，界面用。 */
    public static synchronized List<String> active() {
        final long t = now();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Entry> en : map.entrySet()) {
            if (en.getValue().until > t) out.add(en.getKey());
        }
        java.util.Collections.sort(out);
        return out;
    }

    public static synchronized int size() {
        return active().size();
    }

    public static synchronized void clear() {
        map.clear();
    }

    /** 清掉过期条目与已无意义的失败计数。 */
    private static void prune(long t) {
        List<String> drop = new ArrayList<>();
        for (Map.Entry<String, Entry> en : map.entrySet()) {
            Entry e = en.getValue();
            boolean expired = e.until != 0 && e.until <= t;
            boolean stale = e.until == 0 && t - e.lastFail > WINDOW_MS;
            if (expired || stale) drop.add(en.getKey());
        }
        for (String k : drop) map.remove(k);
    }
}

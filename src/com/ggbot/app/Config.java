package com.ggbot.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 本机配置：上游 DNS、拦截方式、自定义规则、白名单、排除应用、开机自启。
 * 全部存在应用私有的 SharedPreferences 里，不上传任何服务器。
 */
public final class Config {

    private static final String FILE = "ggbot";

    public static final int MODE_NXDOMAIN = 0;
    public static final int MODE_NULL = 1;

    public static final String[] UPSTREAMS = {
            "223.5.5.5", "119.29.29.29", "114.114.114.114", "1.1.1.1", "8.8.8.8"
    };
    public static final String[] UPSTREAM_LABELS = {
            "223.5.5.5 · 阿里 DNS",
            "119.29.29.29 · 腾讯 DNS",
            "114.114.114.114 · 114 DNS",
            "1.1.1.1 · Cloudflare",
            "8.8.8.8 · Google"
    };

    /** DoH 上游端点：全部按 IP 直连（引导做法参考 PersonalDNSFilter 的 bootstrapDNS，
     *  端点域名如果还要解析一次 DNS，就又绕回隧道自己了）。 */
    public static final String[] DOH_URLS = {
            "https://223.5.5.5/dns-query",
            "https://120.53.53.53/dns-query",
            "https://1.1.1.1/dns-query",
            "https://8.8.8.8/dns-query"
    };
    public static final String[] DOH_LABELS = {
            "223.5.5.5 · 阿里 DoH",
            "120.53.53.53 · 腾讯 DoH",
            "1.1.1.1 · Cloudflare DoH",
            "8.8.8.8 · Google DoH"
    };

    private static volatile String upstream = UPSTREAMS[0];
    private static volatile int mode = MODE_NXDOMAIN;
    private static volatile boolean doh = false;
    private static volatile String dohUrl = DOH_URLS[0];

    private Config() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 供 Feeds 读写自己的元数据，共用同一个配置文件。 */
    static SharedPreferences prefs(Context c) {
        return sp(c);
    }

    public static void init(Context c) {
        SharedPreferences p = sp(c);
        upstream = p.getString("upstream", UPSTREAMS[0]);
        mode = p.getInt("mode", MODE_NXDOMAIN);
        doh = p.getBoolean("doh", false);
        dohUrl = p.getString("dohUrl", DOH_URLS[0]);
    }

    public static String upstream() {
        return upstream;
    }

    public static void setUpstream(Context c, String ip) {
        upstream = ip;
        sp(c).edit().putString("upstream", ip).apply();
    }

    public static boolean doh() {
        return doh;
    }

    public static void setDoh(Context c, boolean v) {
        doh = v;
        sp(c).edit().putBoolean("doh", v).apply();
    }

    public static String dohUrl() {
        return dohUrl;
    }

    public static void setDohUrl(Context c, String u) {
        dohUrl = u;
        sp(c).edit().putString("dohUrl", u).apply();
    }

    public static int mode() {
        return mode;
    }

    public static void setMode(Context c, int m) {
        mode = m;
        sp(c).edit().putInt("mode", m).apply();
    }

    public static boolean enabled(Context c) {
        return sp(c).getBoolean("enabled", false);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("enabled", v).apply();
    }

    public static boolean notifAsked(Context c) {
        return prefs(c).getBoolean("notifAsked", false);
    }

    public static void setNotifAsked(Context c, boolean v) {
        prefs(c).edit().putBoolean("notifAsked", v).apply();
    }

    /** 设置页「高级设置」折叠区是否展开。记住上次的选择。 */
    public static boolean advancedOpen(Context c) {
        return prefs(c).getBoolean("advancedOpen", false);
    }

    public static void setAdvancedOpen(Context c, boolean v) {
        prefs(c).edit().putBoolean("advancedOpen", v).apply();
    }

    public static boolean boot(Context c) {
        return sp(c).getBoolean("boot", false);
    }

    /** 暂停过滤的截止时刻（毫秒）；0 表示没有暂停。 */
    public static long pausedUntil(Context c) {
        return sp(c).getLong("pausedUntil", 0L);
    }

    public static void setPausedUntil(Context c, long v) {
        sp(c).edit().putLong("pausedUntil", v).apply();
    }

    public static void setBoot(Context c, boolean v) {
        sp(c).edit().putBoolean("boot", v).apply();
    }

    /* ---------- 域名规则 ---------- */

    public static Set<String> blocks(Context c) {
        return toSet(sp(c).getString("blocks", ""));
    }

    public static Set<String> allow(Context c) {
        return toSet(sp(c).getString("allow", ""));
    }

    public static Set<String> bypass(Context c) {
        return toSet(sp(c).getString("bypass", ""));
    }

    public static int addDomains(Context c, boolean allow, String raw) {
        Set<String> cur = allow ? allow(c) : blocks(c);
        int before = cur.size();
        if (raw != null) {
            String[] parts = raw.split("[\\s,，;；]+");
            for (String p : parts) {
                String d = normalize(p);
                if (d != null) cur.add(d);
            }
        }
        write(c, allow ? "allow" : "blocks", cur);
        return cur.size() - before;
    }

    public static void removeDomain(Context c, boolean allow, String domain) {
        Set<String> cur = allow ? allow(c) : blocks(c);
        cur.remove(domain);
        write(c, allow ? "allow" : "blocks", cur);
    }

    public static void setBypass(Context c, Set<String> pkgs) {
        write(c, "bypass", pkgs);
    }

    private static void write(Context c, String key, Collection<String> v) {
        StringBuilder sb = new StringBuilder();
        for (String s : v) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        sp(c).edit().putString(key, sb.toString()).apply();
    }

    private static Set<String> toSet(String s) {
        HashSet<String> out = new HashSet<>();
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            String d = normalize(line);
            if (d != null) out.add(d);
        }
        return out;
    }

    /* ---------- URL 规则（解密后按路径匹配） ---------- */

    public static Set<String> urlBlocks(Context c) {
        return toUrlSet(sp(c).getString("urlBlocks", ""));
    }

    public static Set<String> urlAllow(Context c) {
        return toUrlSet(sp(c).getString("urlAllow", ""));
    }

    public static int addUrlRules(Context c, boolean allow, String raw) {
        Set<String> cur = allow ? urlAllow(c) : urlBlocks(c);
        int before = cur.size();
        if (raw != null) {
            for (String p : raw.split("[\\s,，;；]+")) {
                String r = UrlRule.normalize(p);
                if (r != null) cur.add(r);
            }
        }
        write(c, allow ? "urlAllow" : "urlBlocks", cur);
        return cur.size() - before;
    }

    public static void removeUrlRule(Context c, boolean allow, String rule) {
        Set<String> cur = allow ? urlAllow(c) : urlBlocks(c);
        cur.remove(rule);
        write(c, allow ? "urlAllow" : "urlBlocks", cur);
    }

    private static Set<String> toUrlSet(String s) {
        HashSet<String> out = new HashSet<>();
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            String r = UrlRule.normalize(line);
            if (r != null) out.add(r);
        }
        return out;
    }

    /* ---------- 解密（HTTPS 中间人） ---------- */

    /** 日志筛选：-1 全部，其余是 QueryLog.ALLOW/BLOCK/DECRYPT。 */
    public static int logFilter(Context c) {
        return sp(c).getInt("logFilter", -1);
    }

    public static void setLogFilter(Context c, int v) {
        sp(c).edit().putInt("logFilter", v).apply();
    }

    /** 重复日志折叠，默认开：同样的域名刷屏时先看频次更有用。 */
    public static boolean foldLogs(Context c) {
        return sp(c).getBoolean("foldLogs", true);
    }

    public static void setFoldLogs(Context c, boolean v) {
        sp(c).edit().putBoolean("foldLogs", v).apply();
    }

    public static boolean mitm(Context c) {
        return sp(c).getBoolean("mitm", false);
    }

    public static void setMitm(Context c, boolean v) {
        sp(c).edit().putBoolean("mitm", v).apply();
    }

    /** 无障碍服务的“上一次已知状态”，用于 ContentObserver 判断是断开还是恢复。 */
    public static boolean a11yLastKnown(Context c) {
        return sp(c).getBoolean("a11yLastKnown", false);
    }

    public static void setA11yLastKnown(Context c, boolean v) {
        sp(c).edit().putBoolean("a11yLastKnown", v).apply();
    }

    /** 省电模式：normal / light / skip，见 Power。 */
    public static String powerMode(Context c) {
        return sp(c).getString("powerMode", "normal");
    }

    public static void setPowerMode(Context c, String mode) {
        sp(c).edit().putString("powerMode", mode).apply();
    }

    public static boolean routeSelf(Context c) {
        return sp(c).getBoolean("routeSelf", false);
    }

    public static boolean debugAll(Context c) {
        return sp(c).getBoolean("debugAll", false);
    }

    public static void setDebugAll(Context c, boolean v) {
        sp(c).edit().putBoolean("debugAll", v).apply();
    }

    public static Set<String> decrypt(Context c) {
        return toSet(sp(c).getString("decrypt", ""));
    }

    public static int addDecrypt(Context c, String raw) {
        Set<String> cur = decrypt(c);
        int before = cur.size();
        if (raw != null) {
            String[] parts = raw.split("[\\s,，;；]+");
            for (String p : parts) {
                String d = normalize(p);
                if (d != null) cur.add(d);
            }
        }
        write(c, "decrypt", cur);
        return cur.size() - before;
    }

    public static void removeDecrypt(Context c, String domain) {
        Set<String> cur = decrypt(c);
        cur.remove(domain);
        write(c, "decrypt", cur);
    }

    /** 清洗用户输入：去协议、去路径、去端口、去通配前缀，转小写并校验字符。 */
    public static String normalize(String raw) {
        return Domains.normalize(raw);
    }

    /* ---------- 规则订阅 ---------- */

    public static boolean feedAuto(Context c) {
        return sp(c).getBoolean("feedAuto", true);
    }

    public static void setFeedAuto(Context c, boolean v) {
        sp(c).edit().putBoolean("feedAuto", v).apply();
    }

    public static long feedChecked(Context c) {
        return sp(c).getLong("feedChecked", 0L);
    }

    public static void setFeedChecked(Context c, long v) {
        sp(c).edit().putLong("feedChecked", v).apply();
    }

    /* ---------- 内容重写 ---------- */

    public static boolean rewrite(Context c) {
        return sp(c).getBoolean("rewrite", false);
    }

    public static void setRewrite(Context c, boolean v) {
        sp(c).edit().putBoolean("rewrite", v).apply();
    }

    /** 重写规则原文，每行：域名|查找|替换 */
    public static String rewriteRules(Context c) {
        return sp(c).getString("rewriteRules", "");
    }

    public static void setRewriteRules(Context c, String v) {
        sp(c).edit().putString("rewriteRules", v == null ? "" : v).apply();
    }
}

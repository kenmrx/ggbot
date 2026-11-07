package com.ggbot.app;

import java.util.Arrays;
import java.util.Collection;

/**
 * 域名里带通配的规则，例如 AdBlock 的 ||ac*.786ip.com^。
 *
 * 这类规则没法塞进后缀哈希，但数量很少（二十多万条里只有一千多条），
 * 所以按顺序扫一遍即可，代价是每条先做一次 contains 预筛，命中率低时几乎不费时间。
 * 语义和 AdBlock 一致：||a*.b.com^ 同时管住 b.com 的所有可能子域写法。
 */
public final class Wild {

    public static final Wild EMPTY = new Wild(new String[0]);

    private final String[] pat;
    private final String[] tail;

    private Wild(String[] p) {
        pat = p;
        tail = new String[p.length];
        for (int i = 0; i < p.length; i++) {
            // 预筛用的字面尾巴：取最后一个通配符（* 或 ?）之后的部分
            int cut = Math.max(p[i].lastIndexOf('*'), p[i].lastIndexOf('?'));
            tail[i] = cut < 0 ? p[i] : p[i].substring(cut + 1);
        }
    }

    public static Wild of(Collection<String> src) {
        if (src == null || src.isEmpty()) return EMPTY;
        String[] a = src.toArray(new String[0]);
        Arrays.sort(a);
        return new Wild(a);
    }

    public int size() {
        return pat.length;
    }

    /** 返回命中的那条模式，没命中返回 null。 */
    public String match(String host) {
        if (pat.length == 0 || host == null || host.isEmpty()) return null;
        for (int i = 0; i < pat.length; i++) {
            String t = tail[i];
            // 预筛：主机名里没有这段字面量，就一定不可能匹配
            if (!t.isEmpty() && host.indexOf(t) < 0) continue;
            if (hit(pat[i], host)) return pat[i];
        }
        return null;
    }

    public boolean matches(String host) {
        return match(host) != null;
    }

    /** 规则里的域名视为「该域及其子域」，逐级后缀都比一次。 */
    private static boolean hit(String p, String host) {
        if (UrlRule.wildcard(p, host)) return true;
        int idx = host.indexOf('.');
        while (idx >= 0) {
            int next = idx + 1;
            if (next >= host.length()) return false;
            if (UrlRule.wildcard(p, host.substring(next))) return true;
            idx = host.indexOf('.', next);
        }
        return false;
    }
}

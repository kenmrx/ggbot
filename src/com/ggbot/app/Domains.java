package com.ggbot.app;

import java.util.Locale;
import java.util.Set;

/**
 * 域名归一化与规则匹配（纯 Java，可单独测试）。
 * 匹配语义：精确命中，或某级后缀命中（a.b.ads.com 命中 ads.com）。
 */
public final class Domains {

    private Domains() {
    }

    /** 清洗输入：去协议/路径/端口/通配前缀，转小写并校验字符；非法返回 null。 */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.US);
        if (s.isEmpty() || s.startsWith("#")) return null;
        if (s.equals("0.0.0.0") || s.equals("127.0.0.1") || s.equals("::")) return null;
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        if (s.startsWith("*.")) s = s.substring(2);
        while (s.startsWith(".")) s = s.substring(1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty() || s.length() > 253) return null;
        int dot = s.indexOf('.');
        if (dot <= 0 || dot == s.length() - 1) return null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '.' || ch == '-' || ch == '_';
            if (!ok) return null;
        }
        return s;
    }

    public static boolean matches(Set<String> set, String host) {
        if (set == null || set.isEmpty() || host == null || host.isEmpty()) return false;
        if (set.contains(host)) return true;
        int idx = host.indexOf('.');
        while (idx >= 0) {
            int next = idx + 1;
            if (next >= host.length()) return false;
            if (set.contains(host.substring(next))) return true;
            idx = host.indexOf('.', next);
        }
        return false;
    }
}

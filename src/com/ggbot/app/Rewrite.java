package com.ggbot.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 响应正文重写规则。
 *
 * 每行一条：域名|查找|替换。域名精确或后缀匹配（a.com 覆盖其子域），
 * 查找/替换按字面量处理，替换留空即删除。只作用于 MITM 解密命中的响应。
 */
public final class Rewrite {

    public static final class Rule {
        public final String domain;
        public final String find;
        public final String replace;

        Rule(String domain, String find, String replace) {
            this.domain = domain;
            this.find = find;
            this.replace = replace;
        }
    }

    private Rewrite() {
    }

    /** 解析规则文本；空行与 # 注释忽略。 */
    public static List<Rule> parse(String raw) {
        List<Rule> out = new ArrayList<>();
        if (raw == null) return out;
        for (String line : raw.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int i = t.indexOf('|');
            if (i <= 0) continue;
            int j = t.indexOf('|', i + 1);
            if (j < 0) continue;
            String domain = t.substring(0, i).trim().toLowerCase(Locale.US);
            if (domain.isEmpty()) continue;
            out.add(new Rule(domain, t.substring(i + 1, j), t.substring(j + 1)));
        }
        return out;
    }

    /** 域名匹配：精确，或 host 以 .domain 结尾。 */
    public static boolean matches(String domain, String host) {
        if (domain == null || host == null) return false;
        String h = host.toLowerCase(Locale.US);
        return h.equals(domain) || h.endsWith("." + domain);
    }

    /** 该域名是否有规则命中。 */
    public static boolean applies(List<Rule> rules, String host) {
        for (Rule r : rules) {
            if (matches(r.domain, host)) return true;
        }
        return false;
    }

    /**
     * 按规则改写字节体（ISO-8859-1 逐字节往返，不破坏二进制内容）。
     * 返回 null 表示没有任何改动。
     */
    public static byte[] apply(List<Rule> rules, String host, byte[] body) {
        if (rules == null || rules.isEmpty() || body == null || body.length == 0) return null;
        String s = new String(body, java.nio.charset.StandardCharsets.ISO_8859_1);
        String out = null;
        for (Rule r : rules) {
            if (!matches(r.domain, host) || r.find.isEmpty()) continue;
            if (!s.contains(r.find)) continue;
            s = s.replace(r.find, r.replace);
            out = s;
        }
        if (out == null) return null;
        return out.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }
}

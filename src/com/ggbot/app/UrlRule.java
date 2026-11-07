package com.ggbot.app;

import java.util.Locale;

/**
 * URL 级规则：Jinx 那种「域名 + 路径 + 通配符」的写法。
 *
 * 支持的形态：
 *   example.com                 只写域名，等价于整站匹配（含子域）
 *   example.com/ad/*            路径通配，* 可跨越 / 
 *   *.example.com/ads/*         域名里也能用 *
 *   /cpro/                      只有路径片段，任意域名
 *   example.com/a?c=1           支持 ? 匹配单个字符
 *
 * 规则统一按 host+path 的小写串去比，所以写规则时不用关心大小写。
 */
public final class UrlRule {

    private UrlRule() {
    }

    /** 清洗用户输入的规则：去协议头、去空白、转小写。 */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.US);
        if (s.isEmpty()) return null;
        if (s.startsWith("http://")) s = s.substring(7);
        else if (s.startsWith("https://")) s = s.substring(8);
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        // 只把「纯域名 + 结尾斜杠」的斜杠去掉；example.com/ad/ 里的斜杠是有意义的
        if (s.length() > 1 && s.endsWith("/") && s.indexOf('/') == s.length() - 1) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty()) return null;
        // 必须含字母或数字，纯符号的规则一律丢弃（^ 允许出现：尾部 ^ 是入库的锚点标记）
        if (!s.matches("[a-z0-9.*?\\-_:/~%&=+,;!$'()\\[\\]\\^]+")) return null;
        boolean hasAlnum = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                hasAlnum = true;
                break;
            }
        }
        return hasAlnum ? s : null;
    }

    /**
     * 判断一条规则是否命中某次请求。
     *
     * @param rule 已 normalize 过的规则
     * @param host 小写主机名
     * @param path 请求路径（含查询串）
     */
    public static boolean matches(String rule, String host, String path) {
        if (rule == null || host == null) return false;

        int slash = rule.indexOf('/');
        if (slash < 0) {
            // 纯域名规则：既接受整站，也接受带子域
            String d = rule;
            String h = host.toLowerCase(Locale.US);
            if (d.contains("*") || d.contains("?")) return wildcard(d, h);
            return h.equals(d) || h.endsWith("." + d);
        }

        String domainPart = rule.substring(0, slash);
        String pathPart = rule.substring(slash);
        String h = host.toLowerCase(Locale.US);
        String p = path == null || path.isEmpty() ? "/" : path.toLowerCase(Locale.US);

        if (!domainPart.isEmpty() && !domainPart.equals("*")) {
            boolean site;
            if (domainPart.contains("*") || domainPart.contains("?")) {
                site = wildcard(domainPart, h);
            } else {
                site = h.equals(domainPart) || h.endsWith("." + domainPart);
            }
            if (!site) return false;
        }
        if (pathPart.equals("/")) return true;
        // 尾部 ^ 是 AdBlock 的「分隔符或串尾」断言（ListParser 入库时保留在这里）
        boolean caret = pathPart.endsWith("^");
        if (caret) pathPart = pathPart.substring(0, pathPart.length() - 1);
        if (pathPart.isEmpty() || pathPart.equals("/")) return true;
        boolean hasStar = pathPart.indexOf('*') >= 0;
        // 以 / 结尾的路径规则表示「这一层以及它下面的全部」
        if (pathPart.endsWith("/")) {
            boolean hit = p.startsWith(pathPart);
            return caret ? hit && sepFollows(p, pathPart.length()) : hit;
        }
        if (hasStar) return wildcard(pathPart, p);
        if (caret) {
            // ||ad.com/ads^：前缀命中，且后面要么结束要么是分隔符（/ads?w=300 命中，
            // /adsense 不命中——那是另一条路径）
            return p.startsWith(pathPart) && sepFollows(p, pathPart.length());
        }
        // 无 ^ 无 *：adblock 语义是子串命中——||cnt.com/px.gif?zone= 要能命中
        // /px.gif?zone=123&x=1。早先这里是精确匹配，路径带查询串/前缀就系统性漏拦。
        // 本项目方言里 ? 兼作单字符通配（见上），两种命中任一成立即命中：
        // 字面 ? 写法的追踪像素规则（子串）与通配写法（全匹配）都要活着
        if (pathPart.indexOf('?') >= 0) {
            return wildcard(pathPart, p) || p.contains(pathPart);
        }
        return p.contains(pathPart);
    }

    /** adblock 的 ^：命中位置之后是串尾或分隔符（字母数字 _ - . 之外的任意字符）。 */
    static boolean sepFollows(String p, int pos) {
        if (pos >= p.length()) return true;
        char c = p.charAt(pos);
        boolean word = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.';
        return !word;
    }

    /** 取规则里的域名部分；纯路径规则或通配域名返回 null。 */
    public static String domainOf(String rule) {
        if (rule == null) return null;
        int slash = rule.indexOf('/');
        String d = slash < 0 ? rule : rule.substring(0, slash);
        if (d.isEmpty() || d.equals("*")) return null;
        if (d.startsWith("*.")) d = d.substring(2);
        if (d.contains("*") || d.contains("?")) return null;
        return d;
    }

    /** 经典的通配回溯匹配，* 匹配任意长度（含空），? 匹配单字符。 */
    public static boolean wildcard(String pat, String s) {
        int p = 0;
        int i = 0;
        int star = -1;
        int mark = 0;
        int pn = pat.length();
        int sn = s.length();
        while (i < sn) {
            if (p < pn && (pat.charAt(p) == '?' || pat.charAt(p) == s.charAt(i))) {
                p++;
                i++;
            } else if (p < pn && pat.charAt(p) == '*') {
                star = p++;
                mark = i;
            } else if (star >= 0) {
                p = star + 1;
                i = ++mark;
            } else {
                return false;
            }
        }
        while (p < pn && pat.charAt(p) == '*') p++;
        return p == pn;
    }
}

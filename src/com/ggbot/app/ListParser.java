package com.ggbot.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 订阅源解析器。真实规则源里混着五六种语法，这里统一拆成五类结果：
 *
 *   hosts 文件        0.0.0.0 ads.example.com / 127.0.0.1 x.com
 *   纯域名列表        ads.example.com（一行一个，国内源最爱用）
 *   AdBlock          ||ads.example.com^ / ||x.com/ad/* / $third-party 这类选项
 *   AdBlock 例外     @@||ad.azure.com^ 反向规则，必须尊重，否则会把人家特意放行的站点封掉
 *   dnsmasq          address=/ads.example.com/0.0.0.0
 *
 * 解析不了的（元素隐藏 ##、正则 /.../、按 IP 封禁）直接丢弃并计数，不猜。
 */
public final class ListParser {

    /** 单源上限，防止有人塞个几百万行把手机搞崩。 */
    private static final int MAX_RULES = 400_000;

    /** 公共后缀：单独封掉等于封掉整个国家的站点，一律拒绝。 */
    private static final Set<String> PUBLIC_SUFFIX = new HashSet<>();

    /** 主机文件里的本机名与占位名，不是广告域。 */
    private static final Set<String> JUNK = new HashSet<>();

    static {
        String[] ps = {
                "com", "net", "org", "cn", "io", "co", "cc", "tv", "me", "info",
                "co.uk", "org.uk", "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
                "com.hk", "com.tw", "com.sg", "com.au", "co.jp", "co.kr", "com.br",
                "co.in", "co.za", "com.mx", "com.tr", "com.ru", "com.ar", "com.pl",
        };
        for (String s : ps) PUBLIC_SUFFIX.add(s);

        String[] junk = {
                "localhost", "localhost.localdomain", "local", "broadcasthost",
                "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
                "ip6-allnodes", "ip6-allrouters", "wpad", "isatap", "localhost6",
        };
        for (String s : junk) JUNK.add(s);
    }

    private ListParser() {
    }

    /** 解析结果。每个集合对应一种用途，badfilter 是要撤销的键。 */
    public static final class Out {
        public final HashSet<String> domains = new HashSet<>();
        public final HashSet<String> wild = new HashSet<>();
        public final HashSet<String> urls = new HashSet<>();
        public final HashSet<String> allowDomains = new HashSet<>();
        public final HashSet<String> allowUrls = new HashSet<>();
        public final HashSet<String> badfilter = new HashSet<>();
        /** $important 的域名与 URL 规则，判定时排在所有 @@ 例外之前。 */
        public final HashSet<String> impDomains = new HashSet<>();
        public final HashSet<String> impUrls = new HashSet<>();
        /** $redirect 规则 → 替代资源名。key 与 urls 里的规则串完全一致。 */
        public final java.util.HashMap<String, String> redirects = new java.util.HashMap<>();

        public int lines;
        public int droppedCosmetic;
        public int droppedRegex;
        public int droppedInvalid;
        public int droppedModifier;
        /** 因「拦的是注册域本身、又带页面上下文条件」而丢弃的条数。 */
        public int droppedScoped;

        public int total() {
            // badfilter 必须计入：它不在判定集合里，但不计闸的话，
            // 恶意源用 12MB 下载上限能塞几十万条 badfilter 键，内存被无限放大
            return domains.size() + wild.size() + urls.size()
                    + allowDomains.size() + allowUrls.size()
                    + impDomains.size() + impUrls.size()
                    + badfilter.size();
        }
    }

    public static void parse(BufferedReader in, Out out) throws IOException {
        String raw;
        while ((raw = in.readLine()) != null) {
            if (out.total() >= MAX_RULES) return;
            out.lines++;
            parseLine(raw, out);
        }
    }

    /** 单行解析，独立出来方便测试。 */
    public static void parseLine(String raw, Out out) {
        if (raw == null) return;
        String s = raw.trim();
        if (s.isEmpty()) return;

        char c0 = s.charAt(0);
        if (c0 == '#' || c0 == '!' || c0 == '[') return;   // 注释与文件头

        // 元素隐藏类规则：没有网络请求，跟 DNS 拦截毫无关系
        if (s.indexOf("##") >= 0 || s.indexOf("#@#") >= 0
                || s.indexOf("#?#") >= 0 || s.indexOf("#$#") >= 0) {
            out.droppedCosmetic++;
            return;
        }

        // 例外方向要先剥掉 @@ 再判断，否则 /xxx/ 两个方向待遇不一致：
        // 拦截方向的 /pagead/ 被当正则丢弃，@@/pagead/ 却会一路走到 addUrl。
        boolean allow = false;
        if (s.startsWith("@@")) {
            allow = true;
            s = s.substring(2);
            if (s.isEmpty()) return;
        }

        // 正则规则：无法安全转换，宁可漏也不误伤。
        // 但内容里没有正则元字符的 /xxx/ 在 adblock 语义里就是普通子串匹配
        // （/pagead/、/cpro/ 是订阅源里的高频规则），按字面路径规则放行到下面的正常流程。
        if (s.length() > 2 && s.charAt(0) == '/' && s.charAt(s.length() - 1) == '/'
                && hasRegexMeta(s)) {
            out.droppedRegex++;
            return;
        }

        // dnsmasq：address=/ads.example.com/0.0.0.0
        if (s.startsWith("address=/")) {
            int end = s.indexOf('/', 9);
            if (end > 9) addDomain(s.substring(9, end), out, out.domains);
            return;
        }

        // hosts 形式：IP 后面跟一个或多个域名
        if (isHostsLine(s)) {
            int sp = indexOfSpace(s);
            String rest = s.substring(sp).trim();
            String[] parts = rest.split("\\s+");
            for (String d : parts) {
                if (d.equals("localhost") || d.equals("localhost.localdomain")
                        || d.equals("local") || d.equals("broadcasthost")
                        || d.startsWith("ip6-")) continue;
                addDomain(d, out, out.domains);
            }
            return;
        }

        // $ 之后是修饰符。不能一删了之，它可能彻底改变这条规则的语义，详见 Modifiers
        String mods = null;
        int dollar = s.indexOf('$');
        if (dollar > 0) {
            mods = s.substring(dollar + 1);
            s = s.substring(0, dollar);
        }
        int kind = Modifiers.classify(allow, mods);
        if (kind == Modifiers.DROP) {
            out.droppedModifier++;
            return;
        }
        // $redirect 的值要跟着规则一起存，否则换资源的时候就只剩「拦截」这一半信息
        String redirect = kind == Modifiers.IMPORTANT || kind == Modifiers.REDIRECT
                ? Modifiers.value(mods, "redirect") : null;

        if (s.startsWith("||")) {
            s = s.substring(2);
        } else {
            // 单竖线是「开头锚定」：|http://x.com/ads 这种写法要把协议一起剥掉
            if (s.startsWith("|")) s = s.substring(1);
            if (s.startsWith("http://")) s = s.substring(7);
            else if (s.startsWith("https://")) s = s.substring(8);
        }

        // 结尾的 ^ 是 AdBlock 的「分隔符或串尾」断言：保留一个随规则入库，
        // 匹配侧(UrlRule)拿它做前缀+分隔符判定；不带它的路径规则按子串命中。
        // 早先一律剥掉，||ad.com/ads^ 变成精确匹配 /ads 才命中，/ads?w=300 全部漏拦。
        boolean tailCaret = false;
        while (s.endsWith("^")) {
            s = s.substring(0, s.length() - 1);
            tailCaret = true;
        }
        if (s.isEmpty()) {
            out.droppedInvalid++;
            return;
        }
        if (s.indexOf('/') < 0) tailCaret = false;   // 域名规则的后缀匹配自带点边界，^ 无意义
        if (s.indexOf('^') >= 0) {                   // 路径中段的分隔符断言，现有形状表达不了
            out.droppedInvalid++;
            return;
        }

        int slash = s.indexOf('/');
        if (kind == Modifiers.BADFILTER) {
            String k = key(s, slash, tailCaret);
            if (k != null) out.badfilter.add(k);
            return;
        }
        if (slash < 0) {
            // 域名规则没有路径，也就没有可替换的资源，$redirect 在这里退化成普通拦截
            //
            // 但带页面上下文条件的规则不能这么处理：拿不到页面是谁，就只能二选一。
            // 拦的是子域（ads.site.com）时忽略条件只会更严，可以接受；
            // 拦的是注册域本身（baidu.com）时忽略条件会把整站封死，必须丢。
            if (!allow && Modifiers.scopeIsRestrictive(mods) && isApex(hostOf(s))) {
                out.droppedScoped++;
                return;
            }
            addDomain(s, out, kind == Modifiers.IMPORTANT ? out.impDomains
                    : (allow ? out.allowDomains : out.domains));
            return;
        }
        String rule = addUrl(s.substring(0, slash), s.substring(slash), out);
        if (rule == null) return;
        if (tailCaret) rule = rule + "^";
        if (kind == Modifiers.IMPORTANT) out.impUrls.add(rule);
        else if (allow) out.allowUrls.add(rule);
        else out.urls.add(rule);
        if (redirect != null && !allow) out.redirects.put(rule, redirect);
    }

    /** $badfilter 记下的是被撤销那条规则的键，形态必须和入库时完全一致才能删准。 */
    private static String key(String s, int slash, boolean tailCaret) {
        if (slash < 0) {
            // 域名分支必须与 addDomain 的入库形态一致：带通配的存 clean() 原串
            // （Domains.normalize 不认 '*'，早先在这里 normalize 出 null 或错键，
            //  ||*.example.com^$badfilter 甚至会误删掉无关的普通规则 example.com）。
            String d = clean(s);
            if (d == null) return null;
            if (isWild(d)) return validWild(d) ? d : null;
            return Domains.normalize(d);
        }
        String h = clean(s.substring(0, slash));
        String p = clean(s.substring(slash));
        if (p == null || p.isEmpty()) return null;
        if (h != null && !h.isEmpty() && !isWild(h)) h = Domains.normalize(h);
        // 纯路径规则入库时 host 归一成空串（addUrl 的 h = ""），badfilter 键必须对齐
        if (h == null) h = "";
        String n = UrlRule.normalize(h + p);
        // 入库侧带一个尾 ^（见 parseLine），撤销键必须同形
        return n == null ? null : (tailCaret ? n + "^" : n);
    }

    /**
     * 域名：可能带通配，交给 Wild 处理。
     *
     * 通配规则只保留「拦截」这一种用途：白名单本来就不收通配（一个 ||*.com^ 能放行全网），
     * $important 的通配也并入普通通配——宁可比作者本意弱一点，也不能因为漏判而漏拦。
     */
    private static void addDomain(String raw, Out out, Set<String> target) {
        String d = clean(raw);
        if (d == null) {
            out.droppedInvalid++;
            return;
        }
        if (isWild(d)) {
            if (!validWild(d)) {
                out.droppedInvalid++;
                return;
            }
            if (target == out.domains || target == out.impDomains) out.wild.add(d);
            return;
        }
        String n = Domains.normalize(d);
        if (n == null || JUNK.contains(n) || !validDomain(n)) {
            out.droppedInvalid++;
            return;
        }
        target.add(n);
    }

    /**
     * URL 规则：域名部分 + 路径部分。域名可以留空（纯路径规则，例如 /cpro/），
     * 也可以带通配（例如 iyxdm.* ）。返回归一化后的规则串，非法返回 null。
     */
    private static String addUrl(String hostPart, String pathPart, Out out) {
        String h = clean(hostPart);
        String p = clean(pathPart);
        if (p == null || p.isEmpty()) {
            out.droppedInvalid++;
            return null;
        }
        if (h != null && !h.isEmpty()) {
            boolean ok = isWild(h) ? validWild(h) : validDomain(Domains.normalize(h));
            if (!ok) {
                out.droppedInvalid++;
                return null;
            }
            if (!isWild(h)) h = Domains.normalize(h);
        } else {
            h = "";
        }
        String rule = UrlRule.normalize(h + p);
        if (rule == null) out.droppedInvalid++;
        return rule;
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.US);
        while (t.endsWith("^")) t = t.substring(0, t.length() - 1);
        while (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        return t.isEmpty() ? null : t;
    }

    /** /.../ 里是否含正则元字符：有就只能当正则丢掉，没有就当字面子串收录。 */
    private static boolean hasRegexMeta(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ("\\?*+()[]{}|^$.".indexOf(c) >= 0) return true;
        }
        return false;
    }

    private static boolean isHostsLine(String s) {
        if (s.startsWith("0.0.0.0") || s.startsWith("127.0.0.1")
                || s.startsWith("255.255.255.255")
                || s.startsWith("fe80::") || s.startsWith("ff00::")
                || s.startsWith("::1") || s.startsWith("::")) {
            int sp = indexOfSpace(s);
            return sp > 0 && sp < s.length() - 1;
        }
        return false;
    }

    private static int indexOfSpace(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') return i;
        }
        return -1;
    }

    /** 去掉通配前缀，取域名部分。 */
    private static String hostOf(String d) {
        String h = d;
        while (h.startsWith("*.")) h = h.substring(2);
        if (h.startsWith("*") || h.startsWith("?")) h = h.substring(1);
        while (h.startsWith(".")) h = h.substring(1);
        return h;
    }

    /**
     * 是否只到注册域这一级：baidu.com、bbc.co.uk 算，ads.baidu.com 不算。
     * 用来区分「拦一个广告主机」和「拦一整个站点」。
     */
    static boolean isApex(String d) {
        if (d == null || d.isEmpty()) return false;
        // www 就是站点本身，按注册域处理：封掉 www.baidu.com 和封掉 baidu.com 一样致命
        if (d.startsWith("www.")) d = d.substring(4);
        String[] p = d.split("\\.");
        if (p.length == 2) return true;
        if (p.length == 3) return PUBLIC_SUFFIX.contains(p[1] + "." + p[2]);
        return false;
    }

    static boolean isWild(String d) {
        return d.indexOf('*') >= 0 || d.indexOf('?') >= 0;
    }

    /** 普通域名：至少两段、顶级域是纯字母、不是公共后缀、不是 IP。 */
    static boolean validDomain(String d) {
        if (d == null || d.isEmpty() || d.length() > 253) return false;
        if (PUBLIC_SUFFIX.contains(d)) return false;
        int dot = d.indexOf('.');
        if (dot <= 0 || dot == d.length() - 1) return false;
        if (d.indexOf("..") >= 0) return false;
        int last = d.lastIndexOf('.');
        if (d.length() - last - 1 < 2) return false;
        // 顶级域必须是字母：结尾是数字的只会是 IP 或残缺规则
        for (int i = last + 1; i < d.length(); i++) {
            char c = d.charAt(i);
            if (c < 'a' || c > 'z') return false;
        }
        char c0 = d.charAt(0);
        if (c0 == '.' || c0 == '-' || c0 == '_') return false;
        char cl = d.charAt(d.length() - 1);
        if (cl == '.' || cl == '-') return false;
        return true;
    }

    /**
     * 通配域名：除了普通域名的检查，还要求「去掉通配后至少剩两级字面量」。
     * 这条是防呆的关键：||*.com^ 这种一旦收录，整个互联网都会被判成广告。
     */
    static boolean validWild(String d) {
        if (d == null || d.isEmpty() || d.length() > 253) return false;
        int dot = d.indexOf('.');
        if (dot <= 0 || dot == d.length() - 1) return false;
        if (d.indexOf("..") >= 0) return false;
        int last = d.lastIndexOf('.');
        if (d.length() - last - 1 < 2) return false;
        for (int i = last + 1; i < d.length(); i++) {
            char c = d.charAt(i);
            if (c < 'a' || c > 'z') return false;
        }
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_' || c == '*' || c == '?') continue;
            return false;
        }
        char cl = d.charAt(d.length() - 1);
        if (cl == '.' || cl == '-') return false;
        if (PUBLIC_SUFFIX.contains(d.replace("*", "").replace("?", ""))) return false;

        // 统计字面段：至少两级不含通配的标签，否则太宽
        int literal = 0;
        int i = 0;
        while (i < d.length()) {
            int j = d.indexOf('.', i);
            if (j < 0) j = d.length();
            String seg = d.substring(i, j);
            if (!seg.isEmpty() && seg.indexOf('*') < 0 && seg.indexOf('?') < 0) literal++;
            i = j + 1;
        }
        return literal >= 2;
    }
}

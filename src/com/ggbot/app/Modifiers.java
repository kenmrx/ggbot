package com.ggbot.app;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * AdBlock / AdGuard 修饰符（规则里 $ 之后那一段）的语义分类。
 *
 * 真实规则源把四种性质完全不同的东西都塞在 $ 后面，混在一起处理一定会出错：
 *
 *   收窄条件   $domain=a.com|b.com   $third-party   $script   $xmlhttprequest
 *              规则只在特定站点、特定资源类型下才成立。我们的判定粒度只到域名和 URL，
 *              拿不到页面上下文，所以这类规则照单全收地执行，就会比作者本意拦得宽。
 *
 *   动作替换   $redirect   $csp   $removeparam   $cookie   $rewrite
 *              作者要的不是「拦住」，是「换掉 / 删掉某个东西」。其中 $csp、$removeparam、
 *              $cookie 作用在页面 URL 上，当成拦截会直接把整站打死。
 *
 *   范围例外   $generichide   $elemhide   $genericblock   $specifichide
 *              写在 @@ 规则上，意思是「这个站不要再做元素隐藏」，跟网络请求放不放行
 *              毫无关系。把它当放行规则用，等于给这个站开一整张免死金牌。
 *              EasyList China 里这样的规则有 205 条。
 *
 *   指令       $badfilter
 *              用来撤掉另一条一模一样的规则。上游列表经常这么修正自己的误伤，
 *              比如 AdGuard DNS 过滤器里的 ||pl.ua^$badfilter 就是在撤销对波兰
 *              国家注册域的整段封禁。
 *
 * 处理策略按方向区分，因为两种错的代价不一样：
 *   拦截规则 → 收窄条件可以照拦，结果只会更严，符合「去广告」的意图
 *   放行规则 → 带任何条件都不能降级成全域放行，否则等于替广告开门
 */
public final class Modifiers {

    /** 可以当普通规则用。 */
    public static final int OK = 0;
    /** 与我们的判定能力不匹配，丢弃。 */
    public static final int DROP = 1;
    /** 撤销另一条规则，调用方负责把对应的键删掉。 */
    public static final int BADFILTER = 2;
    /**
     * $important：优先级高于一切 @@ 例外。
     * 上游列表用它标记「这个域确实是广告，别家的例外规则写错了」，必须单独存放。
     */
    public static final int IMPORTANT = 3;
    /** $redirect：不是拦住，而是换成一个空的合法资源。 */
    public static final int REDIRECT = 4;

    /** 只关掉元素隐藏 / 通用规则，不涉及网络请求本身。 */
    private static final Set<String> SCOPE_ONLY = new HashSet<>();

    /** 作用在页面 URL 上，拦了会打死整站。 */
    private static final Set<String> PAGE_ACTION = new HashSet<>();

    /** 不影响作用范围、也不会改变动作的修饰符。 */
    private static final Set<String> NEUTRAL = new HashSet<>();

    /**
     * 页面上下文条件：只有「站在某类页面上」时这条规则才成立。
     *
     * 我们的判定粒度只有域名和 URL，拿不到当前页面是谁，所以这类条件根本无法执行。
     * 对普通广告域来说忽略它无伤大雅；但如果规则拦的是 baidu.com 这种注册域本身，
     * 忽略条件就等于把整个站点封死。EasyList China 里就有这样的规则：
     *     ||baidu.com^$domain=pos.baidu.com
     *     ||baidu.com^$subdocument,domain=dm5.com|dm5.hk
     * 作者的意思是「在某个站里出现的 baidu.com 子文档不要加载」，不是「封掉百度」。
     */
    private static final Set<String> PAGE_SCOPE = new HashSet<>();

    static {
        String[] scope = {
                "generichide", "elemhide", "genericblock", "specifichide",
                "jsinject", "content", "ehide", "shide",
        };
        for (String s : scope) SCOPE_ONLY.add(s);

        String[] page = {
                "csp", "removeparam", "queryprune", "cookie", "header", "headers",
                "permissions", "inline-script", "inline-font",
        };
        for (String s : page) PAGE_ACTION.add(s);

        String[] neutral = {
                "important", "match-case", "all", "doc", "document",
        };
        for (String s : neutral) NEUTRAL.add(s);

        String[] scope2 = {"domain", "denyallow", "from", "to"};
        for (String s : scope2) PAGE_SCOPE.add(s);
    }

    private Modifiers() {
    }

    /**
     * @param allow 这条是不是 @@ 例外规则
     * @param mods  $ 之后那一段，没有就传 null
     */
    public static int classify(boolean allow, String mods) {
        if (mods == null || mods.isEmpty()) return OK;

        HashSet<String> names = new HashSet<>(8);
        names(mods, names);
        if (names.isEmpty()) return OK;

        if (names.contains("badfilter")) return BADFILTER;

        for (String k : names) {
            if (SCOPE_ONLY.contains(k)) return DROP;
            if (PAGE_ACTION.contains(k)) return DROP;
        }

        // $denyallow 不是收窄条件，是豁免列表：「除 a.com 之外全部适用」。
        // 对拦截规则忽略它，会在作者明确豁免的站点上过拦——方向与收窄条件相反，
        // 「忽略了只会更严」的论证在这里不成立，只能整条丢弃（宁可漏，不能误伤）。
        if (!allow && names.contains("denyallow")) return DROP;

        // 到这里剩下的都是「纯拦截 + 也许有收窄条件」的规则。
        // $important 必须排在放行判断之前：它的全部意义就是压过 @@ 例外。
        if (!allow && names.contains("important")) return IMPORTANT;

        if (names.contains("redirect")) {
            // 例外规则上写 $redirect 没有定义，当放行处理等于白送一个域名
            return allow ? DROP : REDIRECT;
        }
        // $redirect-rule 的语义是「这条请求本来要被拦住时，再换成空资源」。
        // 单独执行等于把本来放行的请求也拦下来，比作者本意更严，宁可不收。
        if (names.contains("redirect-rule")) return DROP;

        if (allow) {
            // 放行规则只要带了任何收窄条件，就不能降级成全域放行
            for (String k : names) {
                if (!NEUTRAL.contains(k)) return DROP;
            }
        }
        return OK;
    }

    /** 这条规则是否依赖页面上下文才成立。 */
    public static boolean hasPageScope(String mods) {
        if (mods == null || mods.isEmpty()) return false;
        HashSet<String> names = new HashSet<>(8);
        names(mods, names);
        for (String k : names) {
            if (PAGE_SCOPE.contains(k)) return true;
        }
        return false;
    }

    /**
     * 这条规则的页面上下文条件是否「收窄」了作用范围。
     *
     * 关键区别在正负号：
     *   domain=a.com        只在 a.com 上成立 → 收窄，忽略它会把别的站一起封掉
     *   domain=~a.com       除 a.com 之外都成立 → 本来就是全域，忽略它只会多封一点点
     * EasyList China 里 ||alimama.cn^$third-party,domain=~alimama.com 就是后者，
     * 按前者处理会把阿里妈妈整个丢掉，白白漏拦。
     */
    public static boolean scopeIsRestrictive(String mods) {
        if (!hasPageScope(mods)) return false;
        if (value(mods, "from") != null || value(mods, "to") != null) return true;
        String v = value(mods, "domain");
        if (v == null) v = value(mods, "denyallow");
        if (v == null) return true;
        for (String part : v.split("\\|")) {
            String p = part.trim();
            if (p.isEmpty() || p.startsWith("~")) continue;
            return true;
        }
        return false;
    }

    /**
     * 取某个修饰符的值，$redirect=noop.js 返回 noop.js。
     * 只认 key=value 形式，$important 这种没有值，返回 null。
     */
    public static String value(String mods, String key) {
        if (mods == null || key == null) return null;
        int i = 0;
        int n = mods.length();
        while (i < n) {
            int comma = mods.indexOf(',', i);
            if (comma < 0) comma = n;
            int eq = mods.indexOf('=', i);
            if (eq > 0 && eq < comma) {
                String k = mods.substring(i, eq).trim().toLowerCase(Locale.US);
                if (k.startsWith("~")) k = k.substring(1);
                if (k.equals(key)) {
                    String v = mods.substring(eq + 1, comma).trim();
                    return v.isEmpty() ? null : v;
                }
            }
            i = comma + 1;
        }
        return null;
    }

    /**
     * 取出修饰符名。逗号分隔，名字在等号之前，开头的 ~ 表示取反。
     * $domain=a.com|b.com 这种值里用竖线分隔，不会跟逗号冲突。
     */
    public static void names(String mods, Set<String> out) {
        int i = 0;
        int n = mods.length();
        while (i < n) {
            int comma = mods.indexOf(',', i);
            if (comma < 0) comma = n;
            int eq = mods.indexOf('=', i);
            int end = (eq >= 0 && eq < comma) ? eq : comma;
            int s = i;
            while (s < end && mods.charAt(s) == '~') s++;
            if (end > s) {
                String k = mods.substring(s, end).trim().toLowerCase(Locale.US);
                if (!k.isEmpty()) out.add(k);
            }
            i = comma + 1;
        }
    }
}

import com.ggbot.app.ListParser;
import com.ggbot.app.Modifiers;
import com.ggbot.app.TokenIndex;
import com.ggbot.app.UrlIndex;
import com.ggbot.app.UrlRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 修饰符分类、$badfilter 撤销、token 索引的单元测试。
 *
 * 重点在最后一段差分测试：拿 token 索引的结果跟「把每条规则都跑一遍」的朴素实现对比，
 * 只要两边对同一个请求的结论不一致，就说明有规则被索引漏掉了。
 */
public class EngineTest {

    static int pass;
    static int fail;

    static void check(String name, boolean ok) {
        if (ok) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL  " + name);
        }
    }

    public static void main(String[] a) {
        modifiers();
        parser();
        token();
        differential();
        caretSemantics();
        hostileCaps();
        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }

    /* ---------------- 尾 ^ 与子串语义（URL 规则） ---------------- */

    static void caretSemantics() {
        // ||ad.com/ads^：前缀 + 分隔符断言。真实列表里数千条这种形态，
        // 早先剥掉 ^ 变精确匹配，/ads?w=300 这类带查询串的全部漏拦
        check("带 ^ 命中带查询串的路径",
                UrlRule.matches("ad.example.com/ads^", "ad.example.com", "/ads?w=300"));
        check("带 ^ 不误伤更长的单词路径",
                !UrlRule.matches("ad.example.com/ads^", "ad.example.com", "/adsense/x"));
        check("带 ^ 命中目录型路径", UrlRule.matches(
                "ad.example.com/ads^", "ad.example.com", "/ads/300x250.png"));
        // ||cnt.com/px.gif?zone=（无 ^ 无通配）：adblock 语义是子串命中
        check("无 ^ 路径按子串命中",
                UrlRule.matches("cnt.example.com/px.gif?zone=",
                        "cnt.example.com", "/px.gif?zone=123&x=1"));
        check("无 ^ 子串不越域", !UrlRule.matches(
                "cnt.example.com/px.gif?zone=", "cnt.example.com", "/other"));
        check("无 ^ 子串不误伤别的路径", !UrlRule.matches(
                "cnt.example.com/px.gif?zone=", "cnt.example.com", "/px.gif"));
        // 旧形态回归：目录斜杠仍是前缀
        check("目录斜杠仍是前缀语义",
                UrlRule.matches("ad.example.com/ads/", "ad.example.com", "/ads/x/y"));
        // 通配不受影响
        check("通配路径照常", UrlRule.matches(
                "ad.example.com/a*c^", "ad.example.com", "/abc"));

        // 解析侧：尾 ^ 保留进库，中段 ^ 丢弃
        ListParser.Out o = new ListParser.Out();
        ListParser.parseLine("||ad.example.com/ads^", o);
        check("尾 ^ 随规则入库", o.urls.contains("ad.example.com/ads^") && o.droppedInvalid == 0);
        o = new ListParser.Out();
        ListParser.parseLine("||ad.example.com^", o);
        check("域名规则的 ^ 照旧剥掉", o.domains.contains("ad.example.com"));
        o = new ListParser.Out();
        ListParser.parseLine("||cdn.example.com^*/adbox/", o);
        check("中段 ^ 整条丢弃并计数", o.urls.isEmpty() && o.droppedInvalid == 1);

        // $badfilter 的撤销键必须与入库键同形（尾 ^ 两侧一致）
        o = new ListParser.Out();
        ListParser.parseLine("||ad.example.com/ads^$badfilter", o);
        check("badfilter 键带尾 ^", o.badfilter.contains("ad.example.com/ads^"));

        // badfilter 计入 total()：否则恶意源用它绕过 MAX_RULES 防洪闸
        o = new ListParser.Out();
        for (int i = 0; i < 200; i++) {
            ListParser.parseLine("||d" + i + ".example.com^$badfilter", o);
        }
        check("badfilter 计入防洪闸总数", o.total() >= 200);
    }

    /* ---------------- 恶意源的复杂度闸 ---------------- */

    static void hostileCaps() {
        // 6000 条全切不出 token 的规则：loose 必须被闸住（真实源千级，恶意源无上限）
        List<String> noTok = new ArrayList<>();
        for (int i = 0; i < 6000; i++) noTok.add("ab.cd/ef");   // 所有字母数字段都 <3 字符
        TokenIndex ti = TokenIndex.of(noTok);
        check("loose 超闸截断", ti.looseSize() < 6000 && ti.looseSize() >= 4090);
        check("正常规则照常索引", ti.size() == 6000);

        // 1000 条共用全部 token（只有 com/zzz 两个 ≥3 字符 token）：
        // 无区分度整条丢弃——否则每个 /zzz?x= 请求都全量扫
        List<String> shared = new ArrayList<>();
        for (int i = 0; i < 1000; i++) shared.add("com/zzz?x=" + i);
        TokenIndex t2 = TokenIndex.of(shared);
        check("共用高频 token 的规则被丢弃",
                t2.match("anything.com", "/zzz?x=1") == null);

        // 对照：正常分布式规则在索引下照常命中
        List<String> normal = new ArrayList<>();
        for (int i = 0; i < 50; i++) normal.add("host" + i + ".example.com/px" + i);
        normal.add("special.example.com/px50");
        TokenIndex t3 = TokenIndex.of(normal);
        check("正常规则索引后照常命中",
                "special.example.com/px50".equals(
                        t3.match("special.example.com", "/px50?q=1")));
    }

    /* ---------------- 修饰符语义 ---------------- */

    static void modifiers() {
        check("无修饰符", Modifiers.classify(false, null) == Modifiers.OK);
        check("空修饰符", Modifiers.classify(false, "") == Modifiers.OK);

        // 拦截侧：收窄条件不影响我们照拦，符合去广告意图
        check("拦截 + 第三方照拦", Modifiers.classify(false, "third-party") == Modifiers.OK);
        check("拦截 + 资源类型照拦", Modifiers.classify(false, "script,third-party") == Modifiers.OK);
        check("拦截 + 站点限定照拦", Modifiers.classify(false, "domain=~edushida.com") == Modifiers.OK);

        // 这三类必须丢：否则会替广告开门，或者把整站打死
        check("元素隐藏例外（放行侧）丢弃", Modifiers.classify(true, "generichide") == Modifiers.DROP);
        check("元素隐藏例外（拦截侧）丢弃", Modifiers.classify(false, "elemhide") == Modifiers.DROP);
        check("通用块例外丢弃", Modifiers.classify(true, "genericblock") == Modifiers.DROP);
        check("带站点条件的放行丢弃", Modifiers.classify(true, "domain=litv.tv") == Modifiers.DROP);
        check("取反的第三方也是条件", Modifiers.classify(true, "~third-party") == Modifiers.DROP);
        check("按资源类型的放行丢弃", Modifiers.classify(true, "xmlhttprequest") == Modifiers.DROP);

        check("csp 是页面改写，不能拦", Modifiers.classify(false, "csp=script-src *") == Modifiers.DROP);
        check("removeparam 是页面改写", Modifiers.classify(false, "removeparam=utm_source") == Modifiers.DROP);
        check("cookie 是页面改写", Modifiers.classify(false, "cookie=name") == Modifiers.DROP);

        check("important 放行保留", Modifiers.classify(true, "important") == Modifiers.OK);
        check("badfilter 是指令", Modifiers.classify(false, "badfilter") == Modifiers.BADFILTER);
        check("badfilter 优先于放行", Modifiers.classify(true, "badfilter") == Modifiers.BADFILTER);

        Set<String> names = new HashSet<>();
        Modifiers.names("script,third-party,domain=a.com|b.com", names);
        check("修饰符按逗号切开", names.size() == 3);
        check("等号右边不当作名字", names.contains("domain") && !names.contains("a.com|b.com"));
        names.clear();
        Modifiers.names("~third-party,match-case", names);
        check("波浪号被剥掉", names.contains("third-party") && names.contains("match-case"));
    }

    /* ---------------- 解析器接上语义后 ---------------- */

    static void parser() {
        ListParser.Out o = new ListParser.Out();
        ListParser.parseLine("@@||10010.com^$generichide", o);
        check("$generichide 不进放行名单", o.allowDomains.isEmpty());
        check("$generichide 计入丢弃计数", o.droppedModifier == 1);

        o = new ListParser.Out();
        ListParser.parseLine("@@||ad.azure.com^", o);
        check("无条件例外正常进入放行名单", o.allowDomains.contains("ad.azure.com"));

        o = new ListParser.Out();
        ListParser.parseLine("@@||x.com^$domain=a.com", o);
        check("带站点条件的例外被丢弃", o.allowDomains.isEmpty() && o.droppedModifier == 1);

        o = new ListParser.Out();
        ListParser.parseLine("@@||x.com^$script,third-party", o);
        check("按资源类型的例外被丢弃", o.allowDomains.isEmpty());

        o = new ListParser.Out();
        ListParser.parseLine("||pl.ua^", o);
        ListParser.parseLine("||pl.ua^$badfilter", o);
        check("被撤销的规则本身照常入库", o.domains.contains("pl.ua"));
        check("badfilter 记下要撤销的键", o.badfilter.contains("pl.ua"));
        check("badfilter 不进域名库", o.domains.size() == 1);

        o = new ListParser.Out();
        ListParser.parseLine("||x.com^$csp=script-src 'self' *", o);
        check("csp 规则不入域名库", o.domains.isEmpty() && o.droppedModifier == 1);

        o = new ListParser.Out();
        ListParser.parseLine("||17173cdn.com/20*.mp4$rewrite=abp-resource:blank-mp3", o);
        check("替换类规则仍按拦截处理", !o.urls.isEmpty());

        o = new ListParser.Out();
        ListParser.parseLine("||ads.a.com^$important", o);
        check("important 进独立集合", o.impDomains.contains("ads.a.com"));
        check("important 不再混进普通库", o.domains.isEmpty());
    }

    /* ---------------- token 索引基本行为 ---------------- */

    static void token() {
        List<String> rules = Arrays.asList(
                "/phpads/*", "/adrecover.js", "ac*.786ip.com/p", "*.adhost.net/banner/*",
                "/a/b/c/d/e", "/x*/y.js", "/*/tracker.js");
        TokenIndex t = TokenIndex.of(rules);

        check("纯路径规则命中", t.match("www.baidu.com", "/phpads/1.js") != null);
        check("无关路径不命中", t.match("www.baidu.com", "/healthy/food.js") == null);
        check("域名通配命中", t.match("srv.adhost.net", "/banner/a.png") != null);
        check("域名通配不误伤别的域", t.match("srv.other.net", "/banner/a.png") == null);
        check("域名前半段通配命中", t.match("acx.786ip.com", "/p") != null);
        check("切不出 token 的规则仍然命中", t.match("www.a.com", "/a/b/c/d/e") != null);
        check("只有短片段的规则仍然命中", t.match("www.a.com", "/x/y.js") != null);
        check("通配中段的规则命中", t.match("www.a.com", "/z/tracker.js") != null);
        check("空集合不误报", TokenIndex.EMPTY.match("a.com", "/x") == null);
        check("空集合大小为 0", TokenIndex.EMPTY.size() == 0);
    }

    /* ---------------- 差分：索引 vs 逐条扫 ---------------- */

    static void differential() {
        String[] raw = {
                "/phpads/*", "/adrecover.js", "/track.gif", "/ads/*", "/banner/*",
                "ac*.786ip.com/p", "*.adhost.net/banner/*", "track.net/*",
                "ads.example.com/x", "/js/ad/*", "/*/tracker.js", "/a?c/x",
                "/v1/pixel*", "cdn.site.org/*", "/img/ad/*.png",
        };
        UrlIndex idx = UrlIndex.of(Arrays.asList(raw));

        String[] hosts = {
                "www.example.com", "ads.example.com", "srv.adhost.net", "ac1.786ip.com",
                "ac.786ip.com", "cdn.site.org", "a.b.c.d.com", "track.net", "x.y.z",
        };
        String[] paths = {
                "/phpads/a.js", "/ad/1", "/ads/x", "/banner/b.png", "/img/ad/x.png",
                "/js/ad/a.js", "/track.gif", "/adrecover.js", "/p", "/x/y/z",
                "/z/tracker.js", "/abc/x", "/v1/pixel.gif", "/a/b/c/d/e", "/",
                "/search?q=ad", "/healthy/food.js",
        };

        int total = 0;
        int hits = 0;
        int mismatch = 0;
        String firstMismatch = null;
        for (String h : hosts) {
            for (String p : paths) {
                total++;
                String fast = idx.match(h, p);
                String slow = null;
                for (String r : raw) {
                    if (UrlRule.matches(r, h, p)) {
                        slow = r;
                        break;
                    }
                }
                if ((fast == null) != (slow == null)) {
                    mismatch++;
                    if (firstMismatch == null) {
                        firstMismatch = h + p + "  索引=" + fast + "  逐条=" + slow;
                    }
                } else if (fast != null) {
                    hits++;
                }
            }
        }
        check("索引与逐条扫结论一致（" + total + " 组，命中 " + hits + "）", mismatch == 0);
        if (firstMismatch != null) System.out.println("      首处不一致：" + firstMismatch);

        // 候选规模：索引里每条规则只挂一个 token，桶大小不应出现长尾
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 500; i++) many.add("/bulk" + i + "/x.js");
        TokenIndex bulk = TokenIndex.of(many);
        check("同构规则不会挤在一个桶里", bulk.size() == 500);
        check("同构规则仍能精确命中", bulk.match("h.com", "/bulk42/x.js") != null);
        check("同构规则不串号", bulk.match("h.com", "/bulk999/x.js") == null);
    }
}

import com.ggbot.app.ListParser;
import com.ggbot.app.RuleSet;
import com.ggbot.app.UrlIndex;
import com.ggbot.app.UrlRule;
import com.ggbot.app.Wild;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * 订阅源解析与规则容器的测试。
 * 样本直接取自真实规则源（anti-AD / AdGuard / StevenBlack / EasyList China），
 * 不是我照着文档臆想的格式。
 */
public class FeedTest {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        parseFormats();
        rejectsDangerous();
        ruleSet();
        wild();
        urlIndex();
        realSamples();
        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL " + name);
        }
    }

    private static ListParser.Out parse(String... lines) {
        ListParser.Out out = new ListParser.Out();
        for (String l : lines) ListParser.parseLine(l, out);
        return out;
    }

    /* ---------------- 各种语法 ---------------- */

    private static void parseFormats() {
        ListParser.Out o = parse("0.0.0.0 ads.a.com");
        check("hosts 单域名", o.domains.size() == 1 && o.domains.contains("ads.a.com"));

        o = parse("127.0.0.1 b.com c.com");
        check("hosts 一行多域名", o.domains.contains("b.com") && o.domains.contains("c.com"));

        o = parse("::1 localhost");
        check("hosts 的 localhost 被丢掉", o.domains.isEmpty());

        o = parse("ads.example.com");
        check("纯域名列表", o.domains.contains("ads.example.com"));

        o = parse("||x.com^");
        check("AdBlock 域名规则", o.domains.contains("x.com"));

        o = parse("||x.com^$third-party");
        check("AdBlock 选项被切掉", o.domains.contains("x.com"));

        o = parse("||x.com/ad/*^");
        check("AdBlock 路径规则进 URL 桶",
                o.urls.size() == 1 && o.domains.isEmpty());

        o = parse("@@||ad.azure.com^");
        check("例外进放行名单", o.allowDomains.contains("ad.azure.com") && o.domains.isEmpty());

        o = parse("@@||ok.com/p^");
        check("路径例外进放行 URL", o.allowUrls.size() == 1);

        o = parse("address=/dnsmasq.com/0.0.0.0");
        check("dnsmasq 语法", o.domains.contains("dnsmasq.com"));

        o = parse("x.com##.ad-banner", "x.com#@#.ad", "x.com#?#.ad");
        check("元素隐藏规则被跳过",
                o.total() == 0 && o.droppedCosmetic == 3);

        o = parse("/^ads?[0-9]+\\./");
        check("正则规则被跳过", o.total() == 0 && o.droppedRegex == 1);

        o = parse("# 注释", "! 注释", "[Adblock Plus 2.0]", "");
        check("注释与文件头被跳过", o.total() == 0);

        o = parse("|http://anchor.com/ads^");
        check("开头锚定 + 协议", o.urls.size() == 1);

        o = parse("||ac*.786ip.com^");
        check("域名通配进 wild 桶",
                o.wild.contains("ac*.786ip.com") && o.domains.isEmpty());
    }

    /* ---------------- 危险条目必须挡住 ---------------- */

    private static void rejectsDangerous() {
        ListParser.Out o = parse("||*.com^");
        check("拒绝 ||*.com^（会把全网判成广告）", o.total() == 0);

        o = parse("||com^", "com", "co.uk", "com.cn");
        check("拒绝裸顶级域与公共后缀", o.total() == 0);

        o = parse("||154.7.80.158^", "192.168.1.1");
        check("拒绝 IP（按 IP 封禁在 DNS 层没有意义）", o.total() == 0);

        o = parse("localhost", "localhost.localdomain", "broadcasthost",
                "fe80::1%lo0 localhost", "ff00::0 ip6-localnet");
        check("拒绝本机名与 IPv6 杂项", o.total() == 0);

        o = parse("||*.example.com^");
        check("接受足够具体的通配", o.wild.contains("*.example.com"));

        o = parse("-*-*-*-*.alpha^$script,third-party");
        check("拒绝没有字面量的通配", o.total() == 0);

        o = parse("||ads*.normal-hl.zijieapi.com^");
        check("接受真实的三级通配", o.wild.size() == 1);
    }

    /* ---------------- 容器 ---------------- */

    private static void ruleSet() {
        RuleSet s = RuleSet.of(new HashSet<>(Arrays.asList("ads.com", "track.net")));
        check("RuleSet 精确命中", s.matches("ads.com"));
        check("RuleSet 后缀命中", s.matches("img.ads.com"));
        check("RuleSet 不误伤相似域名", !s.matches("notads.com"));
        check("RuleSet 不匹配子串后缀", !s.matches("ads.com.cn"));

        HashSet<String> a = new HashSet<>(Arrays.asList("a.com", "b.com", "dup.com"));
        HashSet<String> b = new HashSet<>(Arrays.asList("dup.com", "c.com"));
        RuleSet m = RuleSet.merge(a, b);
        check("合并去重后 4 条", m.size() == 4);
        check("合并后仍能后缀命中", m.matches("x.c.com"));

        check("空集合合并安全", RuleSet.merge(null, null).size() == 0);
        check("单边为空的合并", RuleSet.merge(a, null).size() == 3);
    }

    private static void wild() {
        Wild w = Wild.of(new HashSet<>(Arrays.asList(
                "ac*.786ip.com", "*-ad-sign.byteimg.com", "ad?.x.com")));

        check("通配匹配 ac1.786ip.com", w.matches("ac1.786ip.com"));
        check("通配匹配 ac.786ip.com（* 可以为空）", w.matches("ac.786ip.com"));
        check("通配不误伤 x.786ip.com", !w.matches("x.786ip.com"));
        check("通配管住子域", w.matches("deep.ac1.786ip.com"));
        check("中间通配", w.matches("x-ad-sign.byteimg.com"));
        check("单字符通配", w.matches("ad1.x.com"));
        check("单字符通配不吞两字符", !w.matches("ad12.x.com"));
        check("返回命中的规则原文", "ac*.786ip.com".equals(w.match("ac1.786ip.com")));
        check("空集合不崩", !Wild.EMPTY.matches("a.com"));
    }

    private static void urlIndex() {
        UrlIndex idx = UrlIndex.of(new java.util.HashSet<>(Arrays.asList(
                "x.com/ad/*", "/cpro/", "*.cdn.com/track/*")));

        check("按域名命中路径", idx.match("x.com", "/ad/1.gif") != null);
        check("子域也能命中", idx.match("img.x.com", "/ad/1.gif") != null);
        check("路径不符不命中", idx.match("x.com", "/news/1.html") == null);
        check("纯路径规则对任意域名生效", idx.match("any.org", "/cpro/x") != null);
        check("通配域名规则可用", idx.match("a.cdn.com", "/track/x") != null);
        check("空索引安全", UrlIndex.EMPTY.match("x.com", "/") == null);

        // 大小写：主机名大写时仍要命中
        check("大写主机名", idx.match("IMG.X.COM", "/ad/1.gif") != null);
    }

    /* ---------------- 真实样本 ---------------- */

    /**
     * 默认订阅源守护：订阅整合成主页一个入口后默认全部开启，
     * 谁手痒把某个源默认关掉，这里就会拦住——主页摘要和手动更新都靠它。
     */
    private static void presetDefaults() {
        int enabled = 0;
        int total = 0;
        boolean antiAdOn = false;
        for (String[] row : com.ggbot.app.Feeds.PRESET) {
            check("订阅源地址必须是 https：" + row[0], row[1].startsWith("https://"));
            total++;
            if ("1".equals(row[2])) {
                enabled++;
                if (row[1].contains("anti-ad.net")) antiAdOn = true;
            }
        }
        check("默认全部开启（当前 " + enabled + "/" + total + " 个）", enabled == total);
        check("默认开启的有 anti-AD", antiAdOn);
    }

    private static void realSamples() throws Exception {
        presetDefaults();
        String dir = "test/samples/";
        if (!new File(dir).isDirectory()) {
            System.out.println("跳过真实样本（找不到 test/samples）");
            return;
        }

        ListParser.Out plain = parseFile(dir + "antiad_plain.txt");
        check("anti-AD 纯域名源解析出规则", plain.domains.size() >= 30);
        check("anti-AD 首条域名正确", plain.domains.contains("000491b06a.com"));
        check("anti-AD 的 #TITLE 行没被当成域名", !plain.domains.contains("#title=anti-ad"));

        ListParser.Out abp = parseFile(dir + "antiad_abp.txt");
        check("anti-AD AdBlock 源解析出域名", abp.domains.size() >= 20);
        check("anti-AD 的例外被识别", abp.allowDomains.contains("ad.azure.com"));
        check("anti-AD 的例外没混进拦截", !abp.domains.contains("ad.azure.com"));
        check("anti-AD 的通配被识别", abp.wild.size() > 0);

        ListParser.Out adg = parseFile(dir + "adguard.txt");
        check("AdGuard 源解析出规则", adg.domains.size() >= 20);
        check("AdGuard 的注释行被跳过", !adg.domains.contains("doubleclick.net"));
        check("AdGuard 的正则被跳过", adg.droppedRegex > 0);
        check("AdGuard 的 $important 单独入库", adg.impDomains.contains("adsrvmedia.adk2.co"));
        check("$important 没混进普通域名库", !adg.domains.contains("adsrvmedia.adk2.co"));

        ListParser.Out hosts = parseFile(dir + "hosts.txt");
        check("StevenBlack hosts 解析出域名", hosts.domains.contains("ad-assets.futurecdn.net"));
        check("hosts 的 broadcasthost 被丢掉", !hosts.domains.contains("broadcasthost"));
        check("hosts 的 IPv6 杂项被丢掉", !hosts.domains.contains("ip6-localnet"));

        ListParser.Out china = parseFile(dir + "easylist_china.txt");
        check("EasyList China 解析出 URL 规则", china.urls.size() > 0);
        // 这份样本里的 @@ 全是带 $domain= 的条件例外，只能放行特定站点。
        // 降级成全域放行等于替这些站的广告开门，所以必须一条都不留。
        check("带条件的例外不降级成全域放行",
                china.allowDomains.size() + china.allowUrls.size() == 0);
        check("条件例外计入丢弃计数", china.droppedModifier > 0);
        // 以 # 开头的元素隐藏规则与注释无法区分，一律按注释跳过，不产生拦截项
        check("EasyList China 没有把元素隐藏当成域名", china.domains.size() > 0);

        ListParser.Out adrules = parseFile(dir + "adrules.txt");
        check("adrules 解析出域名", adrules.domains.size() >= 20);

        // 合并后的规模与去重效果
        HashSet<String> all = new HashSet<>();
        all.addAll(plain.domains);
        all.addAll(abp.domains);
        all.addAll(adg.domains);
        all.addAll(hosts.domains);
        all.addAll(adrules.domains);
        RuleSet merged = RuleSet.of(all);
        check("合并后可查询", merged.matches("000491b06a.com"));

        // ── 订阅突变防线：纯判定逻辑（与 Feeds.update 内联条件同式）。
        //    旧条数 ≥1000 且新条数 < 旧的一半 → 判为疑似污染，拒收。
        check("突变判定：1000→400 拦下", FeedTest.isSuspectedDrop(1000, 400));
        check("突变判定：1000→499 拦下", FeedTest.isSuspectedDrop(1000, 499));
        check("突变判定：1000→500 放行（恰好半数，边界归正常）", !FeedTest.isSuspectedDrop(1000, 500));
        check("突变判定：1000→999 放行", !FeedTest.isSuspectedDrop(1000, 999));
        check("突变判定：小源 500→10 放行（低于采样基准，不误伤）", !FeedTest.isSuspectedDrop(500, 10));
        check("突变判定：首装 0→50000 放行（oldCount=0 不比较）", !FeedTest.isSuspectedDrop(0, 50000));
        check("突变判定：1000→20000 放行（扩容不拦）", !FeedTest.isSuspectedDrop(1000, 20000));
    }

    /** 与 Feeds.update 里的突变判定同一公式；改一处必须同步另一处。 */
    static boolean isSuspectedDrop(int oldCount, int newCount) {
        return oldCount >= 1000 && newCount < oldCount / 2;
    }

    private static ListParser.Out parseFile(String path) throws Exception {
        ListParser.Out out = new ListParser.Out();
        BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(path), "UTF-8"));
        ListParser.parse(r, out);
        r.close();
        return out;
    }
}

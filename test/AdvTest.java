import com.ggbot.app.Coalescer;
import com.ggbot.app.Decision;
import com.ggbot.app.DnsCache;
import com.ggbot.app.DnsPacket;
import com.ggbot.app.HttpGate;
import com.ggbot.app.ListParser;
import com.ggbot.app.Modifiers;
import com.ggbot.app.Noop;
import com.ggbot.app.RuleSet;
import com.ggbot.app.Rules;
import com.ggbot.app.UrlIndex;
import com.ggbot.app.Wild;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 吸收其它项目经验后的五组机制验证（纯 JVM）。
 *
 *   1 CNAME 链解析       对应 Pi-hole / AdGuard Home 的 CNAME inspection
 *   2 TTL 感知缓存       对应 dnsmasq / Pi-hole 的缓存与负缓存
 *   3 查询合并           对应 dnsmasq / Pi-hole 的 single-flight
 *   4 $important 优先级  对应 uBlock Origin 的修饰符语义
 *   5 $redirect 替换资源 对应 uBO 的 redirectable resources
 *
 * 这些都是「不报错但会悄悄失效」的逻辑：顺序错一位、TTL 少一处，界面完全正常，
 * 只是每天多放过去一批广告、多等几百毫秒。所以必须能被桌面测试直接盯住。
 */
public class AdvTest {

    static int pass = 0;
    static int fail = 0;
    static final Charset L1 = Charset.forName("ISO-8859-1");

    static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("[1] CNAME 链");
        cname();
        System.out.println("[2] TTL 感知缓存");
        ttlCache();
        System.out.println("[3] 查询合并");
        coalesce();
        System.out.println("[4] 修饰符与解析");
        modifiers();
        System.out.println("[5] 判定优先级");
        priority();
        System.out.println("[6] 替换资源");
        noop();
        System.out.println("[7] 分帧与应答替换");
        gate();

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }

    /* ---------------- 报文构造 ---------------- */

    static byte[] name(String d) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (String seg : d.split("\\.")) {
            byte[] b = seg.getBytes(L1);
            o.write(b.length);
            o.write(b, 0, b.length);
        }
        o.write(0);
        return o.toByteArray();
    }

    /** 造一个应答：问题 + 若干 CNAME + 可选 A 记录。CNAME 的 TTL 60，A 的 TTL 30。 */
    static byte[] response(String qname, String[] cnames, String ip) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int an = cnames.length + (ip == null ? 0 : 1);
        o.write(0x12);
        o.write(0x34);
        o.write(0x81);
        o.write(0x80);
        o.write(0);
        o.write(1);
        o.write(an >> 8);
        o.write(an);
        o.write(0);
        o.write(0);
        o.write(0);
        o.write(0);

        byte[] q = name(qname);
        o.write(q, 0, q.length);
        o.write(0);
        o.write(1);
        o.write(0);
        o.write(1);

        for (String c : cnames) {
            byte[] n = name(c);
            o.write(0xC0);
            o.write(0x0C);
            o.write(0);
            o.write(5);
            o.write(0);
            o.write(1);
            o.write(0);
            o.write(0);
            o.write(0);
            o.write(60);
            o.write(0);
            o.write(n.length);
            o.write(n, 0, n.length);
        }
        if (ip != null) {
            o.write(0xC0);
            o.write(0x0C);
            o.write(0);
            o.write(1);
            o.write(0);
            o.write(1);
            o.write(0);
            o.write(0);
            o.write(0);
            o.write(30);
            o.write(0);
            o.write(4);
            for (String p : ip.split("\\.")) o.write(Integer.parseInt(p));
        }
        return o.toByteArray();
    }

    /* ---------------- 1 CNAME ---------------- */

    static void cname() throws Exception {
        byte[] r = response("analytics.shop.com",
                new String[]{"shop.com.sc.omtrdc.net"}, "1.2.3.4");
        String[] chain = DnsPacket.cnameChain(r, 0, r.length);
        check("取出一条 CNAME", chain.length == 1);
        check("CNAME 目标是追踪域", "shop.com.sc.omtrdc.net".equals(chain[0]));

        byte[] two = response("a.shop.com",
                new String[]{"b.shop.com", "metrics.example.org"}, "5.6.7.8");
        String[] c2 = DnsPacket.cnameChain(two, 0, two.length);
        check("两跳链按顺序取出", c2.length == 2
                && "b.shop.com".equals(c2[0]) && "metrics.example.org".equals(c2[1]));

        byte[] none = response("www.example.com", new String[0], "9.9.9.9");
        check("没有 CNAME 返回空数组", DnsPacket.cnameChain(none, 0, none.length).length == 0);

        // 压缩指针自指：必须靠跳数上限止损，不能死循环
        byte[] loop = new byte[]{0x12, 0x34, (byte) 0xC0, 0x00, 0, 0, 0, 0, 0, 0, 0, 0};
        check("自指指针不死循环", DnsPacket.readName(loop, 2, 0, 12) == null);

        byte[] ok = name("cdn.example.com");
        check("普通名字可读出", "cdn.example.com".equals(
                DnsPacket.readName(ok, 0, 0, ok.length)));

        // 指针指向报文里的另一处名字
        byte[] buf = new byte[64];
        byte[] n = name("tracker.net");
        System.arraycopy(n, 0, buf, 0, n.length);
        buf[20] = (byte) 0xC0;
        buf[21] = 0x00;
        check("压缩指针可跟随", "tracker.net".equals(DnsPacket.readName(buf, 20, 0, 64)));
    }

    /* ---------------- 2 TTL / 缓存 ---------------- */

    static void ttlCache() throws Exception {
        byte[] r = response("a.example.com", new String[]{"b.example.com"}, "1.1.1.1");
        check("取最小 TTL", DnsPacket.minTtl(r, 0, r.length) == 30);
        check("改写 TTL 命中两条记录", DnsPacket.stampTtl(r, 0, r.length, 5) == 2);
        check("改写后最小 TTL 为 5", DnsPacket.minTtl(r, 0, r.length) == 5);

        byte[] q = DnsPacket.buildQuery("x.com");
        byte[] err = DnsPacket.buildError(q, 0, q.length, 3);
        check("rcode 读出 NXDOMAIN", DnsPacket.rcode(err, 0) == 3);
        check("空应答没有 TTL", DnsPacket.minTtl(err, 0, err.length) == -1);

        DnsCache c = new DnsCache();
        byte[] good = response("a.example.com", new String[0], "1.1.1.1");
        c.put("a|1", good, 60);
        check("60 秒 TTL 原样保留", c.peek("a|1").ttl == 60_000L);
        check("新鲜命中", c.get("a|1") != null);
        check("未命中返回 null", c.get("nope") == null);

        c.put("b|1", good, 1);
        check("过短 TTL 抬到 30 秒", c.peek("b|1").ttl == DnsCache.MIN_TTL_MS);
        c.put("c|1", good, 99999);
        check("过长 TTL 压到 1 小时", c.peek("c|1").ttl == DnsCache.MAX_TTL_MS);

        byte[] nx = DnsPacket.buildError(q, 0, q.length, 3);
        c.put("d|1", nx, -1);
        check("失败应答记为负缓存", c.peek("d|1").negative);
        check("负缓存时长 30 秒", c.peek("d|1").ttl == DnsCache.NEG_TTL_MS);
        check("负缓存也照常回报", c.get("d|1") != null);
        check("负缓存不进兜底层", c.stale("d|1") == null);

        DnsCache.Entry e = c.peek("a|1");
        check("到期前算新鲜", e.fresh(e.at + e.ttl - 1));
        check("到期即不新鲜", !e.fresh(e.at + e.ttl));
        check("过期后仍可兜底", e.usable(e.at + e.ttl + 1));
        check("超出兜底窗口丢弃", !e.usable(e.at + e.ttl + DnsCache.STALE_MS));

        DnsCache small = new DnsCache();
        for (int i = 0; i < 1100; i++) small.put("k" + i, good, 600);
        check("容量上限生效", small.size() <= 1024);
        check("最旧的被淘汰", small.peek("k0") == null);
        small.clear();
        check("清空后为空", small.size() == 0);
    }

    /* ---------------- 3 查询合并 ---------------- */

    static void coalesce() throws Exception {
        final Coalescer c = new Coalescer();
        final int[] owners = {0};
        Thread[] ts = new Thread[8];
        for (int i = 0; i < ts.length; i++) {
            ts[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    Coalescer.Ticket t = c.begin("same|1");
                    if (t.owner) {
                        synchronized (owners) {
                            owners[0]++;
                        }
                    }
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        check("同域名并发只放一个出去", owners[0] == 1);

        Coalescer d = new Coalescer();
        Coalescer.Ticket owner = d.begin("k|1");
        Coalescer.Ticket waiter = d.begin("k|1");
        check("第一个是主人", owner.owner);
        check("第二个是等待者", !waiter.owner);
        byte[] data = new byte[]{1, 2, 3};
        d.finish("k|1", data);
        check("等待者拿到同一份结果", waiter.await(500) == data);
        check("槽位用完即清", d.size() == 0);
        check("下一轮重新成为主人", d.begin("k|1").owner);

        Coalescer t2 = new Coalescer();
        t2.begin("slow|1");
        check("等不到就超时返回 null", t2.begin("slow|1").await(60) == null);

        Coalescer t3 = new Coalescer();
        t3.begin("x|1");
        Coalescer.Ticket w3 = t3.begin("x|1");
        t3.clear();
        check("关闭时唤醒等待者", w3.await(200) == null);
    }

    /* ---------------- 4 修饰符与解析 ---------------- */

    static void modifiers() {
        check("important 单独成类", Modifiers.classify(false, "important") == Modifiers.IMPORTANT);
        check("important 带收窄条件仍是 important",
                Modifiers.classify(false, "important,third-party") == Modifiers.IMPORTANT);
        check("例外上的 important 不影响放行",
                Modifiers.classify(true, "important") == Modifiers.OK);
        check("redirect 单独成类",
                Modifiers.classify(false, "redirect=noop.js") == Modifiers.REDIRECT);
        check("redirect-rule 不能独立执行",
                Modifiers.classify(false, "redirect-rule=noop.js") == Modifiers.DROP);
        check("例外上的 redirect 不收",
                Modifiers.classify(true, "redirect=noop.js") == Modifiers.DROP);
        check("csp 仍然丢弃", Modifiers.classify(false, "csp=script-src *") == Modifiers.DROP);

        check("取出 redirect 值", "noop.js".equals(
                Modifiers.value("redirect=noop.js,third-party", "redirect")));
        check("没有该修饰符返回 null", Modifiers.value("important", "redirect") == null);
        check("优先级后缀原样保留", "noop.js:5".equals(
                Modifiers.value("redirect=noop.js:5", "redirect")));
        check("带优先级的资源名照样认", Noop.of("noop.js:5").type.contains("javascript"));

        ListParser.Out o = new ListParser.Out();
        ListParser.parseLine("||ads.a.com^$important", o);
        check("important 进独立集合", o.impDomains.contains("ads.a.com"));
        check("important 不混进普通域名库", o.domains.isEmpty());
        check("important 计入总数", o.total() == 1);

        o = new ListParser.Out();
        ListParser.parseLine("/ads/banner.js$redirect=noop.js", o);
        check("redirect 规则照常拦截", o.urls.contains("/ads/banner.js"));
        check("redirect 资源被记下", "noop.js".equals(o.redirects.get("/ads/banner.js")));

        o = new ListParser.Out();
        ListParser.parseLine("||cdn.x.com/js/ads.js$redirect=1x1.gif", o);
        check("带域名的 redirect 规则", o.urls.contains("cdn.x.com/js/ads.js"));
        check("带域名的 redirect 资源", "1x1.gif".equals(o.redirects.get("cdn.x.com/js/ads.js")));

        o = new ListParser.Out();
        ListParser.parseLine("@@||x.com^$redirect=noop.js", o);
        check("例外上的 redirect 被丢弃", o.droppedModifier == 1 && o.allowDomains.isEmpty());

        o = new ListParser.Out();
        ListParser.parseLine("||x.com^$redirect-rule=noop.js", o);
        check("redirect-rule 不入库", o.urls.isEmpty() && o.droppedModifier == 1);

        // 页面上下文条件：拦注册域又限定站点时，忽略条件会把整站封死，只能丢
        o = new ListParser.Out();
        ListParser.parseLine("||baidu.com^$domain=pos.baidu.com", o);
        check("注册域 + 站点条件被丢弃", o.droppedScoped == 1 && o.domains.isEmpty());

        o = new ListParser.Out();
        ListParser.parseLine("||puma.com^$popup,domain=hupu.com", o);
        check("品牌注册域 + 弹窗条件被丢弃", o.droppedScoped == 1);

        o = new ListParser.Out();
        ListParser.parseLine("||www.baidu.com^$domain=x.com", o);
        check("www 视同注册域", o.droppedScoped == 1 && o.domains.isEmpty());

        o = new ListParser.Out();
        ListParser.parseLine("||ads.site.com^$domain=site.com", o);
        check("子域仍照拦", o.domains.contains("ads.site.com") && o.droppedScoped == 0);

        o = new ListParser.Out();
        ListParser.parseLine("||alimama.cn^$third-party,domain=~alimama.com", o);
        check("全否定条件不算收窄", o.domains.contains("alimama.cn") && o.droppedScoped == 0);

        o = new ListParser.Out();
        ListParser.parseLine("||tanx.com^$third-party,domain=~1688.com|~tmall.com", o);
        check("否定列表照样入库", o.domains.contains("tanx.com"));

        o = new ListParser.Out();
        ListParser.parseLine("||bbc.co.uk^$domain=x.com", o);
        check("两级后缀也算注册域", o.droppedScoped == 1);
    }

    /* ---------------- 5 判定优先级 ---------------- */

    static Set<String> set(String... items) {
        Set<String> s = new HashSet<>();
        Collections.addAll(s, items);
        return s;
    }

    static Decision.Sets snapshot(boolean withImportant) {
        Decision.Sets s = new Decision.Sets();
        s.base = RuleSet.of(set("tracker.com", "clean.com"));
        s.feedAllows = RuleSet.of(set("tracker.com"));
        if (withImportant) {
            s.important = RuleSet.of(set("tracker.com"));
            s.importantUrls = UrlIndex.of(set("cdn.example.net/js/a.js"));
        }
        Map<String, String> red = new HashMap<>();
        red.put("cdn.example.net/js/a.js", "noop.js");
        s.redirects = red;
        return s;
    }

    static void priority() {
        check("订阅例外优先于域名库",
                !Decision.match(snapshot(false), "tracker.com", "/").blocked);
        Rules.Hit h = Decision.match(snapshot(true), "tracker.com", "/");
        check("$important 压过订阅例外", h.blocked);
        check("归因写明 important", h.rule != null && h.rule.contains("important"));

        Decision.Sets s = snapshot(true);
        s.userAllow = set("tracker.com");
        check("用户白名单最大", !Decision.match(s, "tracker.com", "/").blocked);

        s = snapshot(false);
        s.userBlock = set("tracker.com");
        check("用户拦截压过订阅例外", Decision.match(s, "tracker.com", "/").blocked);

        s = snapshot(true);
        s.urlAllow = set("cdn.example.net/*");
        check("用户 URL 白名单压过 important",
                !Decision.match(s, "cdn.example.net", "/js/a.js").blocked);

        s = snapshot(true);
        Rules.Hit ih = Decision.match(s, "cdn.example.net", "/js/a.js");
        check("important URL 规则命中", ih.blocked);
        check("命中时带出替换资源", "noop.js".equals(ih.redirect));

        s = new Decision.Sets();
        s.feedUrls = UrlIndex.of(set("cdn.example.net/js/b.js"));
        s.redirects = Collections.singletonMap("cdn.example.net/js/b.js", "1x1.gif");
        Rules.Hit fh = Decision.match(s, "cdn.example.net", "/js/b.js");
        check("普通规则也带替换资源", fh.blocked && "1x1.gif".equals(fh.redirect));

        s = new Decision.Sets();
        s.wild = Wild.of(set("*.adhost.net"));
        check("通配拦得住", Decision.match(s, "srv.adhost.net", "/x").blocked);

        s = new Decision.Sets();
        check("内置特征兜底", Decision.match(s, "pagead2.googlesyndication.com", "/pagead/x").blocked);
        check("普通请求放行", !Decision.match(s, "www.example.com", "/index.html").blocked);
        check("空主机名放行", !Decision.match(s, "", "/").blocked);
    }

    /* ---------------- 6 替换资源 ---------------- */

    static void noop() {
        Noop.Res js = Noop.of("noop.js");
        check("JS 资源为空脚本", js.body.length == 0 && js.type.contains("javascript"));
        Noop.Res gif = Noop.of("1x1.gif");
        check("GIF 资源 43 字节", gif.body.length == 43);
        check("GIF 文件头正确", (gif.body[0] & 0xff) == 'G'
                && (gif.body[1] & 0xff) == 'I' && (gif.body[2] & 0xff) == 'F');
        check("GIF 类型正确", "image/gif".equals(gif.type));
        check("empty 是空文本", Noop.of("empty").body.length == 0);
        check("未知资源退化为空文本", Noop.of("noopmp4-1s").type.startsWith("text/plain"));
        check("按路径猜 JS", Noop.byPath("/a/b.js?x=1") == Noop.of("noop.js"));
        check("按路径猜图片", Noop.byPath("/a/b.PNG") == Noop.of("1x1.gif"));
        check("JSON 不猜", Noop.byPath("/api/list.json") == null);
    }

    /* ---------------- 7 HttpGate ---------------- */

    static final class Rig {
        final ByteArrayOutputStream up = new ByteArrayOutputStream();
        final ByteArrayOutputStream down = new ByteArrayOutputStream();
        final HttpGate gate;

        Rig(HttpGate.Matcher m) {
            gate = new HttpGate(up, down, new Object(), null, m);
        }

        void feed(String s) throws IOException {
            byte[] d = s.getBytes(L1);
            gate.feed(d, 0, d.length);
        }

        String up() {
            return new String(up.toByteArray(), L1);
        }

        byte[] downBytes() {
            return down.toByteArray();
        }

        String down() {
            return new String(downBytes(), L1);
        }
    }

    static final HttpGate.Matcher BLOCK_ALL = new HttpGate.Matcher() {
        @Override
        public Rules.Hit match(String host, String path) {
            return new Rules.Hit(true, "test");
        }
    };

    static void gate() throws IOException {
        Rig r = new Rig(new HttpGate.Matcher() {
            @Override
            public Rules.Hit match(String host, String path) {
                return new Rules.Hit(true, "test", "noop.js");
            }
        });
        r.feed("GET /x.js HTTP/1.1\r\nHost: ads.t.com\r\n\r\n");
        String resp = r.down();
        check("替换资源回 200", resp.startsWith("HTTP/1.1 200 OK"));
        check("替换资源类型是 JS", resp.contains("Content-Type: text/javascript"));
        check("空脚本长度为 0", resp.contains("Content-Length: 0"));
        check("替换时不打扰上游", r.up().isEmpty());

        Rig img = new Rig(BLOCK_ALL);
        img.feed("GET /banner/x.png HTTP/1.1\r\nHost: ads.t.com\r\n\r\n");
        byte[] ib = img.downBytes();
        String ih = new String(ib, L1);
        check("图片换成 1×1", ih.contains("Content-Length: 43"));
        check("图片本体是 GIF", ib[ib.length - 43] == 'G' && ib[ib.length - 1] == 0x3B);

        Rig json = new Rig(BLOCK_ALL);
        json.feed("GET /api/list.json HTTP/1.1\r\nHost: ads.t.com\r\n\r\n");
        check("JSON 仍回 204", json.down().startsWith("HTTP/1.1 204"));

        Rig head = new Rig(BLOCK_ALL);
        head.feed("HEAD /x.js HTTP/1.1\r\nHost: ads.t.com\r\n\r\n");
        String hs = head.down();
        check("HEAD 只回头", hs.endsWith("\r\n\r\n") && hs.contains("Content-Length: 0"));

        Rig ka = new Rig(new HttpGate.Matcher() {
            @Override
            public Rules.Hit match(String host, String path) {
                return path.startsWith("/ad/")
                        ? new Rules.Hit(true, "test") : Rules.Hit.PASS;
            }
        });
        ka.feed("POST /ad/1 HTTP/1.1\r\nHost: a.t.com\r\nContent-Length: 5\r\n\r\nhello"
                + "GET /normal HTTP/1.1\r\nHost: a.t.com\r\n\r\n");
        check("拦截后仍能解析下一个请求", ka.up().contains("GET /normal"));
        check("被拦请求的正文不进上游", !ka.up().contains("hello"));
        check("第一个请求回 204", ka.down().startsWith("HTTP/1.1 204"));
    }
}

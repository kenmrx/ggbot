import com.ggbot.app.HttpGate;
import com.ggbot.app.Rules;
import com.ggbot.app.UrlRule;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;

/**
 * HttpGate 分帧 + URL 规则的桌面单元测试。
 * 只碰纯逻辑，不加载 Android 运行时（android.jar 仅作编译/链接占位）。
 */
public class HttpTest {

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

    static byte[] b(String s) {
        return s.getBytes(L1);
    }

    static String s(ByteArrayOutputStream o) {
        return new String(o.toByteArray(), L1);
    }

    static final class Rig {
        final ByteArrayOutputStream up = new ByteArrayOutputStream();
        final ByteArrayOutputStream down = new ByteArrayOutputStream();
        final HttpGate gate = new HttpGate(up, down, new Object());

        void feed(String text) throws IOException {
            byte[] d = b(text);
            gate.feed(d, 0, d.length);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 规则匹配 ==");
        check("正文路径放行", !Rules.blockUrl("www.example.com", "/download/app.apk"));
        check("下载页放行（不是 /ad）", !Rules.blockUrl("www.example.com", "/download/adapter.html"));
        check("/ad/ 段拦截", Rules.blockUrl("www.example.com", "/ad/300x250.png"));
        check("/ads/ 段拦截", Rules.blockUrl("www.example.com", "/ads/list.json"));
        check("/banner/ 段拦截", Rules.blockUrl("img.example.com", "/banner/home.jpg"));
        check("广告域名拦截", Rules.blockUrl("pagead2.googlesyndication.com", "/pagead/x"));
        check("路径特征拦截", Rules.blockUrl("s.example.com", "/cpro/ui.js"));
        check("查询串不参与段匹配",
                !Rules.blockUrl("www.example.com", "/search?q=ad&p=2"));
        check("大小写不敏感", Rules.blockUrl("WWW.EXAMPLE.COM", "/AD/x.png"));
        check("空路径只看域名", !Rules.blockUrl("www.example.com", ""));
        check("空主机名放行", !Rules.blockUrl("", "/ad/x.png"));

        System.out.println("== 单请求转发 ==");
        {
            Rig r = new Rig();
            r.feed("GET /index.html HTTP/1.1\r\nHost: www.example.com\r\n\r\n");
            check("头部原样上送", s(r.up).equals(
                    "GET /index.html HTTP/1.1\r\nHost: www.example.com\r\n\r\n"));
            check("不回任何数据给客户端", r.down.size() == 0);
            check("未计数拦截", r.gate.blockedCount() == 0);
        }

        System.out.println("== 拦截并就地应答 ==");
        {
            Rig r = new Rig();
            r.feed("GET /ad/300x250.png HTTP/1.1\r\nHost: www.example.com\r\n\r\n");
            check("不给服务器发任何字节", r.up.size() == 0);
            // 图片不再回 204：换成 1×1 透明 GIF，页面不会因为「加载失败」而重试或报错
            check("图片换成 1×1 GIF", s(r.down).startsWith("HTTP/1.1 200 OK")
                    && s(r.down).contains("Content-Length: 43"));
            check("拦截计数为 1", r.gate.blockedCount() == 1);
        }
        {
            Rig r = new Rig();
            r.feed("GET /ad/list.json HTTP/1.1\r\nHost: www.example.com\r\n\r\n");
            check("没有合适替代资源时回 204",
                    s(r.down).startsWith("HTTP/1.1 204 No Content"));
            check("204 以空行收尾", s(r.down).endsWith("\r\n\r\n"));
        }

        System.out.println("== keep-alive 连续请求 ==");
        {
            Rig r = new Rig();
            r.feed("GET /a.html HTTP/1.1\r\nHost: www.example.com\r\n\r\n"
                    + "GET /ads/x.js HTTP/1.1\r\nHost: www.example.com\r\n\r\n"
                    + "GET /b.html HTTP/1.1\r\nHost: www.example.com\r\n\r\n");
            String up = s(r.up);
            check("第一条被转发", up.contains("/a.html"));
            check("第二条未上送", !up.contains("/ads/x.js"));
            check("第三条被转发", up.contains("/b.html"));
            check("只拦了一条", r.gate.blockedCount() == 1);
        }

        System.out.println("== 带请求体的 POST ==");
        {
            Rig r = new Rig();
            String body = "{\"k\":\"v\"}";
            r.feed("POST /api/submit HTTP/1.1\r\nHost: www.example.com\r\n"
                    + "Content-Length: " + body.length() + "\r\n\r\n" + body);
            check("POST 头与体整体上送",
                    s(r.up).endsWith("\r\n\r\n" + body));
        }
        {
            Rig r = new Rig();
            String body = "0123456789";
            r.feed("POST /ads/collect HTTP/1.1\r\nHost: www.example.com\r\n"
                    + "Content-Length: 10\r\n\r\n" + body);
            check("被拦的请求体不上送", r.up.size() == 0);
            check("被拦的请求体被丢弃", r.gate.blockedCount() == 1);
        }

        System.out.println("== 请求体分片到达 ==");
        {
            Rig r = new Rig();
            r.feed("POST /api/x HTTP/1.1\r\nHost: www.example.com\r\nContent-Length: 10\r\n\r\n01234");
            check("前半段只上去头和前 5 字节",
                    s(r.up).endsWith("\r\n\r\n01234"));
            r.feed("56789");
            check("后半段补齐", s(r.up).endsWith("\r\n\r\n0123456789"));
        }

        System.out.println("== 头部跨包 ==");
        {
            Rig r = new Rig();
            r.feed("GET /index.html HTT");
            check("头没完整前一个字节都不发", r.up.size() == 0);
            r.feed("P/1.1\r\nHost: www.example.com\r\n\r\n");
            check("补齐后一次发出", s(r.up).equals(
                    "GET /index.html HTTP/1.1\r\nHost: www.example.com\r\n\r\n"));
        }

        System.out.println("== 非 HTTP 流量退透传 ==");
        {
            Rig r = new Rig();
            String pre = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
            r.feed(pre);
            check("h2 前言整体透传", s(r.up).equals(pre));
            r.feed("\u0000\u0000\u0000binary");
            check("后续二进制也透传", s(r.up).endsWith("binary"));
            check("不误判为拦截", r.gate.blockedCount() == 0);
        }
        {
            Rig r = new Rig();
            r.feed("GET /x HTTP/1.1\r\nHost: www.example.com\r\nTransfer-Encoding: chunked\r\n\r\n");
            r.feed("5\r\nhello\r\n0\r\n\r\n");
            check("分块请求体退透传", s(r.up).endsWith("5\r\nhello\r\n0\r\n\r\n"));
        }

        System.out.println("== 绝对 URI 形式 ==");
        {
            Rig r = new Rig();
            r.feed("GET http://cdn.example.com/ads/x.gif HTTP/1.1\r\n\r\n");
            check("代理式绝对 URI 也能识别并拦截", r.up.size() == 0 && r.gate.blockedCount() == 1);
        }

        System.out.println("== URL 规则（Jinx 式通配） ==");
        check("归一化去协议头", "example.com/ad/*".equals(UrlRule.normalize("https://example.com/ad/*")));
        check("归一化去尾斜杠", "example.com".equals(UrlRule.normalize("Example.com/")));
        check("裸字符规则被丢弃", UrlRule.normalize("!!!") == null);
        check("路径通配", UrlRule.matches("example.com/ad/*", "example.com", "/ad/300.png"));
        check("子域也算命中", UrlRule.matches("example.com/ad/*", "img.example.com", "/ad/x.js"));
        check("/ad/* 不误伤 /ads/", !UrlRule.matches("example.com/ad/*", "example.com", "/ads/x.js"));
        check("域名带通配", UrlRule.matches("*.example.com/ads/*", "a.example.com", "/ads/x"));
        check("通配不跨域", !UrlRule.matches("*.example.com/ads/*", "example.org", "/ads/x"));
        check("只写域名=整站", UrlRule.matches("example.com", "example.com", "/anything"));
        check("只写域名含子域", UrlRule.matches("example.com", "m.example.com", "/x"));
        check("只写路径=任意域名", UrlRule.matches("/cpro/", "b.site.cn", "/cpro/ui.js"));
        check("只写路径不误伤", !UrlRule.matches("/cpro/", "b.site.cn", "/normal/ui.js"));
        check("单字符通配 ?", UrlRule.matches("example.com/a?c", "example.com", "/abc"));
        check("单字符通配不越界", !UrlRule.matches("example.com/a?c", "example.com", "/abbc"));
        check("通配可跨斜杠", UrlRule.matches("example.com/*/ad", "example.com", "/a/b/ad"));
        check("规则区分主机大小写不敏感",
                UrlRule.matches("example.com/ad/*", "EXAMPLE.COM", "/AD/x.png"));

        System.out.println("== 请求记录 ==");
        {
            Rig r = new Rig();
            r.feed("GET /ads/x.js HTTP/1.1\r\nHost: www.example.com\r\n\r\n");
            check("拦截事件可回溯", r.gate.blockedCount() == 1);
        }

        System.out.println("== Host 尾点（合法 DNS 写法不得绕过拦截） ==");
        check("剥尾点", "example.com".equals(Rules.stripTrailingDots("example.com.")));
        check("连续尾点剥净", "A.B".equals(Rules.stripTrailingDots("A.B..")));
        check("无尾点原样", "example.com".equals(Rules.stripTrailingDots("example.com")));
        check("null 原样", Rules.stripTrailingDots(null) == null);
        check("纯点剥成空串", "".equals(Rules.stripTrailingDots("...")));

        System.out.println("== 方法队列（HEAD 配对给 RespGate 用） ==");
        {
            java.util.Queue<String> mq = new java.util.LinkedList<>();
            HttpGate g = new HttpGate(new ByteArrayOutputStream(),
                    new ByteArrayOutputStream(), new Object(), null, mq);
            byte[] d = b("HEAD /page.html HTTP/1.1\r\nHost: www.example.com\r\n\r\n");
            g.feed(d, 0, d.length);
            check("转发的 HEAD 入队", "HEAD".equals(mq.poll()) && mq.isEmpty());
            byte[] d2 = b("GET /ad/300x250.png HTTP/1.1\r\nHost: www.example.com\r\n\r\n");
            g.feed(d2, 0, d2.length);
            check("被拦请求不入队（本地应答没有服务器响应）", mq.isEmpty());
        }

        System.out.println("== 被拦 chunked 请求体格式坏 ==");
        {
            Rig r = new Rig();
            r.feed("POST /ad/track HTTP/1.1\r\nHost: www.example.com\r\n"
                    + "Transfer-Encoding: chunked\r\n\r\nZZZZ\r\nGARBAGE-BYTES");
            check("坏块不外泄给服务器", !s(r.up).contains("ZZZZ") && !s(r.up).contains("GARBAGE"));
            check("后续仍走透传（连接已不可二次解析）",
                    r.gate.blockedCount() == 1);
            r.feed("GET /normal HTTP/1.1\r\nHost: www.example.com\r\n\r\n");
            check("坏块之后的字节原样透传", s(r.up).endsWith(
                    "GET /normal HTTP/1.1\r\nHost: www.example.com\r\n\r\n"));
        }

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}

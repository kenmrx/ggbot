import com.ggbot.app.RespGate;
import com.ggbot.app.Rewrite;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * 回归测试：响应头与响应体在同一次读取里到达的场景，以及改写后头部结构的完整性。
 *
 * 第 2 轮在模拟器上做 MITM 端到端实测时，客户端始终收不到完整响应：
 *   - 抓包显示上游 httpbin 的响应是完整的（一个 TLS 记录里带头和体），
 *     但 GGBot 只把响应头发了出去，正文被丢弃；
 *   - 修掉正文丢失后，客户端改报 unexpected end of stream，
 *     因为改写后的响应头丢了结尾的 CRLFCRLF，客户端把正文当头部继续解析。
 * 两处都源于「头 + 体一次喂入」这个此前没有测试覆盖的路径，这里把它固定成断言。
 */
public class RespGateFeedTest {

    static int pass = 0;
    static int fail = 0;

    static void ok(boolean cond, String name) {
        if (cond) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    static final String HEAD =
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 30\r\n\r\n";
    static final String BODY = "User-agent: *\nDisallow: /deny\n";
    static final String FIND = "User-agent";
    static final String REPL = "GGBot-REWRITE-OK";

    static byte[] b(String s) throws Exception {
        return s.getBytes("ISO-8859-1");
    }

    static String s(byte[] a) throws Exception {
        return new String(a, "ISO-8859-1");
    }

    /** 头部结构检查：必须有 CRLFCRLF 结尾、头部行都是合法头部、正文没有混进头部块。 */
    static void checkHead(String out, String expectBody, String tag) {
        int sep = out.indexOf("\r\n\r\n");
        ok(sep > 0, tag + "：响应头以 CRLFCRLF 结束");
        if (sep <= 0) return;
        String headBlock = out.substring(0, sep);
        String[] hl = headBlock.split("\r\n");
        boolean allHeader = hl.length > 0 && hl[0].startsWith("HTTP/");
        for (int i = 1; i < hl.length; i++) if (hl[i].indexOf(':') <= 0) allHeader = false;
        ok(allHeader, tag + "：状态行 + 头部行格式合法");
        ok(hl.length == 3, tag + "：头部行数正确（状态行 + 2 个头部）");
        ok(!headBlock.contains(expectBody), tag + "：正文没有混进头部块");
        ok(out.length() > sep + 4 && out.charAt(sep + 4) == expectBody.charAt(0),
                tag + "：头部结束符后紧跟正文首字节");
    }

    public static void main(String[] args) throws Exception {
        List<Rewrite.Rule> rules = Rewrite.parse("httpbin.org|" + FIND + "|" + REPL);
        int expected = BODY.length() - FIND.length() + REPL.length();
        String expectBody = REPL + ": *\nDisallow: /deny\n";

        // 1) 命中规则：头与体同批到达 -> 改写正文并重算 Content-Length
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RespGate g = new RespGate("httpbin.org", sink, new Object(), rules);
        byte[] all = b(HEAD + BODY);
        g.feed(all, 0, all.length);
        String out = s(sink.toByteArray());
        ok(out.contains(REPL), "同批到达：正文被改写");
        ok(!out.contains(FIND), "同批到达：原查找串已消失");
        ok(out.contains("Content-Length: " + expected), "同批到达：Content-Length 重算为 " + expected);
        ok(out.startsWith("HTTP/1.1 200 OK\r\n"), "同批到达：响应头原样保留");
        ok(out.endsWith(expectBody), "同批到达：正文尾部未被破坏");
        checkHead(out, expectBody, "同批到达");

        // 2) 头与体分两次到达：行为必须与同批到达一致
        ByteArrayOutputStream sink2 = new ByteArrayOutputStream();
        RespGate g2 = new RespGate("httpbin.org", sink2, new Object(), rules);
        byte[] hb = b(HEAD);
        g2.feed(hb, 0, hb.length);
        byte[] bb = b(BODY);
        g2.feed(bb, 0, bb.length);
        String out2 = s(sink2.toByteArray());
        ok(out2.contains(REPL), "分批到达：正文被改写");
        ok(out2.contains("Content-Length: " + expected), "分批到达：Content-Length 重算为 " + expected);
        checkHead(out2, expectBody, "分批到达");

        // 3) 压缩响应：整段原样透传，一个字节都不能少
        String gzipHead = "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 4\r\n\r\n";
        ByteArrayOutputStream sink3 = new ByteArrayOutputStream();
        RespGate g3 = new RespGate("httpbin.org", sink3, new Object(), rules);
        byte[] gz = b(gzipHead + "AB");
        g3.feed(gz, 0, gz.length);
        ok(s(sink3.toByteArray()).equals(gzipHead + "AB"), "gzip 同批到达：整段原样透传");

        // 4) 不命中规则的域名：整段原样透传
        ByteArrayOutputStream sink4 = new ByteArrayOutputStream();
        RespGate g4 = new RespGate("clean.com", sink4, new Object(), rules);
        byte[] cl = b(HEAD + BODY);
        g4.feed(cl, 0, cl.length);
        ok(s(sink4.toByteArray()).equals(HEAD + BODY), "无规则域名：整段原样透传");

        // 5) chunked 响应：整段原样透传
        String chunkHead = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n";
        String chunked = chunkHead + "1e\r\nUser-agent: *\nDisallow: /deny\r\n0\r\n\r\n";
        ByteArrayOutputStream sink5 = new ByteArrayOutputStream();
        RespGate g5 = new RespGate("httpbin.org", sink5, new Object(), rules);
        byte[] ch = b(chunked);
        g5.feed(ch, 0, ch.length);
        ok(s(sink5.toByteArray()).equals(chunked), "chunked：整段原样透传");

        // 6) 同批到达但正文比声明长度短：剩余字节不能被吞掉（截断响应容错）
        ByteArrayOutputStream sink6 = new ByteArrayOutputStream();
        RespGate g6 = new RespGate("httpbin.org", sink6, new Object(), rules);
        byte[] shortBody = b(HEAD + BODY.substring(0, 10));
        g6.feed(shortBody, 0, shortBody.length);
        ok(sink6.size() == 0, "同批到达但正文不足：未凑齐前不发给客户端");

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}

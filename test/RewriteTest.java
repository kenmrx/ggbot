import com.ggbot.app.RespGate;
import com.ggbot.app.Rewrite;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * 内容重写引擎测试（纯 JVM）：规则解析 / 域名匹配 / 正文改写 /
 * 响应网关的长度修复与透传边界。
 */
public class RewriteTest {

    static int pass = 0;

    static void ok(boolean cond, String name) {
        if (!cond) throw new AssertionError("FAIL: " + name);
        System.out.println("ok  " + name);
        pass++;
    }

    public static void main(String[] args) throws Exception {
        List<Rewrite.Rule> rs = Rewrite.parse(
                "# 注释\nexample.com|<script src=\"ad.js\"></script>|\n"
                        + "ad.example.com|tracker|\n坏行没有竖线");
        ok(rs.size() == 2, "规则解析忽略注释与坏行");

        ok(Rewrite.matches("example.com", "example.com"), "精确匹配");
        ok(Rewrite.matches("example.com", "a.b.example.com"), "后缀匹配");
        ok(!Rewrite.matches("example.com", "badexample.com"), "不误匹配半截域名");

        byte[] body = "<html><script src=\"ad.js\"></script>xtracker</html>"
                .getBytes("ISO-8859-1");
        byte[] out = Rewrite.apply(rs, "ad.example.com", body);
        ok(out != null && new String(out, "ISO-8859-1").equals("<html>x</html>"),
                "两条规则先后生效（删除+替换）");

        ok(Rewrite.apply(rs, "other.com", body) == null, "无规则域名不改写");
        ok(Rewrite.apply(rs, "www.example.com", new byte[0]) == null, "空体不改写");

        Object lock = new Object();
        byte[] b2 = "<html><script src=\"ad.js\"></script>xtracker</html>"
                .getBytes("ISO-8859-1");
        String expect = "<html>xtracker</html>";
        byte[] h2 = ("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: "
                + b2.length + "\r\n\r\n").getBytes("ISO-8859-1");

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RespGate g = new RespGate("www.example.com", sink, lock, rs);
        g.feed(h2, 0, h2.length);
        g.feed(b2, 0, b2.length);
        String got = sink.toString("ISO-8859-1");
        ok(got.endsWith(expect), "响应改写后转发");
        ok(got.contains("Content-Length: " + expect.length()), "Content-Length 重写");

        ByteArrayOutputStream sink2 = new ByteArrayOutputStream();
        RespGate g2 = new RespGate("www.example.com", sink2, lock, rs);
        byte[] h3 = ("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: "
                + b2.length + "\r\n\r\n").getBytes("ISO-8859-1");
        g2.feed(h3, 0, h3.length);
        g2.feed(b2, 0, b2.length);
        ok(sink2.toString("ISO-8859-1").endsWith(new String(b2, "ISO-8859-1")), "gzip 响应透传");

        ByteArrayOutputStream sink3 = new ByteArrayOutputStream();
        RespGate g3 = new RespGate("www.example.com", sink3, lock, rs);
        byte[] h4 = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n"
                .getBytes("ISO-8859-1");
        g3.feed(h4, 0, h4.length);
        ok(sink3.toString("ISO-8859-1").endsWith("5\r\nhello\r\n0\r\n\r\n"), "chunked 响应透传");

        ByteArrayOutputStream sink4 = new ByteArrayOutputStream();
        RespGate g4 = new RespGate("clean.com", sink4, lock, rs);
        g4.feed(h2, 0, h2.length);
        g4.feed(b2, 0, b2.length);
        ok(sink4.toString("ISO-8859-1")
                        .equals(new String(h2, "ISO-8859-1") + new String(b2, "ISO-8859-1")),
                "无规则域名透传");

        System.out.println("通过 " + pass + " 项");
    }
}

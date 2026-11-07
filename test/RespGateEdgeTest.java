import com.ggbot.app.RespGate;
import com.ggbot.app.Rewrite;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * 第 17 轮：RespGate 边界定点——2MB 缓冲上限、Content-Length 与实际长度不符、
 * 分段到达、超长头部。全部针对"不截断、不越界、不挂连接"三个不变量。
 */
public class RespGateEdgeTest {

    static int pass = 0;
    static int fail = 0;

    static void ok(boolean cond, String name) {
        if (cond) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    static final String HOST = "edge.ggbot.test";
    static final String FIND = "MARKER";
    static final String REPL = "GGBOT-BIG-OK";

    static byte[] b(String s) throws Exception { return s.getBytes("ISO-8859-1"); }
    static String s(byte[] a) throws Exception { return new String(a, "ISO-8859-1"); }

    static String head(int cl) {
        return "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + cl + "\r\n\r\n";
    }

    static String bigBody(int total, String lead, String tail) {
        StringBuilder sb = new StringBuilder(total);
        sb.append(lead);
        int pad = total - lead.length() - tail.length();
        for (int i = 0; i < pad; i++) sb.append((char) ('a' + (i % 26)));
        sb.append(tail);
        return sb.toString();
    }

    static RespGate feedChunks(ByteArrayOutputStream sink, List<Rewrite.Rule> rules,
                                byte[] all, int chunk) {
        RespGate g = new RespGate(HOST, sink, new Object(), rules);
        int off = 0;
        while (off < all.length) {
            int n = Math.min(chunk, all.length - off);
            try { g.feed(all, off, n); } catch (Exception e) { throw new RuntimeException(e); }
            off += n;
        }
        return g;
    }

    public static void main(String[] args) throws Exception {
        List<Rewrite.Rule> rules = Rewrite.parse(HOST + "|" + FIND + "|" + REPL);

        // 1) 超过 2MB 上限：不缓冲不改写，逐字节透传，不截断不挂连接
        int over = 2 * 1024 * 1024 + 4096;
        String bodyOver = bigBody(over, FIND + "-START", "-END-" + FIND);
        byte[] all1 = b(head(over) + bodyOver);
        ByteArrayOutputStream sink1 = new ByteArrayOutputStream();
        feedChunks(sink1, rules, all1, 8192);
        String out1 = s(sink1.toByteArray());
        ok(out1.equals(head(over) + bodyOver), "2MB+4096：超限响应逐字节透传（无截断/无改写/无丢失）");
        ok(out1.contains(FIND), "2MB+4096：未做改写（规则不命中超限响应）");
        ok(out1.length() == head(over).length() + over, "2MB+4096：总长度与上游完全一致");

        // 2) 恰好 2MB：缓冲上限内，正常改写并重算 Content-Length
        int exact = 2 * 1024 * 1024;
        String body2 = bigBody(exact, FIND, "");
        byte[] all2 = b(head(exact) + body2);
        ByteArrayOutputStream sink2 = new ByteArrayOutputStream();
        feedChunks(sink2, rules, all2, 8192);
        String out2 = s(sink2.toByteArray());
        int expect2 = exact - FIND.length() + REPL.length();
        ok(out2.contains(REPL), "恰好2MB：正文头部被改写");
        ok(out2.contains("Content-Length: " + expect2), "恰好2MB：Content-Length 重算为 " + expect2);
        ok(out2.length() == head(exact).length() + expect2, "恰好2MB：总长度精确匹配");

        // 3) Content-Length 声多实少（声明 100 实发 40）+ 上游 EOF：收口 flush 已收字节
        String body3 = bigBody(40, FIND, "");
        ByteArrayOutputStream sink3 = new ByteArrayOutputStream();
        RespGate g3 = new RespGate(HOST, sink3, new Object(), rules);
        byte[] h3 = b(head(100) + body3);
        g3.feed(h3, 0, h3.length);
        ok(sink3.size() == 0, "CL 声多实少：凑齐前不下发（不提前泄漏）");
        g3.close();
        String out3 = s(sink3.toByteArray());
        int expect3 = 40 - FIND.length() + REPL.length();
        ok(out3.contains("Content-Length: " + expect3), "EOF 收口：Content-Length 强制改为实际下发长度 " + expect3);
        ok(out3.contains(REPL), "EOF 收口：已收正文仍被改写");
        ok(out3.endsWith(body3.substring(FIND.length())), "EOF 收口：正文尾部完整（未截断）");

        // 3b) EOF 时头部都没凑齐：已有字节原样透传
        ByteArrayOutputStream sink3b = new ByteArrayOutputStream();
        RespGate g3b = new RespGate(HOST, sink3b, new Object(), rules);
        byte[] part = b("HTTP/1.1 200 OK\r\nContent-Ty");
        g3b.feed(part, 0, part.length);
        g3b.close();
        ok(s(sink3b.toByteArray()).equals("HTTP/1.1 200 OK\r\nContent-Ty"), "EOF 时头部未凑齐：残余字节原样透传");

        // 4) Content-Length 声少实多（声明 10 实发 30）：头 10 字节改写，多余 20 字节原样透传不丢
        String body4 = bigBody(30, FIND, "");
        String tail4 = body4.substring(10); // 多余的 20 字节
        ByteArrayOutputStream sink4 = new ByteArrayOutputStream();
        RespGate g4 = new RespGate(HOST, sink4, new Object(), rules);
        byte[] all4 = b(head(10) + body4);
        g4.feed(all4, 0, all4.length);
        g4.close(); // 上游 EOF：把缓冲里等下一个响应头的 20 字节也收口下发
        String out4 = s(sink4.toByteArray());
        int expect4 = 10 - FIND.length() + REPL.length();
        ok(out4.startsWith("HTTP/1.1 200 OK\r\n"), "CL 声少实多：状态行保留");
        ok(out4.contains("Content-Length: " + expect4), "CL 声少实多：首响应 Content-Length 重算为 " + expect4);
        ok(out4.contains(REPL), "CL 声少实多：声明的 10 字节被改写");
        ok(out4.endsWith(tail4), "CL 声少实多：多余 20 字节原样透传（未吞掉）");

        // 5) 逐字节分段到达：改写正确
        String body5 = bigBody(64, FIND, "");
        byte[] all5 = b(head(64) + body5);
        ByteArrayOutputStream sink5 = new ByteArrayOutputStream();
        feedChunks(sink5, rules, all5, 1);
        String out5 = s(sink5.toByteArray());
        int expect5 = 64 - FIND.length() + REPL.length();
        ok(out5.contains("Content-Length: " + expect5), "逐字节到达：Content-Length 重算正确");
        ok(out5.endsWith(body5.substring(FIND.length())), "逐字节到达：正文完整");

        // 6) 大响应 256KB 按 7KB 分段：改写且内容完整
        int big6 = 256 * 1024;
        String body6 = bigBody(big6, FIND, "");
        byte[] all6 = b(head(big6) + body6);
        ByteArrayOutputStream sink6 = new ByteArrayOutputStream();
        feedChunks(sink6, rules, all6, 7 * 1024);
        String out6 = s(sink6.toByteArray());
        int expect6 = big6 - FIND.length() + REPL.length();
        ok(out6.contains("Content-Length: " + expect6), "256KB 分段：Content-Length 重算正确");
        ok(out6.length() == head(big6).length() + expect6, "256KB 分段：总长度精确（无丢失）");
        ok(out6.endsWith(body6.substring(FIND.length())), "256KB 分段：正文尾部完整");

        // 7) 头部超 16KB 未出现 CRLFCRLF：整段转透传，一个字节不丢
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < 20000; i++) pad.append('h');
        String weird = "HTTP/1.1 200 OK\r\nX-Pad: " + pad + "\r\n\r\n" + FIND + "TAIL";
        byte[] all7 = b(weird);
        ByteArrayOutputStream sink7 = new ByteArrayOutputStream();
        feedChunks(sink7, rules, all7, 4096);
        ok(s(sink7.toByteArray()).equals(weird), "超长头部：超限转透传，逐字节完整（未改写）");

        // 8) CRLFCRLF 跨读取边界（头部在 \'\\r\\n\\r\' 与 \'\\n\' 之间断开）
        String full8 = head(30) + bigBody(30, FIND, "");
        int cut = full8.indexOf("\r\n\r\n") + 3; // 断在最后一个 \r 后
        ByteArrayOutputStream sink8 = new ByteArrayOutputStream();
        RespGate g8 = new RespGate(HOST, sink8, new Object(), rules);
        byte[] p8a = b(full8.substring(0, cut));
        g8.feed(p8a, 0, p8a.length);
        byte[] p8b = b(full8.substring(cut));
        g8.feed(p8b, 0, p8b.length);
        String out8 = s(sink8.toByteArray());
        int expect8 = 30 - FIND.length() + REPL.length();
        ok(out8.contains("Content-Length: " + expect8), "CRLFCRLF 跨界：仍正确识别头部并重算长度");
        ok(out8.contains(REPL), "CRLFCRLF 跨界：改写生效");

        // 9) Content-Length: 0：立即收口，头部原样
        ByteArrayOutputStream sink9 = new ByteArrayOutputStream();
        RespGate g9 = new RespGate(HOST, sink9, new Object(), rules);
        byte[] h0 = b(head(0));
        g9.feed(h0, 0, h0.length);
        ok(s(sink9.toByteArray()).equals(head(0)), "CL=0：空响应头原样透传");

        // 10) 304 带 Content-Length：无正文状态类，头部立即透传，
        //     keep-alive 下一响应不被当正文吞掉（修复前会挂到 EOF 且连累后续响应）
        ByteArrayOutputStream sinkA = new ByteArrayOutputStream();
        RespGate gA = new RespGate(HOST, sinkA, new Object(), rules);
        byte[] r304 = b("HTTP/1.1 304 Not Modified\r\nETag: \"x\"\r\nContent-Length: 500\r\n\r\n");
        gA.feed(r304, 0, r304.length);
        ok(sinkA.size() == r304.length, "304+CL=500：头部立即透传（不等不存在的正文）");
        byte[] nextA = b(head(20) + bigBody(20, FIND, ""));
        gA.feed(nextA, 0, nextA.length);
        String outA = s(sinkA.toByteArray());
        ok(outA.contains(REPL), "304 之后 keep-alive 下一响应照常缓冲改写");
        ok(outA.contains("Content-Length: " + (20 - FIND.length() + REPL.length())),
                "304 之后下一响应 CL 重算正确");
        gA.close();

        // 11) 204 与 1xx 同类立即透传
        ByteArrayOutputStream sinkB = new ByteArrayOutputStream();
        RespGate gB = new RespGate(HOST, sinkB, new Object(), rules);
        byte[] r204 = b("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n");
        gB.feed(r204, 0, r204.length);
        ok(sinkB.size() == r204.length, "204 立即透传");
        byte[] r100 = b("HTTP/1.1 100 Continue\r\n\r\n");
        gB.feed(r100, 0, r100.length);
        ok(s(sinkB.toByteArray()).contains("100 Continue"), "1xx 立即透传");
        gB.close();

        // 12) HEAD 响应：靠请求方法 FIFO 认出「声明了 CL 也没有正文」
        java.util.Queue<String> mq = new java.util.LinkedList<>();
        mq.add("HEAD");
        ByteArrayOutputStream sinkC = new ByteArrayOutputStream();
        RespGate gC = new RespGate(HOST, sinkC, new Object(), rules, mq);
        byte[] rHead = b("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 4096\r\n\r\n");
        gC.feed(rHead, 0, rHead.length);
        ok(sinkC.size() == rHead.length, "HEAD+CL=4096：头部立即透传，不扣不存在的正文");
        mq.add("GET");
        byte[] rGet = b(head(20) + bigBody(20, FIND, ""));
        gC.feed(rGet, 0, rGet.length);
        String outC = s(sinkC.toByteArray());
        ok(outC.contains(REPL), "HEAD 之后的 GET 响应照常改写（队列按序配对）");
        ok(outC.contains("Content-Length: " + (20 - FIND.length() + REPL.length())),
                "HEAD 之后的 GET 响应 CL 重算正确");
        gC.close();

        // 13) 无队列构造（老路径）：HEAD 退化按普通响应处理，EOF 兜底收口不挂死
        ByteArrayOutputStream sinkD = new ByteArrayOutputStream();
        RespGate gD = new RespGate(HOST, sinkD, new Object(), rules);
        gD.feed(rHead, 0, rHead.length);
        ok(sinkD.size() == 0, "无队列：HEAD 按 CL=4096 等正文（fail-open 保持旧行为）");
        gD.close();
        ok(s(sinkD.toByteArray()).contains("HTTP/1.1 200 OK"), "无队列：EOF 收口把头部交给客户端");

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}

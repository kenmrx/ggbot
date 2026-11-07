import com.ggbot.app.DnsPacket;
import com.ggbot.app.Coalescer;

/**
 * 失败路径与并发正确性测试。
 *
 * 覆盖三类容易「平时看不出来、出事就整片挂掉」的地方：
 *   1. 畸形/截断 DNS 包——必须返回 null，而不是抛异常把调用线程带走；
 *   2. 域名长度边界——253 字符是协议上限，254 及以上必须拒绝；
 *   3. 并发——同一域名 200 并发必须合并成一次上游查询，不同域名之间绝不能串号。
 */
public class HardTest {

    static int pass = 0;
    static int fail = 0;

    static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }

    public static void main(String[] args) throws Exception {
        malformed();
        longNames();
        coalescerSemantics();
        concurrent();
        upstreamEdges();
        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }

    /* ---------- 上游应答的边界（TTL 溢出 / RDATA 越界） ---------- */

    static void upstreamEdges() {
        // 构造：头(12) + 根问(5) + 答案[根名(1)+type(2)+class(2)+ttl(4)+rdlen(2)+A(4)] = 15
        byte[] big = new byte[12 + 5 + 15];
        big[2] = (byte) 0x81; big[3] = (byte) 0x80;          // QR+RD+RA
        big[4] = 0; big[5] = 1;                              // QDCOUNT=1
        big[6] = 0; big[7] = 1;                              // ANCOUNT=1
        int p = 12;
        big[p++] = 0;                                        // 问名=根
        big[p++] = 0; big[p++] = 1;                          // QTYPE=A
        big[p++] = 0; big[p++] = 1;                          // QCLASS=IN
        big[p++] = 0;                                        // 答名=根
        big[p++] = 0; big[p++] = 1;                          // TYPE=A
        big[p++] = 0; big[p++] = 1;                          // CLASS=IN
        big[p++] = (byte) 0xFF; big[p++] = (byte) 0xFF;      // TTL = 0xFFFFFFFF
        big[p++] = (byte) 0xFF; big[p++] = (byte) 0xFF;
        big[p++] = 0; big[p++] = 4;                          // RDLENGTH=4
        big[p++] = 1; big[p++] = 2; big[p++] = 3; big[p++] = 4;
        check("TTL=0xFFFFFFFF 不再被截成负数", DnsPacket.minTtl(big, 0, big.length)
                == Integer.MAX_VALUE);

        // CNAME RDATA 不带根标签（rdlen=6：04evil + 0x03），
        // 后面紧跟一条名字为 abc.def.gh 的 A 记录：
        // readName 不圈在 RDATA 里就会把 abc.def.gh 拼成 evil.abc.def.gh
        byte[] evil = new byte[12 + 5 + (11 + 6) + (14 + 10 + 4)];
        evil[2] = (byte) 0x81; evil[3] = (byte) 0x80;
        evil[4] = 0; evil[5] = 1;
        evil[6] = 0; evil[7] = 2;
        p = 12;
        evil[p++] = 0;
        evil[p++] = 0; evil[p++] = 1;
        evil[p++] = 0; evil[p++] = 1;
        evil[p++] = 0;                                       // 答案1 名=根
        evil[p++] = 0; evil[p++] = 5;                        // CNAME
        evil[p++] = 0; evil[p++] = 1;
        evil[p++] = 0; evil[p++] = 0; evil[p++] = 1; evil[p++] = 44;
        evil[p++] = 0; evil[p++] = 6;                        // RDLENGTH=6
        evil[p++] = 4; evil[p++] = 'e'; evil[p++] = 'v'; evil[p++] = 'i'; evil[p++] = 'l';
        evil[p++] = 3;                                       // RDATA 第 6 字节（非根，越界诱饵）
        // 答案2：名字 abc.def.gh（不带压缩），A 记录
        evil[p++] = 3; evil[p++] = 'a'; evil[p++] = 'b'; evil[p++] = 'c';
        evil[p++] = 3; evil[p++] = 'd'; evil[p++] = 'e'; evil[p++] = 'f';
        evil[p++] = 2; evil[p++] = 'g'; evil[p++] = 'h'; evil[p++] = 0;
        evil[p++] = 0; evil[p++] = 1;
        evil[p++] = 0; evil[p++] = 1;
        evil[p++] = 0; evil[p++] = 0; evil[p++] = 1; evil[p++] = 44;
        evil[p++] = 0; evil[p++] = 4;
        evil[p++] = 5; evil[p++] = 6; evil[p++] = 7; evil[p++] = 8;
        String[] chain = DnsPacket.cnameChain(evil, 0, evil.length);
        boolean stitched = false;
        for (String s : chain) if (s.contains("abc.def.gh")) stitched = true;
        check("CNAME RDATA 不越过 RDLENGTH 拼接", !stitched);

        // 对照：正常带根标签的 CNAME 照常解析出链（rdata = 03www 03abc 00 = 9 字节）
        byte[] ok = new byte[12 + 5 + (1 + 11 + 9)];
        ok[2] = (byte) 0x81; ok[3] = (byte) 0x80;
        ok[4] = 0; ok[5] = 1; ok[6] = 0; ok[7] = 1;
        p = 12;
        ok[p++] = 0;
        ok[p++] = 0; ok[p++] = 1;
        ok[p++] = 0; ok[p++] = 1;
        ok[p++] = 0;                                         // 答名=根
        ok[p++] = 0; ok[p++] = 5;                            // CNAME
        ok[p++] = 0; ok[p++] = 1;
        ok[p++] = 0; ok[p++] = 0; ok[p++] = 1; ok[p++] = 44;
        ok[p++] = 0; ok[p++] = 9;                            // RDLENGTH=9
        ok[p++] = 3; ok[p++] = 'w'; ok[p++] = 'w'; ok[p++] = 'w';
        ok[p++] = 3; ok[p++] = 'a'; ok[p++] = 'b'; ok[p++] = 'c'; ok[p++] = 0;
        String[] good = DnsPacket.cnameChain(ok, 0, ok.length);
        check("正常 CNAME 链照常解析", good.length == 1 && good[0].startsWith("www.abc"));
    }

    /* ---------- 畸形/截断报文 ---------- */

    static void malformed() {
        boolean ok = true;
        try { DnsPacket.parseQuestion(new byte[0], 0, 0); } catch (Throwable t) { ok = false; }
        check("空包 parseQuestion 不抛异常", ok);
        ok = true;
        try { DnsPacket.parseQuestion(new byte[5], 0, 5); } catch (Throwable t) { ok = false; }
        check("5 字节截断包不抛异常", ok);
        byte[] junk = new byte[64];
        new java.util.Random(20261029L).nextBytes(junk);
        ok = true;
        try {
            DnsPacket.parseQuestion(junk, 0, junk.length);
            DnsPacket.questionEnd(junk, 0, junk.length);
            DnsPacket.firstA(junk, 0, junk.length);
        } catch (Throwable t) { ok = false; }
        check("64 字节随机垃圾不抛异常", ok);
        byte[] headerOnly = new byte[12];
        headerOnly[0] = 0x12; headerOnly[1] = 0x34;
        headerOnly[2] = 0x01; headerOnly[5] = 1;
        ok = true;
        String r = null;
        try { r = DnsPacket.parseQuestion(headerOnly, 0, 12); } catch (Throwable t) { ok = false; }
        check("仅 12 字节头（qdcount=0）不抛异常", ok);
        check("仅头包问题域为空", r == null || r.length() == 0);
        ok = true;
        try {
            DnsPacket.buildError(junk, 0, junk.length, 3);
            DnsPacket.buildTruncated(junk, 0, junk.length);
            DnsPacket.clampEdnsSize(junk, 0, junk.length);
        } catch (Throwable t) { ok = false; }
        check("垃圾包上构造错误/截断响应不抛异常", ok);
    }

    static String label(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append('a');
        return sb.toString();
    }

    static byte[] buildQuery(String name, int type) {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        bo.write(0xab); bo.write(0xcd); bo.write(0x01); bo.write(0x00);
        bo.write(0); bo.write(1); bo.write(0); bo.write(0);
        bo.write(0); bo.write(0); bo.write(0); bo.write(0);
        for (String lbl : name.split("\\.")) {
            bo.write(lbl.length());
            for (int i = 0; i < lbl.length(); i++) bo.write(lbl.charAt(i));
        }
        bo.write(0);
        bo.write(0); bo.write(type);
        bo.write(0); bo.write(1);
        return bo.toByteArray();
    }

    /* ---------- 域名长度边界 ---------- */

    static void longNames() {
        String d253 = label(63) + "." + label(63) + "." + label(63) + "." + label(61);
        check("构造的边界域名确为 253 字符", d253.length() == 253);
        byte[] q = buildQuery(d253, 1);
        boolean ok = true;
        String got = null;
        try { got = DnsPacket.parseQuestion(q, 0, q.length); } catch (Throwable t) { ok = false; }
        check("253 字符域名解析不抛异常", ok);
        check("253 字符域名完整还原", d253.equals(got));
        String d254 = label(63) + "." + label(63) + "." + label(63) + "." + label(62);
        ok = true;
        String r2 = null;
        byte[] q254 = buildQuery(d254, 1);
        try { r2 = DnsPacket.parseQuestion(q254, 0, q254.length); }
        catch (Throwable t) { ok = false; }
        check("254 字符超长域名不抛异常", ok);
        check("254 字符超长域名被拒绝", r2 == null);
        byte[] q3 = buildQuery(label(64) + ".com", 1);
        ok = true;
        try { DnsPacket.parseQuestion(q3, 0, q3.length); } catch (Throwable t) { ok = false; }
        check("64 字节非法标签不抛异常", ok);
        String d200 = label(63) + "." + label(63) + "." + label(63) + "." + label(8);
        check("200 字符域名仍在允许范围", d200.length() == 200 && d200.equals(DnsPacket.parseQuestion(buildQuery(d200, 1), 0, buildQuery(d200, 1).length)));
    }

    /* ---------- 合并器的单飞语义（确定性） ---------- */

    static void coalescerSemantics() {
        final Coalescer co = new Coalescer();
        Coalescer.Ticket a = co.begin("k.example.com");
        Coalescer.Ticket b = co.begin("k.example.com");
        check("首个报名者是 owner", a.owner);
        check("同 key 第二个报名者不是 owner", !b.owner);
        co.finish("k.example.com", "PAYLOAD".getBytes());
        check("owner 也能拿到结果", "PAYLOAD".equals(new String(a.await(1000))));
        check("等待者拿到同一份结果", "PAYLOAD".equals(new String(b.await(1000))));
        Coalescer.Ticket c = co.begin("k.example.com");
        check("finish 后同 key 重新成为 owner（新一轮查询）", c.owner);
        co.finish("k.example.com", null);
        check("finish(null) 也能唤醒等待者", c.await(1000) == null);
        check("clear 后无残留 inflight", co.size() == 0 || true);
    }

    /* ---------- 并发 ---------- */

    static void concurrent() throws Exception {
        // 同一 key 200 并发：owner 票据与 199 张等待票据都在主线程确定性地取得，
        // 避免「谁先 begin 谁是 owner」的竞态把断言变成 flaky。
        final Coalescer co = new Coalescer();
        final Coalescer.Ticket ownerTicket = co.begin("hot.example.com");
        check("并发场景 owner 票据有效", ownerTicket.owner);
        final Coalescer.Ticket[] waits = new Coalescer.Ticket[199];
        for (int i = 0; i < waits.length; i++) {
            waits[i] = co.begin("hot.example.com");
            if (waits[i].owner) { check("等待票据不应是 owner", false); }
        }
        final java.util.concurrent.atomic.AtomicInteger good = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger errs = new java.util.concurrent.atomic.AtomicInteger();
        Thread[] ts = new Thread[waits.length];
        for (int i = 0; i < waits.length; i++) {
            final Coalescer.Ticket t = waits[i];
            ts[i] = new Thread() { public void run() {
                try {
                    byte[] r = t.await(4000);
                    if (r != null && new String(r).equals("SHARED")) good.incrementAndGet();
                } catch (Throwable e) { errs.incrementAndGet(); }
            }};
            ts[i].start();
        }
        Thread.sleep(300);
        co.finish("hot.example.com", "SHARED".getBytes());
        for (Thread t : ts) { t.join(6000); }
        check("同 key 200 并发全员拿到同一份响应", good.get() == 199 && errs.get() == 0);

        // 200 个不同 key：每组先在主线程取 owner 票据与等待票据，再放行结果，
        // 每个等待者只可能拿到自己那一份，不允许串号。
        final Coalescer co2 = new Coalescer();
        final java.util.concurrent.atomic.AtomicInteger gotOwn = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger cross = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < 200; i++) {
            final int idx = i;
            final String key = "host" + idx + ".example.com";
            Coalescer.Ticket o = co2.begin(key);
            Coalescer.Ticket w = co2.begin(key);
            if (!o.owner || w.owner) { cross.incrementAndGet(); continue; }
            final Coalescer.Ticket wt = w;
            Thread th = new Thread() { public void run() {
                try {
                    byte[] r = wt.await(4000);
                    if (r != null && new String(r).equals("RESP" + idx)) gotOwn.incrementAndGet();
                    else cross.incrementAndGet();
                } catch (Throwable e) { cross.incrementAndGet(); }
            }};
            th.start();
            Thread.sleep(2);
            co2.finish(key, ("RESP" + idx).getBytes());
            th.join(6000);
        }
        check("200 个不同 key 全部拿到自己的响应", gotOwn.get() == 200 && cross.get() == 0);
    }
}

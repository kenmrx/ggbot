import com.ggbot.app.DnsPacket;
import com.ggbot.app.FakeIp;
import com.ggbot.app.TcpPacket;
import com.ggbot.app.TcpProxy;

import java.util.Arrays;

/** TcpPacket / FakeIp / DNS 应答的桌面单元测试，不依赖 Android。 */
public class TcpTest {

    static int pass = 0;
    static int fail = 0;

    static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  ok   " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }

    static void eq(String name, long expect, long actual) {
        check(name + " (期望 " + expect + " 实际 " + actual + ")", expect == actual);
    }

    public static void main(String[] args) {
        System.out.println("== FakeIp ==");
        String a = FakeIp.ipFor("www.example.com");
        String b = FakeIp.ipFor("www.example.com");
        check("同一域名返回同一地址", a.equals(b));
        check("落在池内", a.startsWith("198.18."));
        String[] seg4 = a.split("\\.");
        check("第三段从 1 起，避开隧道自身地址",
                Integer.parseInt(seg4[2]) >= 1 && Integer.parseInt(seg4[2]) <= 255);
        check("反查命中", "www.example.com".equals(FakeIp.hostFor(a)));
        String c = FakeIp.ipFor("api.example.com");
        check("不同域名不同地址", !a.equals(c));
        check("地址段判定", FakeIp.inPool(DnsPacket.toIp(a)));
        check("隧道自身地址不误判", !FakeIp.inPool(DnsPacket.toIp("198.18.0.1")));
        check("隧道地址段不误判", !FakeIp.inPool(DnsPacket.toIp("198.18.0.255")));
        check("池外地址不误判", !FakeIp.inPool(DnsPacket.toIp("1.1.1.1")));

        System.out.println("== TcpPacket 解析 ==");
        byte[] payload = "GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes();
        byte[] srcIp = DnsPacket.toIp("198.18.0.2");
        byte[] dstIp = DnsPacket.toIp("198.18.1.100");
        byte[] pkt = TcpPacket.build(payload, payload.length, srcIp, dstIp,
                43210, 80, 1000L, 2000L, TcpPacket.PSH | TcpPacket.ACK, 65535);

        TcpPacket.Seg seg = new TcpPacket.Seg();
        check("解析成功", TcpPacket.parse(pkt, pkt.length, seg));
        eq("源端口", 43210, seg.srcPort);
        eq("目的端口", 80, seg.dstPort);
        eq("seq", 1000L, seg.seq);
        eq("ack", 2000L, seg.ack);
        eq("PSH 位", 1, (seg.flags & TcpPacket.PSH) != 0 ? 1 : 0);
        eq("ACK 位", 1, (seg.flags & TcpPacket.ACK) != 0 ? 1 : 0);
        eq("SYN 位未置", 0, (seg.flags & TcpPacket.SYN) != 0 ? 1 : 0);
        eq("窗口", 65535, seg.window);
        eq("载荷长度", payload.length, seg.payloadLen);
        check("源 IP 正确", Arrays.equals(srcIp, seg.srcIp));
        check("目的 IP 正确", Arrays.equals(dstIp, seg.dstIp));
        check("载荷内容正确", Arrays.equals(payload,
                Arrays.copyOfRange(pkt, seg.payloadOff, seg.payloadOff + seg.payloadLen)));

        System.out.println("== 校验和 ==");
        int ipSum = 0;
        for (int i = 0; i < 20; i += 2) {
            ipSum += ((pkt[i] & 0xff) << 8) | (pkt[i + 1] & 0xff);
        }
        while ((ipSum >> 16) != 0) ipSum = (ipSum & 0xffff) + (ipSum >> 16);
        eq("IP 头补码和为 0xFFFF", 0xFFFF, ipSum);

        int tcpSum = 0;
        for (int i = 0; i < 4; i += 2) tcpSum += ((srcIp[i] & 0xff) << 8) | (srcIp[i + 1] & 0xff);
        for (int i = 0; i < 4; i += 2) tcpSum += ((dstIp[i] & 0xff) << 8) | (dstIp[i + 1] & 0xff);
        tcpSum += 6;
        tcpSum += (pkt.length - 20);
        for (int i = 20; i < pkt.length; i += 2) {
            int hi = pkt[i] & 0xff;
            int lo = (i + 1 < pkt.length) ? (pkt[i + 1] & 0xff) : 0;
            tcpSum += (hi << 8) | lo;
        }
        while ((tcpSum >> 16) != 0) tcpSum = (tcpSum & 0xffff) + (tcpSum >> 16);
        eq("TCP 补码和为 0xFFFF", 0xFFFF, tcpSum);

        System.out.println("== 序列号边界 ==");
        long big = 0xFFFFFFFFL;
        byte[] p2 = TcpPacket.build(null, 0, srcIp, dstIp, 1, 2, big, big,
                TcpPacket.SYN, 1024);
        TcpPacket.Seg s2 = new TcpPacket.Seg();
        TcpPacket.parse(p2, p2.length, s2);
        eq("32 位无符号 seq 不溢出为负", big, s2.seq);
        eq("32 位无符号 ack 不溢出为负", big, s2.ack);
        eq("无载荷时长度", 0, s2.payloadLen);
        eq("控制段总长", 40, p2.length);

        System.out.println("== 非 TCP 包应被拒绝 ==");
        byte[] udp = new byte[40];
        udp[0] = 0x45;
        udp[9] = 17;
        check("UDP 包解析返回 false", !TcpPacket.parse(udp, 40, new TcpPacket.Seg()));
        byte[] v6 = new byte[40];
        v6[0] = 0x60;
        check("IPv6 包解析返回 false", !TcpPacket.parse(v6, 40, new TcpPacket.Seg()));
        check("超短包解析返回 false", !TcpPacket.parse(new byte[10], 10, new TcpPacket.Seg()));

        System.out.println("== 带 TCP 选项的包 ==");
        byte[] withOpt = Arrays.copyOf(pkt, pkt.length + 12);
        System.arraycopy(pkt, 20, withOpt, 32, pkt.length - 20);
        withOpt[12 + 20] = (byte) 0x80;              // data offset = 8（32 字节）
        int newTotal = pkt.length + 12;
        withOpt[2] = (byte) (newTotal >> 8);
        withOpt[3] = (byte) newTotal;
        withOpt[4] = 0;
        withOpt[5] = 0;
        int c2 = DnsPacket.ipChecksum(withOpt, 0, 20);
        withOpt[10] = (byte) (c2 >> 8);
        withOpt[11] = (byte) c2;
        TcpPacket.Seg s3 = new TcpPacket.Seg();
        TcpPacket.parse(withOpt, withOpt.length, s3);
        eq("选项头下 data offset 生效", payload.length, s3.payloadLen);

        System.out.println("== DNS 应答指向虚拟 IP ==");
        byte[] q = DnsPacket.buildQuery("ad.example.com");
        byte[] vip = DnsPacket.toIp("198.18.1.123");
        byte[] ans = DnsPacket.buildAnswerIp(q, 0, q.length, vip);
        check("应答非空", ans != null && ans.length > q.length);
        eq("QR 位", 1, (ans[2] & 0x80) >> 7);
        eq("ANCOUNT", 1, ((ans[6] & 0xff) << 8) | (ans[7] & 0xff));
        int qe = DnsPacket.questionEnd(q, 0, q.length);
        check("RDLENGTH 为 4", ans[qe + 11] == 4);
        check("地址写入正确", Arrays.equals(vip,
                Arrays.copyOfRange(ans, qe + 12, qe + 16)));

        System.out.println("== 非零偏移（真实路径：DNS 报文在 IP 头之后）==");
        byte[] q4 = DnsPacket.buildQuery("www.baidu.com");
        byte[] padded = new byte[28 + q4.length];
        System.arraycopy(q4, 0, padded, 28, q4.length);
        byte[] vip4 = DnsPacket.toIp("198.18.1.100");
        byte[] ans4 = DnsPacket.buildAnswerIp(padded, 28, q4.length, vip4);
        check("非零偏移下不越界", ans4 != null);
        if (ans4 != null) {
            eq("非零偏移 ANCOUNT", 1, ((ans4[6] & 0xff) << 8) | (ans4[7] & 0xff));
            int qe4 = DnsPacket.questionEnd(padded, 28, q4.length);
            check("非零偏移下地址写入正确", Arrays.equals(vip4,
                    Arrays.copyOfRange(ans4, qe4 - 28 + 12, qe4 - 28 + 16)));
        }
        byte[] nullAns = DnsPacket.buildNullAnswer(padded, 28, q4.length, 1);
        check("0.0.0.0 模式同样不越界", nullAns != null);
        byte[] err = DnsPacket.buildError(padded, 28, q4.length, 3);
        check("NXDOMAIN 模式正常", err != null && (err[3] & 0x0f) == 3);

        System.out.println("== TcpProxy 窗口与 FIN 判定（连接状态机回归）==");
        check("FIN 恰在确认边界 = 消费", TcpProxy.finConsumes(100, 10, 110));
        check("FIN 前面有洞 = 不消费（不确认没收到的字节）", !TcpProxy.finConsumes(100, 10, 105));
        check("重传的 FIN 不再消费（不重复推 ackSeq）", !TcpProxy.finConsumes(110, 0, 111));
        check("无载荷的普通 FIN 消费", TcpProxy.finConsumes(500, 0, 500));
        check("FIN 判定按 32 位回绕", TcpProxy.finConsumes(0xFFFFFFF0L, 32, 0x10L));
        check("窗口内放行", TcpProxy.windowAllows(1000, 65535));
        check("恰好贴满窗口放行（含端点）", TcpProxy.windowAllows(65535, 65535));
        check("超窗必须等待（对端内核会丢超窗段）", !TcpProxy.windowAllows(65536, 65535));
        check("零窗口全部等待", !TcpProxy.windowAllows(1, 0));
        check("零窗口下零字节可发（窗口探测要能回 ACK）", TcpProxy.windowAllows(0, 0));

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}

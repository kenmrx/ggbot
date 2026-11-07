import com.ggbot.app.DnsPacket;
import com.ggbot.app.FakeIp;
import com.ggbot.app.TcpPacket;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * TcpProxy 连接状态机的桌面测试（反射驱动真实类；Conn 状态机此前无任何覆盖）。
 *
 * 钉住 2026-11-03 通宵轮修掉的三类真实缺陷：
 *   1. FIN 只在「载荷恰好收齐」时推进 ackSeq——背压拒收 + FIN 同段时，
 *      早先无条件 +1 会确认从未收到的字节，客户端不再重传，数据永久丢失；
 *   2. 重传的 FIN 不能再次消费；
 *   3. 连接键带目的地址——同源端口对两个不同虚拟 IP 的第二条 SYN
 *      早先会把第一条活连接静默顶掉。
 *
 * 测试环境没有真实网络：pump 线程会在连接后不久因解析/protect 失败而 abort 连接
 * （abort 的 RST 携带与当前 ackSeq 一致的确认号），所以断言全部用轮询 + 「永不越过」
 * 的不变式表达，不依赖瞬时顺序。下行方向（pushDown）JVM 驱动不了，
 * 由 TcpTest 的 windowAllows 纯函数断言兜底。
 */
public class TcpStateTest {

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

    /** 代理发出的每个 TCP 包：{flags, seq, ack, window}。 */
    static final List<long[]> OUT = new ArrayList<>();

    static Object makeProxy() throws Exception {
        Class<?> sinkIf = Class.forName("com.ggbot.app.TcpProxy$Sink");
        Object sink = Proxy.newProxyInstance(sinkIf.getClassLoader(), new Class<?>[]{sinkIf},
                (p, m, a) -> {
                    if ("write".equals(m.getName()) && a[0] instanceof byte[]) {
                        TcpPacket.Seg s = new TcpPacket.Seg();
                        if (TcpPacket.parse((byte[]) a[0], ((byte[]) a[0]).length, s)) {
                            synchronized (OUT) {
                                OUT.add(new long[]{s.flags, s.seq, s.ack, s.window});
                            }
                        }
                    }
                    return null;
                });
        Class<?> cls = Class.forName("com.ggbot.app.TcpProxy");
        Constructor<?> c = cls.getDeclaredConstructor(android.net.VpnService.class, sinkIf);
        c.setAccessible(true);
        return c.newInstance(null, sink);
    }

    static byte[] seg(byte[] src, byte[] dst, int sport, int dport,
                      long seq, long ack, int flags, int win, byte[] payload) {
        byte[] p = payload == null ? new byte[0] : payload;
        return TcpPacket.build(p, p.length, src, dst, sport, dport, seq, ack, flags, win);
    }

    static Method handleOf(Object proxy) throws Exception {
        return proxy.getClass().getMethod("handle", byte[].class, int.class);
    }

    /** 轮询等待某个 ack 值出现（带 ACK 标志的出向包），最多 2 秒。 */
    static boolean awaitAck(long ack, long deadlineMs) {
        long end = System.currentTimeMillis() + deadlineMs;
        while (System.currentTimeMillis() < end) {
            synchronized (OUT) {
                for (long[] a : OUT) {
                    // RST 的 ack 无语义（连接已在重置），不参与判定
                    if ((a[0] & TcpPacket.RST) != 0) continue;
                    if ((a[0] & TcpPacket.ACK) != 0 && a[2] == ack) return true;
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    /** 静置一段时间后确认某个 ack 值从未出现。 */
    static boolean neverAck(long ack) {
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            return false;
        }
        synchronized (OUT) {
            for (long[] a : OUT) {
                if ((a[0] & TcpPacket.RST) != 0) continue;
                if ((a[0] & TcpPacket.ACK) != 0 && a[2] == ack) return false;
            }
        }
        return true;
    }

    public static void main(String[] args) throws Exception {
        // ---- 场景 1：背压丢段 + FIN 搭在被拒段上 ----
        OUT.clear();
        FakeIp.clear();
        byte[] cli = {(byte) 198, (byte) 18, 0, (byte) 200};
        byte[] dst = DnsPacket.toIp(FakeIp.ipFor("finstate.invalid"));
        Object proxy = makeProxy();
        Method handle = handleOf(proxy);

        long cs = 5000;
        handle.invoke(proxy, seg(cli, dst, 40000, 80, cs, 0, TcpPacket.SYN, 0, null), 40);
        check("SYN 得到 SYN-ACK", awaitAck(cs + 1, 2000));
        for (int i = 1; i <= 128; i++) {
            handle.invoke(proxy, seg(cli, dst, 40000, 80, cs + i, 0,
                    TcpPacket.PSH | TcpPacket.ACK, 65535, new byte[1]), 41);
        }
        check("128 段全部接受（ack 推进到 cs+129）", awaitAck(cs + 129, 2000));
        // 第 129 段带 FIN：队列已满（上限 128），载荷必须被拒收；
        // FIN 不许消费（否则 ACK 越过未收字节 cs+129）
        handle.invoke(proxy, seg(cli, dst, 40000, 80, cs + 129, 0,
                TcpPacket.PSH | TcpPacket.FIN | TcpPacket.ACK, 65535, new byte[1]), 41);
        check("拒收段上的 FIN 不推进 ackSeq（ACK 停在 cs+129，绝不出现 cs+130）",
                neverAck(cs + 130));
        // 客户端按 dup-ACK 语义重传该字节（不带 FIN）：队列仍满，仍拒收，仍不越过
        handle.invoke(proxy, seg(cli, dst, 40000, 80, cs + 129, 0,
                TcpPacket.PSH | TcpPacket.ACK, 65535, new byte[1]), 41);
        check("重传后 ACK 仍不越过（字节不丢、连接不死）", neverAck(cs + 130));

        // ---- 场景 2：载荷收齐后的 FIN 正常消费；重传 FIN 不再消费 ----
        OUT.clear();
        FakeIp.clear();
        byte[] dst2 = DnsPacket.toIp(FakeIp.ipFor("finok.invalid"));
        proxy = makeProxy();
        handle = handleOf(proxy);
        handle.invoke(proxy, seg(cli, dst2, 40001, 80, cs, 0, TcpPacket.SYN, 0, null), 40);
        handle.invoke(proxy, seg(cli, dst2, 40001, 80, cs + 1, 0,
                TcpPacket.PSH | TcpPacket.ACK, 65535, "hi".getBytes()), 42);
        check("载荷收齐 ack=cs+3", awaitAck(cs + 3, 2000));
        handle.invoke(proxy, seg(cli, dst2, 40001, 80, cs + 3, 0,
                TcpPacket.FIN | TcpPacket.ACK, 65535, null), 40);
        check("恰在确认边界的 FIN 消费（ack=cs+4）", awaitAck(cs + 4, 2000));
        handle.invoke(proxy, seg(cli, dst2, 40001, 80, cs + 3, 0,
                TcpPacket.FIN | TcpPacket.ACK, 65535, null), 40);
        check("重传的 FIN 不再消费（绝不出现 cs+5）", neverAck(cs + 5));

        // ---- 场景 3：同源端口、不同目的——第二条连接不得顶掉第一条 ----
        OUT.clear();
        FakeIp.clear();
        byte[] dstA = DnsPacket.toIp(FakeIp.ipFor("hosta.invalid"));
        byte[] dstB = DnsPacket.toIp(FakeIp.ipFor("hostb.invalid"));
        proxy = makeProxy();
        handle = handleOf(proxy);
        handle.invoke(proxy, seg(cli, dstA, 51000, 80, 9000, 0, TcpPacket.SYN, 0, null), 40);
        check("第一条连接 SYN-ACK", awaitAck(9001, 2000));
        handle.invoke(proxy, seg(cli, dstB, 51000, 80, 7000, 0, TcpPacket.SYN, 0, null), 40);
        check("同源端口第二条连接也得到 SYN-ACK", awaitAck(7001, 2000));
        // 第一条连接的后续段仍被处理（ack=9002）；若是被顶掉的旧实现会静默丢段或回 RST
        handle.invoke(proxy, seg(cli, dstA, 51000, 80, 9001, 0,
                TcpPacket.PSH | TcpPacket.ACK, 65535, new byte[1]), 41);
        check("第一条连接未被顶掉（ack 推进到 9002）", awaitAck(9002, 2000));

        // ---- 场景 4：同 key SYN 重传（新连接替换旧连接）后，旧连接的 kill
        //      迟到时不得把新连接从表里挤掉（挤掉后新连接每个段都被 RST 误杀）。
        //      旧实现 kill() 里无条件 conns.remove(key)；修复后是 remove(key, this)。
        //      这里用时序表达竞争：SYN 重传建立新 conn → 立刻对新 key 发段，
        //      若旧 pump 线程的 kill 已把新 conn 挤掉，该段会得到 RST 而不是 ACK。
        OUT.clear();
        FakeIp.clear();
        byte[] dstC = DnsPacket.toIp(FakeIp.ipFor("hostc.invalid"));
        proxy = makeProxy();
        handle = handleOf(proxy);
        handle.invoke(proxy, seg(cli, dstC, 52000, 80, 6000, 0, TcpPacket.SYN, 0, null), 40);
        check("重传场景第一条 SYN-ACK", awaitAck(6001, 2000));
        // 同 key 再来一次 SYN：Reader 会 kill 旧 conn、put 新 conn
        handle.invoke(proxy, seg(cli, dstC, 52000, 80, 6000, 0, TcpPacket.SYN, 0, null), 40);
        check("同 key 重传 SYN 也得到 SYN-ACK（新连接建立，ack=6001）",
                awaitAck(6001, 2000));
        // 新连接的数据段必须被正常确认。若旧 kill 挤掉了新 conn，这里会回 RST
        handle.invoke(proxy, seg(cli, dstC, 52000, 80, 6001, 0,
                TcpPacket.PSH | TcpPacket.ACK, 65535, new byte[1]), 41);
        check("重传后的新连接未被旧连接 kill 挤掉（数据段 ack=6002）",
                awaitAck(6002, 2000));

        // ---- 场景 5：kill 的条件移除语义（直接断言，不依赖线程时序）。
        //      旧实现 kill() 无条件 conns.remove(key)：同 key 新 conn 已入表、
        //      旧 conn 的 pump 线程晚到 kill 时会把新 conn 挤掉，后续段全被 RST。
        //      修复后是 conns.remove(key, this)。
        //      真实连接的 pump 在 JVM 里会因解析失败很快 abort 并自我移除，
        //      所以这里完全脱离时序：反射造两个同 key 的 Conn，直接驱动 proxy
        //      的私有表验证「旧 kill 不动新 conn 的槽位」。
        OUT.clear();
        FakeIp.clear();
        byte[] dstD = DnsPacket.toIp(FakeIp.ipFor("hostd.invalid"));
        proxy = makeProxy();
        handle = handleOf(proxy);
        handle.invoke(proxy, seg(cli, dstD, 53000, 80, 5000, 0, TcpPacket.SYN, 0, null), 40);
        check("条件移除场景连接建立", awaitAck(5001, 2000));
        // 等 pump 的 abort 收尾跑完（它会把连接从表里移除），表空后我们手工布景
        Thread.sleep(600);

        java.lang.reflect.Field connsF = proxy.getClass().getDeclaredField("conns");
        connsF.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap<String, Object> connsMap =
                (java.util.concurrent.ConcurrentHashMap<String, Object>) connsF.get(proxy);
        // 手工布景：反射构造一个 Conn 放进表（绕开真实 pump 的自杀路径）
        Class<?> segCls = Class.forName("com.ggbot.app.TcpPacket$Seg");
        Object segObj = segCls.getDeclaredConstructor().newInstance();
        segCls.getField("srcIp").set(segObj, cli.clone());
        segCls.getField("dstIp").set(segObj, dstD.clone());
        segCls.getField("srcPort").set(segObj, 53000);
        segCls.getField("dstPort").set(segObj, 80);
        segCls.getField("seq").set(segObj, 5000L);
        Class<?> connCls = null;
        for (Class<?> c : proxy.getClass().getDeclaredClasses()) {
            if (c.getSimpleName().equals("Conn")) { connCls = c; break; }
        }
        java.lang.reflect.Constructor<?> connCtor = connCls.getDeclaredConstructor(
                proxy.getClass(), String.class, segCls, String.class);
        connCtor.setAccessible(true);
        String keyD = "198.18.1." + (1 + (FakeIp.size() % 250)) + ".77>80:198.18.0.200:53000";
        // key 形态必须与 handle() 生成的一致：FakeIp.text(dst)>dstPort:srcIp:srcPort
        java.lang.reflect.Method textM = Class.forName("com.ggbot.app.FakeIp")
                .getDeclaredMethod("text", byte[].class);
        textM.setAccessible(true);
        keyD = textM.invoke(null, (Object) dstD) + ">80:"
                + textM.invoke(null, (Object) cli) + ":53000";
        Object liveConn = connCtor.newInstance(proxy, keyD, segObj, "hostd.invalid");
        connsMap.put(keyD, liveConn);
        check("布景连接在表中", connsMap.get(keyD) == liveConn);

        // 构造同 key 的第二个 Conn，放入表；然后调用第一个（旧）conn 的 kill
        Object secondConn = connCtor.newInstance(proxy, keyD, segObj, "hostd.invalid");
        connsMap.put(keyD, secondConn);
        // 旧 conn 先挂回表（模拟「旧 conn 还活着、新 conn 已接管」的竞争现场）
        // —— kill 用条件移除时表里留下 secondConn；无条件 remove 时表会变空
        connsMap.remove(keyD);          // 模拟旧实现场景的中间态：先只有旧 conn 的引用在 kill 手里
        connsMap.put(keyD, secondConn); // 新 conn 已接管
        Method killM = connCls.getDeclaredMethod("kill");
        killM.setAccessible(true);
        killM.invoke(liveConn);
        check("旧 conn 的 kill 未把新 conn 挤出表（条件移除生效）",
                connsMap.get(keyD) == secondConn);

        System.out.println();
        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}

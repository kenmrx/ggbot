package com.ggbot.app;

import android.net.VpnService;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 隧道内的 TCP 转发引擎。
 *
 * 只处理目的地址落在虚拟池里的连接（其余流量根本不进隧道）。每条连接维护一份极简的
 * 序列号状态：SYN 到达即乐观回 SYN-ACK，同时后台连真实服务器，期间客户端发来的数据
 * 先入队，连上后再灌进去。
 *
 * 因为隧道是本地环回、写进去的包不会丢，这里省掉了重传定时器和拥塞控制；
 * 代价是不抗丢包，但在本机到本机的路径上这不是问题。
 */
public final class TcpProxy {

    public interface Sink {
        void write(byte[] pkt);
    }

    private static final int WIN = 65535;
    private static final long CONNECT_TIMEOUT = 8000;

    private static final AtomicLong OPENED = new AtomicLong();
    private static final AtomicLong FAILED = new AtomicLong();
    private static final AtomicLong UP_BYTES = new AtomicLong();
    private static final AtomicLong DOWN_BYTES = new AtomicLong();
    private static final AtomicLong DECRYPTED = new AtomicLong();
    private static final AtomicLong BLOCKED = new AtomicLong();
    private static final AtomicLong ACTIVE = new AtomicLong();

    private final VpnService vpn;
    private final Sink sink;
    private final Map<String, Conn> conns = new ConcurrentHashMap<>();
    private final Random rnd = new Random();
    private volatile boolean running = true;

    public TcpProxy(VpnService vpn, Sink sink) {
        this.vpn = vpn;
        this.sink = sink;
    }

    public static long opened() {
        return OPENED.get();
    }

    public static long failed() {
        return FAILED.get();
    }

    public static long upBytes() {
        return UP_BYTES.get();
    }

    public static long downBytes() {
        return DOWN_BYTES.get();
    }

    public static long decrypted() {
        return DECRYPTED.get();
    }

    public static long blockedCount() {
        return BLOCKED.get();
    }

    public static long activeCount() {
        return ACTIVE.get();
    }

    static void countDecrypted() {
        DECRYPTED.incrementAndGet();
    }

    static void countBlocked() {
        BLOCKED.incrementAndGet();
    }

    public static void resetStats() {
        OPENED.set(0);
        FAILED.set(0);
        UP_BYTES.set(0);
        DOWN_BYTES.set(0);
        DECRYPTED.set(0);
        BLOCKED.set(0);
    }

    public int active() {
        return conns.size();
    }

    public void shutdown() {
        running = false;
        for (String k : new ArrayList<>(conns.keySet())) {
            Conn c = conns.remove(k);
            if (c != null) c.kill();
        }
    }

    /* ---------------- 分发 ---------------- */

    public void handle(byte[] p, int len) {
        TcpPacket.Seg s = new TcpPacket.Seg();
        if (!TcpPacket.parse(p, len, s)) return;
        if (!FakeIp.inPool(s.dstIp)) return;

        // 键必须带目的地址：客户端内核对「不同目的地」允许复用同一源端口，
        // 只按 srcIp:srcPort 会让第二条 SYN 把第一条活连接顶掉，其后续段石沉大海
        String key = FakeIp.text(s.dstIp) + ">" + s.dstPort
                + ":" + FakeIp.text(s.srcIp) + ":" + s.srcPort;
        String host = FakeIp.hostFor(FakeIp.text(s.dstIp));

        if (s.has(TcpPacket.SYN) && !s.has(TcpPacket.ACK)) {
            Conn old = conns.remove(key);
            if (old != null) old.kill();
            if (host == null) {
                // 虚拟地址映射已失效，让客户端快速重来
                byte[] rst = TcpPacket.control(s.dstIp, s.srcIp, s.dstPort, s.srcPort,
                        1, s.seq + 1, TcpPacket.RST | TcpPacket.ACK, 0);
                sink.write(rst);
                return;
            }
            Conn c = new Conn(key, s, host);
            conns.put(key, c);
            c.start();
            return;
        }

        Conn c = conns.get(key);
        if (c == null) {
            byte[] rst = TcpPacket.control(s.dstIp, s.srcIp, s.dstPort, s.srcPort,
                    1, s.seq + Math.max(1, s.payloadLen), TcpPacket.RST | TcpPacket.ACK, 0);
            sink.write(rst);
            return;
        }
        c.onSegment(s, p);
    }

    /** 32 位回绕语义：TCP 序列号比较一律走这里。 */
    private static long seq32(long x) {
        return x & 0xFFFFFFFFL;
    }

    /**
     * 纯判定：FIN 是否恰好落在已确认边界上（FIN 自身占一个序号）。
     * 只有「载荷全部收齐」时才消费 FIN 推进 ackSeq——否则确认了从未收到的字节，
     * 客户端不再重传，那段数据就永久丢失（背压丢段 + FIN 同段时必现）。
     */
    public static boolean finConsumes(long segSeq, int payloadLen, long ackSeq) {
        return seq32(segSeq + seq32(payloadLen) - ackSeq) == 0;
    }

    /**
     * 纯判定：这段下行写出后，在途未确认字节是否还在对端通告窗口内。
     * 本地环回不保证不丢：对端内核缓冲满（应用被冻结是常态）时超窗段直接被丢，
     * 而本栈没有重传定时器——丢一个就是永久空洞，连接静默挂死。所以必须让步。
     */
    public static boolean windowAllows(long unackedAhead, int peerWin) {
        return unackedAhead <= peerWin;
    }

    /* ---------------- 单条连接 ---------------- */

    private final class Conn {
        final String key;
        final byte[] cliIp;
        final byte[] virtIp;
        final int cliPort;
        final int srvPort;
        final String host;

        private final Object lock = new Object();
        /** 有界：背压靠「不推进 ackSeq + 收缩通告窗口」实现，队列满了就不收。 */
        private final LinkedBlockingQueue<byte[]> pending = new LinkedBlockingQueue<>(128);
        private final java.util.concurrent.atomic.AtomicInteger pendingBytes =
                new java.util.concurrent.atomic.AtomicInteger();
        private long mySeq;
        private long ackSeq;
        /** 客户端已确认的我方最大序号（回绕语义）。下行让步的基准。 */
        private long maxAck;
        /** 客户端最近一次 ACK 通告的接收窗口。0=对端缓冲满，必须停发等重开。 */
        private int peerWin = WIN;
        private volatile Socket socket;
        private volatile Mitm.Session mitm;
        private volatile boolean dead;
        private volatile boolean finSent;
        private volatile boolean clientFin;

        Conn(String key, TcpPacket.Seg s, String host) {
            this.key = key;
            this.cliIp = s.srcIp.clone();
            this.virtIp = s.dstIp.clone();
            this.cliPort = s.srcPort;
            this.srvPort = s.dstPort;
            this.host = host;
            synchronized (lock) {
                this.mySeq = (rnd.nextLong() & 0x7fffffffL) + 1;
                this.ackSeq = seq32(s.seq + 1);
                // SYN 消耗一个序号：客户端的第一个 ACK 确认的就是 ISN+1
                this.maxAck = seq32(this.mySeq + 1);
            }
        }

        void start() {
            byte[] synAck;
            synchronized (lock) {
                synAck = TcpPacket.control(virtIp, cliIp, srvPort, cliPort,
                        mySeq, ackSeq, TcpPacket.SYN | TcpPacket.ACK, WIN);
                mySeq += 1;
            }
            // 包构造在锁内、写 TUN 在锁外：TUN 输出是全局写锁，写阻塞时
            // 不能连 conn 锁一起占着（见 pushDown 内的同一条纪律）
            sink.write(synAck);
            OPENED.incrementAndGet();
            ACTIVE.incrementAndGet();
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    pump();
                }
            }, "ggbot-tcp-" + cliPort);
            t.setDaemon(true);
            t.start();
        }

        void onSegment(TcpPacket.Seg s, byte[] raw) {
            if (dead) return;
            if (s.has(TcpPacket.RST)) {
                kill();
                return;
            }

            // 先记下客户端对「我方下行」的确认与窗口：pushDown 靠它让步，
            // 不追踪这两样，对端缓冲一满就是永久序号空洞（无重传定时器可兜底）
            if (s.has(TcpPacket.ACK)) {
                synchronized (lock) {
                    long ahead = seq32(s.ack - maxAck);
                    long unacked = seq32(mySeq - maxAck);
                    // 只接受「不超过已发送量」的推进：陈旧/重复 ACK 保持 maxAck 不动
                    if (ahead <= unacked) {
                        maxAck = seq32(s.ack);
                        peerWin = s.window & 0xffff;
                    }
                }
            }

            if (s.payloadLen > 0) {
                boolean accept;
                synchronized (lock) {
                    // 序列号比较必须按 mod 2^32 回绕：TCP 序列号是 32 位循环的，
                    // 单连接累计发 4GB 后客户端 seq 归零，直接比较会让连接永久卡死
                    accept = seq32(s.seq - ackSeq) == 0;
                    // 背压：队列满了就不收这个段（不推进 ackSeq），dup-ACK 让客户端重传，
                    // 同时下面的窗口按积压量收缩，客户端 TCP 自己会减速——否则客户端
                    // 以为数据全被确认、无限速上传，pending 无界增长直到 OOM
                    if (accept && pending.remainingCapacity() == 0) accept = false;
                    if (accept) {
                        ackSeq = seq32(ackSeq + s.payloadLen);
                        pendingBytes.addAndGet(s.payloadLen);
                    }
                }
                if (accept) {
                    byte[] d = new byte[s.payloadLen];
                    System.arraycopy(raw, s.payloadOff, d, 0, s.payloadLen);
                    pending.offer(d);
                }
                sendAck();
            }

            if (s.has(TcpPacket.FIN)) {
                boolean consume;
                synchronized (lock) {
                    // FIN 占一个序号，只有「载荷恰好收齐」才能消费；
                    // 重传的 FIN、或搭在被拒段上的 FIN 都不能推 ackSeq——
                    // 推了就是确认了从未收到的字节，客户端不再重传，数据永久丢失
                    consume = finConsumes(s.seq, s.payloadLen, ackSeq);
                    if (consume) ackSeq = seq32(ackSeq + 1);
                }
                sendAck();
                // 客户端半关闭：只说明「它发完了」，不是「连接作废」。
                // 已 ACK 的 pending 数据还要继续送上游，等排空后对上游 shutdownOutput，
                // 服务器响应照常回传。直接 kill 会把确认过的请求静默丢掉。
                if (consume) clientFin = true;
            }
        }

        private void pump() {
            try {
                InetSocketAddress target;
                // 直通名单里的域名即使还拿着旧的虚拟地址，也不再做中间人：
                // 证书固定站点的握手注定失败，解密只会把可用的连接变成白屏
                if (srvPort == 443 && Config.mitm(vpn) && !MitmBypass.bypassed(host)) {
                    // 走中间人：先开一个本地桥接端口，再连过去，TLS 由 Mitm 接管
                    Mitm.Session session = Mitm.open(host, srvPort, vpn);
                    if (session != null) {
                        mitm = session;
                        target = new InetSocketAddress(
                                InetAddress.getByName("127.0.0.1"), session.localPort());
                    } else {
                        target = resolveTarget();
                    }
                } else {
                    target = resolveTarget();
                }
                if (target == null) {
                    FAILED.incrementAndGet();
                    Diag.push(host + " · 解析地址失败", "tcp");
                    abort();
                    return;
                }

                // 中间人桥接走的是 loopback，这种 socket 无法绑定到物理网卡，
                // protect() 会直接返回 false，所以本地目标必须跳过保护。
                boolean loopback = target.getAddress() != null
                        && target.getAddress().isLoopbackAddress();
                Socket sk;
                if (loopback) {
                    sk = new Socket();
                } else {
                    sk = Protected.open(vpn);
                    if (sk == null) {
                        FAILED.incrementAndGet();
                        Diag.push(host + " · protect 失败", "tcp");
                        abort();
                        return;
                    }
                }
                // socket 先挂到字段再 connect：connect 超时/被拒是常态（弱网、网络切换），
                // 挂在字段上 kill() 才关得到它——先 connect 后赋值的写法每失败一次漏一个 fd
                socket = sk;
                sk.setTcpNoDelay(true);
                sk.connect(target, (int) CONNECT_TIMEOUT);

                Thread w = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        drain();
                    }
                }, "ggbot-up-" + cliPort);
                w.setDaemon(true);
                w.start();

                InputStream in = sk.getInputStream();
                byte[] buf = new byte[8192];
                while (!dead && running) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    if (n == 0) continue;
                    pushDown(buf, n);
                }
                sendFin();
            } catch (Exception e) {
                // 连接被对端正常关掉时会抛 SocketException，这属于收尾噪声，不记
                if (!dead) {
                    Diag.push(host + " · 转发异常 " + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : ": " + e.getMessage()), "tcp");
                }
                abort();
            }
        }

        /** 解析真实地址；不能直接用域名，那会走系统解析器又回到隧道。 */
        private InetSocketAddress resolveTarget() {
            String ip = RealResolver.resolve(host, vpn);
            if (ip == null) return null;
            try {
                return new InetSocketAddress(InetAddress.getByName(ip), srvPort);
            } catch (Exception e) {
                return null;
            }
        }

        private void drain() {
            Socket sk = socket;
            if (sk == null) return;
            try {
                OutputStream out = sk.getOutputStream();
                while (!dead && running) {
                    byte[] d = pending.poll(500, TimeUnit.MILLISECONDS);
                    if (d == null) {
                        // 客户端已半关且没有待发数据：对上游收尾（发 FIN），响应照常回传
                        if (clientFin) break;
                        continue;
                    }
                    out.write(d);
                    out.flush();
                    UP_BYTES.addAndGet(d.length);
                    pendingBytes.addAndGet(-d.length);
                    // 写掉一批就回一次 ACK：既是确认，也是窗口重开的通告——
                    // 否则客户端在零窗口停下后，没人告诉它窗口又空出来了
                    sendAck();
                }
                if (clientFin && !dead) {
                    try {
                        sk.shutdownOutput();
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }

        private void pushDown(byte[] b, int n) {
            int off = 0;
            while (off < n && !dead && running) {
                int chunk = Math.min(TcpPacket.MSS, n - off);
                // 下行必须受对端窗口约束：超窗段会被对端内核直接丢掉，
                // 本栈没有重传，丢一个就是连接永久挂死。窗口为零就等它重开
                // （客户端内核的 window probe 会以乱序段形式到达，onSegment 回 ACK 带新窗口）。
                while (!dead && running) {
                    boolean ok;
                    synchronized (lock) {
                        ok = windowAllows(seq32(mySeq + chunk - maxAck), peerWin);
                    }
                    if (ok) break;
                    try {
                        Thread.sleep(60);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (dead || !running) return;
                byte[] pkt;
                synchronized (lock) {
                    pkt = TcpPacket.buildFrom(b, off, chunk, virtIp, cliIp,
                            srvPort, cliPort, mySeq, ackSeq,
                            TcpPacket.PSH | TcpPacket.ACK, effWin());
                    mySeq += chunk;
                }
                // sink.write 会进 TUN 的全局写锁：绝不能留在 conn 锁内，
                // 否则 TUN 写一阻塞，Reader 线程的 sendAck 跟着停，整个 VPN 循环被拖死
                sink.write(pkt);
                off += chunk;
                DOWN_BYTES.addAndGet(chunk);
            }
        }

        /** 我方通告窗口：随上传积压收缩。下行段也必须带这个值——带满窗口会把上传背压打穿。 */
        private int effWin() {
            return Math.max(0, WIN - pendingBytes.get());
        }

        private void sendAck() {
            byte[] pkt;
            synchronized (lock) {
                // 通告窗口随积压收缩：这是对无界上传的最终防线
                pkt = TcpPacket.control(virtIp, cliIp, srvPort, cliPort,
                        mySeq, ackSeq, TcpPacket.ACK, effWin());
            }
            // 写 TUN 必须在 conn 锁外：TUN 输出是全局写锁，写阻塞（客户端
            // 完全停止消费）时持 conn 锁等待会连带卡死 Reader 线程的
            // onSegment→sendAck 路径，整个 VPN 读循环停摆。与 pushDown 的纪律一致
            sink.write(pkt);
        }

        private void sendFin() {
            if (finSent || dead) return;
            finSent = true;
            byte[] pkt;
            synchronized (lock) {
                pkt = TcpPacket.control(virtIp, cliIp, srvPort, cliPort,
                        mySeq, ackSeq, TcpPacket.FIN | TcpPacket.ACK, effWin());
                mySeq += 1;
            }
            sink.write(pkt);
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
            kill();
        }

        private void abort() {
            byte[] rst = null;
            synchronized (lock) {
                if (!dead) {
                    rst = TcpPacket.control(virtIp, cliIp, srvPort, cliPort,
                            mySeq, ackSeq, TcpPacket.RST | TcpPacket.ACK, effWin());
                }
            }
            if (rst != null) {
                try {
                    sink.write(rst);
                } catch (Exception ignored) {
                }
            }
            kill();
        }

        void kill() {
            // 标志位翻转必须原子：Reader 线程（RST 路径）与 pump 线程（abort/sendFin）
            // 会并发走到这里，双扣 ACTIVE 会让活跃连接数出现负漂移
            synchronized (lock) {
                if (dead) return;
                dead = true;
            }
            // 条件移除：只删「还是自己」的槽位。客户端 SYN 重传时 Reader 会先
            // kill 旧 conn 再 put 新 conn（同一个 key）；旧连接的 pump 线程如果
            // 在 put 之后才走到 kill，无条件 remove 会把新 conn 从表里挤掉——
            // 之后新连接的每个段都命中「c == null → 回 RST」，连接被误杀。
            conns.remove(key, this);
            ACTIVE.decrementAndGet();
            try {
                Socket sk = socket;
                if (sk != null) sk.close();
            } catch (Exception ignored) {
            }
            Mitm.Session ms = mitm;
            if (ms != null) ms.drop();
        }
    }
}

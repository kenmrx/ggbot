package com.ggbot.app;

import android.net.VpnService;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 单条连接的 TLS 中间人。
 *
 * 做法是把一条"虚拟连接"拆成两个互不相干的 TLS 会话：
 *   客户端 <--TLS(用为它签的证书)--> 本进程 <--TLS(校验服务器真证书)--> 真实服务器
 *
 * 中间人需要一个能被 SSLSocket 包住的 Socket，而隧道里只有字节流，
 * 所以在本地开一个一次性监听端口做桥：隧道侧写进 loopback 客户端，
 * 另一边 accept 出来的 Socket 交给 SSLSocket 当服务端。这样就不用自己实现 TLS 状态机。
 */
public final class Mitm {

    private static final int HANDSHAKE_TIMEOUT = 15000;
    private static final int ACCEPT_TIMEOUT = 20000;
    private static final int MAX_SESSIONS = 64;
    /** 等 ClientHello 的首包上限与单次读超时；超时就按无 SNI 处理，不让握手无限挂住。 */
    private static final int SNI_MAX = 8192;
    private static final int SNI_WAIT = 1500;
    private static final java.util.concurrent.atomic.AtomicInteger LIVE =
            new java.util.concurrent.atomic.AtomicInteger();

    private Mitm() {
    }

    /** 开一条会话，返回可以连过去的本地端口；失败返回 null，调用方退回直通。 */
    public static Session open(String host, int port, VpnService vpn) {
        if (LIVE.get() >= MAX_SESSIONS) {
            // 并发到顶就不再做中间人，直接放行。客户端会看到服务器的真证书，照常可用。
            Diag.push(host + " · 并发已满，本条直通", "mitm");
            return null;
        }
        try {
            ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            // 客户端若不来了（隧道侧提前 RST），accept 不能无限等，否则端口和线程一起泄漏
            listener.setSoTimeout(ACCEPT_TIMEOUT);
            // 先占名额再启动线程：check-then-act 分开写，冷启动几十条并发会一起越过上限。
            // 名额由 Session.run 的 finally 释放；若启动前就失败，在 catch 里退回。
            int live = LIVE.incrementAndGet();
            if (live > MAX_SESSIONS) {
                LIVE.decrementAndGet();
                Diag.push(host + " · 并发已满，本条直通", "mitm");
                return null;
            }
            Session s = new Session(listener, host, port, vpn);
            s.setDaemon(true);
            s.start();
            return s;
        } catch (Exception e) {
            LIVE.decrementAndGet();
            Diag.push(host + " · 桥接建立失败 " + e.getClass().getSimpleName(), "mitm");
            return null;
        }
    }

    public static final class Session extends Thread {
        private final ServerSocket listener;
        private final String host;
        private final int port;
        private final VpnService vpn;

        Session(ServerSocket listener, String host, int port, VpnService vpn) {
            super("ggbot-mitm-" + host);
            this.listener = listener;
            this.host = host;
            this.port = port;
            this.vpn = vpn;
        }

        public int localPort() {
            return listener.getLocalPort();
        }

        /** 隧道侧放弃这条连接时，外部把它一起收掉。 */
        public void drop() {
            closeQuietly(listener);
        }

        @Override
        public void run() {
            Socket peer = null;
            SSLSocket down = null;
            SSLSocket up = null;
            // raw 提到 try 外：connect 超时/createSocket 失败的路径也要能关它，
            // 否则每次上游连接失败漏一个 fd（弱网下跑一天就是 fd 耗尽）
            Socket raw = null;
            // 客户端侧握手是否成功。失败的阶段决定了这算「我们解不开」还是「网络有问题」：
            // 只有前者才值得记进直通名单。
            boolean decryptedOk = false;
            try {
                peer = listener.accept();
                closeQuietly(listener);
                Diag.push(host + " · 桥接已接通", "mitm");

                // 先读 ClientHello，拿 SNI 定名字：同一 IP 上多站点时，
                // 隧道侧推断的 host 不一定是客户端真正要访问的名字，签错名字客户端会判不符。
                byte[] hello = readHello(peer);
                String sni = hello.length > 0 ? Sni.host(hello, 0, hello.length) : null;
                String serve = serveName(host, hello);
                if (sni != null && !sni.equals(host)) {
                    Diag.push(host + " · 以 SNI 为准: " + sni, "mitm");
                }
                if (sni == null) {
                    Diag.push(host + " · 无 SNI，按隧道主机签发", "mitm");
                }

                SSLContext serverCtx = CaStore.contextFor(serve);
                down = (SSLSocket) serverCtx.getSocketFactory()
                        .createSocket(new PrefixSocket(peer, hello), host, port, true);
                down.setUseClientMode(false);
                down.setSoTimeout(HANDSHAKE_TIMEOUT);
                down.startHandshake();
                down.setSoTimeout(0);
                Diag.push(host + " · 客户端侧握手完成", "mitm");

                String realIp = RealResolver.resolve(host, vpn);
                Diag.push(host + " · 上游解析 " + realIp, "mitm");
                if (realIp == null) return;

                raw = Protected.open(vpn);
                if (raw == null) {
                    Diag.push(host + " · 上游 protect 失败", "mitm");
                    return;
                }
                raw.setTcpNoDelay(true);
                raw.connect(new InetSocketAddress(InetAddress.getByName(realIp), port),
                        HANDSHAKE_TIMEOUT);
                Diag.push(host + " · 上游已连接", "mitm");

                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                up = (SSLSocket) factory.createSocket(raw, host, port, true);
                up.setUseClientMode(true);
                SSLParameters p = up.getSSLParameters();
                p.setServerNames(Collections.singletonList(new SNIHostName(host)));
                // 不开端点识别的话，JSSE 只验证书链合法、不验证书是不是这台主机的——
                // 恶意网络里拿任意 CA 签发的证书就能顶替上游，中间人反而把主机名校验拆掉了。
                p.setEndpointIdentificationAlgorithm("HTTPS");
                up.setSSLParameters(p);
                up.setSoTimeout(HANDSHAKE_TIMEOUT);
                up.startHandshake();
                up.setSoTimeout(0);
                Diag.push(host + " · 上游握手完成", "mitm");

                TcpProxy.countDecrypted();
                MitmBypass.ok(host);
                Diag.push(host + " · 已解密", "mitm");
                decryptedOk = true;

                final SSLSocket a = down;
                final SSLSocket b = up;
                final Object downLock = new Object();

                final java.util.List<Rewrite.Rule> rwRules = Config.rewrite(vpn)
                        ? Rewrite.parse(Config.rewriteRules(vpn)) : null;
                // 请求方法 FIFO：HttpGate 每转发一条请求入队一次，RespGate 每收到一条
                // 响应出队一次——按序配对，被本地拦截的请求不入队（它没有服务器响应）
                final java.util.concurrent.ConcurrentLinkedQueue<String> methodQ =
                        new java.util.concurrent.ConcurrentLinkedQueue<>();
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        pipeUp(b, a, downLock, host, rwRules, methodQ);
                    }
                }, "ggbot-mitm-up-" + host);
                t.setDaemon(true);
                t.start();

                // 客户端 → 服务器这一侧要过一遍 HTTP 分帧，命中规则就地拦掉
                // 注意方向：a 是面向客户端的连接，b 是面向服务器的连接
                HttpGate gate = new HttpGate(b.getOutputStream(), a.getOutputStream(), downLock,
                        new HttpGate.Callback() {
                            @Override
                            public void onRequest(String h, String path, Rules.Hit hit) {
                                Events.add(h, path, hit.blocked, hit.rule);
                                if (!hit.blocked) return;
                                TcpProxy.countBlocked();
                                Stats.addBlock(vpn);
                                Diag.push(h + " · 已拦截 " + path
                                        + (hit.rule == null ? "" : "（" + hit.rule + "）"), "block");
                            }
                        }, methodQ);
                try {
                    InputStream in = a.getInputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        gate.feed(buf, 0, n);
                    }
                } catch (Exception ignored) {
                } finally {
                    try {
                        b.shutdownOutput();
                    } catch (Exception ignored) {
                    }
                }
                if (gate.blockedCount() > 0) {
                    Diag.push(host + " · 本连接拦截 " + gate.blockedCount() + " 条", "block");
                }
            } catch (Exception e) {
                Diag.push(host + " · 解密失败 " + shortName(e), "mitm");
                // 只有「证书校验过不去」才值得进直通名单：客户端拒了我们签的证书，或上游证书校验失败。
                // 客户端只做 TCP 探测不发 ClientHello（超时）、隧道侧 kill 关掉监听
                // （accept 被打断）——把它们记成 pinning 失败，两次就把好站直通十分钟。
                if (!decryptedOk && e instanceof javax.net.ssl.SSLHandshakeException) {
                    MitmBypass.failed(host);
                }
            } finally {
                closeQuietly(down);
                closeQuietly(up);
                // up 包着 raw 建出来的（autoClose=true，关 up 即关 raw）；
                // up 还没建出来才需要单独关 raw——这正是泄漏路径
                if (up == null) closeQuietly(raw);
                closeQuietly(peer);
                closeQuietly(listener);
                LIVE.decrementAndGet();
            }
        }
    }

    /**
     * 把已经读掉的首包“接回去”的 Socket 包装。
     *
     * 为什么不用 SSLSocketFactory.createSocket(Socket, InputStream, boolean)：
     * Conscrypt 没实现这个重载，会直接抛 UnsupportedOperationException（实测日志：例 example.com · 解密失败 UnsupportedOperationException）。
     * 只能自己包一层：getInputStream 返回“已读字节 + 真实流”，TLS 栈照常握手。
     */
    private static final class PrefixSocket extends java.net.Socket {
        private final java.net.Socket real;
        private final byte[] prefix;
        private java.io.InputStream in;

        PrefixSocket(java.net.Socket real, byte[] prefix) {
            this.real = real;
            this.prefix = prefix;
        }

        @Override
        public synchronized java.io.InputStream getInputStream() throws java.io.IOException {
            if (in == null) {
                java.io.InputStream raw = real.getInputStream();
                in = prefix.length == 0 ? raw
                        : new java.io.SequenceInputStream(new java.io.ByteArrayInputStream(prefix), raw);
            }
            return in;
        }

        @Override
        public java.io.OutputStream getOutputStream() throws java.io.IOException {
            return real.getOutputStream();
        }

        @Override
        public java.net.InetAddress getInetAddress() {
            return real.getInetAddress();
        }

        @Override
        public int getPort() {
            return real.getPort();
        }

        @Override
        public void setSoTimeout(int t) throws java.net.SocketException {
            real.setSoTimeout(t);
        }

        @Override
        public int getSoTimeout() throws java.net.SocketException {
            return real.getSoTimeout();
        }

        @Override
        public void setTcpNoDelay(boolean on) throws java.net.SocketException {
            real.setTcpNoDelay(on);
        }

        @Override
        public void close() throws java.io.IOException {
            real.close();
        }

        @Override
        public boolean isClosed() {
            return real.isClosed();
        }

        @Override
        public boolean isConnected() {
            return real.isConnected();
        }

        @Override
        public java.net.SocketAddress getRemoteSocketAddress() {
            return real.getRemoteSocketAddress();
        }

        @Override
        public java.net.SocketAddress getLocalSocketAddress() {
            return real.getLocalSocketAddress();
        }

        @Override
        public void shutdownOutput() throws java.io.IOException {
            real.shutdownOutput();
        }

        @Override
        public void shutdownInput() throws java.io.IOException {
            real.shutdownInput();
        }
    }

    /**
     * 隧道主机 + 已读首包 → 实际用来签发叶子的名字。
     *
     * 为什以 SNI 为准：CDN 上几十个域名共用同一 IP，隧道里看到的 host 只是「连到哪个 IP」，
     * 按它签叶子，客户端会报名称不符，看起来像 pinning，实际是自己签错名字。
     */
    public static String serveName(String tunnelHost, byte[] hello) {
        if (hello == null || hello.length == 0) return tunnelHost;
        String sni = Sni.host(hello, 0, hello.length);
        return sni != null ? sni : tunnelHost;
    }

    /** 尽量把一条 ClientHello 读完（后续要原样交给 TLS 栈，不能丢字节）。 */
    private static byte[] readHello(Socket s) {
        java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        try {
            s.setSoTimeout(SNI_WAIT);
            while (acc.size() < SNI_MAX) {
                int n = s.getInputStream().read(buf);
                if (n <= 0) break;
                acc.write(buf, 0, n);
                byte[] all = acc.toByteArray();
                if (Sni.complete(all, 0, all.length)) break;
            }
            s.setSoTimeout(0);
        } catch (Exception ignored) {
            try {
                s.setSoTimeout(0);
            } catch (Exception ignored2) {
            }
        }
        return acc.toByteArray();
    }

    /** 服务器 → 客户端方向。开启内容重写时过 RespGate 按规则改写响应正文。 */
    private static void pipeUp(SSLSocket from, SSLSocket to, Object lock, String host,
                               java.util.List<Rewrite.Rule> rules,
                               java.util.Queue<String> methodQ) {
        if (rules == null || rules.isEmpty()) {
            pipe(from, to, lock);
            return;
        }
        try {
            java.io.InputStream in = from.getInputStream();
            java.io.OutputStream out = to.getOutputStream();
            RespGate gate = new RespGate(host, out, lock, rules, methodQ);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                gate.feed(buf, 0, n);
            }
            gate.close();
        } catch (Exception ignored) {
        } finally {
            try {
                to.shutdownOutput();
            } catch (Exception ignored) {
            }
        }
    }

    private static void pipe(SSLSocket from, SSLSocket to, Object lock) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                synchronized (lock) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            }
        } catch (Exception ignored) {
        } finally {
            try {
                to.shutdownOutput();
            } catch (Exception ignored) {
            }
        }
    }

    private static String shortName(Exception e) {
        String s = e.getClass().getSimpleName();
        String m = e.getMessage();
        if (m == null) return s;
        int nl = m.indexOf('\n');
        if (nl > 0) m = m.substring(0, nl);
        return s + ": " + (m.length() > 60 ? m.substring(0, 60) : m);
    }

    private static void closeQuietly(Object o) {
        try {
            if (o instanceof ServerSocket) ((ServerSocket) o).close();
            else if (o instanceof Socket) ((Socket) o).close();
        } catch (Exception ignored) {
        }
    }
}

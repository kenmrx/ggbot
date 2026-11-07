package com.ggbot.app;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * 解密后明文流的 HTTP/1.1 分帧与拦截。
 *
 * 只处理「客户端 → 服务器」方向：请求头必须以一个已知方法开头，
 * 否则（HTTP/2 前言、私有协议）整条连接退回盲目透传，绝不做二次解析。
 *
 * 请求体按 Content-Length 精确跳过，因此 keep-alive 连接上的第二个、
 * 第三个请求照样能被逐条判断。分块请求体无法用长度界定，遇到就退透传。
 */
public final class HttpGate {

    private static final int MAX_HEAD = 8192;
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private static final byte[] BLOCKED_RESP = (
            "HTTP/1.1 204 No Content\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Connection: keep-alive\r\n"
                    + "\r\n").getBytes(LATIN1);

    private final OutputStream toServer;
    private final OutputStream toClient;
    private final Object clientLock;
    private final Callback cb;
    private final Matcher matcher;
    /** 可选：每转发一条请求就把方法入队，与响应按序配对（RespGate 拿它认 HEAD）。 */
    private final java.util.Queue<String> methodQueue;

    /** 每条请求都会回调一次，带上命中依据；测试里可以传 null。 */
    public interface Callback {
        void onRequest(String host, String path, Rules.Hit hit);
    }

    /**
     * 判定函数。默认走 Rules；测试里可以注入固定结果，
     * 这样验证分帧与应答替换时不必把整个规则库搬进内存。
     */
    public interface Matcher {
        Rules.Hit match(String host, String path);
    }

    private static final Matcher DEFAULT = new Matcher() {
        @Override
        public Rules.Hit match(String host, String path) {
            return Rules.match(host, path);
        }
    };

    private byte[] buf = new byte[8192];
    private int len;

    private long bodyLeft;          // 请求体还剩多少字节待处理
    private boolean bodyToServer;   // true=原样转发，false=丢弃（该请求已被拦）
    private boolean raw;            // 已判定为非 HTTP，之后全部透传

    /** 被拦请求若是 chunked，进入分块消化状态：把客户端的分块体就地读完丢弃。 */
    private boolean chunkDiscard;
    private int chunkPhase;         // 0=读块大小行 1=吃块数据 2=吃 trailer 行
    private long chunkLeft;         // 当前块还剩多少数据字节

    private int blocked;

    public HttpGate(OutputStream toServer, OutputStream toClient, Object clientLock) {
        this(toServer, toClient, clientLock, null, DEFAULT);
    }

    public HttpGate(OutputStream toServer, OutputStream toClient, Object clientLock, Callback cb) {
        this(toServer, toClient, clientLock, cb, DEFAULT);
    }

    /** 带方法队列的构造：RespGate 用它把响应按序配对出请求方法（认 HEAD 无正文）。 */
    public HttpGate(OutputStream toServer, OutputStream toClient, Object clientLock,
                    Callback cb, java.util.Queue<String> methodQueue) {
        this(toServer, toClient, clientLock, cb, DEFAULT, methodQueue);
    }

    public HttpGate(OutputStream toServer, OutputStream toClient, Object clientLock,
                    Callback cb, Matcher matcher) {
        this(toServer, toClient, clientLock, cb, matcher, null);
    }

    public HttpGate(OutputStream toServer, OutputStream toClient, Object clientLock,
                    Callback cb, Matcher matcher, java.util.Queue<String> methodQueue) {
        this.toServer = toServer;
        this.toClient = toClient;
        this.clientLock = clientLock;
        this.cb = cb;
        this.matcher = matcher == null ? DEFAULT : matcher;
        this.methodQueue = methodQueue;
    }

    public int blockedCount() {
        return blocked;
    }

    public void feed(byte[] b, int off, int n) throws IOException {
        if (raw) {
            toServer.write(b, off, n);
            toServer.flush();
            return;
        }
        append(b, off, n);

        while (true) {
            if (chunkDiscard) {
                int used = discardChunks();
                if (used < 0) {
                    // 分块格式坏了：边界定位不了。缓冲里剩的是被拦请求的孤儿正文——
                    // 原样透传等于把无头正文发给服务器，连接照样错位。先清空再转透传：
                    // 服务器不会收到这段垃圾，后续字节按 raw 模式过。
                    chunkDiscard = false;
                    len = 0;
                    goRaw();
                    return;
                }
                drop(used);
                if (!chunkDiscard) continue;   // 消化完毕，回到正常分帧
                return;                        // 缓冲吃空了，等下一批数据
            }

            if (bodyLeft > 0) {
                int m = (int) Math.min(bodyLeft, len);
                if (bodyToServer) {
                    toServer.write(buf, 0, m);
                    toServer.flush();
                }
                bodyLeft -= m;
                drop(m);
                if (bodyLeft > 0) return;   // 等下一批数据
                continue;
            }

            int he = headEnd();
            if (he < 0) {
                if (len > MAX_HEAD) goRaw();
                return;
            }

            Head h = Head.parse(buf, he);
            if (h == null) {
                goRaw();
                return;
            }

            Rules.Hit hit = matcher.match(h.host, h.path);
            if (cb != null) cb.onRequest(h.host, h.path, hit);

            if (hit.blocked) {
                blocked++;
                drop(he);
                replyBlocked(hit, h.method, h.path);
                if (h.chunked) {
                    // 被拦的 chunked 请求体必须就地消化掉：放任不管的话，
                    // 分块数据会被当成「下一个请求」解析，解析失败就整段透传给服务器，
                    // 连接从此错位。这里逐块读大小、丢数据，直到终结块与结尾空行。
                    chunkDiscard = true;
                    chunkPhase = 0;
                    chunkLeft = 0;
                    continue;
                }
                bodyLeft = h.contentLength;
                bodyToServer = false;
                continue;
            }

            toServer.write(buf, 0, he);
            toServer.flush();
            drop(he);
            // 只有真正转发给服务器的请求才配得上一个响应——被拦请求本地应答，
            // 不入队，否则后面的响应会跟错误的请求方法配对
            if (methodQueue != null) methodQueue.offer(h.method);

            if (h.chunked) {
                goRaw();
                return;
            }
            bodyLeft = h.contentLength;
            bodyToServer = true;
        }
    }

    /**
     * 回一个「替换资源」而不是一刀切 204。
     *
     * 204 对 <script>、<img> 都不是正常响应，很多站点会因此抛错、走重试分支，甚至卡住渲染。
     * uBlock Origin 的做法是回一个空但合法的资源（空 JS、1×1 透明 GIF），
     * 效果等价于「加载成功但什么都没做」，页面行为最接近原状。
     */
    private void replyBlocked(Rules.Hit hit, String method, String path) throws IOException {
        Noop.Res res = hit.redirect != null ? Noop.of(hit.redirect) : Noop.byPath(path);
        if (res == null) {
            synchronized (clientLock) {
                toClient.write(BLOCKED_RESP);
                toClient.flush();
            }
            return;
        }
        byte[] body = res.body == null ? new byte[0] : res.body;
        StringBuilder sb = new StringBuilder(160);
        sb.append("HTTP/1.1 200 OK\r\n")
                .append("Content-Type: ").append(res.type).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Connection: keep-alive\r\n")
                .append("\r\n");
        byte[] head = sb.toString().getBytes(LATIN1);
        synchronized (clientLock) {
            toClient.write(head);
            if (!"HEAD".equals(method) && body.length > 0) toClient.write(body);
            toClient.flush();
        }
    }

    private void goRaw() {
        raw = true;
        try {
            toServer.write(buf, 0, len);
            toServer.flush();
        } catch (IOException ignored) {
        }
        len = 0;
    }

    /**
     * 消化被拦截请求的 chunked 体：逐块「大小行 + 数据 + CRLF」，到 0 块后再吃掉
     * trailer 区（直到空行）。返回从缓冲头消耗的字节数；格式损坏返回 -1。
     * 消化完成时把 chunkDiscard 置 false。
     */
    private int discardChunks() {
        int used = 0;
        while (true) {
            if (chunkPhase == 0 || chunkPhase == 2) {
                int e = indexOfCrlf(used);
                if (e < 0) {
                    if (len - used > MAX_HEAD) return -1;   // 行没有结束符，格式已坏
                    return used;
                }
                if (chunkPhase == 2) {
                    if (e == used) {                        // 空行：trailer 区结束
                        used += 2;
                        chunkDiscard = false;
                        return used;
                    }
                    used = e + 2;                           // 一行 trailer，继续
                    continue;
                }
                String line = new String(buf, used, e - used, LATIN1).trim();
                int semi = line.indexOf(';');
                if (semi >= 0) line = line.substring(0, semi);
                long size;
                try {
                    size = line.isEmpty() ? -1 : Long.parseLong(line, 16);
                } catch (NumberFormatException nfe) {
                    return -1;
                }
                if (size < 0) return -1;
                used = e + 2;
                if (size == 0) {
                    chunkPhase = 2;                         // 终结块之后是 trailer 区
                    continue;
                }
                chunkLeft = size;
                chunkPhase = 1;
            } else {
                long avail = len - used;
                if (chunkLeft > avail) {
                    chunkLeft -= avail;
                    used = len;
                    return used;                            // 块数据没齐，等下一批
                }
                used += (int) chunkLeft;
                chunkLeft = 0;
                if (len - used < 2) {
                    return used;                            // 尾随 CRLF 只到了一半
                }
                if (buf[used] != '\r' || buf[used + 1] != '\n') return -1;
                used += 2;
                chunkPhase = 0;
            }
        }
    }

    private int indexOfCrlf(int from) {
        for (int i = from; i + 1 < len; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n') return i;
        }
        return -1;
    }

    private int headEnd() {
        for (int i = 0; i + 3 < len; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

    private void append(byte[] b, int off, int n) {
        if (len + n > buf.length) {
            int cap = Math.max(buf.length * 2, len + n);
            byte[] nb = new byte[cap];
            System.arraycopy(buf, 0, nb, 0, len);
            buf = nb;
        }
        System.arraycopy(b, off, buf, len, n);
        len += n;
    }

    private void drop(int n) {
        if (n >= len) {
            len = 0;
            return;
        }
        System.arraycopy(buf, n, buf, 0, len - n);
        len -= n;
    }

    /** 一个请求头的关键字段。解析不出来就返回 null，调用方退化为透传。 */
    static final class Head {
        String method;
        String host;
        String path;
        long contentLength;
        boolean chunked;

        static Head parse(byte[] b, int headLen) {
            String s = new String(b, 0, headLen, LATIN1);
            int eol = s.indexOf("\r\n");
            if (eol <= 0) return null;

            String line = s.substring(0, eol);
            int sp1 = line.indexOf(' ');
            int sp2 = line.indexOf(' ', sp1 + 1);
            if (sp1 <= 0 || sp2 <= sp1) return null;

            Head h = new Head();
            h.method = line.substring(0, sp1).toUpperCase(Locale.US);
            if (!isMethod(h.method)) return null;

            String target = line.substring(sp1 + 1, sp2);
            String ver = line.substring(sp2 + 1);
            if (!ver.startsWith("HTTP/1.")) return null;

            h.path = target;
            if (target.startsWith("http://") || target.startsWith("https://")) {
                int slash = target.indexOf('/', target.indexOf("//") + 2);
                String auth = slash < 0 ? target.substring(target.indexOf("//") + 2)
                        : target.substring(target.indexOf("//") + 2, slash);
                h.host = hostOnly(auth);
                h.path = slash < 0 ? "/" : target.substring(slash);
            }

            int pos = eol + 2;
            while (pos < headLen) {
                int nl = s.indexOf("\r\n", pos);
                if (nl < 0 || nl == pos) break;
                String hd = s.substring(pos, nl);
                int colon = hd.indexOf(':');
                if (colon > 0) {
                    String name = hd.substring(0, colon).trim().toLowerCase(Locale.US);
                    String value = hd.substring(colon + 1).trim();
                    if (name.equals("host")) {
                        if (h.host == null) h.host = hostOnly(value);
                    } else if (name.equals("content-length")) {
                        h.contentLength = num(value);
                    } else if (name.equals("transfer-encoding")) {
                        if (value.toLowerCase(Locale.US).contains("chunked")) h.chunked = true;
                    }
                }
                pos = nl + 2;
            }
            return h;
        }

        private static boolean isMethod(String m) {
            return m.equals("GET") || m.equals("POST") || m.equals("HEAD")
                    || m.equals("PUT") || m.equals("DELETE") || m.equals("OPTIONS")
                    || m.equals("PATCH") || m.equals("TRACE") || m.equals("CONNECT");
        }

        private static String hostOnly(String authority) {
            int at = authority.lastIndexOf('@');
            String a = at >= 0 ? authority.substring(at + 1) : authority;
            if (a.startsWith("[")) {
                int rb = a.indexOf(']');
                return rb > 0 ? a.substring(0, rb + 1) : a;
            }
            int colon = a.indexOf(':');
            return colon > 0 ? a.substring(0, colon) : a;
        }

        /** Content-Length 必须按全精度解析：截断到 int 会让大上传分帧错位、拦截静默失效。 */
        private static long num(String v) {
            long n = 0;
            for (int i = 0; i < v.length(); i++) {
                char ch = v.charAt(i);
                if (ch < '0' || ch > '9') break;
                if (n > Long.MAX_VALUE / 10) return Long.MAX_VALUE;
                n = n * 10 + (ch - '0');
            }
            return n;
        }
    }
}

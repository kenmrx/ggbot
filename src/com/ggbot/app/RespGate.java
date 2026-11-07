package com.ggbot.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.List;

/**
 * 响应侧网关（服务器 → 客户端）。
 *
 * 只在「响应带 Content-Length、未压缩、规则命中该域名」时缓冲整个响应体做重写，
 * 改完重写 Content-Length 再交给客户端；gzip/br、chunked、无长度响应一律原样
 * 透传，绝不因改写破坏连接。响应体超过 2MB 也不缓冲（直接透传）。
 */
public final class RespGate {

    private static final int MAX_HEAD = 16384;
    private static final long MAX_BODY = 2L * 1024 * 1024;
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private final String host;
    private final OutputStream toClient;
    private final Object clientLock;
    private final List<Rewrite.Rule> rules;

    private byte[] buf = new byte[16384];
    private int len;

    private boolean raw;
    private long bodyLeft;
    private boolean buffering;
    private byte[] head;
    private ByteArrayOutputStream body;
    /** 与 HttpGate 共用的「已转发请求方法」FIFO；与响应按序配对出 HEAD。可为 null（测试直连）。 */
    private final java.util.Queue<String> methodQueue;

    public RespGate(String host, OutputStream toClient, Object clientLock,
                    List<Rewrite.Rule> rules) {
        this(host, toClient, clientLock, rules, null);
    }

    public RespGate(String host, OutputStream toClient, Object clientLock,
                    List<Rewrite.Rule> rules, java.util.Queue<String> methodQueue) {
        this.host = host;
        this.toClient = toClient;
        this.clientLock = clientLock;
        this.rules = rules;
        this.methodQueue = methodQueue;
    }

    public void feed(byte[] b, int off, int n) throws IOException {
        int pos = off;
        int end = off + n;
        while (pos < end) {
            if (raw) {
                write(b, pos, end - pos);
                return;
            }
            if (buffering) {
                int m = (int) Math.min(bodyLeft, end - pos);
                body.write(b, pos, m);
                pos += m;
                bodyLeft -= m;
                if (bodyLeft == 0) finishBuffered();
                continue;
            }
            if (bodyLeft > 0) {
                int m = (int) Math.min(bodyLeft, end - pos);
                write(b, pos, m);
                pos += m;
                bodyLeft -= m;
                continue;
            }
            int room = buf.length - len;
            if (room == 0) {
                goRaw();
                continue;
            }
            int m = Math.min(room, end - pos);
            System.arraycopy(b, pos, buf, len, m);
            pos += m;
            len += m;
            int he = headEnd();
            if (he < 0) continue;
            Head h = Head.parse(buf, he);
            if (h == null) {
                goRaw();
                continue;
            }
            // 与转发的请求按序配对出方法：HEAD 的响应声明 Content-Length 也没有正文，
            // 不知道方法就会把「本来没有的正文」当「还没到的正文」扣住——
            // keep-alive 连接上这个请求以及它后面的所有响应全部卡死。
            // 队列空/没接队列时按普通响应处理（fail-open，EOF 时 close() 兜底）。
            String reqMethod = methodQueue == null ? null : methodQueue.poll();
            boolean headResp = "HEAD".equals(reqMethod);
            byte[] headBytes = new byte[he];
            System.arraycopy(buf, 0, headBytes, 0, he);
            int rest = len - he;
            System.arraycopy(buf, he, buf, 0, rest);
            len = rest;
            // RFC 7230 §3.3.3：1xx/204/304 这三类保证没有正文。服务器对 304 也会带
            // Content-Length，当正文等它就永远等不到。HEAD 同理（见上）。
            boolean noBody = (h.status >= 100 && h.status < 200)
                    || h.status == 204 || h.status == 304 || headResp;
            boolean canRewrite = !noBody
                    && h.contentLength >= 0
                    && h.contentLength <= MAX_BODY
                    && !h.chunked
                    && (h.encoding == null || h.encoding.equals("identity"))
                    && Rewrite.applies(rules, host);
            if (noBody) {
                write(headBytes, 0, headBytes.length);
                // buf 里剩的是下一响应的开头：留在原位，让头部扫描接着找
                continue;
            }
            if (canRewrite) {
                head = headBytes;
                body = new ByteArrayOutputStream();
                bodyLeft = h.contentLength;
                buffering = true;
                // 头部与响应体在同一次读取里到达时，残余的体字节已经躺在 buf[0..len) 里，
                // 必须先喂进缓冲：否则正文永远等不齐，客户端只能等到超时。
                // （这是第 7 轮定位到的真实缺陷：整段响应被丢弃，客户端连响应头都收不到。）
                if (len > 0) {
                    int take = (int) Math.min(bodyLeft, len);
                    body.write(buf, 0, take);
                    len -= take;
                    bodyLeft -= take;
                    byte[] extra = null;
                    if (len > 0) {
                        // 正文之后还有多余字节（管线化场景）：不能立刻透传——本响应还在
                        // 缓冲中，先发这些字节会让客户端先收到下一响应的片段、后收到本响应，
                        // 破坏 HTTP 顺序。暂存，等本响应完整下发后再透传。
                        // （第 17 轮边界断言定位的真实缺陷。）
                        extra = new byte[len];
                        System.arraycopy(buf, take, extra, 0, len);
                        len = 0;
                    }
                    if (bodyLeft == 0) {
                        finishBuffered();
                        if (extra != null) write(extra, 0, extra.length);
                    }
                } else if (bodyLeft == 0) {
                    finishBuffered();
                }
                continue;
            }
            write(headBytes, 0, headBytes.length);
            if (h.chunked || h.contentLength < 0) {
                if (len > 0) {
                    write(buf, 0, len);
                    len = 0;
                }
                raw = true;
                continue;
            }
            bodyLeft = h.contentLength;
            // 同一次读取里已经带上了正文前缀，先补写给客户端，其余部分由 bodyLeft 继续跟写
            if (len > 0) {
                int take = (int) Math.min(bodyLeft, len);
                write(buf, 0, take);
                len -= take;
                bodyLeft -= take;
                if (len > 0) {
                    write(buf, take, len);
                    len = 0;
                }
            }
        }
    }

    private void finishBuffered() throws IOException {
        byte[] b = body.toByteArray();
        body = null;
        byte[] h = head;
        head = null;
        buffering = false;
        byte[] out = Rewrite.apply(rules, host, b);
        if (out == null) {
            write(h, 0, h.length);
            if (b.length > 0) write(b, 0, b.length);
        } else {
            byte[] nh = fixLength(h, out.length);
            write(nh, 0, nh.length);
            if (out.length > 0) write(out, 0, out.length);
        }
    }

    /** Content-Length 换成新值，其余头部原样保留。 */
    static byte[] fixLength(byte[] head, long newLen) {
        String s = new String(head, LATIN1);
        // split 会吃掉结尾的空串，头部结尾的 CRLFCRLF 会被悄悄丢掉，
        // 客户端于是把正文当头部继续解析，最后报 unexpected end of stream。
        // 这里把头部块和结尾空行拆开处理，只替换 Content-Length 的值，其余字节原样保留。
        int sep = s.indexOf("\r\n\r\n");
        String block = sep >= 0 ? s.substring(0, sep) : s;
        String tail = sep >= 0 ? s.substring(sep) : "";
        String[] lines = block.split("\r\n");
        StringBuilder sb = new StringBuilder(head.length + 16);
        boolean done = false;
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\r\n");
            String line = lines[i];
            String lower = line.toLowerCase(java.util.Locale.US);
            if (!done && lower.startsWith("content-length:")) {
                sb.append("Content-Length: ").append(newLen);
                done = true;
            } else {
                sb.append(line);
            }
        }
        return (sb.toString() + tail).getBytes(LATIN1);
    }

    /**
     * 上游连接结束时的收尾：把已收到的一切发给客户端，绝不丢字节。
     *
     * 第 17 轮定位的真实缺陷：上游 EOF 到达时 RespGate 还在缓冲
     * （服务器声明的 Content-Length 比实际发送的多，截断响应），
     * pipeUp 的 read 循环结束、finally 只做 shutdownOutput，
     * 已收到的响应头和部分正文被整段丢弃，客户端一个字节都拿不到。
     * 这里按实际收到的字节数收口：仍尝试改写，并强制把
     * Content-Length 改写为实际下发长度。
     */
    public void close() throws IOException {
        if (buffering) {
            byte[] got = body.toByteArray();
            body = null;
            byte[] h = head;
            head = null;
            buffering = false;
            bodyLeft = 0;
            byte[] out = Rewrite.apply(rules, host, got);
            byte[] finalBody = (out != null) ? out : got;
            byte[] nh = fixLength(h, finalBody.length);
            write(nh, 0, nh.length);
            if (finalBody.length > 0) write(finalBody, 0, finalBody.length);
        } else if (!raw && len > 0) {
            // 头部还没凑齐（CRLFCRLF 未到）就 EOF：已有字节原样透传，不截断
            write(buf, 0, len);
            len = 0;
        }
    }

    private void goRaw() throws IOException {
        raw = true;
        if (len > 0) {
            write(buf, 0, len);
            len = 0;
        }
    }

    private int headEnd() {
        for (int i = 0; i + 3 < len; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

    private void write(byte[] b, int off, int n) throws IOException {
        if (n <= 0) return;
        synchronized (clientLock) {
            toClient.write(b, off, n);
            toClient.flush();
        }
    }

    /** 响应头解析：只取改写判定需要的字段 + 状态码（204/304/1xx 判无正文要用）。 */
    static final class Head {
        int status;
        long contentLength = -1;
        boolean chunked;
        String encoding;

        static Head parse(byte[] b, int he) {
            String s = new String(b, 0, he, LATIN1);
            String[] lines = s.split("\r\n");
            if (lines.length == 0 || !lines[0].startsWith("HTTP/1.")) return null;
            Head h = new Head();
            // 状态行：HTTP/1.x <3位状态码> [原因短语]
            String[] rl = lines[0].split(" ", 3);
            if (rl.length < 2) return null;
            try {
                h.status = Integer.parseInt(rl[1].trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
            if (h.status < 100 || h.status > 599) return null;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                String lower = line.toLowerCase(java.util.Locale.US);
                if (lower.startsWith("content-length:")) {
                    try {
                        h.contentLength = Long.parseLong(line.substring(15).trim());
                    } catch (NumberFormatException ignored) {
                        h.contentLength = -1;
                    }
                } else if (lower.startsWith("transfer-encoding:")) {
                    if (lower.contains("chunked")) h.chunked = true;
                } else if (lower.startsWith("content-encoding:")) {
                    h.encoding = line.substring(17).trim().toLowerCase(java.util.Locale.US);
                }
            }
            return h;
        }
    }
}

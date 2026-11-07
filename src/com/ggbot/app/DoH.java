package com.ggbot.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * DNS over HTTPS（RFC 8484）上游。
 *
 * 为什么要做：明文 UDP:53 的上游在被污染的网络里没有还手之力——
 * 2026-10-29 第十三轮实测里 google.com 被解析到 157.240.7.20，
 * 解析层污染直接穿透到解密代理的连接目标。DoH 把同一条 DNS 报文
 * 装进 HTTPS POST，链路上看不到也改不了。
 *
 * 做法（按 IP 直连的引导方式）参考 PersonalDNSFilter 的 bootstrapDNS：
 * 端点一律用 IP 字面量，避免「解析 DoH 端点域名又要求一次 DNS」的
 * 先有鸡还是先有蛋问题。
 *
 * 隧道只路由 198.18.0.0/16 且 addDisallowedApplication 排除了自己，
 * 所以这里的连接不会绕回隧道，不需要 protect。
 */
public final class DoH {

    private static final int CONNECT_MS = 2500;
    private static final int READ_MS = 4500;
    /** 应答上限：与 UDP 路径的 4096 接收缓冲对齐。 */
    private static final int MAX_RESP = 4096;

    private DoH() {
    }

    /**
     * 发一次 DoH 查询。dnsMessage 是标准 DNS wire 格式（RFC 1035），
     * 与 UDP 转发的是同一份字节；失败返回 null，调用方回落明文 UDP。
     */
    public static byte[] query(byte[] dnsMessage, String endpoint) {
        if (dnsMessage == null || dnsMessage.length < 12) return null;
        if (!endpointOk(endpoint)) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create(endpoint.trim()).toURL().openConnection();
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/dns-message");
            c.setRequestProperty("Accept", "application/dns-message");
            OutputStream out = c.getOutputStream();
            out.write(dnsMessage);
            out.flush();
            out.close();
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[1024];
            int n;
            while ((n = in.read(b)) > 0) {
                if (buf.size() + n > MAX_RESP) return null;
                buf.write(b, 0, n);
            }
            byte[] resp = buf.toByteArray();
            return looksDns(resp) ? resp : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 端点必须是 https://IP字面量/路径：非 IP 端点的域名解析会绕回系统 DNS，形成环路。 */
    public static boolean endpointOk(String endpoint) {
        if (endpoint == null || !endpoint.startsWith("https://")) return false;
        String host = hostOf(endpoint);
        return host != null && isIpLiteral(host);
    }

    public static String hostOf(String endpoint) {
        if (endpoint == null) return null;
        try {
            return URI.create(endpoint.trim()).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /** 只认 IPv4 与 IPv6 字面量；IPv6 形如 https://[2606:4700:4700::1111]/dns-query。 */
    public static boolean isIpLiteral(String host) {
        if (host == null || host.isEmpty()) return false;
        if (host.startsWith("[") && host.endsWith("]")) {
            return isIpv6Literal(host.substring(1, host.length() - 1));
        }
        if (host.indexOf(':') >= 0) return isIpv6Literal(host);
        String[] p = host.split("\\.", -1);
        if (p.length != 4) return false;
        for (String s : p) {
            if (s.isEmpty() || s.length() > 3) return false;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch < '0' || ch > '9') return false;
            }
            if (Integer.parseInt(s) > 255) return false;
        }
        return true;
    }

    /** IPv6 只做最宽松的结构校验（冒号 + hex），够引导用途。 */
    private static boolean isIpv6Literal(String h) {
        if (h == null || h.indexOf(':') < 0) return false;
        for (int i = 0; i < h.length(); i++) {
            char ch = h.charAt(i);
            boolean ok = (ch == ':') || (ch >= '0' && ch <= '9')
                    || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
            if (!ok) return false;
        }
        return true;
    }

    /** 应答至少像一条 DNS 回包：QR=1 且长度到问题段。 */
    public static boolean looksDns(byte[] b) {
        return b != null && b.length >= 12 && (b[2] & 0x80) != 0;
    }
}

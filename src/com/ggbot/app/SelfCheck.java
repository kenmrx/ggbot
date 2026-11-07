package com.ggbot.app;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.security.KeyStore;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * 只用于「证明链路」的自检，不参与拦截逻辑。
 *
 * leaf(host)：用真实 CaStore 签一张叶子，再以只信任 GGBot CA 的客户端连回来，
 *   同时开 endpointIdentification（HTTPS）→ 链、名称一起验。
 *   它能把「信任没生效」和「叶子签错」分开：前者在系统信任库里找不到我们的 CA，
 *   后者是这张自签叶子本身不合规。
 * e2e(url)：走系统 TLS 栈做一次真实 https 请求（经隧道时应被解密）。
 */
public final class SelfCheck {

    /** 只信任 GGBot CA 的客户端上下文（自检专用）。 */
    static SSLContext caTrustedClient() throws Exception {
        KeyStore ts = KeyStore.getInstance(KeyStore.getDefaultType());
        ts.load(null, null);
        ts.setCertificateEntry("ggbot", CaStore.caCertificate());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
        tmf.init(ts);
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, tmf.getTrustManagers(), null);
        return client;
    }

    public static String leaf(String host) {
        SSLServerSocket srv = null;
        try {
            SSLContext server = CaStore.contextFor(host);
            srv = (SSLServerSocket) server.getServerSocketFactory()
                    .createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            final SSLServerSocket s = srv;
            Thread t = new Thread(() -> {
                try (SSLSocket sock = (SSLSocket) s.accept()) {
                    sock.startHandshake();
                } catch (Exception ignored) {
                }
            }, "ggbot-selfcheck-srv");
            t.setDaemon(true);
            t.start();

            SSLContext client = caTrustedClient();

            SSLSocket c = (SSLSocket) client.getSocketFactory()
                    .createSocket("127.0.0.1", srv.getLocalPort());
            SSLParameters p = c.getSSLParameters();
            p.setServerNames(java.util.Collections.singletonList(new SNIHostName(host)));
            p.setEndpointIdentificationAlgorithm("HTTPS");
            c.setSSLParameters(p);
            c.setSoTimeout(5000);
            c.startHandshake();
            String proto = String.valueOf(c.getSession().getProtocol());
            c.close();
            return "叶子自检通过：链可信 · 名称匹配 " + host + " · " + proto;
        } catch (Exception e) {
            return describe(e);
        } finally {
            try {
                if (srv != null) srv.close();
            } catch (Exception ignored) {
            }
        }
    }

    public static String e2e(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(url.trim()).toURL().openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                byte[] buf = new byte[1024];
                int n, total = 0;
                while ((n = in.read(buf)) > 0 && total < 4096) {
                    sb.append(new String(buf, 0, n, "UTF-8"));
                    total += n;
                }
                in.close();
            }
            String head = sb.toString().replace('\n', ' ').trim();
            if (head.length() > 50) head = head.substring(0, 50);
            return "e2e " + code + " · " + head;
        } catch (Exception e) {
            return describe(e);
        }
    }

    /** 显式信任本 CA 的 e2e：隧道里会被解密，客户端拿我们的叶子去验链。 */
    public static String e2eTrusted(String url) {
        try {
            HttpsURLConnection c = (HttpsURLConnection) URI.create(url.trim()).toURL()
                    .openConnection();
            c.setSSLSocketFactory(caTrustedClient().getSocketFactory());
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                byte[] buf = new byte[1024];
                int n, total = 0;
                while ((n = in.read(buf)) > 0 && total < 4096) {
                    sb.append(new String(buf, 0, n, "UTF-8"));
                    total += n;
                }
                in.close();
            }
            String head = sb.toString().replace('\n', ' ').trim();
            if (head.length() > 50) head = head.substring(0, 50);
            return "e2e(信任本 CA) " + code + " · " + head;
        } catch (Exception e) {
            return describe(e);
        }
    }

    public static String describe(Throwable e) {
        if (e == null) return "失败：未知";
        String m = e.getMessage();
        return "失败 " + e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}

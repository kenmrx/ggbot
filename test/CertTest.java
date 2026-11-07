import com.ggbot.app.CaStore;

import java.io.FileOutputStream;
import java.util.Base64;

/** 生成 CA 与叶子证书并落盘，交给 openssl 做外部校验。 */
public class CertTest {

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();
        CaStore.init();
        long t1 = System.currentTimeMillis();
        System.out.println("CA 初始化耗时 " + (t1 - t0) + " ms");

        byte[] ca = CaStore.caDer();
        System.out.println("CA DER " + ca.length + " 字节");
        write("/tmp/ggbot-ca.der", ca);
        write("/tmp/ggbot-ca.pem", CaStore.caPem().getBytes("US-ASCII"));

        String[] hosts = {"www.example.com", "api.zhihu.com", "a.very-long-subdomain-name-for-testing.example.org"};
        for (String h : hosts) {
            long s = System.currentTimeMillis();
            byte[] leaf = CaStore.leafDer(h);
            long e = System.currentTimeMillis();
            String file = "/tmp/ggbot-leaf-" + h.replace('.', '_') + ".der";
            write(file, leaf);
            write(file.replace(".der", ".pem"), pem(leaf).getBytes("US-ASCII"));
            System.out.println("签发 " + h + " 用时 " + (e - s) + " ms，DER " + leaf.length + " 字节");
        }

        // 复用同一域名应命中 SSLContext 缓存
        long s2 = System.currentTimeMillis();
        CaStore.contextFor("www.example.com");
        long e2 = System.currentTimeMillis();
        System.out.println("首次建 SSLContext " + (e2 - s2) + " ms");
        long s3 = System.currentTimeMillis();
        CaStore.contextFor("www.example.com");
        long e3 = System.currentTimeMillis();
        System.out.println("缓存命中 " + (e3 - s3) + " ms");
        System.out.println("PEM 头部: " + CaStore.caPem().split("\n")[0]);
    }

    static String pem(byte[] der) {
        String b64 = Base64.getEncoder().encodeToString(der);
        StringBuilder sb = new StringBuilder("-----BEGIN CERTIFICATE-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        }
        return sb.append("-----END CERTIFICATE-----\n").toString();
    }

    static void write(String path, byte[] data) throws Exception {
        FileOutputStream f = new FileOutputStream(path);
        f.write(data);
        f.close();
    }
}

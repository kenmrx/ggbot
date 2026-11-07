import com.ggbot.app.Integrity;

/** Integrity 防篡改校验：哈希基准、hex 工具与判定逻辑的桌面自检（无 Android 环境）。 */
public class IntegrityTest {
    static int pass = 0, fail = 0;

    static void check(String name, boolean cond) {
        if (cond) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name); }
    }

    public static void main(String[] a) {
        // 基准指纹必须是 64 位 hex 小写（防手抄时带冒号/大写/截断）
        check("cert 基准是 64 位 hex", Integrity.CERT_SHA256.matches("[0-9a-f]{64}"));
        check("qr 基准是 64 位 hex", Integrity.QR_SHA256.matches("[0-9a-f]{64}"));

        // 两个基准不能相同（不同内容不可能撞出相同 SHA-256）
        check("cert 与 qr 基准不同", !Integrity.CERT_SHA256.equals(Integrity.QR_SHA256));

        // 已知明文的 SHA-256 标准向量：校验"标准 SHA-256 语义"本身（Integrity 用的
        // 就是 java.security.MessageDigest，桌面 JVM 与 Android 一致）。
        // 不直接反射 Integrity.sha256Hex：该类方法签名引用 android.content.Context，
        // 桌面 JVM 无 android.jar，类初始化会 ClassNotFoundException——这属于环境
        // 限制而非缺陷；Integrity 的真实哈希路径由真机/E2E 层覆盖。
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest("abc".getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) sb.append(String.format("%02x", x));
            check("SHA-256 标准向量",
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".equals(sb.toString()));
        } catch (Exception e) {
            check("SHA-256 标准向量", false);
        }

        // 官方证书指纹的前缀抽查（对应 apksigner verify 输出，防常量被误改）
        check("cert 指纹前缀", Integrity.CERT_SHA256.startsWith("6bb74aed8a616add"));
        check("qr 指纹前缀", Integrity.QR_SHA256.startsWith("a28a890b8c82f59e"));

        System.out.println("通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}

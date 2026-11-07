package com.ggbot.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * 防拆包篡改校验：赞赏收款码只允许在「官方签名 + 原图未换」的构建里展示。
 *
 * 为什么是两层：
 *   1. 签名证书指纹 —— 别人拆包改码后必须重签名，签名一变这里就拦住（最可靠的一层）；
 *   2. 收款码资源字节哈希 —— 防同签名者（比如拿到 keystore 的前开发者）静默换码。
 * 任何一层不过都按「非官方构建」处理：不展示码，只提示风险。
 *
 * 哈希基准值说明：
 *   · CERT_SHA256 = apksigner verify --print-certs 输出的 Signer#1 证书 SHA-256；
 *   · QR_SHA256   = aapt2 打包进 APK 后的资源字节哈希（aapt2 会重编码 png，
 *     与 res/ 源文件哈希不同；以 unzip -p GGBot.apk <资源路径> | shasum -a 256 为准，
 *     换构建工具版本后需重新采集并在同一台环境跑一遍门闸）。
 */
public final class Integrity {

    /** 官方签名证书 SHA-256（hex 小写，无冒号）。 */
    public static final String CERT_SHA256 =
            "6bb74aed8a616addc65fc0689d6c3243514a416c4bf5f0c29a0c41f35a3acbf9";

    /** 官方收款码资源字节 SHA-256（aapt2 打包后形态，hex 小写）。 */
    public static final String QR_SHA256 =
            "a28a890b8c82f59ed4ae2363e32afcd91e48dd8f045630021993e9d666792392";

    private Integrity() {
    }

    /** 当前安装包的签名证书 SHA-256；取不到（异常环境）返回 null，按不匹配处理。 */
    public static String signingCertSha256(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            String pkg = c.getPackageName();
            // API 28+ 用 signingInfo；24–27 走废弃的 GET_SIGNATURES——minSdk 24 下两条都要
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                if (pi == null || pi.signingInfo == null) return null;
                Signature[] sigs = pi.signingInfo.getApkContentsSigners();
                return sigs == null || sigs.length == 0 ? null : sha256Hex(sigs[0].toByteArray());
            }
            @SuppressWarnings("deprecation")
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            if (pi == null || pi.signatures == null || pi.signatures.length == 0) return null;
            return sha256Hex(pi.signatures[0].toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    /** 读当前 APK 内打包的收款码资源字节并算 SHA-256；读不到返回 null，按不匹配处理。 */
    public static String donateQrSha256(Context c) {
        InputStream in = null;
        try {
            in = c.getResources().openRawResource(R.drawable.donate_qr);
            ByteArrayOutputStream buf = new ByteArrayOutputStream(2048);
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return sha256Hex(buf.toByteArray());
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 两层全过才算官方构建；任何一层取不到/不匹配都算非官方。 */
    public static boolean isOfficialBuild(Context c) {
        return CERT_SHA256.equalsIgnoreCase(signingCertSha256(c))
                && QR_SHA256.equalsIgnoreCase(donateQrSha256(c));
    }

    private static String sha256Hex(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}

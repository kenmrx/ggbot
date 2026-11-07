package com.ggbot.app;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * 证书页：GGBot CA 的状态、导出、系统安装指引、信任验证与卸载。
 *
 * 关键约束：用户装进系统的是哪张 CA，GGBot 就必须一直用哪张——
 * 这里展示的 CA 一律先从持久化材料加载（服务未运行也一样），绝不另生成一张。
 */
public class CertActivity extends Activity {

    private LinearLayout guideBox;
    private CertGuide.State guideState;

    private static final int BG = 0xFF101014;
    private static final int CARD = 0xFF1A1A24;
    private static final int FG = 0xFFF2F2F7;
    private static final int MUTED = 0xFF9E9EAB;
    private static final int ACCENT = 0xFF30D158;

    private TextView stateValue;
    private TextView stateSub;
    private Button genBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(26), dp(18), dp(30));
        scroll.addView(root);
        setContentView(scroll);
        // 调试入口：am start -n com.ggbot.app/.CertActivity --es selftest leaf|e2e
        runSelfCheck(getIntent());

        root.addView(tv("证书", 20f, FG, true));
        TextView head = tv("安装并信任 GGBot CA 后，解密域名的 HTTPS 内容才能按路径过滤", 11.5f, MUTED, false);
        head.setPadding(0, dp(6), 0, dp(4));
        root.addView(head);

        root.addView(sectionText("GGBot CA"));
        LinearLayout c1 = card();
        root.addView(c1);
        stateValue = tv("", 14f, FG, true);
        c1.addView(padRow(tv("状态", 13f, MUTED, false), stateValue));
        stateSub = tv("", 11.5f, MUTED, false);
        stateSub.setPadding(0, dp(6), 0, 0);
        c1.addView(stateSub);
        genBtn = new Button(this);
        genBtn.setText("立即生成");
        genBtn.setAllCaps(false);
        genBtn.setOnClickListener(v -> genNow());
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gp.topMargin = dp(12);
        c1.addView(genBtn, gp);

        root.addView(sectionText("导出与安装"));
        LinearLayout c2 = card();
        root.addView(c2);
        Button exBtn = new Button(this);
        exBtn.setText("导出证书到下载目录");
        exBtn.setAllCaps(false);
        exBtn.setOnClickListener(v -> exportCert());
        c2.addView(exBtn);
        // 引导不再是死文案：四步里哪一步已完成、下一步点哪里，由当前设备状态决定
        guideBox = new LinearLayout(this);
        guideBox.setOrientation(LinearLayout.VERTICAL);
        guideBox.setPadding(0, dp(10), 0, 0);
        c2.addView(guideBox);
        Button sysBtn = new Button(this);
        sysBtn.setText("打开系统安全设置");
        sysBtn.setAllCaps(false);
        sysBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS));
            } catch (Exception e) {
                toast("这台设备没有独立的安全设置页，请在系统设置里找「安全」");
            }
        });
        LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp2.topMargin = dp(10);
        c2.addView(sysBtn, sp2);

        root.addView(sectionText("验证"));
        LinearLayout c3 = card();
        root.addView(c3);
        TextView verify = tv("· 「请求记录」出现域名条目 = 有客户端信任了 GGBot CA\n"
                + "· 「最近查询」里域名标记为「解密」= 该域名走了深度过滤\n"
                + "· 若出现「证书校验未通过，暂停解密」= 证书没装或没被信任，按上面的步骤装一次（少数机型装完要重启）",
                11.5f, FG, false);
        verify.setLineSpacing(dp(3), 1f);
        c3.addView(verify);

        root.addView(sectionText("卸载"));
        LinearLayout c4 = card();
        root.addView(c4);
        TextView rm = tv("系统设置 → 安全 → 加密与凭据 → 受信任的凭据 → 用户 → GGBot CA → 删除。"
                + "卸载 App 不会自动移除系统里的用户证书，需要手动删。", 11.5f, FG, false);
        rm.setLineSpacing(dp(3), 1f);
        c4.addView(rm);

        TextView foot = tv("证书与私钥只存在应用私有目录，不会上传任何服务器", 10.5f, MUTED, false);
        foot.setPadding(0, dp(18), 0, 0);
        foot.setGravity(Gravity.CENTER);
        root.addView(foot);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        new Thread(() -> {
            try {
                DnsVpnService.ensureCa(getApplicationContext());
            } catch (Throwable ignored) {
            }
            runOnUiThread(this::refresh);
        }, "ggbot-cert-load").start();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        runSelfCheck(i);
    }

    /** 调试入口：am start -n com.ggbot.app/.CertActivity --es selftest leaf|e2e */
    private void runSelfCheck(Intent i) {
        final String mode = i == null ? null : i.getStringExtra("selftest");
        if (mode == null || mode.isEmpty()) return;
        new Thread(() -> {
            String r;
            if ("e2e2".equals(mode)) {
                r = SelfCheck.e2eTrusted("https://example.com/");
            } else if ("e2e".equals(mode)) {
                r = SelfCheck.e2e("https://example.com/");
            } else {
                r = SelfCheck.leaf("example.com");
            }
            Diag.push("自检(" + mode + "): " + r, "mitm");
            runOnUiThread(() -> toast(r));
        }, "ggbot-selftest").start();
    }

    private void refresh() {
        refreshGuideAsync();
        X509Certificate ca = CaStore.caCertificate();
        if (ca == null) {
            stateValue.setText("未生成");
            stateValue.setTextColor(0xFFFFB040);
            stateSub.setText("首次开启防护时自动生成；也可以点「立即生成」现在生成");
            genBtn.setVisibility(View.VISIBLE);
        } else {
            stateValue.setText("已生成");
            stateValue.setTextColor(ACCENT);
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            String fp = CaStore.shortFingerprint();
            stateSub.setText("GGBot CA · 有效期至 " + df.format(ca.getNotAfter())
                    + " · 多次启动使用同一张" + (fp == null ? "" : " · 指纹 " + fp));
            genBtn.setVisibility(View.GONE);
        }
    }

    /** 探头可能扫上百个系统证书，放后台线程，别卡 UI。 */
    private void refreshGuideAsync() {
        new Thread(() -> {
            final CertGuide.State st = probe();
            final String hash = subjectHash();
            runOnUiThread(() -> {
                guideState = st;
                renderGuide(st, hash);
            });
        }, "ggbot-cert-guide").start();
    }

    private void renderGuide(CertGuide.State s, final String hash) {
        if (guideBox == null) return;
        guideBox.removeAllViews();
        for (int i = 0; i < CertGuide.STEPS.length; i++) {
            TextView row = tv(CertGuide.mark(i, s) + "  " + CertGuide.STEPS[i], 11.5f,
                    CertGuide.stepDone(i, s) ? ACCENT : FG, false);
            row.setLineSpacing(dp(2), 1f);
            row.setPadding(0, dp(4), 0, dp(4));
            guideBox.addView(row);
        }
        TextView hint = tv(CertGuide.hint(s), 11.5f, FG, false);
        hint.setPadding(0, dp(8), 0, 0);
        hint.setLineSpacing(dp(3), 1f);
        guideBox.addView(hint);

        Button go = new Button(this);
        go.setAllCaps(false);
        go.setText(nextLabel(s));
        go.setOnClickListener(v -> nextAction(guideState, hash));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        guideBox.addView(go, lp);
    }

    private String nextLabel(CertGuide.State s) {
        if (!s.exported) return "1. 导出 CA（现在做）";
        if (!s.userInstalled) return "2. 打开安全设置装证书";
        if (!s.systemInstalled) return s.systemWritable ? "3. 复制 root 安装命令" : "3. 看为什么装不进系统";
        return "4. 已完成，去日志看解密";
    }

    private void nextAction(CertGuide.State s, String hash) {
        if (s == null) return;
        if (!s.exported) {
            exportCert();
        } else if (!s.userInstalled) {
            openSecurity();
        } else if (!s.systemInstalled) {
            if (s.systemWritable && hash != null && !hash.isEmpty()) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "ggbot-ca", CertGuide.rootCommand(hash)));
                toast("命令已复制，到终端粘贴执行后重启");
            } else {
                toast(s.root ? "/system 是只读的（dm-verity），先 disable-verity 再试"
                        : "没有 root，也写不了系统信任库：只能停在用户证书这一步");
            }
            openSecurity();
        } else {
            toast("已就绪：打开 https 站点，日志里看到「解密」即成功");
        }
    }

    private void openSecurity() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS));
        } catch (Exception e) {
            toast("这台设备没有独立安全设置页，请在系统设置里找「安全」");
        }
    }

    /** 当前设备现状：导出没导出、装没装、系统库能不能写、有没有 root。 */
    private CertGuide.State probe() {
        CertGuide.State s = new CertGuide.State();
        s.exported = exportedExists();
        X509Certificate ca = CaStore.caCertificate();
        s.userInstalled = ca != null && inUserStore(ca);
        s.systemInstalled = ca != null && inSystemStore(ca);
        s.systemWritable = new File("/system/etc/security/cacerts").canWrite();
        s.root = new File("/system/bin/su").exists() || new File("/system/xbin/su").exists()
                || new File("/sbin/su").exists() || new File("/su/bin/su").exists();
        return s;
    }

    private boolean exportedExists() {
        if (Build.VERSION.SDK_INT >= 29) {
            try (android.database.Cursor c = getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Downloads._ID},
                    MediaStore.Downloads.DISPLAY_NAME + " LIKE ?",
                    new String[]{"GGBotCA%"}, null)) {
                return c != null && c.getCount() > 0;
            } catch (Exception e) {
                return false;
            }
        }
        return new File(android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "GGBotCA.crt").exists();
    }

    private boolean inUserStore(X509Certificate ca) {
        try {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("AndroidCAStore");
            ks.load(null, null);
            byte[] want = ca.getEncoded();
            java.util.Enumeration<String> e = ks.aliases();
            while (e.hasMoreElements()) {
                java.security.cert.Certificate c = ks.getCertificate(e.nextElement());
                if (c != null && java.util.Arrays.equals(c.getEncoded(), want)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean inSystemStore(X509Certificate ca) {
        try {
            File dir = new File("/system/etc/security/cacerts");
            File[] fs = dir.listFiles();
            if (fs == null) return false;
            byte[] want = ca.getEncoded();
            String pem = CaStore.caPem();
            byte[] pemBytes = pem == null ? null : pem.getBytes("US-ASCII");
            for (File f : fs) {
                long len = f.length();
                if (len < 100 || len > 8192) continue;
                byte[] raw = new byte[(int) len];
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    int off = 0, n;
                    while (off < raw.length && (n = in.read(raw, off, raw.length - off)) > 0) off += n;
                } catch (Exception e) {
                    continue;
                }
                if (java.util.Arrays.equals(raw, want)) return true;
                if (pemBytes != null && java.util.Arrays.equals(raw, pemBytes)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** 系统信任库文件名用的 subject_hash_old（MD5 前 4 字节小端）。 */
    private String subjectHash() {
        try {
            X509Certificate ca = CaStore.caCertificate();
            if (ca == null) return "";
            byte[] dn = ca.getSubjectX500Principal().getEncoded();
            byte[] md5 = java.security.MessageDigest.getInstance("MD5").digest(dn);
            int v = (md5[0] & 0xFF) | ((md5[1] & 0xFF) << 8)
                    | ((md5[2] & 0xFF) << 16) | ((md5[3] & 0xFF) << 24);
            return String.format(java.util.Locale.US, "%08x", v);
        } catch (Exception e) {
            return "";
        }
    }

    private void genNow() {
        genBtn.setEnabled(false);
        new Thread(() -> {
            try {
                DnsVpnService.ensureCa(getApplicationContext());
            } catch (Throwable ignored) {
            }
            runOnUiThread(() -> {
                genBtn.setEnabled(true);
                refresh();
                toast(CaStore.ready() ? "已生成" : "生成失败，请重试");
            });
        }, "ggbot-cert-gen").start();
    }

    /**
     * 导出 CA 到下载目录。
     *
     * 上一版有三个坑，这次一并补上：
     *   1) 只 insert 不删旧的：同名文件已存在时，系统可能生成「GGBotCA (1).crt」，
     *      用户按文案去点 GGBotCA.crt，装的其实是旧证书（实测就踩过：Downloads 里留着 3 天前的旧文件）。
     *   2) 不设 IS_PENDING：写一半被看到，甚至写失败也留下空文件。
     *   3) 写完后不回读：磁盘满/权限只给了一半时，界面上仍显示「已导出」。
     * 现在：先删同名旧文件 → 建待定条目 → 写入 → 回读比对长度 → 清除待定 → 报真实路径。
     */
    private void exportCert() {
        final String pem = CaStore.caPem();
        if (pem == null) {
            toast("CA 还没有生成，先点「立即生成」");
            return;
        }
        try {
            toast("已导出：" + writePemToDownloads(pem.getBytes("US-ASCII")));
        } catch (Exception e) {
            String msg = e.getMessage();
            toast("导出失败：" + (msg == null ? e.getClass().getSimpleName() : msg));
            Diag.push("CA 导出失败: " + e.getClass().getSimpleName()
                    + " " + (msg == null ? "" : msg), "warn");
        }
    }

    private String writePemToDownloads(byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            android.content.ContentResolver cr = getContentResolver();
            Uri col = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            // 先把同名旧文件删干净：实测只按 _ID 逐条删会留残，insert 时报
            // SQLiteConstraintException UNIQUE constraint failed: files._data。
            // 这里改成批量删（按 DISPLAY_NAME），再查一遍确认没剩。
            cr.delete(col, MediaStore.Downloads.DISPLAY_NAME + "=?", new String[]{"GGBotCA.crt"});
            try (android.database.Cursor c = cr.query(col,
                    new String[]{MediaStore.Downloads._ID},
                    MediaStore.Downloads.DISPLAY_NAME + "=?",
                    new String[]{"GGBotCA.crt"}, null)) {
                if (c != null && c.getCount() > 0) {
                    while (c.moveToNext()) {
                        cr.delete(android.content.ContentUris.withAppendedId(col, c.getLong(0)), null, null);
                    }
                }
            }
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, "GGBotCA.crt");
            cv.put(MediaStore.Downloads.MIME_TYPE, "application/x-x509-ca-cert");
            cv.put(MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = null;
            try {
                uri = cr.insert(col, cv);
            } catch (RuntimeException e) {
                // 还是撞库（UNIQUE _data）：说明旧行还在，找到它直接覆盖
                uri = findByName(cr, col);
                if (uri == null) throw e;
            }
            if (uri == null) throw new IllegalStateException("系统拒绝写入下载目录");
            OutputStream os = cr.openOutputStream(uri);
            if (os == null) throw new IllegalStateException("无法打开下载文件");
            try {
                os.write(data);
                os.flush();
            } finally {
                os.close();
            }
            byte[] back = readBack(cr, uri);
            if (back == null || back.length != data.length) {
                throw new IllegalStateException("写入校验失败（"
                        + (back == null ? 0 : back.length) + "/" + data.length + " 字节）");
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            return "下载目录/GGBotCA.crt";
        }
        File dir = android.os.Environment
                .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("下载目录不可用");
        File f = new File(dir, "GGBotCA.crt");
        FileOutputStream fo = new FileOutputStream(f);
        try {
            fo.write(data);
            fo.flush();
        } finally {
            fo.close();
        }
        if (f.length() != data.length) {
            throw new IllegalStateException("写入校验失败（" + f.length() + "/" + data.length + " 字节）");
        }
        android.media.MediaScannerConnection.scanFile(this,
                new String[]{f.getAbsolutePath()}, new String[]{"application/x-x509-ca-cert"}, null);
        return f.getAbsolutePath();
    }

    /** 按文件名找已存在的下载条目（用于撞 UNIQUE 时直接覆盖）。 */
    private static Uri findByName(android.content.ContentResolver cr, Uri col) {
        try (android.database.Cursor c = cr.query(col,
                new String[]{MediaStore.Downloads._ID},
                MediaStore.Downloads.DISPLAY_NAME + "=?",
                new String[]{"GGBotCA.crt"}, null)) {
            if (c != null && c.moveToFirst()) {
                return android.content.ContentUris.withAppendedId(col, c.getLong(0));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static byte[] readBack(android.content.ContentResolver cr, Uri uri) {
        try {
            java.io.InputStream in = cr.openInputStream(uri);
            if (in == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bos.write(b, 0, n);
            in.close();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private TextView tv(String text, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView sectionText(String text) {
        TextView t = tv(text, 12.5f, MUTED, true);
        t.setPadding(dp(2), dp(18), 0, dp(8));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackgroundColor(CARD);
        return c;
    }

    private LinearLayout padRow(TextView label, TextView value) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        value.setGravity(Gravity.RIGHT);
        r.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

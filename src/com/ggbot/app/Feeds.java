package com.ggbot.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * 规则订阅：从公开列表抓规则，解析后落到本机，再并进拦截库。
 *
 * 设计要点：
 *   · 每个源单独存一个文件，某个源抓失败或格式变坏，不会污染其它源
 *   · 元数据（地址、条数、更新时间、错误）存 SharedPreferences，启动时不必联网
 *   · 地址和规则都不经过任何服务器
 */
public final class Feeds {

    /** 单次下载上限，防着有人把地址指向一个巨大文件。 */
    private static final long MAX_BYTES = 12L * 1024 * 1024;
    /** 自动更新的最小间隔。 */
    private static final long AUTO_INTERVAL = 24L * 3600 * 1000;

    private static final String KEY_META = "feedMeta";
    private static final String KEY_INIT = "feedInit";
    /** v1.1 起换新初始化键：老用户升级时做一次「重叠源降噪」迁移。 */
    private static final String KEY_INIT2 = "feedInit2";

    /**
     * 内置订阅源。全部实测过可访问——
     * 注意 GitHub raw 在部分网络下完全不通，所以 GitHub 上的列表走 jsDelivr 镜像。
     *
     * 默认全部开启：订阅已整合成主页一个入口、源名不再露出，
     * 逐源启停的入口没了，重叠源多占的那点内存（每条一个 HashSet 对象）换省心。
     */
    public static final String[][] PRESET = {
            // 名称, 地址, 默认开启
            {"anti-AD 中国广告与追踪", "https://anti-ad.net/domains.txt", "1"},
            {"AdGuard DNS 过滤器", "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt", "1"},
            {"adrules DNS 规则", "https://adrules.top/dns.txt", "1"},
            {"StevenBlack 综合列表", "https://cdn.jsdelivr.net/gh/StevenBlack/hosts@master/hosts", "1"},
            {"EasyList China（含 URL 规则）", "https://easylist-downloads.adblockplus.org/easylistchina.txt", "1"},
            {"AdGuard 基础过滤器（含 URL 规则）", "https://filters.adtidy.org/extension/ublock/filters/2.txt", "1"},
    };

    private Feeds() {
    }

    /**
     * 元数据与规则文件的跨线程互斥。能并发进来 update 的入口有三个互不感知：
     * FeedJob（JobScheduler 每天）、启动时的 autoUpdate、设置页的手动更新。
     * 没有这把锁时，两个线程写同一个 .tmp 会交错写坏规则文件；
     * save() 的「读整串元数据 → 改 → 写回」会丢更新，用户刚删的源会被复活。
     * 注意锁只包共享状态的读写，网络下载不进来，避免 UI 线程等 37 秒。
     */
    private static final Object MONITOR = new Object();

    /* ---------------- 数据模型 ---------------- */

    public static final class Source {
        public String id;
        public String name;
        public String url;
        public boolean enabled;
        public int count;
        public long updated;
        public String note = "";

        public String updatedText() {
            if (updated <= 0) return note.isEmpty() ? "未更新" : note;
            long days = (System.currentTimeMillis() - updated) / 86400000L;
            String when;
            if (days <= 0) when = "今天";
            else if (days == 1) when = "昨天";
            else when = days + " 天前";
            return count + " 条 · " + when;
        }
    }

    /** 一次更新的结果回调，UI 与后台任务共用。 */
    public interface Listener {
        void onSourceDone(Source s, String error);
    }

    /* ---------------- 元数据存取 ---------------- */

    private static SharedPreferences sp(Context c) {
        return Config.prefs(c);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    /**
     * v3 迁移：订阅整合成一个入口、源名隐藏后不再有逐源启停，
     * 老安装升级时把内置源全部打开（只动 PRESET 里的地址，用户自加的源不碰）。
     */
    private static final String KEY_INIT3 = "feedInit3";

    private static void migrateAllOn(Context c, SharedPreferences p) {
        if (p.getBoolean(KEY_INIT3, false)) return;
        List<Source> all = parseMeta(p.getString(KEY_META, ""));
        boolean ch = false;
        for (Source s : all) {
            for (String[] row : PRESET) {
                if (row[1].equals(s.url) && !s.enabled) {
                    s.enabled = true;
                    ch = true;
                }
            }
        }
        if (ch) save(c, all);
        p.edit().putBoolean(KEY_INIT3, true).apply();
    }

    /** 首次使用时写入预设源；老用户升级时把默认开着的重叠源关掉。之后完全以用户的选择为准。 */
    private static void ensureInit(Context c) {
        SharedPreferences p = sp(c);
        // feedMeta 被清掉（例如外部恢复备份只留了开关位）时，标记位不可信，
        // 落回首次初始化：标记位说初始化过但规则一条没有，比按存量元数据跑安全
        boolean metaEmpty = p.getString(KEY_META, "").isEmpty();
        if (p.getBoolean(KEY_INIT2, false) && !metaEmpty) {
            migrateAllOn(c, p);
            return;
        }
        if (p.getBoolean(KEY_INIT, false) && !metaEmpty) {
            // v1 老安装统一收编到 v3 语义（内置源全开）。
            // v2 曾在这里先关掉与 anti-AD 重叠过半的 AdGuard DNS，但 v3 的迁移是
            // 「内置源全部打开」——两段目标相反，背靠背执行时后者把前者原样撤销，
            // 降噪等于没做。按最新语义只留全开这一段。
            migrateAllOn(c, p);
            p.edit().putBoolean(KEY_INIT2, true).apply();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String[] row : PRESET) {
            Source s = new Source();
            s.name = row[0];
            s.url = row[1];
            s.enabled = "1".equals(row[2]);
            s.id = idOf(s.url);
            sb.append(s.id).append('\t')
                    .append(esc(s.name)).append('\t')
                    .append(esc(s.url)).append('\t')
                    .append(s.enabled ? '1' : '0').append("\t0\t0\t\n");
        }
        p.edit().putString(KEY_META, sb.toString()).putBoolean(KEY_INIT2, true).apply();
    }

    /**
     * 订阅源是否用明文 http。
     *
     * 为什么要单独判：清单里全局允许明文（cleartextTrafficPermitted），唯一会用到它的是
     * 「用户自己填的订阅地址」。明文订阅的规则可以被链路上任何人改写，而规则决定哪些域名被拦，
     * 所以宁可提示一句，也不默认放过。自动补协议时一律补 https（见 add），
     * 只有用户明确写 http:// 才算明文。
     */
    public static boolean insecure(String url) {
        if (url == null) return false;
        return url.trim().toLowerCase(java.util.Locale.US).startsWith("http://");
    }

    public static String idOf(String url) {
        return Integer.toHexString(url == null ? 0 : url.hashCode());
    }

    public static List<Source> list(Context c) {
        // 元数据是「读整串→改→写回」模型，读这一侧也要过 MONITOR：
        // 不加锁的话，首装首秒 autoUpdate 线程还在 ensureInit、设置页 add() 已经进来，
        // 两边都会拿纯 PRESET 覆写 KEY_META，用户刚加的源就被抹掉（MONITOR 可重入，
        // add 持锁调进来不死锁）。
        synchronized (MONITOR) {
            ensureInit(c);
            return parseMeta(sp(c).getString(KEY_META, ""));
        }
    }

    /** 只做文本→对象，不触发初始化，迁移逻辑专用。 */
    private static List<Source> parseMeta(String raw) {
        ArrayList<Source> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 4) continue;
            Source s = new Source();
            s.id = f[0];
            s.name = f[1];
            s.url = f[2];
            s.enabled = "1".equals(f[3]);
            try {
                s.count = Integer.parseInt(f[4]);
            } catch (Exception ignored) {
            }
            try {
                s.updated = Long.parseLong(f[5]);
            } catch (Exception ignored) {
            }
            s.note = f.length > 6 ? f[6] : "";
            out.add(s);
        }
        return out;
    }

    private static void save(Context c, List<Source> all) {
        StringBuilder sb = new StringBuilder();
        for (Source s : all) {
            sb.append(s.id).append('\t')
                    .append(esc(s.name)).append('\t')
                    .append(esc(s.url)).append('\t')
                    .append(s.enabled ? '1' : '0').append('\t')
                    .append(s.count).append('\t')
                    .append(s.updated).append('\t')
                    .append(esc(s.note)).append('\n');
        }
        // 「读整串 → 改 → 写回」的元数据只在这里序列化写，并发丢更新就靠 MONITOR
        synchronized (MONITOR) {
            sp(c).edit().putString(KEY_META, sb.toString()).apply();
        }
    }

    public static Source byId(Context c, String id) {
        for (Source s : list(c)) {
            if (s.id.equals(id)) return s;
        }
        return null;
    }

    public static Source add(Context c, String url, String name) {
        if (url == null) return null;
        String u = url.trim();
        if (u.isEmpty()) return null;
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        synchronized (MONITOR) {
            List<Source> all = list(c);
            String id = idOf(u);
            for (Source s : all) {
                if (s.id.equals(id)) return null;   // 已经有了
            }
            Source s = new Source();
            s.id = id;
            s.url = u;
            s.name = (name == null || name.trim().isEmpty()) ? shortName(u) : name.trim();
            s.enabled = true;
            all.add(s);
            save(c, all);
            return s;
        }
    }

    private static String shortName(String url) {
        try {
            String h = URI.create(url.trim()).getHost();
            return h == null ? url : h;
        } catch (Exception e) {
            return url;
        }
    }

    public static void remove(Context c, String id) {
        synchronized (MONITOR) {
            List<Source> all = list(c);
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id.equals(id)) {
                    all.remove(i);
                    break;
                }
            }
            save(c, all);
            File f = fileFor(c, id);
            if (f.exists()) f.delete();
        }
    }

    public static void setEnabled(Context c, String id, boolean on) {
        synchronized (MONITOR) {
            List<Source> all = list(c);
            for (Source s : all) {
                if (s.id.equals(id)) {
                    s.enabled = on;
                    break;
                }
            }
            save(c, all);
        }
    }

    /* ---------------- 抓取与落盘 ---------------- */

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "feeds");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File fileFor(Context c, String id) {
        return new File(dir(c), id + ".txt");
    }

    /**
     * 抓一个源并落盘。返回 null 表示成功，否则是给人看的错误原因。
     * 必须在后台线程调用。
     */
    public static String update(Context c, Source s) {
        ListParser.Out out = new ListParser.Out();
        String err;
        boolean notModified = false;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(s.url.trim()).toURL().openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(25000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) GGBot/1.0");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            // 条件请求：有上次的验证器就带上，服务端说没变就不用下整包再解析
            android.content.SharedPreferences sp = Config.prefs(c);
            String etag = Cond.ifNoneMatch(sp.getString("etag:" + s.id, null));
            String lastMod = Cond.ifModifiedSince(sp.getString("lm:" + s.id, null));
            if (etag != null) conn.setRequestProperty("If-None-Match", etag);
            if (lastMod != null) conn.setRequestProperty("If-Modified-Since", lastMod);
            int code = conn.getResponseCode();
            if (Cond.fresh(code)) {
                notModified = true;
                err = null;
            } else if (code != 200) {
                err = "HTTP " + code;
            } else {
                String type = conn.getContentType();
                InputStream is = conn.getInputStream();
                String enc = conn.getContentEncoding();
                if (enc != null && enc.toLowerCase(Locale.US).contains("gzip")) {
                    is = new GZIPInputStream(is);
                }
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(new Limited(is, MAX_BYTES), "UTF-8"), 32768);
                ListParser.parse(r, out);
                r.close();
                if (out.total() == 0) {
                    err = "没有解析出规则" + (type == null ? "" : "（" + type + "）");
                } else if (type != null && type.toLowerCase(Locale.US).contains("html")) {
                    err = "返回的是网页，不是规则文件";
                } else {
                    err = null;
                    // 存下验证器，下次带上；服务端不认就整包重下，不影响正确性
                    String newEtag = conn.getHeaderField("ETag");
                    String newLm = conn.getHeaderField("Last-Modified");
                    if (Cond.usable(newEtag)) sp.edit().putString("etag:" + s.id, newEtag).apply();
                    if (Cond.usable(newLm)) sp.edit().putString("lm:" + s.id, newLm).apply();
                }
            }
        } catch (Exception e) {
            err = shortError(e);
        } finally {
            if (conn != null) conn.disconnect();
        }

        // 下面这段在多个更新线程间是共享可变状态（同一 .tmp 文件、整串元数据），
        // 必须互斥；网络下载在上面，没占锁
        synchronized (MONITOR) {
            if (notModified) {
                // 304：内容没变，别重写规则文件，也别当成失败让系统重试
                List<Source> same = list(c);
                for (Source x : same) {
                    if (x.id.equals(s.id)) x.note = "未变更";
                }
                s.note = "未变更";
                save(c, same);
                Diag.push("订阅未变更（304）：" + s.id, "info");
                return null;
            }
            if (err != null) {
                s.note = err;
                Diag.push("订阅更新失败：" + s.id + " · " + err, "warn");
                List<Source> all = list(c);
                for (Source x : all) {
                    if (x.id.equals(s.id)) x.note = err;
                }
                save(c, all);
                return err;
            }

            int newCount = out.total();
            // 内容突变防线（https 之上的一层）：规则条数比上次骤减超半，
            // 大概率是源站被黑/CDN 投毒/服务端出错，不落盘，保留旧规则并告警。
            // 条数上升不拦——列表扩容是正常演进；骤减才危险（拦截能力被静默抽空）。
            List<Source> before = list(c);
            int oldCount = 0;
            for (Source x : before) {
                if (x.id.equals(s.id)) oldCount = x.count;
            }
            if (oldCount >= 1000 && newCount < oldCount / 2) {
                String msg = "规则骤减 " + oldCount + "→" + newCount + "，疑似源被污染，已保留旧规则";
                s.note = msg;
                Diag.push("订阅安全：" + s.id + " · " + msg, "warn");
                for (Source x : before) {
                    if (x.id.equals(s.id)) x.note = msg;
                }
                save(c, before);
                return msg;
            }
            if (!writeFile(c, s.id, out)) return "写入失败";
            s.count = newCount;
            s.updated = System.currentTimeMillis();
            s.note = "";
            Diag.push("订阅已更新：" + s.id + " 共 " + newCount + " 条", "info");
            List<Source> all = list(c);
            for (Source x : all) {
                if (x.id.equals(s.id)) {
                    x.count = s.count;
                    x.updated = s.updated;
                    x.note = "";
                }
            }
            save(c, all);
            return null;
        }
    }

    private static String shortError(Exception e) {
        String m = e.getMessage();
        String name = e.getClass().getSimpleName();
        if (m == null) return name;
        if (m.length() > 60) m = m.substring(0, 60);
        return name + ": " + m;
    }

    private static boolean writeFile(Context c, String id, ListParser.Out out) {
        File tmp = new File(dir(c), id + ".tmp");
        BufferedWriter w = null;
        try {
            w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8"), 32768);
            section(w, ">d", out.domains);
            section(w, ">w", out.wild);
            section(w, ">u", out.urls);
            section(w, ">a", out.allowDomains);
            section(w, ">au", out.allowUrls);
            section(w, ">b", out.badfilter);
            section(w, ">i", out.impDomains);
            section(w, ">iu", out.impUrls);
            mapSection(w, ">r", out.redirects);
            w.flush();
            w.close();
            w = null;
            File dst = fileFor(c, id);
            // POSIX rename 原子替换目标文件，先 delete 是画蛇添足：
            // delete 成功而 rename 失败时整源规则彻底消失（finally 还会把 tmp 删掉），
            // 加载端静默跳过缺失文件，拦截能力悄悄缩水且无感。直接 rename，
            // 失败时旧文件原样在，规则退回上一版。
            return tmp.renameTo(dst);
        } catch (Exception e) {
            return false;
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Exception ignored) {
                }
            }
            if (tmp.exists()) tmp.delete();
        }
    }

    private static void section(BufferedWriter w, String tag, Set<String> items) throws IOException {
        w.write(tag);
        w.write('\n');
        for (String s : items) {
            w.write(s);
            w.write('\n');
        }
    }

    /** 键值段落：一行一条「规则 → 值」，用制表符分隔。 */
    private static void mapSection(BufferedWriter w, String tag,
                                   java.util.Map<String, String> items) throws IOException {
        w.write(tag);
        w.write('\n');
        for (java.util.Map.Entry<String, String> e : items.entrySet()) {
            w.write(e.getKey());
            w.write('\t');
            w.write(e.getValue());
            w.write('\n');
        }
    }

    /**
     * 更新所有启用的源。返回成功条数；全程在调用线程执行，请放在后台线程。
     */
    public static int updateAll(Context c, Listener l) {
        int ok = 0;
        for (Source s : list(c)) {
            if (!s.enabled) continue;
            String err = update(c, s);
            if (err == null) ok++;
            if (l != null) l.onSourceDone(s, err);
        }
        Config.setFeedChecked(c, System.currentTimeMillis());
        Rules.reloadAll(c);
        return ok;
    }

    /**
     * 打开界面时的兜底检查：距上次更新超过一天就拉一次。
     *
     * 常态更新交给 FeedJob（JobScheduler，每天一次、只在 WiFi 下），
     * 这里的职责只是兜住那些禁用 JobScheduler 的 ROM，以及用户手动开关订阅源之后的即时生效。
     */
    public static void autoUpdate(Context c) {
        if (!Config.feedAuto(c)) return;
        long last = Config.feedChecked(c);
        boolean empty = totalCount(c) == 0;
        // 第一次下载有近 3MB，只在非计费网络下自动进行
        if (empty && isMetered(c)) return;
        if (!empty && System.currentTimeMillis() - last < AUTO_INTERVAL) return;

        boolean any = false;
        for (Source s : list(c)) {
            if (s.enabled) {
                any = true;
                break;
            }
        }
        if (!any) return;
        Thread t = new Thread(() -> updateAll(c, null), "ggbot-feed-auto");
        t.setDaemon(true);
        t.start();
    }

    private static boolean isMetered(Context c) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    c.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null && cm.isActiveNetworkMetered();
        } catch (Exception e) {
            return false;
        }
    }

    /* ---------------- 读回内存 ---------------- */

    /** 合并后的订阅规则。 */
    public static final class Bundle {
        public final Set<String> domains = new HashSet<>();
        public final Set<String> wild = new HashSet<>();
        public final Set<String> urls = new HashSet<>();
        public final Set<String> allowDomains = new HashSet<>();
        public final Set<String> allowUrls = new HashSet<>();
        /** $badfilter 点名的规则，合并时要删掉。 */
        public final Set<String> badfilter = new HashSet<>();
        /** $important 规则，判定时排在 @@ 例外之前。 */
        public final Set<String> impDomains = new HashSet<>();
        public final Set<String> impUrls = new HashSet<>();
        /** $redirect 规则 → 替代资源名。 */
        public final java.util.HashMap<String, String> redirects = new java.util.HashMap<>();
    }

    public static Bundle load(Context c) {
        Bundle b = new Bundle();
        for (Source s : list(c)) {
            if (!s.enabled) continue;
            File f = fileFor(c, s.id);
            if (!f.exists()) continue;
            readInto(f, b);
        }
        return b;
    }

    private static void readInto(File f, Bundle b) {
        BufferedReader r = null;
        Set<String> cur = null;
        java.util.HashMap<String, String> curMap = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 16384);
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                if (line.charAt(0) == '>') {
                    curMap = null;
                    switch (line) {
                        case ">d": cur = b.domains; break;
                        case ">w": cur = b.wild; break;
                        case ">u": cur = b.urls; break;
                        case ">a": cur = b.allowDomains; break;
                        case ">au": cur = b.allowUrls; break;
                        case ">b": cur = b.badfilter; break;
                        case ">i": cur = b.impDomains; break;
                        case ">iu": cur = b.impUrls; break;
                        case ">r": cur = null; curMap = b.redirects; break;
                        default: cur = null;
                    }
                    continue;
                }
                if (curMap != null) {
                    int tab = line.indexOf('\t');
                    if (tab > 0 && tab < line.length() - 1) {
                        curMap.put(line.substring(0, tab), line.substring(tab + 1));
                    }
                    continue;
                }
                if (cur != null) cur.add(line);
            }
        } catch (Exception ignored) {
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static int enabledCount(Context c) {
        int n = 0;
        for (Source s : list(c)) {
            if (s.enabled) n++;
        }
        return n;
    }

    public static int totalCount(Context c) {
        int n = 0;
        for (Source s : list(c)) {
            if (s.enabled) n += s.count;
        }
        return n;
    }

    /** 下载量上限，超了就停，避免被超大文件拖死。 */
    private static final class Limited extends FilterInputStream {
        private long left;

        Limited(InputStream in, long max) {
            super(in);
            left = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                if (--left < 0) throw new IOException("文件超过 " + (MAX_BYTES / 1024 / 1024) + "MB 上限");
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (n > 0) {
                left -= n;
                if (left < 0) throw new IOException("文件超过 " + (MAX_BYTES / 1024 / 1024) + "MB 上限");
            }
            return n;
        }
    }
}

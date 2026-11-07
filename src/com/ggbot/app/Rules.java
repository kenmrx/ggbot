package com.ggbot.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 规则库 = 内置黑名单（assets/blocklist.txt）+ 订阅源 + 用户自定义。
 *
 * 候选量到三十万这一级之后，数据结构就不能随便用了：
 *   · 域名与订阅源合并后存成排序数组（RuleSet），比 HashSet 省近一半内存
 *   · 订阅源带来的 URL 规则按域名分桶（UrlIndex），请求进来只比对几条
 *   · 域名带通配的规则单独放 Wild，扫一遍但很便宜
 *
 * 优先级：用户白名单 > 用户拦截 > 订阅源例外 > 订阅源规则 > 内置库 > 内置特征。
 * 用户显式表达永远排在最前面，这样误伤随时能救回来。
 */
public final class Rules {

    private static final Object LOCK = new Object();

    private static HashSet<String> builtin = new HashSet<>();
    private static boolean assetsLoaded = false;

    // volatile：DNS 线程在 isBlocked/blockReason 里直接读这两个集合，
    // 写入在别的线程（UI 点放行、规则重载）。没有 volatile 就没有 happens-before，
    // 用户当场放行的域名可能在 DNS 层继续被拦。URL 层走 snap 快照不受此影响。
    private static volatile HashSet<String> userBlock = new HashSet<>();
    private static volatile HashSet<String> userAllow = new HashSet<>();
    private static volatile HashSet<String> urlBlock = new HashSet<>();
    private static volatile HashSet<String> urlAllow = new HashSet<>();

    private static volatile RuleSet base = RuleSet.EMPTY;
    private static volatile RuleSet feedAllows = RuleSet.EMPTY;
    private static volatile Wild wild = Wild.EMPTY;
    private static volatile UrlIndex feedUrls = UrlIndex.EMPTY;
    private static volatile UrlIndex feedAllowUrls = UrlIndex.EMPTY;
    private static volatile RuleSet important = RuleSet.EMPTY;
    private static volatile UrlIndex importantUrls = UrlIndex.EMPTY;
    private static volatile java.util.Map<String, String> redirects =
            java.util.Collections.emptyMap();

    /** 判定用的整体快照，规则变了就换一份，读侧永远看到一致的组合。 */
    private static volatile Decision.Sets snap = new Decision.Sets();

    private static volatile int epoch = 0;
    private static volatile int cachedEpoch = -1;
    private static volatile boolean loading = false;
    private static final java.util.concurrent.atomic.AtomicBoolean PENDING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private Rules() {
    }

    /** 首次加载内置库；订阅源数据变了才重算合并结果。 */
    public static void load(Context c) {
        synchronized (LOCK) {
            loading = true;
            try {
                if (!assetsLoaded) {
                    loadBuiltin(c);
                    assetsLoaded = true;
                    epoch++;
                }
                userBlock = new HashSet<>(Config.blocks(c));
                userAllow = new HashSet<>(Config.allow(c));
                urlBlock = new HashSet<>(Config.urlBlocks(c));
                urlAllow = new HashSet<>(Config.urlAllow(c));
                if (cachedEpoch != epoch) rebuild(c);
                // 用户规则变了不必重排三十万条，但快照必须跟着换，否则新加的白名单要等下次重启才生效
                rebuildSnapshot();
            } finally {
                loading = false;
            }
        }
    }

    /** 订阅源增删改后调用，下一轮判断立刻用上新规则。 */
    public static void reloadAll(Context c) {
        synchronized (LOCK) {
            epoch++;
            load(c);
        }
    }

    /**
     * 后台载入。三十万条规则排序要一到两秒，放主线程会明显卡顿，
     * 所以界面和服务都用这个入口；载入完成前一律放行（宁可漏拦，不能断网）。
     */
    public static void loadAsync(final Context c, final Runnable done) {
        // 界面每次 resume 都会调一次，已经在载入就不必再排一个线程等锁
        if (!PENDING.compareAndSet(false, true)) {
            if (done != null) done.run();
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    load(c);
                } finally {
                    PENDING.set(false);
                }
                if (done != null) done.run();
            }
        }, "ggbot-rules-load");
        t.setDaemon(true);
        t.start();
    }

    /** 规则是否已经可用。返回 false 时匹配会一律放行。 */
    public static boolean ready() {
        return cachedEpoch >= 0;
    }

    /** 用户改了 URL 规则后立即生效，不必重启通道。 */
    public static void reloadUrl(Context c) {
        synchronized (LOCK) {
            urlBlock = new HashSet<>(Config.urlBlocks(c));
            urlAllow = new HashSet<>(Config.urlAllow(c));
            rebuildSnapshot();
        }
    }

    /**
     * 用户当场点「放行」时用这个，不要用 reloadAll。
     *
     * reloadAll 会把三十万条域名重新排序、重建索引，在 UI 线程上要卡一秒左右；
     * 而这里只需要把一个域名塞进白名单集合，然后换一份快照指针——微秒级。
     * 判定侧读的是 volatile 快照，所以换完立刻生效，不必等下次重启。
     */
    public static void allowNow(String host) {
        if (host == null || host.isEmpty()) return;
        synchronized (LOCK) {
            String h = host.toLowerCase(Locale.US);
            HashSet<String> next = new HashSet<>(userAllow);
            if (!next.add(h)) return;
            userAllow = next;
            rebuildSnapshot();
        }
    }

    /**
     * 用户当场点「拦截这个域名」时用这个，和 allowNow 对称：只往自定义拦截集合里加一条，
     * 换一份快照指针，判定侧下一次查询就生效，不重排整库。
     */
    public static void blockNow(String host) {
        if (host == null || host.isEmpty()) return;
        synchronized (LOCK) {
            String h = host.toLowerCase(Locale.US);
            HashSet<String> next = new HashSet<>(userBlock);
            if (!next.add(h)) return;
            userBlock = next;
            rebuildSnapshot();
        }
    }

    private static void loadBuiltin(Context c) {
        HashSet<String> set = new HashSet<>(131072);
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(
                    c.getAssets().open("blocklist.txt"), Charset.forName("UTF-8")), 16384);
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int sp = line.indexOf(' ');
                if (sp >= 0) line = line.substring(0, sp);
                line = line.trim().toLowerCase(Locale.US);
                if (line.length() < 4 || line.indexOf('.') < 0) continue;
                set.add(line);
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
        builtin = set;
    }

    private static void rebuild(Context c) {
        Feeds.Bundle b = Feeds.load(c);
        applyBadFilter(b);
        base = RuleSet.merge(builtin, b.domains);
        feedAllows = RuleSet.of(b.allowDomains);
        wild = Wild.of(b.wild);
        feedUrls = UrlIndex.of(b.urls);
        feedAllowUrls = UrlIndex.of(b.allowUrls);
        important = RuleSet.of(b.impDomains);
        importantUrls = UrlIndex.of(b.impUrls);
        redirects = b.redirects.isEmpty()
                ? java.util.Collections.<String, String>emptyMap()
                : new java.util.HashMap<>(b.redirects);
        cachedEpoch = epoch;
        rebuildSnapshot();
    }

    /** 把当前各份规则拼成一份不可变快照，判定路径只读这一份。 */
    private static void rebuildSnapshot() {
        Decision.Sets s = new Decision.Sets();
        s.userAllow = userAllow;
        s.urlAllow = urlAllow;
        s.urlBlock = urlBlock;
        s.userBlock = userBlock;
        s.important = important;
        s.importantUrls = importantUrls;
        s.feedAllowUrls = feedAllowUrls;
        s.feedAllows = feedAllows;
        s.feedUrls = feedUrls;
        s.wild = wild;
        s.base = base;
        s.redirects = redirects;
        snap = s;
    }

    /**
     * 执行 $badfilter：上游列表用它撤销自己写错的规则。
     *
     * 这一步不能省。AdGuard DNS 过滤器里有一条 ||pl.ua^$badfilter，就是在撤销对
     * 波兰国家注册域的整段封禁；不执行的话，pl.ua 下面所有站点都会被判成广告。
     * 同一批还有 wykop.pl、fixtures.onet.pl 这类正经站点。
     */
    private static void applyBadFilter(Feeds.Bundle b) {
        if (b.badfilter.isEmpty()) return;
        b.domains.removeAll(b.badfilter);
        b.allowDomains.removeAll(b.badfilter);
        b.urls.removeAll(b.badfilter);
        b.allowUrls.removeAll(b.badfilter);
        b.wild.removeAll(b.badfilter);
        // $important 与 $redirect 也必须能被撤销，否则上游改一次规则，这边就永久留一条错判
        b.impDomains.removeAll(b.badfilter);
        b.impUrls.removeAll(b.badfilter);
        b.redirects.keySet().removeAll(b.badfilter);
    }

    /* ---------------- DNS 层判断 ---------------- */

    /**
     * DNS 层的拦截判定。这里有两条与 URL 层一致的顺序要求：
     *   用户白名单最大，$important 高于订阅源例外。
     * 少一条 $important，上游辛辛苦苦标记的「这确实是广告」就会被别人家的 @@ 放行规则吃掉。
     *
     * 剥掉主机名尾部的根点（example.com. → example.com）。HTTP 层的 Host 头合法可带。
     */
    public static String stripTrailingDots(String host) {
        if (host == null) return host;
        String h = host;
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h;
    }

    public static boolean isBlocked(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = stripTrailingDots(host.toLowerCase(Locale.US));
        // 尾点不剥的话全部后缀规则对它失配，静默放行
        if (h.isEmpty()) return false;
        if (Domains.matches(userAllow, h)) return false;
        if (Domains.matches(userBlock, h)) return true;
        if (important.matches(h)) return true;
        if (feedAllows.matches(h)) return false;
        if (wild.matches(h)) return true;
        return base.matches(h);
    }

    /**
     * 检查 CNAME 链，返回第一个命中黑名单的目标域名，没有则返回 null。
     *
     * 这条判定专门对付「第一方 CNAME 隐身」：查询的域名干干净净（往往是站点自己的子域），
     * 上游却把它 CNAME 到某个统计域。只看 QNAME 的拦截器一辈子也发现不了。
     * Pi-hole 的 CNAME inspection 与 AdGuard Home 的同类功能做的都是这一件事。
     */
    public static String cnameHit(String[] chain) {
        if (chain == null || chain.length == 0) return null;
        for (String hop : chain) {
            if (hop == null || hop.isEmpty()) continue;
            if (isBlocked(hop)) return hop;
        }
        return null;
    }

    /** 同上，但告诉调用方是哪一类规则拦的，用于日志归因。 */
    public static String blockReason(String host) {
        if (host == null || host.isEmpty()) return null;
        String h = host.toLowerCase(Locale.US);
        if (Domains.matches(userBlock, h)) return "自定义 " + h;
        if (important.matches(h)) return "$important";
        String w = wild.match(h);
        if (w != null) return "订阅通配 " + w;
        if (base.matches(h)) return "订阅/内置域名库";
        return null;
    }

    /* ---------------- 解密后的 URL 级判断 ---------------- */

    /** 判定顺序全部在 Decision 里，这里只负责把当前快照递过去。 */
    public static Hit match(String host, String path) {
        return Decision.match(snap, host, path);
    }

    public static boolean blockUrl(String host, String path) {
        return match(host, path).blocked;
    }

    /** 一次匹配的结果。rule 为 null 表示放行且没有命中白名单。 */
    public static final class Hit {
        public static final Hit PASS = new Hit(false, null, null);

        public final boolean blocked;
        public final String rule;
        /** 命中 $redirect 时的替代资源名，例如 noop.js；没有则为 null。 */
        public final String redirect;

        public Hit(boolean blocked, String rule) {
            this(blocked, rule, null);
        }

        public Hit(boolean blocked, String rule, String redirect) {
            this.blocked = blocked;
            this.rule = rule;
            this.redirect = redirect;
        }
    }

    /* ---------------- 统计 ---------------- */

    public static int urlRuleSize() {
        return Decision.urlRuleSize();
    }

    public static int importantSize() {
        return important.size() + importantUrls.size();
    }

    public static int redirectSize() {
        return redirects.size();
    }

    public static int builtinSize() {
        return builtin.size();
    }

    public static int baseSize() {
        return base.size();
    }

    public static int feedUrlSize() {
        return feedUrls.size() + feedAllowUrls.size() + importantUrls.size();
    }

    public static int wildSize() {
        return wild.size();
    }

    public static int userBlockSize() {
        return userBlock.size();
    }

    public static int userAllowSize() {
        return userAllow.size();
    }

    public static int totalSize() {
        return base.size() + userBlock.size();
    }

    public static boolean isLoading() {
        return loading;
    }
}

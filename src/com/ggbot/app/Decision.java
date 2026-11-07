package com.ggbot.app;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * URL 级判定的唯一入口：谁先谁后，全写在这里。
 *
 * 之所以单独拆出来，是因为这套顺序本身就是一份规格说明，而它最容易在改动中被悄悄打乱。
 * 顺序错了不会崩，只会安静地多放过去一批广告——这种错最难发现，所以必须能被桌面测试直接盯住。
 *
 * 优先级（从上到下，先命中先返回）：
 *   1 用户白名单        自己写的东西永远最大
 *   2 用户 URL 白名单
 *   3 用户 URL 拦截
 *   4 用户域名拦截
 *   5 $important        上游标记「这确实是广告」，压过一切订阅源的 @@ 例外
 *   6 订阅源 URL 例外   作者对自己规则的修正，必须尊重，否则会误伤人家特意放行的站点
 *   7 订阅源域名例外
 *   8 订阅源 URL 规则
 *   9 订阅通配
 *  10 域名库
 *  11 内置特征
 *
 * 第 5 与第 6 的先后是 uBlock Origin 明确规定的：$important 存在的唯一理由，
 * 就是在别人写了错误例外时仍然拦住。搞反了等于给广告留后门。
 */
public final class Decision {

    /** 一次判定用的规则快照。规则重载时整体换掉，读侧不必加锁。 */
    public static final class Sets {
        public Set<String> userAllow = java.util.Collections.emptySet();
        public Set<String> urlAllow = java.util.Collections.emptySet();
        public Set<String> urlBlock = java.util.Collections.emptySet();
        public Set<String> userBlock = java.util.Collections.emptySet();
        public RuleSet important = RuleSet.EMPTY;
        public UrlIndex importantUrls = UrlIndex.EMPTY;
        public UrlIndex feedAllowUrls = UrlIndex.EMPTY;
        public RuleSet feedAllows = RuleSet.EMPTY;
        public UrlIndex feedUrls = UrlIndex.EMPTY;
        public Wild wild = Wild.EMPTY;
        public RuleSet base = RuleSet.EMPTY;
        /** 规则串 → $redirect 资源名。 */
        public Map<String, String> redirects = java.util.Collections.emptyMap();
    }

    /** 主机名包含即命中。都是纯广告/统计域，与域名库互补。 */
    private static final String[] HOST_HINTS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "hm.baidu.com", "pos.baidu.com", "cpro.baidu.com",
            "mobads.baidu.com", "umeng.com", "cnzz.com", "mmstat.com",
            "adnxs.com", "criteo.com", "taboola.com", "outbrain.com",
            "amazon-adsystem.com", "scorecardresearch.com", "quantserve.com",
            "adsafeprotected.com", "app-measurement.com", "adsrvr.org",
            "rubiconproject.com", "pubmatic.com", "casalemedia.com",
            "tanx.com", "alimama.com", "ipinyou.com", "mediav.com",
            "growingio.com", "talkingdata.com", "sensorsdata.cn",
    };

    /** 路径包含即命中：这些片段本身就是广告服务的独有端点。 */
    private static final String[] PATH_HINTS = {
            "/cpro/", "/eclick/", "/adx/", "/pagead/", "/adsbygoogle",
            "/adsense/", "/adserver/", "/adservice/", "/adframe",
            "/beacon.js", "/beacon.gif", "/track.gif", "/pixel.gif",
            // 裸词（doubleclick 等）不能放这里：/how-doubleclick-works/ 这类正常页面
            // 会被误拦；广告域本身已由 HOST_HINTS 按标签边界拦住，路径层不必重复。
            // /collect 也不能放：GA 端点是精确的 /collect 结尾，contains 会误伤 /collector。
            "/analytics.js",
    };

    /** 路径段精确等于这些词才命中：/ad/ 中招，/download/ 不会被误伤。 */
    private static final Set<String> AD_SEGMENTS = new HashSet<>();

    static {
        String[] segs = {
                "ad", "ads", "adv", "adx", "adserver", "adview", "adclick",
                "banner", "banners", "popunder", "preroll",
                "tracking", "analytics", "beacon",
        };
        for (String s : segs) AD_SEGMENTS.add(s);
    }

    private Decision() {
    }

    public static Rules.Hit match(Sets s, String host, String path) {
        if (host == null || host.isEmpty()) return Rules.Hit.PASS;
        String h = host.toLowerCase(Locale.US);

        // 1 用户白名单
        if (Domains.matches(s.userAllow, h)) return new Rules.Hit(false, "白名单");

        // 2 用户 URL 白名单
        if (!s.urlAllow.isEmpty()) {
            for (String r : s.urlAllow) {
                if (UrlRule.matches(r, h, path)) return new Rules.Hit(false, r);
            }
        }

        // 3 用户 URL 拦截
        if (!s.urlBlock.isEmpty()) {
            for (String r : s.urlBlock) {
                if (UrlRule.matches(r, h, path)) return hit(s, true, "自定义 " + r, r);
            }
        }

        // 4 用户域名拦截
        if (Domains.matches(s.userBlock, h)) return new Rules.Hit(true, "自定义拦截 " + h);

        // 5 $important：压过下面所有例外
        if (!s.importantUrls.isEmpty()) {
            String r = s.importantUrls.match(h, path);
            if (r != null) return hit(s, true, "$important " + r, r);
        }
        if (s.important.matches(h)) return new Rules.Hit(true, "$important");

        // 6 订阅源 URL 例外
        if (!s.feedAllowUrls.isEmpty()) {
            String r = s.feedAllowUrls.match(h, path);
            if (r != null) return new Rules.Hit(false, "订阅例外 " + r);
        }

        // 7 订阅源域名例外
        if (!s.feedAllows.isEmpty() && s.feedAllows.matches(h)) {
            return new Rules.Hit(false, "订阅例外");
        }

        // 8 订阅源 URL 规则
        if (!s.feedUrls.isEmpty()) {
            String r = s.feedUrls.match(h, path);
            if (r != null) return hit(s, true, "订阅 " + r, r);
        }

        // 9 订阅通配
        String w = s.wild.match(h);
        if (w != null) return new Rules.Hit(true, "订阅通配 " + w);

        // 10 域名库
        if (s.base.matches(h)) return new Rules.Hit(true, "域名库");

        // 11 内置特征
        for (String hint : HOST_HINTS) {
            if (hintEndsAtLabel(h, hint)) return new Rules.Hit(true, "广告域 " + hint);
        }
        if (path == null || path.isEmpty()) return Rules.Hit.PASS;

        int q = path.indexOf('?');
        String p = (q >= 0 ? path.substring(0, q) : path).toLowerCase(Locale.US);

        for (String hint : PATH_HINTS) {
            if (p.contains(hint)) return new Rules.Hit(true, "端点 " + hint);
        }
        // Google Analytics 的上报端点是精确的 /collect（原先写 /collect?v= 再剥掉查询串，
        // 变成永远不可能命中的死规则）；按「以 /collect 结尾」判断，/collector 不会误伤。
        if (p.endsWith("/collect")) return new Rules.Hit(true, "端点 /collect");
        int i = 0;
        int n = p.length();
        while (i < n) {
            int j = p.indexOf('/', i);
            if (j < 0) j = n;
            if (j > i && AD_SEGMENTS.contains(p.substring(i, j))) {
                return new Rules.Hit(true, "路径段 /" + p.substring(i, j) + "/");
            }
            i = j + 1;
        }
        return Rules.Hit.PASS;
    }

    /**
     * 主机名特征匹配：hint 必须落在标签边界上才算命中。
     * 早期用裸 contains，commstat.com（含 mmstat.com）、notdoubleclick.net
     * （含 doubleclick.net）这类合法域名会被整串误拦。
     */
    private static boolean hintEndsAtLabel(String host, String hint) {
        int at = host.indexOf(hint);
        while (at >= 0) {
            int before = at - 1;
            int after = at + hint.length();
            boolean leftOk = before < 0 || host.charAt(before) == '.';
            boolean rightOk = after >= host.length() || host.charAt(after) == '.';
            if (leftOk && rightOk) return true;
            at = host.indexOf(hint, at + 1);
        }
        return false;
    }

    /** 命中时把 $redirect 资源名一起带出去，调用方据此决定回空资源还是 204。 */
    private static Rules.Hit hit(Sets s, boolean blocked, String reason, String rule) {
        String redirect = rule == null ? null : s.redirects.get(rule);
        return new Rules.Hit(blocked, reason, redirect);
    }

    public static int urlRuleSize() {
        return HOST_HINTS.length + PATH_HINTS.length + AD_SEGMENTS.size();
    }
}

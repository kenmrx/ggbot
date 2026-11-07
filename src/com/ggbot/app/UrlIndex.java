package com.ggbot.app;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * 订阅源带来的 URL 规则索引，两级结构：
 *
 *   域名部分是字面值的规则  →  按域名分桶，顺着主机名后缀查表，通常只比对 0 到 2 条
 *   纯路径 / 域名带通配的规则 →  交给 TokenIndex，按最稀有的 token 找候选
 *
 * 早先只有第一级，第二类规则（AdGuard 基础过滤器里 1,477 条）只能整个线性扫，
 * 每个请求多花几十微秒。补上 token 索引之后候选降到 1 到 2 条，
 * 两种规则合成一次判定，代价都跟规则总量脱钩了。
 *
 * 只有主动启用带 URL 规则的订阅源才会付出这份成本，默认的两个纯域名源是零成本。
 */
public final class UrlIndex {

    public static final UrlIndex EMPTY =
            new UrlIndex(new HashMap<String, List<String>>(0), TokenIndex.EMPTY);

    private final HashMap<String, List<String>> byHost;
    private final TokenIndex token;

    private UrlIndex(HashMap<String, List<String>> byHost, TokenIndex token) {
        this.byHost = byHost;
        this.token = token;
    }

    public static UrlIndex of(Collection<String> rules) {
        if (rules == null || rules.isEmpty()) return EMPTY;
        HashMap<String, List<String>> map = new HashMap<>(rules.size() * 2);
        ArrayList<String> rest = new ArrayList<>();
        for (String r : rules) {
            if (r == null || r.isEmpty()) continue;
            int slash = r.indexOf('/');
            String host = slash < 0 ? r : r.substring(0, slash);
            if (host.isEmpty() || host.indexOf('*') >= 0 || host.indexOf('?') >= 0) {
                rest.add(r);
                continue;
            }
            List<String> bucket = map.get(host);
            if (bucket == null) {
                bucket = new ArrayList<>(2);
                map.put(host, bucket);
            }
            bucket.add(r);
        }
        return new UrlIndex(map, TokenIndex.of(rest));
    }

    public int size() {
        return token.size() + countBucketed();
    }

    private int countBucketed() {
        int n = 0;
        for (List<String> v : byHost.values()) n += v.size();
        return n;
    }

    public boolean isEmpty() {
        return byHost.isEmpty() && token.isEmpty();
    }

    /** 返回命中的规则文本，没有命中返回 null。 */
    public String match(String host, String path) {
        if (host == null || host.isEmpty()) return null;
        String h = host.toLowerCase(Locale.US);

        // 顺着后缀查：a.b.ads.com → b.ads.com → ads.com
        String key = h;
        while (true) {
            List<String> bucket = byHost.get(key);
            if (bucket != null) {
                for (int i = 0; i < bucket.size(); i++) {
                    String r = bucket.get(i);
                    if (UrlRule.matches(r, h, path)) return r;
                }
            }
            int dot = key.indexOf('.');
            if (dot < 0 || dot == key.length() - 1) break;
            key = key.substring(dot + 1);
        }

        return token.match(h, path);
    }
}

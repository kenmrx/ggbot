package com.ggbot.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * URL 规则的 token 索引，思路来自 uBlock Origin 的静态过滤引擎。
 *
 * 前提很简单：一条规则要能匹配某个 URL，它里面那段「长度 ≥ 3 的连续字母数字」
 * 必然原样出现在 URL 里——因为 * 和 ? 都不是字母数字，切不断它。
 *
 * 于是建索引时给每条规则挑一个最稀有的 token 挂上去，匹配时把 URL 切出来的 token
 * 逐个查表，只会碰到极少数几条候选，而不用把上千条规则挨个跑一遍通配匹配。
 * uBO 把这一步叫 token 提取，并且明确说：规则能不能被分词，比规则写得多不多更重要。
 *
 * 本项目的实测：AdGuard 基础过滤器里有 1,477 条规则没法按域名分组（纯路径规则 +
 * 域名带通配），只能线性扫；加了 token 索引之后，单个请求的候选规则降到 1 到 2 条。
 */
public final class TokenIndex {

    public static final TokenIndex EMPTY =
            new TokenIndex(new String[0], new HashMap<String, int[]>(0), new int[0]);

    /** 太短的 token 区分度不够，反而会把一堆规则塞进同一个桶。uBO 也用 3。 */
    private static final int MIN_TOKEN = 3;
    private final String[] rules;
    private final HashMap<String, int[]> byToken;
    /** 一个 token 都切不出来的规则（例如全是短片段），只能顺序扫，通常只剩个位数。 */
    private final int[] loose;

    private final ThreadLocal<Scratch> local;

    private TokenIndex(String[] rules, HashMap<String, int[]> byToken, int[] loose) {
        this.rules = rules;
        this.byToken = byToken;
        this.loose = loose;
        this.local = new ThreadLocal<Scratch>() {
            @Override
            protected Scratch initialValue() {
                return new Scratch(TokenIndex.this.rules.length);
            }
        };
    }

    /** 每个线程一份去重标记，省得每次匹配都新申请数组。 */
    static final class Scratch {
        final int[] seen;
        int gen;

        Scratch(int n) {
            seen = new int[n];
        }
    }

    public static TokenIndex of(Collection<String> src) {
        if (src == null || src.isEmpty()) return EMPTY;
        ArrayList<String> list = new ArrayList<>(src.size());
        for (String r : src) {
            if (r != null && !r.isEmpty()) list.add(r);
        }
        if (list.isEmpty()) return EMPTY;

        int n = list.size();
        String[] rules = list.toArray(new String[n]);

        // 第一遍：切 token 并统计频次，频次决定谁「稀有」
        HashMap<String, Integer> freq = new HashMap<>(n * 4);
        String[][] toks = new String[n][];
        for (int i = 0; i < n; i++) {
            toks[i] = tokens(rules[i]);
            for (String t : toks[i]) {
                Integer c = freq.get(t);
                freq.put(t, c == null ? 1 : c + 1);
            }
        }

        // 第二遍：每条规则挂到它最稀有的那个 token 下；一样稀有就挂长的。
        // 这里是恶意源的复杂度闸：MAX_RULES 只限总量，不限「每个请求要扫多少候选」。
        // 40 万条全切不出 token 的规则会全量线性扫（每次 match 几毫秒，Mitm 全量流量被拖死）；
        // 40 万条共用一个高频 token 的规则会把单个桶撑到 20 万。两种都按无区分度丢弃——
        // 代价是该源在这些形态上的拦截失效（不可信源本来就不该全信），换回稳定的判定延迟。
        int looseCap = Math.max(4096, n / 64);
        int maxShare = Math.max(64, n / 16);
        HashMap<String, List<Integer>> buckets = new HashMap<>(n * 2);
        ArrayList<Integer> looseIds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String best = null;
            int bestCount = Integer.MAX_VALUE;
            for (String t : toks[i]) {
                int c = freq.get(t);
                if (c < bestCount
                        || (c == bestCount && best != null && t.length() > best.length())) {
                    best = t;
                    bestCount = c;
                }
            }
            if (best == null) {
                if (looseIds.size() < looseCap) looseIds.add(i);
                continue;
            }
            if (bestCount > maxShare) continue;
            List<Integer> one = buckets.get(best);
            if (one == null) {
                one = new ArrayList<>(2);
                buckets.put(best, one);
            }
            one.add(i);
        }

        HashMap<String, int[]> byToken = new HashMap<>(buckets.size() * 2);
        for (Map.Entry<String, List<Integer>> e : buckets.entrySet()) {
            List<Integer> v = e.getValue();
            int[] ids = new int[v.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = v.get(i);
            byToken.put(e.getKey(), ids);
        }
        int[] loose = new int[looseIds.size()];
        for (int i = 0; i < loose.length; i++) loose[i] = looseIds.get(i);

        return new TokenIndex(rules, byToken, loose);
    }

    public int size() {
        return rules.length;
    }

    /** 进 loose 顺序扫的规则数；测试观察上限闸用（真实源千级，恶意源被闸到上限内）。 */
    public int looseSize() {
        return loose.length;
    }

    public boolean isEmpty() {
        return rules.length == 0;
    }

    /** 返回命中的规则原文，没有命中返回 null。 */
    public String match(String host, String path) {
        if (rules.length == 0) return null;
        Scratch sc = local.get();
        int g = ++sc.gen;
        if (g < 0) {
            Arrays.fill(sc.seen, 0);
            sc.gen = 1;
            g = 1;
        }

        String r = probe(host, host, path, sc, g);
        if (r != null) return r;
        r = probe(path, host, path, sc, g);
        if (r != null) return r;

        for (int k = 0; k < loose.length; k++) {
            int id = loose[k];
            if (sc.seen[id] == g) continue;
            sc.seen[id] = g;
            if (UrlRule.matches(rules[id], host, path)) return rules[id];
        }
        return null;
    }

    private String probe(String s, String host, String path, Scratch sc, int g) {
        if (s == null || s.isEmpty()) return null;
        int n = s.length();
        int i = 0;
        while (i < n) {
            while (i < n && !isAlnum(s.charAt(i))) i++;
            int j = i;
            while (j < n && isAlnum(s.charAt(j))) j++;
            if (j - i >= MIN_TOKEN) {
                String t = s.substring(i, j).toLowerCase(Locale.US);
                int[] ids = byToken.get(t);
                if (ids != null) {
                    for (int k = 0; k < ids.length; k++) {
                        int id = ids[k];
                        if (sc.seen[id] == g) continue;
                        sc.seen[id] = g;
                        if (UrlRule.matches(rules[id], host, path)) return rules[id];
                    }
                }
            }
            i = j;
        }
        return null;
    }

    /** 建索引时用，允许分配。 */
    static String[] tokens(String s) {
        ArrayList<String> out = new ArrayList<>(8);
        int n = s.length();
        int i = 0;
        while (i < n) {
            while (i < n && !isAlnum(s.charAt(i))) i++;
            int j = i;
            while (j < n && isAlnum(s.charAt(j))) j++;
            if (j - i >= MIN_TOKEN) out.add(s.substring(i, j).toLowerCase(Locale.US));
            i = j;
        }
        return out.toArray(new String[0]);
    }

    static boolean isAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}

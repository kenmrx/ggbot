package com.ggbot.app;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DNS 应答缓存。
 *
 * 早期版本用固定 120 秒过期，两个后果都不小：
 *   · TTL 只有 60 秒的记录被多缓存一倍时间，换了 CDN 节点后客户端还在打旧地址
 *   · NXDOMAIN 与空应答不进缓存，垃圾域名每次都被重新问一遍上游
 *
 * 这里按 dnsmasq / Pi-hole 的做法分三层：
 *   新鲜区   应答自带的 TTL，夹在 [30s, 3600s] 之间
 *   负缓存   失败应答（NXDOMAIN、无记录、解析器拒绝）统一 30 秒
 *   兜底层   TTL 过期后再留一小时，只在上游失联时拿出来顶，且把 TTL 改写成 5 秒
 *
 * 不做 Android 依赖，桌面上可以直接验证。
 */
public final class DnsCache {

    /** 新鲜区下限。TTL 为 0 的记录不能真按 0 处理，否则缓存等于不存在。 */
    public static final long MIN_TTL_MS = 30_000L;
    /** 新鲜区上限。有些权威服务器给 24 小时，对手机来说太长了。 */
    public static final long MAX_TTL_MS = 3_600_000L;
    /** 失败应答的缓存时长。 */
    public static final long NEG_TTL_MS = 30_000L;
    /** 过期之后还能当兜底用的时长。 */
    public static final long STALE_MS = 3_600_000L;
    /** 兜底应答的 TTL，单位秒。 */
    public static final int STALE_TTL_S = 5;

    private static final int MAX = 1024;

    public static final class Entry {
        public final byte[] resp;
        public final boolean negative;
        /** 入库时刻。 */
        public final long at;
        /** 实际生效的缓存时长（已夹到上下限之间，负缓存为 30 秒）。 */
        public final long ttl;

        Entry(byte[] resp, boolean negative, long ttl, long now) {
            this.resp = resp;
            this.negative = negative;
            this.ttl = ttl;
            this.at = now;
        }

        public boolean fresh(long now) {
            return now - at < ttl;
        }

        public boolean usable(long now) {
            return negative ? fresh(now) : now - at < ttl + STALE_MS;
        }

        public long ageMs(long now) {
            return now - at;
        }
    }

    /** 取出条目本身（不过期判断），诊断与测试用。 */
    public Entry peek(String key) {
        synchronized (map) {
            return map.get(key);
        }
    }

    private final LinkedHashMap<String, Entry> map =
            new LinkedHashMap<>(256, 0.75f, true);

    /**
     * 按应答内容入库。
     *
     * @param resp  上游原始应答
     * @param ttlS  记录 TTL（秒），≤0 表示按负缓存处理
     */
    public void put(String key, byte[] resp, int ttlS) {
        if (key == null || resp == null || resp.length > 1500) return;
        long now = System.currentTimeMillis();
        boolean negative = ttlS <= 0 || resp.length <= 12;
        long ttl;
        if (negative) {
            ttl = NEG_TTL_MS;
        } else {
            ttl = ttlS * 1000L;
            if (ttl < MIN_TTL_MS) ttl = MIN_TTL_MS;
            else if (ttl > MAX_TTL_MS) ttl = MAX_TTL_MS;
        }
        synchronized (map) {
            map.put(key, new Entry(resp.clone(), negative, ttl, now));
            while (map.size() > MAX && !map.isEmpty()) {
                String oldest = map.keySet().iterator().next();
                map.remove(oldest);
            }
        }
    }

    /** 新鲜命中；没有或已过期返回 null。 */
    public byte[] get(String key) {
        long now = System.currentTimeMillis();
        synchronized (map) {
            Entry e = map.get(key);
            if (e == null) return null;
            if (!e.fresh(now)) return null;
            return e.resp.clone();
        }
    }

    /**
     * 兜底命中：只要还在 STALE 窗口内就返回，包含已过期但尚未丢弃的。
     * 调用方负责把 TTL 改写成 {@link #STALE_TTL_S}。
     */
    public byte[] stale(String key) {
        long now = System.currentTimeMillis();
        synchronized (map) {
            Entry e = map.get(key);
            if (e == null || e.negative) return null;
            if (!e.usable(now)) {
                map.remove(key);
                return null;
            }
            return e.resp.clone();
        }
    }

    public boolean has(String key) {
        synchronized (map) {
            return map.containsKey(key);
        }
    }

    public int size() {
        synchronized (map) {
            return map.size();
        }
    }

    /** 负缓存条数，用于诊断。 */
    public int negativeSize() {
        synchronized (map) {
            int n = 0;
            for (Map.Entry<String, Entry> e : map.entrySet()) {
                if (e.getValue().negative) n++;
            }
            return n;
        }
    }

    public void clear() {
        synchronized (map) {
            map.clear();
        }
    }
}

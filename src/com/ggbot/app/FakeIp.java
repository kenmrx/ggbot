package com.ggbot.app;

import java.util.HashMap;
import java.util.Map;

/**
 * 虚拟 IP 池。
 *
 * DNS 对命中解密规则的域名不再返回真实地址，而是返回 198.18.1.1-198.18.255.254 之间
 * 的一条地址。这样只有这些域名的后续 TCP 连接会进隧道，其余流量完全不受影响；
 * 隧道里收到发往该虚拟地址的连接时，反查就能知道它原本要访问哪个域名。
 *
 * 用 198.18.0.0/15 而不是 10.x：这是 RFC 2544 保留的测试网段，公网不可达，
 * 家用路由器也不会分配，不会和用户所在的内网撞车。
 */
public final class FakeIp {

    /** 池覆盖 198.18.1.0 - 198.18.255.255；198.18.0.x 留给隧道自身和 DNS。 */
    private static final int SPAN = 255 * 256;

    private static final Map<String, String> hostToIp = new HashMap<>();
    private static final Map<String, String> ipToHost = new HashMap<>();
    private static int cursor;

    private FakeIp() {
    }

    public static synchronized String ipFor(String host) {
        if (host == null || host.isEmpty()) return null;
        String known = hostToIp.get(host);
        if (known != null) return known;

        for (int n = 0; n < 4096; n++) {
            int idx = (cursor + n) % SPAN;
            String ip = text(idx);
            if (!ipToHost.containsKey(ip)) {
                hostToIp.put(host, ip);
                ipToHost.put(ip, host);
                cursor = (idx + 1) % SPAN;
                return ip;
            }
        }

        // 池满：整体重置，重新分配（旧映射失效，但已有连接不受影响）
        hostToIp.clear();
        ipToHost.clear();
        cursor = 0;
        return ipFor(host);
    }

    public static synchronized String hostFor(String ip) {
        if (ip == null) return null;
        return ipToHost.get(ip);
    }

    public static synchronized int size() {
        return hostToIp.size();
    }

    public static synchronized void clear() {
        hostToIp.clear();
        ipToHost.clear();
        cursor = 0;
    }

    /** 判断某个 IPv4 地址是否落在虚拟池范围内，用于快速分流。 */
    public static boolean inPool(byte[] ip) {
        if (ip == null || ip.length != 4) return false;
        return (ip[0] & 0xff) == 198 && (ip[1] & 0xff) == 18 && (ip[2] & 0xff) >= 1;
    }

    public static String text(byte[] ip) {
        if (ip == null || ip.length != 4) return "";
        return (ip[0] & 0xff) + "." + (ip[1] & 0xff) + "." + (ip[2] & 0xff) + "." + (ip[3] & 0xff);
    }

    private static String text(int idx) {
        return "198.18." + (1 + (idx >> 8)) + "." + (idx & 0xff);
    }
}

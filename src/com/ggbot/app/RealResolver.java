package com.ggbot.app;

import android.net.VpnService;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * 解析域名对应的真实地址。
 *
 * 不能调用 InetAddress.getByName：那会走系统解析器，查询又被隧道的 DNS 处理器接管，
 * 命中解密规则时返回的还是虚拟 IP，代理就会连回自己，形成环路。
 * 这里直接向上游发 UDP 查询，走 protect 过的 socket，绕开整条系统解析链路。
 */
public final class RealResolver {

    private static final long TTL = 60000L;
    /** 缓存上限：过期条目不能只增不减，到顶就整体清掉重来（DNS 结果本来就短命）。 */
    private static final int MAX = 4096;
    private static final Map<String, Object[]> cache = new HashMap<>();

    private RealResolver() {
    }

    public static String resolve(String host, VpnService vpn) {
        synchronized (cache) {
            Object[] v = cache.get(host);
            if (v != null && System.currentTimeMillis() - ((Long) v[1]) < TTL) {
                return (String) v[0];
            }
            if (v != null) cache.remove(host);   // 过期条目顺手清掉，不让 map 只增不减
        }
        String ip = query(host, vpn);
        if (ip != null) remember(host, ip);
        return ip;
    }

    public static void remember(String host, String ip) {
        if (host == null || ip == null) return;
        synchronized (cache) {
            if (cache.size() >= MAX) cache.clear();
            cache.put(host, new Object[]{ip, System.currentTimeMillis()});
        }
    }

    public static void clear() {
        synchronized (cache) {
            cache.clear();
        }
    }

    private static String query(String host, VpnService vpn) {
        // DoH 开着时加密优先：解密域名的解析结果决定代理连到哪台真机，
        // 污染网络里明文 UDP 的答案不可信
        if (Config.doh()) {
            byte[] r = DoH.query(DnsPacket.buildQuery(host), Config.dohUrl());
            if (DnsPacket.validUpstreamResponse(DnsPacket.buildQuery(host), r)) {
                byte[] ip = DnsPacket.firstA(r, 0, r.length);
                if (ip != null) return FakeIp.text(ip);
            }
        }
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            if (!vpn.protect(s)) {
                Diag.push("上游解析失败: protect 被拒 " + host, "warn");
                return null;
            }
            s.setSoTimeout(4000);
            byte[] q = DnsPacket.buildQuery(host);
            InetAddress up = InetAddress.getByName(Config.upstream());
            s.send(new DatagramPacket(q, q.length, up, 53));
            byte[] buf = new byte[1500];
            DatagramPacket rp = new DatagramPacket(buf, buf.length);
            s.receive(rp);
            byte[] r = java.util.Arrays.copyOfRange(buf, 0, rp.getLength());
            // buildQuery 的 txid 是常量、查询时机可预测，明文 UDP 路径上
            // 伪造应答门槛很低；这个解析结果决定解密代理连到哪，必须先验 ID/QR/长度
            if (!DnsPacket.validUpstreamResponse(q, r)) {
                Diag.push("上游解析失败: 应答校验不过 " + host, "warn");
                return null;
            }
            byte[] ip = DnsPacket.firstA(r, 0, r.length);
            return ip == null ? null : FakeIp.text(ip);
        } catch (Exception e) {
            Diag.push("上游解析失败: " + e.getClass().getSimpleName() + " " + host, "warn");
            return null;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}

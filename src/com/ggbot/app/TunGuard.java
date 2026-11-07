package com.ggbot.app;

import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * tun 网卡自愈守卫的判定逻辑。
 *
 * 做法来自 AdAway 的 VpnConnectionMonitor：后台每 10 秒看一眼 tun 网卡
 * 还在不在，掉了（系统回收、异常路径没收尾）就重新拉起引擎，
 * 不等用户发现「怎么又没拦了」。
 *
 * 判定逻辑保持纯 Java，桌面门闸可以直接断言；
 * 真正的网卡枚举（anyTunUp）只在设备上调用。
 */
public final class TunGuard {

    /** 轮询间隔。 */
    public static final long CHECK_MS = 10_000L;
    /** 两次自愈重启的最小间隔：防着 establish 反复失败时 10 秒一次的死循环。 */
    public static final long MIN_RESTART_GAP_MS = 10_000L;

    private TunGuard() {
    }

    /** 是否该自愈：引擎在跑、没有收到停止请求、tun 网卡却不在了。 */
    public static boolean shouldRestart(boolean running, boolean stopRequested, boolean tunUp) {
        return running && !stopRequested && !tunUp;
    }

    /** 距上次重启是否已隔够最小间隔。 */
    public static boolean gapOk(long now, long lastRestartAt) {
        return now - lastRestartAt >= MIN_RESTART_GAP_MS;
    }

    /** tun\d+ 才算本服务建的网卡：wlan0、rmnet_usd0 这些不是。 */
    public static boolean isTunName(String name) {
        if (name == null || name.length() < 4) return false;
        if (!name.startsWith("tun")) return false;
        for (int i = 3; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** 系统里还有没有一张 up 状态的 tun 网卡。设备上用；桌面测试不碰它。 */
    public static boolean anyTunUp() {
        try {
            Enumeration<NetworkInterface> ns = NetworkInterface.getNetworkInterfaces();
            while (ns != null && ns.hasMoreElements()) {
                NetworkInterface n = ns.nextElement();
                if (isTunName(n.getName())) {
                    try {
                        if (n.isUp()) return true;
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}

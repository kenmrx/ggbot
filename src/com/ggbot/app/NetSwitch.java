package com.ggbot.app;

/**
 * 网络切换去抖。
 *
 * 参考 AdAway VpnService.registerNetworkCallback() 与 RethinkDNS 的 ConnectionMonitor：
 * 都注册 ConnectivityManager 回调，在网络变化时做处理。
 * 但切换瞬间系统会连发 onLost/onAvailable，逐条处理会让缓存被清好几次，
 * 所以这里只做一件事：限制最小处理间隔。
 */
public final class NetSwitch {

    /** 最小处理间隔。 */
    public static final long DEBOUNCE_MS = 1500L;

    private NetSwitch() {
    }

    public static boolean shouldAct(long now, long lastAt) {
        if (lastAt <= 0) return true;
        return now - lastAt >= DEBOUNCE_MS;
    }
}

package com.ggbot.app;

import android.content.Context;

/**
 * 省电模式的三个档位。
 *
 * 背景：用户问“关掉软件能不能还继续跳过/拦截”。真实约束是——
 *  · 无障碍服务由系统绑定，关掉界面、划掉最近任务都不会停它；只有“强行停止”或厂商冻结才会断，
 *    且断了之后 App 自己起不回来（要用户手动重开，或靠下拉快捷面板把进程拉起来）。
 *  · VPN 拦截必须靠常驻的 VpnService 前台服务：进程一死，隧道 fd 关掉，VPN 必然断开，
 *    没有“关掉软件还继续拦”这种可能。要那样只能开系统自带的“始终开启的 VPN”，由系统拉我们起来。
 * 所以能省的只有“跑多少活”，不是“能不能不跑”：
 *  · 标准：DNS 拦截 + 命中解密规则的域名走 TCP 转发与 MITM 解密。
 *  · 轻量：只拦 DNS（域名级），不建 TCP 转发、不解密；HTTPS 正文里/证书后的广告拦不到，也看不到明文。
 *  · 只跳过：彻底不开 VPN，只留无障碍自动点“跳过”；DNS 层广告完全不拦。
 */
public final class Power {

    public static final String NORMAL = "normal";
    public static final String LIGHT = "light";
    public static final String SKIP = "skip";

    public static final String[] MODES = {NORMAL, LIGHT, SKIP};

    private Power() {
    }

    public static String title(String mode) {
        if (LIGHT.equals(mode)) return "轻量：只拦 DNS";
        if (SKIP.equals(mode)) return "只跳过：不开 VPN";
        return "标准：拦截 + 解密";
    }

    /** 一行能力边界，直接放在选项下面，避免“以为轻量还能解 HTTPS”。 */
    public static String detail(String mode) {
        if (LIGHT.equals(mode)) {
            return "域名级拦截照旧，不做 TCP 转发与解密：HTTPS 里的广告拦不到，也看不到明文";
        }
        if (SKIP.equals(mode)) {
            return "不开 VPN，DNS 层广告完全不拦；只保留开屏自动跳过，最省电";
        }
        return "DNS 拦截 + 命中域名的 HTTPS 解密；最费电，但能看到明文、能拦正文广告";
    }

    /** 是否允许 MITM 解密（只有标准档）。 */
    public static boolean allowsMitm(String mode) {
        return NORMAL.equals(mode);
    }

    /** 是否需要 TCP 转发引擎（只有标准档会用到虚拟池里的解密连接）。 */
    public static boolean allowsTcp(String mode) {
        return NORMAL.equals(mode);
    }

    /** 这一档要不要开 VPN。 */
    public static boolean needsVpn(String mode) {
        return !SKIP.equals(mode);
    }

    public static String current(Context c) {
        String m = Config.powerMode(c);
        return m == null || (!NORMAL.equals(m) && !LIGHT.equals(m) && !SKIP.equals(m)) ? NORMAL : m;
    }
}

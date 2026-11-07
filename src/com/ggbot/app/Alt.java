package com.ggbot.app;

import android.content.Context;
import android.content.pm.PackageManager;

/**
 * 「不用无障碍行不行」这件事的结论，以及为什么不把 Shizuku 接进来。
 *
 * 结论（评估过、也在模拟器上验过）：
 *  1. Android 不允许普通应用自己打开无障碍开关，这一点对 Shizuku 也一样——
 *     Shizuku 给的是 shell(adb) 级别的权限，等价于我们已经写明的
 *     「adb shell pm grant ... WRITE_SECURE_SETTINGS」，不是新能力。
 *  2. 本项目是纯 javac + android.jar 构建，没有 Gradle 依赖链；接 Shizuku 要引
 *     moe.shizuku:api 的 AAR 与 provider 声明，等于为了一个「和 adb 授权等价」的
 *     能力去加一整套第三方运行时——收益为负，所以不接，只做检测与说明。
 *  3. 真要让开关稳定，靠的是厂商保活（见 Vendor），不是换授权渠道。
 *
 * 另外要讲清楚「不开无障碍会怎样」：跳过模块完全不动，DNS 拦截一点不受影响——
 * 这两条链路在代码里本来就是分开的（DnsVpnService 不引用 A11y，SkipService 只管点按）。
 */
public final class Alt {

    /** Shizuku 的包名，只用于检测是否安装，不调用它的任何接口。 */
    public static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";

    private Alt() {
    }

    /** 装了没有；查不到一律当没装（省得给用户指错路）。 */
    public static boolean shizukuInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(SHIZUKU_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 给用户看的一段话；纯字符串拼装，桌面测试能验。 */
    public static String text(boolean shizukuInstalled, boolean canWrite) {
        StringBuilder sb = new StringBuilder();
        sb.append("关于「换个办法开无障碍」：\n");
        sb.append("· Shizuku");
        sb.append(shizukuInstalled ? "（本机已安装）" : "");
        sb.append("：它给的是 shell 权限，和 ");
        sb.append(canWrite ? "本机已有的 adb 授权" : "adb shell pm grant 那条命令");
        sb.append(" 是同一件事，装上它也不会让开关更好地保持——所以 GGBot 不接它的接口，");
        sb.append("只做检测，不额外引第三方运行时。\n");
        sb.append("· 按需临时启用：无障碍开关只能由你在系统设置里拨，应用无法代劳；");
        sb.append("GGBot 的跳过只在开屏出现时执行，不开无障碍时这段逻辑完全不启动。\n");
        sb.append("· 影响面：DNS 拦截不依赖无障碍，开关没开时防广告照常生效。");
        return sb.toString();
    }
}

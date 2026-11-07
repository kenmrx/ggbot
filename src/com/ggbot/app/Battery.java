package com.ggbot.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * 电池优化白名单状态。
 *
 * 做法参考 NetGuard 的 Util.isIgnoringBatteryOptimizations：只判断、只引导，
 * 不替用户改系统设置，也不承诺「加入白名单就不会被杀」——厂商后台策略是另一层。
 */
public final class Battery {

    private Battery() {
    }

    /** 是否已被系统排除在电池优化之外。 */
    public static boolean ignoring(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    /** 状态文案，设置页与诊断导出共用。 */
    public static String label(boolean ignoring) {
        return ignoring ? "已加入白名单" : "仍受电池优化限制";
    }

    /** 系统弹窗请求白名单；需要 manifest 里的 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS。 */
    public static Intent requestIntent(Context c) {
        Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        i.setData(Uri.parse("package:" + c.getPackageName()));
        return i;
    }

    /** 电池优化列表页：比逐应用弹窗更好找，用户能在列表里一眼看到自己那条。 */
    public static Intent settingsIntent() {
        return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
    }

    /** 厂商把上面那个入口吃掉时的兜底：应用详情页。 */
    public static Intent appDetails(Context c) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + c.getPackageName()));
    }
}

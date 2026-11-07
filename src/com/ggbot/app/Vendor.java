package com.ggbot.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 国产 ROM 的「别把我的服务关掉」路线。
 *
 * 背景：荣耀/华为上无障碍服务会被系统反复摘掉，根源不是 Android 标准电池优化，
 * 而是各家自建的「应用启动管理 / 受保护应用 / 耗电管理」三套策略。
 * 它们的页面名不在 AOSP 里，只能按厂商包名去试；试不到就退到应用详情页，
 * 让用户自己往下找——所以这里既给路径文案，也给可点的入口列表。
 *
 * 纯判断（brandOf/steps）单独拿出来，桌面测试能验；Intent 部分只能在真机/模拟器上跑。
 */
public final class Vendor {

    public static final String HONOR = "honor";
    public static final String HUAWEI = "huawei";
    public static final String XIAOMI = "xiaomi";
    public static final String OPPO = "oppo";
    public static final String VIVO = "vivo";
    public static final String OTHER = "other";

    private Vendor() {
    }

    /** 按 manufacturer + brand 判厂商；宁可判成 other，也不硬猜。 */
    public static String brandOf(String manufacturer, String brand) {
        String m = (manufacturer == null ? "" : manufacturer) + " "
                + (brand == null ? "" : brand);
        m = m.toLowerCase(Locale.US);
        if (m.contains("honor")) return HONOR;
        if (m.contains("huawei")) return HUAWEI;
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) return XIAOMI;
        if (m.contains("oppo") || m.contains("realme") || m.contains("oneplus")) return OPPO;
        if (m.contains("vivo") || m.contains("iqoo")) return VIVO;
        return OTHER;
    }

    public static String brand() {
        return brandOf(android.os.Build.MANUFACTURER, android.os.Build.BRAND);
    }

    public static String name(String brand) {
        if (HONOR.equals(brand)) return "荣耀";
        if (HUAWEI.equals(brand)) return "华为";
        if (XIAOMI.equals(brand)) return "小米/红米";
        if (OPPO.equals(brand)) return "OPPO/一加/realme";
        if (VIVO.equals(brand)) return "vivo/iQOO";
        return "这台手机";
    }

    /** 这家 ROM 上把 GGBot 保住的步骤；honor/huawei 给四步，其他给通用三步。 */
    public static String[] steps(String brand) {
        if (HONOR.equals(brand) || HUAWEI.equals(brand)) {
            return new String[]{
                    "① 应用启动管理：设置 → 应用和服务 → 应用启动管理 → 找 GGBot → 关掉「自动管理」，"
                            + "把「自启动」「关联启动」「后台活动」都勾上",
                    "② 受保护应用：设置 → 电池 → 更多电池设置 → 受保护应用 → 把 GGBot 打开"
                            + "（这一项最容易被漏，荣耀关无障碍多半是它没开）",
                    "③ 电池优化：设置 → 电池 → 应用耗电管理 → GGBot → 选「不限制」",
                    "④ 最近任务加锁：多任务里下拉 GGBot 卡片加锁，别让一键清理带走它"
            };
        }
        if (XIAOMI.equals(brand)) {
            return new String[]{
                    "① 自启动：设置 → 应用设置 → 应用管理 → GGBot → 自启动 打开",
                    "② 省电策略：同上页 → 省电策略 → 选「无限制」",
                    "③ 最近任务加锁：多任务里长按 GGBot 卡片加锁"
            };
        }
        if (OPPO.equals(brand) || VIVO.equals(brand)) {
            return new String[]{
                    "① 自启动/后台运行：设置 → 应用管理 → GGBot → 允许自启动、允许后台运行",
                    "② 耗电管理：设置 → 电池 → 应用耗电管理 → GGBot → 允许后台高耗电",
                    "③ 最近任务加锁：多任务里锁定 GGBot"
            };
        }
        return new String[]{
                "① 应用详情 → 电池 → 选「不限制」/「允许后台活动」",
                "② 若有「自启动管理」，把 GGBot 打开",
                "③ 最近任务里给 GGBot 加锁，避免被一键清理"
        };
    }

    /**
     * 可直接尝试的入口，按「最贴近问题」到「最通用」排序：
     * 华为/荣耀先试启动管理与受保护应用，试不到再退系统设置页。
     */
    public static List<Intent> entries(Context c) {
        List<Intent> list = new ArrayList<>();
        String b = brand();
        if (HONOR.equals(b) || HUAWEI.equals(b)) {
            list.add(component("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"));
            list.add(component("com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"));
            list.add(component("com.huawei.systemmanager",
                    "com.huawei.systemmanager.mainscreen.MainScreenActivity"));
            list.add(component("com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"));
        }
        Intent detail = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        detail.setData(Uri.parse("package:" + c.getPackageName()));
        list.add(detail);
        list.add(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        list.add(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
        return list;
    }

    private static Intent component(String pkg, String cls) {
        Intent i = new Intent();
        i.setComponent(new ComponentName(pkg, cls));
        return i;
    }
}

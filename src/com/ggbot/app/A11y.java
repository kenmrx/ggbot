package com.ggbot.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

/**
 * 无障碍绑定保护。
 *
 * 真机实测（荣耀 BRT-AN00 / MagicOS 11 / Android 17）：VPN 前台服务一旦断档，
 * 系统的无障碍管理会把 GGBot 的 SkipService 从 enabled_accessibility_services 里摘掉，
 * 而写安全设置需要 WRITE_SECURE_SETTINGS——普通应用拿不到，只能靠 adb 一次性授权。
 *
 * 所以这里分三层：
 * 1. 根上的修法在 DnsVpnService：自愈改成原地重建隧道，前台服务不再断档；
 * 2. 应用内引导：直接把用户送到「本服务的无障碍详情页」，只需拨一下开关，
 *    不用在系统设置里翻三层菜单；被摘掉时再发一条通知，点它回到同一个页面；
 * 3. 万一系统还是把绑定摘了、而本 App 又恰好被授权了 WRITE_SECURE_SETTINGS，
 *    就把重建前记下的值写回去。
 *
 * 说明一句实话：Android 不允许应用自己打开无障碍（这是系统的安全边界，
 * 连系统签名应用都不行），所以「完全不需要用户动手」做不到；能做的是
 * 把「怎么开」压缩成一次点击，并且开过之后尽量不让它掉。
 */
public final class A11y {

    /** GGBot 无障碍服务在系统设置里的平铺写法。 */
    public static final String SVC = "com.ggbot.app/.SkipService";
    /** 另一种常见的完整类名写法，比较时两种都认。 */
    private static final String SVC_FULL = "com.ggbot.app/com.ggbot.app.SkipService";
    /** 一次性授权命令，设置页直接展示给用户。 */
    public static final String GRANT_CMD =
            "adb shell pm grant com.ggbot.app android.permission.WRITE_SECURE_SETTINGS";

    private A11y() {
    }

    /** 从没开过。 */
    public static final int ST_NEVER = 0;
    /** 正开着。 */
    public static final int ST_ON = 1;
    /** 曾经开过，现在被系统摘掉了——这是荣耀/华为上最常见的一种。 */
    public static final int ST_REMOVED = 2;
    /**
     * 名单里还写着、服务也连着，但事件流断了 ——「僵尸绑定」。
     *
     * 为什么单独列一态：这种情况下名单监听不触发、selfHeal 认为正常，
     * 三态模型会把界面显示成「已启用」，用户看到跳过不动却查不出原因。
     * 必须让用户看见「开着但没响应」，否则他只会反复去拨开关。
     */
    public static final int ST_STALLED = 3;

    /**
     * 三态的纯判断，好让桌面测试能直接验（不碰 Android 对象）。
     * 为什么要有 REMOVED：用户看到的「没开」其实分两种，一种是没去开，
     * 一种是开着被系统关了。后者要说清楚，否则用户会以为开关自己坏了。
     */
    public static int stateOf(boolean enabledNow, boolean seenBefore) {
        if (enabledNow) return ST_ON;
        return seenBefore ? ST_REMOVED : ST_NEVER;
    }

    /**
     * 四态判断：在「开着」的基础上再区分「真在跑」与「僵尸」。
     *
     * stale 由 {@link #looksStale} 判出，调用方负责传。没在名单里时 stale 无意义，
     * 一律按 REMOVED/NEVER 走——僵尸是「名单还在」才有的概念。
     */
    public static int stateOf(boolean enabledNow, boolean seenBefore, boolean stale) {
        if (enabledNow) return stale ? ST_STALLED : ST_ON;
        return seenBefore ? ST_REMOVED : ST_NEVER;
    }

    public static int state(Context c) {
        boolean present = contains(currentList(c));
        // 读状态时也顺手记一次跳变：手动在系统里关掉、守卫还没来得及跑，
        // 界面上就能马上看到「被摘了几次」，而不是等下一轮巡检。
        noteTransition(c, present);
        boolean seen = prefs(c).getBoolean(KEY_SEEN, false);
        if (!present) return stateOf(false, seen);
        // 名单里在：再看它到底还在不在跑。息屏时的安静不算，交给 looksStale 判。
        boolean stale = looksStale(SkipService.isConnected(), true,
                screenOn(c), SkipService.isAlive(STALE_EVENT_MS));
        return stateOf(true, seen, stale);
    }

    /** 累计被摘掉几次（只在「从开到没开」的那一次加一）。 */
    public static int removals(Context c) {
        return prefs(c).getInt(KEY_REMOVALS, 0);
    }

    /** 状态文案：四种情况各自说清，摘掉过两次以上直接点明「系统在反复关它」。 */
    public static String stateTextOf(int state, int removals, boolean canWrite) {
        if (state == ST_STALLED) {
            return canWrite ? "已开启但无响应 · 正在尝试重绑" : "已开启但无响应 · 点此重开";
        }
        if (state == ST_ON) return canWrite ? "已启用 · 可自动补回" : "已启用";
        if (state == ST_REMOVED) {
            return removals >= 2
                    ? "被系统反复关闭（已 " + removals + " 次）· 点此重开"
                    : "已被系统摘掉 · 点此重开";
        }
        return "未开启 · 点此去开";
    }

    public static String stateText(Context c) {
        return stateTextOf(state(c), removals(c), canWrite(c));
    }

    /**
     * 记录「开/关」的跳变。守卫每轮都会调，但只有真的从开到关才算一次摘除，
     * 免得把一次故障记成几百次。
     */
    private static void noteTransition(Context c, boolean present) {
        android.content.SharedPreferences p = prefs(c);
        boolean was = p.getBoolean(KEY_ON_LAST, false);
        if (present == was) return;
        boolean seen = p.getBoolean(KEY_SEEN, false);
        android.content.SharedPreferences.Editor e = p.edit().putBoolean(KEY_ON_LAST, present);
        // 从没开过的「没开」不算被摘，只有开过之后消失了才计数
        if (!present && seen) {
            e.putInt(KEY_REMOVALS, p.getInt(KEY_REMOVALS, 0) + 1);
        }
        e.apply();
        if (!present && seen) Diag.push("无障碍被摘掉（累计 " + removals(c) + " 次）", "warn");
    }

    /** 现在到底开着没有（设置页状态行、诊断导出都用它）。 */
    public static boolean enabledNow(Context c) {
        return contains(currentList(c));
    }

    /**
     * 直接跳到本服务的无障碍详情页：用户只需拨一下开关。
     * Android 8 起有详情页；更老的版本只能落到无障碍总列表。
     */
    public static Intent detailsIntent(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            Intent i = new Intent(ACTION_A11Y_DETAILS);
            ComponentName cn = new ComponentName(c, SkipService.class);
            // 两种写法都塞：系统设置有的版本认 Parcelable，有的只读字符串
            i.putExtra(Intent.EXTRA_COMPONENT_NAME, cn);
            i.putExtra("component_name", cn.flattenToString());
            return i;
        }
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
    }

    /**
     * 被系统摘掉又没写权限时，发一条通知把用户领回去。
     * 十分钟最多一条，避免守卫每 10 秒巡检刷屏。
     */
    public static void notifyMissing(Context c) {
        long now = System.currentTimeMillis();
        if (now - lastNoteAt < 10 * 60_000L) return;
        lastNoteAt = now;
        try {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH_A11Y) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CH_A11Y, "无障碍状态", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            PendingIntent pi = PendingIntent.getActivity(c, 7, detailsIntent(c),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder bd = DnsVpnService.notificationBuilder(c, CH_A11Y);
            nm.notify(NOTE_ID, bd.setContentTitle("开屏跳过已停止工作")
                    .setContentText("系统的无障碍开关被关掉了，点这里拨回来")
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
        } catch (Exception ignored) {
        }
    }

    /** 冒号分隔的无障碍服务列表里是否包含 GGBot 的 SkipService。 */
    public static boolean contains(String list) {
        if (list == null || list.trim().isEmpty()) return false;
        for (String one : list.split(":")) {
            String t = one.trim();
            if (t.equals(SVC) || t.equals(SVC_FULL)) return true;
        }
        return false;
    }

    /** 把 SkipService 并进列表（已存在则原样返回）。 */
    public static String merge(String list) {
        if (contains(list)) return list;
        if (list == null || list.trim().isEmpty()) return SVC;
        return list + ":" + SVC;
    }

    /** 读当前设置里的无障碍服务列表，读不到返回 null。 */
    public static String currentList(Context c) {
        try {
            return Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        } catch (Exception e) {
            return null;
        }
    }

    /** 记录「开关状态|服务列表」，仅在列表里确实有 SkipService 时返回非 null。 */
    public static String snapshot(Context c) {
        try {
            String list = currentList(c);
            if (!contains(list)) return null;
            int en = Settings.Secure.getInt(c.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            return en + "|" + list;
        } catch (Exception e) {
            return null;
        }
    }

    /** 是否持有写安全设置的权限（只能 adb 授权，装包时不会自动有）。 */
    public static boolean canWrite(Context c) {
        try {
            return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /** 纯判定：以前确实开过、现在不在列表里、又有写权限，才值得写回。 */
    public static boolean shouldRestore(boolean seenBefore, boolean presentNow, boolean canWrite) {
        return seenBefore && !presentNow && canWrite;
    }

    /**
     * 纯判定：绑定「还在名单里」但事件已经不再到达 = 僵尸绑定。
     *
     * 为什么需要这一条：{@link A11yWatch} 盯的是 Secure 名单，名单没变它就一声不吭；
     * 而荣耀/华为上真实存在另一种坏法——名单里还写着 SkipService、accessibility_enabled
     * 也是 1，服务对象却已经跟系统断了连接，只是没人去改那个键。此时名单监听、
     * 三态文案全都显示「已启用」，用户却看到跳过不动。
     *
     * 判据：服务已连接（connected）、名单里在（listed）、屏幕亮着（screenOn）、
     * 并且超过 timeoutMs 没收到事件（!alive）。四者缺一不判：
     * - 没连接 → 用户没开，另有分支
     * - 不在名单 → 被摘了，selfHeal 的分支
     * - 屏幕灭着 → 息屏时事件本来就稀少甚至断流，这是正常现象不是故障。
     *   少了这一条，用户每次息屏放口袋都会记一次「僵尸」，进而误触发一次重绑/通知——
     *   那比不判更糟，会把正常状态搅乱。
     */
    public static boolean looksStale(boolean connected, boolean listed,
                                     boolean screenOn, boolean alive) {
        return connected && listed && screenOn && !alive;
    }

    /**
     * 兜底巡检（守卫线程每轮调一次）：只要本 App 曾经被用户开过无障碍，
     * 任何时刻被系统摘掉且已授权，就写回去。关机重开、被清设置都能覆盖。
     */
    public static boolean selfHeal(Context c) {
        boolean present = contains(currentList(c));
        boolean seen = prefs(c).getBoolean(KEY_SEEN, false);
        noteTransition(c, present);
        if (present) {
            if (!seen) prefs(c).edit().putBoolean(KEY_SEEN, true).apply();
            return false;
        }
        if (!seen) return false;
        if (!canWrite(c) && !ShizukuHeal.available(c)) {
            // 两条自动写回的路都没有：留诊断痕迹并用通知把用户领回去重开
            long now = System.currentTimeMillis();
            if (now - lastWarnAt > 60_000L) {
                lastWarnAt = now;
                Diag.push("无障碍被系统摘掉，无写权限也无 Shizuku，已发通知引导重开", "warn");
            }
            notifyMissing(c);
            return false;
        }
        // 有任一条路：写回。Shizuku 在 canWrite=false 时接管（见 writeBackVia）
        return writeBackVia(c, merge(currentList(c)), 1);
    }

    /**
     * 统一写回：先用自家权限，没有再走 Shizuku。
     *
     * 分成两条路径而不是只走一条，是因为：
     * - 自家权限是同步写入，立刻生效，没有跨进程开销
     * - Shizuku 要过一次 Binder 事务，慢但仍在本进程内完成，不需要用户操作
     * 有自家权限时不绕 Shizuku，少一层不确定性。
     */
    private static boolean writeBackVia(Context c, String fullList, int enabled) {
        if (canWrite(c)) {
            try {
                Settings.Secure.putString(c.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, fullList);
                Settings.Secure.putInt(c.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, enabled);
                Diag.push("无障碍已按授权写回（本应用权限）", "warn");
                return true;
            } catch (Exception e) {
                Diag.push("写回失败，转 Shizuku: " + e, "warn");
            }
        }
        boolean ok = ShizukuHeal.writeBack(fullList, enabled == 1);
        Diag.push(ok ? "无障碍已通过 Shizuku 写回" : "无障碍写回失败（权限与 Shizuku 都不可用）",
                ok ? "info" : "error");
        return ok;
    }

    private static android.content.SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("ggbot", Context.MODE_PRIVATE);
    }

    /**
     * 僵尸绑定巡检（守卫线程每轮调一次）。
     *
     * 与 {@link #selfHeal} 互补：那条管「名单里没了」，这条管「名单里还在但事件断了」。
     * 处置分两种：
     * 1. 有写权限 → 先把 SkipService 从名单里摘掉再写回，逼系统重新绑定；
     *    直接重写同样的值是空操作，系统不会重建连接。
     * 2. 没写权限 → 发通知把用户领到详情页，拨一下开关即可（系统只认用户操作）。
     *
     * 返回是否真的做了修复动作。**只在过了冷却期才动**：一条通知/一次重绑都够重，
     * 不能每 10 秒巡检就重来一遍。
     */
    public static boolean healStale(Context c) {
        boolean listed = contains(currentList(c));
        if (!listed) return false;                    // 不在名单：selfHeal 的分支
        if (!SkipService.isConnected()) return false; // 没连上：用户没开，别替用户开
        // 屏幕灭着时的安静是正常的，不判
        if (!screenOn(c)) return false;
        if (!looksStale(true, true, true, SkipService.isAlive(STALE_EVENT_MS))) return false;

        long now = System.currentTimeMillis();
        if (now - lastStaleAt < STALE_COOLDOWN_MS) return false;
        lastStaleAt = now;

        int counter = (int) SkipService.getEventCounter();
        if (!canWrite(c) && !ShizukuHeal.available(c)) {
            Diag.push("无障碍绑定疑似僵尸（事件已停但名单仍在，计数 " + counter
                    + "），无写权限也无 Shizuku，已通知重开", "warn");
            notifyMissing(c);
            return false;
        }
        // 任一条路可用：摘掉再写回，逼系统重建绑定
        boolean ok = rebind(c);
        Diag.push(ok
                ? "无障碍绑定疑似僵尸（计数 " + counter + "），已重绑"
                : "无障碍僵尸绑定重绑失败（计数 " + counter + "）", ok ? "info" : "error");
        return ok;
    }

    /**
     * 屏幕亮着吗。息屏时无障碍事件会大幅减少甚至断流，这是系统省电的正常行为，
     * 不能当成「绑定断了」——这条是僵尸判定的前提，不是可选项。
     *
     * isInteractive 在 API 20+ 都有；拿不到时保守返回 false（宁可不判，也不误判）。
     */
    public static boolean screenOn(Context c) {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Exception e) {
            return false;
        }
    }

    /** 摘掉再写回，强制系统重建绑定；返回是否成功。 */
    private static boolean rebind(Context c) {
        // 先算出"摘掉自己"之后的列表，两条路径都用同一个值，避免行为不一致
        String without = remove(currentList(c));
        String with = merge(without);

        if (canWrite(c)) {
            try {
                // 先摘：写一个不含 SkipService 的列表
                Settings.Secure.putString(c.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without);
                // 再写回：merge 会把 SkipService 追加回去
                Settings.Secure.putString(c.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, with);
                Settings.Secure.putInt(c.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                return true;
            } catch (Exception e) {
                Diag.push("重绑失败，转 Shizuku: " + e, "warn");
            }
        }
        // 走 Shizuku：同样先摘再写回，发两次 PUT
        if (!ShizukuHeal.putSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without)) {
            return false;
        }
        return ShizukuHeal.putSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, with)
                && ShizukuHeal.putSecure(Settings.Secure.ACCESSIBILITY_ENABLED, "1");
    }

    /** 从冒号列表里去掉 SkipService，其余原样保留（绝不覆盖用户别的无障碍服务）。 */
    public static String remove(String list) {
        if (list == null || list.trim().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String one : list.split(":")) {
            String t = one.trim();
            if (t.isEmpty()) continue;
            if (t.equals(SVC) || t.equals(SVC_FULL)) continue;
            if (sb.length() > 0) sb.append(':');
            sb.append(t);
        }
        return sb.toString();
    }

    private static final String KEY_SEEN = "a11ySeen";
    /** 上一轮看到的状态（true=开着），用来识别「从开到关」的跳变。 */
    private static final String KEY_ON_LAST = "a11yOnLast";
    /** 累计被摘次数。 */
    private static final String KEY_REMOVALS = "a11yRemovals";
    private static volatile long lastWarnAt = 0L;
    private static volatile long lastNoteAt = 0L;
    /** 僵尸绑定判定：多久没事件算「断了」。SkipService 只订阅换页/内容变化，静置时会短暂安静，所以不能太短。 */
    private static final long STALE_EVENT_MS = 20_000L;
    /** 僵尸修复的冷却：重绑与通知都够重，别每轮巡检都做。 */
    private static final long STALE_COOLDOWN_MS = 10 * 60_000L;
    private static volatile long lastStaleAt = 0L;
    private static final String CH_A11Y = "ggbot_a11y";
    /** 无障碍详情页动作名（API 26+；直接写字符串，老 SDK 里没有这个常量）。 */
    private static final String ACTION_A11Y_DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS";
    private static final int NOTE_ID = 1003;

    /** 系统把绑定摘掉了就写回去；返回是否真的恢复了。 */
    public static boolean restoreIfNeeded(Context c, String saved) {
        if (saved == null) return false;
        if (contains(currentList(c))) return false;   // 没被摘，不用动
        if (!canWrite(c) && !ShizukuHeal.available(c)) {
            Diag.push("无障碍绑定已被系统摘掉，本应用权限与 Shizuku 都不可用，无法自动写回", "warn");
            notifyMissing(c);
            return false;
        }
        int bar = saved.indexOf('|');
        String en = bar > 0 ? saved.substring(0, bar) : "1";
        return writeBackVia(c, merge(currentList(c)), "1".equals(en) ? 1 : 0);
    }
}

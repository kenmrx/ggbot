package com.ggbot.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 开屏自动跳过：开屏广告的「跳过」按钮一出现就点掉。
 *
 * DNS 层拦不住的开屏（素材本地缓存、走 App 自家域）在这里补上——
 * GKD 的核心动作搬进 GGBot，用户不用再装第二个工具。
 *
 * 同时这里是无障碍保活的载体：事件掩码全量 + Monitor Object 心跳线程，
 * 做法照 DSH-Go 的 PhoneA11yService（见 res/xml/skip_config.xml 的注释）。
 */
public class SkipService extends AccessibilityService {

    /** 同一界面最多点一次的间隔（与 SkipBudget 的冷却同源）。 */
    private static final long CLICK_GAP_MS = SkipBudget.COOLDOWN_MS;
    /** 树搜索的节流：内容变化事件可能每秒几十次。 */
    private static final long SCAN_GAP_MS = 250;

    private static volatile int COUNT = 0;
    private static volatile SkipService INSTANCE = null;

    // === 保活：Monitor Object 心跳 ===
    /** 心跳的监视器。事件里 notifyAll，心跳线程 wait 它。 */
    private static final Object HEARTBEAT_LOCK = new Object();
    /** 全类型事件累计。也能当「保活健康度」看。 */
    private static volatile long eventCounter = 0;
    /** 最后一次事件的 uptimeMillis（单调时钟，不受用户改时间影响）。 */
    private static volatile long lastEventUptime = android.os.SystemClock.uptimeMillis();
    /** 判停滞的等待窗口。全量事件掩码下，正常息屏也远小于这个值。 */
    private static final long HEARTBEAT_WAIT_MS = 10_000L;
    /** 停搏累计次数。 */
    private static volatile int STALLS = 0;
    /** 心跳线程本身（每条线程一条，onDestroy 里中断）。 */
    private static volatile Thread heartbeat = null;

    private long lastClick = 0;
    private long lastScan = 0;
    /** 当前页进入时间与已点击数，用于单页预算（吸收自 GKD 的 actionMaximum/matchTime）。 */
    private long screenEnterAt = 0;
    private int clicksOnScreen = 0;
    private boolean budgetReported = false;

    public static int count() {
        return COUNT;
    }

    public static void resetCount() {
        COUNT = 0;
    }

    /** 系统只允许无障碍服务自己关自己；设置页的开关走这里。 */
    public static boolean turnOff() {
        SkipService s = INSTANCE;
        if (s == null) return false;
        try {
            s.disableSelf();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 第一层保活：获取事件计数（供外部查询）。 */
    public static long getEventCounter() {
        return eventCounter;
    }

    /**
     * 第一层保活：检查心跳是否活跃。
     *
     * 用 uptimeMillis 而不是 currentTimeMillis —— 后者会被用户改时间、NTP 校时
     * 甚至跨时区拨动，一次回拨就会把「刚收到事件」算成「很久没事件」，
     * 或者反过来把真停滞算成活跃。uptimeMillis 只随开机时间单调递增，
     * 是判活的正确时钟（DSH-Go 的 PhoneA11yService 用的也是它）。
     */
    public static boolean isAlive(long timeoutMs) {
        return (android.os.SystemClock.uptimeMillis() - lastEventUptime) < timeoutMs;
    }

    /** 服务当前有没有连上（没连上时数心跳没有意义）。 */
    public static boolean isConnected() {
        return INSTANCE != null;
    }

    /** 心跳停滞被发现的次数，用来判断「保活到底有没有在起作用」。 */
    public static int stalls() {
        return STALLS;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        // 事件类型在这里再钉一次：XML 里的 skip_config 在系统缓存旧配置时可能不生效
        // （升级包后系统仍持着旧 AccessibilityServiceInfo），setServiceInfo 强制刷新。
        applyAllMask();
        startHeartbeat();
        reviveVpnIfNeeded();
        Diag.push("开屏自动跳过服务已连接", "skip");
    }

    /**
     * 借无障碍服务把防护拉回来。
     *
     * 为什么需要：模拟器（Android 14）实测，进程被杀后系统只重启「被绑定」的
     * 无障碍服务，START_STICKY 的 DnsVpnService 反而没有被排进重启
     * （VPN 随进程死亡被系统整体 revoke）——防护就此停摆，直到用户手动再开。
     * 而无障碍服务是全应用复活确定性最高的组件（系统绑定 + 本应用的自愈链都在推它），
     * 顺手在这里补一次「该开着却没开」的检查，进程级死亡也能自愈。
     */
    private void reviveVpnIfNeeded() {
        try {
            if (!Config.enabled(this) || DnsVpnService.isRunning()) return;
            if (android.net.VpnService.prepare(this) != null) return;   // 授权被撤，别打扰用户
            Intent i = new Intent(this, DnsVpnService.class);
            i.setAction(DnsVpnService.ACTION_START);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            Diag.push("进程级死亡后由无障碍服务拉回防护", "warn");
        } catch (Exception e) {
            Diag.push("无障碍服务拉回防护失败: " + e.getClass().getSimpleName(), "warn");
        }
    }

    /**
     * 强制把事件掩码与能力位注册进本服务。
     *
     * 为什么必须在代码里再设一次：无障碍服务的事件掩码和 capability 由系统在
     * 「授权那一刻」写进它自己的绑定记录。之后即使 manifest 里改了 skip_config.xml，
     * 只要用户没去设置页重新拨一次开关，系统用的还是旧配置——表现就是
     * 「包更新了，保活没生效」。setServiceInfo 会走一次重新绑定，钉住新配置。
     */
    private void applyAllMask() {
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info == null) info = new AccessibilityServiceInfo();
            boolean need = info.eventTypes != AccessibilityEvent.TYPES_ALL_MASK
                    // flagRetrieveInteractiveWindows：能拿到交互窗口，系统按「能力更强」对待
                    || (info.flags & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) == 0
                    || (info.flags & AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS) == 0;
            if (need) {
                info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
                info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
                info.notificationTimeout = 100;
                info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                        | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
                // 注意：capability 位（canTakeScreenshot / canPerformGestures）在
                // AccessibilityServiceInfo 里只有 getter、没有 setter，它由 manifest 的
                // skip_config.xml 推导。所以这里只刷 flags，能力位靠 XML 声明。
                setServiceInfo(info);
                Diag.push("无障碍掩码与能力位已刷新（保活地基）", "skip");
            }
        } catch (Exception e) {
            Diag.push("设置无障碍掩码失败: " + e.getClass().getSimpleName(), "warn");
        }
    }

    /**
     * 第二层保活：Monitor Object 心跳线程（照 DSH-Go 的做法）。
     *
     * 每个无障碍事件在 onAccessibilityEvent 里 notifyAll，这条线程就被唤醒一次；
     * 唤醒即说明「事件流还活着」，什么也不做继续等。只有 wait 真的超时了——
     * 也就是 timeout 这么久一个事件都没有——才认定停滞并记一次。
     *
     * 为什么不在这儿主动重绑：无障碍的绑定只有系统能给，应用自己重绑不了；
     * 这条线程的职责是「准确判定并留痕」，实际恢复交给 A11y.healStale（有写权限时
     * 摘-写逼系统重建，没权限时发通知）。判活与恢复分开，两者都不会误伤。
     */
    private void startHeartbeat() {
        if (heartbeat != null) return;
        heartbeat = new Thread(() -> {
            while (true) {
                try {
                    synchronized (HEARTBEAT_LOCK) {
                        // 有事件就立刻被唤醒；没事件则睡满 timeout
                        HEARTBEAT_LOCK.wait(HEARTBEAT_WAIT_MS);
                    }
                    if (!isAlive(HEARTBEAT_WAIT_MS)) {
                        STALLS++;
                        Diag.push("无障碍心跳停滞（已 " + STALLS + " 次，计数 "
                                + eventCounter + "），事件流断了", "warn");
                    }
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    // 这条线程一旦死掉就再没人判活了，任何异常都不能让它退出
                    Diag.push("心跳线程异常: " + t.getClass().getSimpleName(), "error");
                }
            }
        }, "ggbot-a11y-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    @Override
    public void onDestroy() {
        if (INSTANCE == this) INSTANCE = null;
        if (heartbeat != null) {
            heartbeat.interrupt();
            heartbeat = null;
        }
        super.onDestroy();
    }

    /**
     * 荣耀 MagicOS 僵尸绑定的自救通道（2026-11-13，零权限自愈升级）。
     *
     * 场景：名单里还在、屏幕亮着、但事件流断了 30 秒以上——荣耀的耗电管理
     * 会把服务「挂起」而不是摘掉，界面显示已启用、实际永远不回调。
     * 原处置走 rebind（要 WRITE_SECURE_SETTINGS 或 Shizuku）；这条是零权限路径：
     *   disableSelf() 是官方 API，等价用户关掉——系统立刻回收僵尸连接，
     *   随后 FSI 通知把用户全屏拉到详情页拨一下（notifyMissing 十分钟限一条）。
     * 为什么不担心「关了没人开」：被挂起的服务本来就永远不回调，留着只会
     * 让用户以为在保护；关掉+弹页至少把选择权还给用户。
     *
     * 门槛（四条全过才自杀）：已连接、屏幕亮、名单还在（本服务才会被这样挂起）、
     * 事件计数 30 秒纹丝不动。由守卫线程巡检时调用，不在事件回调里调（重入）。
     */
    public static boolean suicideIfZombie(android.content.Context c) {
        SkipService s = INSTANCE;
        if (s == null) return false;
        if (!s.isConnected()) return false;
        if (!A11y.screenOn(c)) return false;
        // 30 秒无事件：STALE_EVENT_MS 是 20s（A11y 巡检用），这里多给 10s 容忍，
        // 避免与巡检判定抢跑——巡检判定僵尸后走 rebind，走不通才轮到这条。
        if (s.isAlive(30_000L)) return false;
        // 关键门槛（2026-11-13 真机误杀修复）：必须「曾经收到过真实事件」才允许自杀。
        // 手机停在系统设置页/桌面时服务本来就没有事件（counter=0），那不是被挂起，
        // 是没有广告场景——这种情况下自杀只会造成「绑上→自杀→写回→再绑」的死循环
        // （荣耀 BRT-AN00 实测每 60 秒一次，settings 反复清空）。
        // 真被挂起的场景（用户刷抖音/红果时被冻结）counter 必然 > 0。
        if (eventCounter == 0) return false;
        try {
            Diag.push("无障碍被荣耀挂起（事件停 30s+），自杀重置 + 全屏引导重开", "warn");
            A11y.notifyMissing(c);
            s.disableSelf();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 这是保活的中心。
     *
     * 前三件事（计数、时间戳、notifyAll）在所有事件类型上无条件执行——
     * 换页、焦点、文本、滚动、通知全都算心跳。后面的跳过匹配才只看两类事件。
     * 顺序不能颠倒：如果把心跳放在类型过滤之后，静置在某一页时心跳就断了，
     * 保活等于没做。
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // === 心跳：全类型无条件执行 ===
        synchronized (HEARTBEAT_LOCK) {
            eventCounter++;
            lastEventUptime = android.os.SystemClock.uptimeMillis();
            HEARTBEAT_LOCK.notifyAll();
        }
        int t = event.getEventType();
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;
        CharSequence p = event.getPackageName();
        String pkg = p == null ? "" : p.toString();
        // 自己的界面不点：设置页自己就有「跳过」字样的可能
        if (pkg.isEmpty() || "com.ggbot.app".equals(pkg)) return;
        long now = System.currentTimeMillis();
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 换页就换账本：新页面重新给满预算
            screenEnterAt = now;
            clicksOnScreen = 0;
            budgetReported = false;
        }
        if (now - lastScan < SCAN_GAP_MS) return;
        lastScan = now;
        String budget = SkipBudget.decide(now, screenEnterAt, lastClick, clicksOnScreen);
        if (!SkipBudget.OK.equals(budget)) {
            if (SkipBudget.USED_UP.equals(budget) && !budgetReported) {
                budgetReported = true;
                Diag.push("跳过预算用尽 · " + pkg + " · " + SkipBudget.text(budget, clicksOnScreen), "skip");
            }
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (clickSkip(root, pkg)) {
            lastClick = System.currentTimeMillis();
            clicksOnScreen++;
            COUNT++;
        }
    }

    private boolean clickSkip(AccessibilityNodeInfo root, String pkg) {
        // 第 1 路：viewId 形态（无文字的小图 ×，马克水印相机的广告关闭钮就是这种，
        // 文本匹配永远接不住）。viewId 形态天然指向广告控件，误伤面极小。
        CharSequence viewId = null;
        try {
            viewId = root.getViewIdResourceName();
        } catch (Throwable ignored) {
        }
        if (viewId != null && SkipMatcher.isAdCloseId(viewId)
                && clickNodeOrAncestor(root, pkg)) {
            return true;
        }
        // 逐节点扫描 viewId（ViewIdResourceName 是节点级的，root 的那一个只够「整树同 id」场景）
        if (scanViewIds(root, pkg)) return true;
        // 第 2.5 路：跳过钮按 id 命中（抖音/红果的开屏跳过控件常无 close 字样，
        // 只有 splash_ad_skip / tt_skip 之类 id；文本与关闭钮两路都接不住它们）
        if (scanSkipIds(root, pkg)) return true;
        // 第 2 路：文本形态（跳过 / Skip …）
        for (String key : new String[]{"跳过", "Skip", "skip"}) {
            for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(key)) {
                CharSequence text = n.getText() != null ? n.getText() : n.getContentDescription();
                if (!SkipMatcher.isSkipText(text)) continue;
                if (clickNodeOrAncestor(n, pkg)) return true;
            }
        }
        return false;
    }

    /** 广度优先扫描整棵树，命中广告关闭 id 就点。树有界（单窗口），深度 20、节点 500 封顶。 */
    private boolean scanViewIds(AccessibilityNodeInfo root, String pkg) {
        java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen++ < 500) {
            AccessibilityNodeInfo cur = queue.poll();
            if (cur == null) continue;
            CharSequence vid = null;
            try {
                vid = cur.getViewIdResourceName();
            } catch (Throwable ignored) {
            }
            if (vid != null && SkipMatcher.isAdCloseId(vid) && clickNodeOrAncestor(cur, pkg)) {
                return true;
            }
            if (queue.size() + seen < 600) {
                // getChild(i) 在节点被回收/窗口切换瞬间会返回 null，ArrayDeque 拒绝 null 元素直接 NPE
                // （2026-11-13 荣耀 BRT-AN00 真机 crash 栈定位）。入队前必须判空。
                for (int i = 0; i < cur.getChildCount(); i++) {
                    AccessibilityNodeInfo ch = cur.getChild(i);
                    if (ch != null && queue.size() + seen < 600) queue.add(ch);
                }
            }
        }
        return false;
    }

    /** 跳过钮按 id 的第二形态扫描：ad/splash/tt_ 语境 + skip/count_down 组合。
     *  与 scanViewIds 同一栈路，复用同一个 500 节点上限，不额外增加事件成本。 */
    private boolean scanSkipIds(AccessibilityNodeInfo root, String pkg) {
        java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int seen = 0;
        while (!queue.isEmpty() && seen++ < 500) {
            AccessibilityNodeInfo cur = queue.poll();
            if (cur == null) continue;
            CharSequence vid = null;
            try {
                vid = cur.getViewIdResourceName();
            } catch (Throwable ignored) {
            }
            if (vid != null && SkipMatcher.isAdSkipId(vid) && clickNodeOrAncestor(cur, pkg)) {
                return true;
            }
            if (queue.size() + seen < 600) {
                // getChild(i) 在节点被回收/窗口切换瞬间会返回 null，ArrayDeque 拒绝 null 元素直接 NPE
                // （2026-11-13 荣耀 BRT-AN00 真机 crash 栈定位）。入队前必须判空。
                for (int i = 0; i < cur.getChildCount(); i++) {
                    AccessibilityNodeInfo ch = cur.getChild(i);
                    if (ch != null && queue.size() + seen < 600) queue.add(ch);
                }
            }
        }
        return false;
    }

    /** 点掉节点；节点本身不可点则向上找可点祖先（最多 6 层）。
     *  performAction 返回 true 不代表真点上了——荣耀/部分 ROM 会静默拦截
     *  无障碍对广告控件的模拟点击。所以 ACTION_CLICK 失败或点了没反应时，
     *  兜底走 dispatchGesture 按 bounds 中心真实点一下（canPerformGestures 已声明）。 */
    private boolean clickNodeOrAncestor(AccessibilityNodeInfo n, String pkg) {
        AccessibilityNodeInfo target = n;
        int up = 0;
        while (target != null && !target.isClickable() && up++ < 6) {
            target = target.getParent();
        }
        if (target == null) return false;
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Diag.push("已自动跳过开屏 · " + pkg, "skip");
            return true;
        }
        return gestureClick(target, pkg);
    }

    /** 手势兜底：按节点 bounds 中心发一次点按。路径独立于 performAction，
     *  对 ROM 拦截模拟点击的场景有效。失败仅留痕，不影响后续扫描。 */
    private boolean gestureClick(AccessibilityNodeInfo n, String pkg) {
        try {
            android.graphics.Rect r = new android.graphics.Rect();
            n.getBoundsInScreen(r);
            if (r.isEmpty()) return false;
            float x = r.exactCenterX(), y = r.exactCenterY();
            android.accessibilityservice.GestureDescription.Builder b =
                    new android.accessibilityservice.GestureDescription.Builder();
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x, y);
            b.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(
                    path, 0, 40));
            boolean ok = dispatchGesture(b.build(), null, null);
            Diag.push(ok ? "模拟点击被拦，已改手势点按 · " + pkg
                         : "手势点按提交失败 · " + pkg, ok ? "skip" : "warn");
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onInterrupt() {
    }
}

package com.ggbot.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 首页与主导航：底部四标签（首页 / 规则 / 日志 / 设置）。
 *
 * 首页只做一件事：一眼看清有没有在被保护、今天拦了多少、走了多少隧道流量。
 * 其余全部收进设置页。
 */
public class MainActivity extends Activity {

    private static final int REQ_VPN = 2001;
    private static final int REQ_NOTIF = 2002;

    private static final int TAB_HOME = 0;
    private static final int TAB_RULES = 1;
    private static final int TAB_LOGS = 2;
    private static final int TAB_SETTINGS = 3;

    /** 拦截估算：一次被拦的 DNS 查询按平均 6KB 广告资源折算（估算值）。 */
    private static final long EST_PER_BLOCK = 6144L;

    private static final int BG = Ui.BG;
    private static final int CARD = Ui.CARD;
    private static final int CARD2 = Ui.CARD2;
    private static final int BORDER = Ui.BORDER;
    private static final int FG = Ui.FG;
    private static final int MUTED = Ui.MUTED;
    private static final int DIM = Ui.DIM;
    private static final int ACCENT = Ui.ACCENT;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Ui ui;

    private View halo;
    private GradientDrawable haloBg;
    private LinearLayout switchCard;
    private BigToggle dnsToggle;
    private TextView statusText;
    private LinearLayout statusChip;

    private TextView statToday;
    private TextView statRate;
    private TextView statTotal;
    private TextView statQTotal;
    private TextView trafEst;
    private TextView trafRx;
    private TextView trafTx;
    /** 监控条数据点与进度填充。 [0]=拦截 [1]=流量 */
    private final TextView[][] meterVals = new TextView[2][3];
    private final View[] meterFills = new View[2];
    private TextView trafState;
    private View barBlock;
    private View barAllow;
    private TextView logBlockedText;
    private TextView logAllowedText;

    private TextView feedValue;
    private boolean updatingFeeds;

    private FrameLayout content;
    private ScrollView homeTab;
    private ScrollView rulesTab;
    private ScrollView logsTab;
    private LinearLayout rulesBody;
    private LinearLayout logsBody;
    private final TextView[] tabViews = new TextView[4];
    private int curTab = TAB_HOME;

    /** 从拨动开关到服务真正起来之间有一段空窗，期间给「正在建立通道」的说法。 */
    private boolean pending;
    private long pendingAt;
    /** 0 关 / 1 开 / 2 启动中。-1 表示还没渲染过。 */
    private int curState = -1;
    /** 上一次渲染时的联网状态，与 curState 一起用于判断要不要重绘。 */
    private boolean curOnline = true;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new Ui(this);
        Config.init(this);
        // 规则库要排序几十万条，放后台；载入期间匹配一律放行，不会断网
        Rules.loadAsync(getApplicationContext(), () -> handler.post(this::refresh));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 未捕获异常至少留一条痕迹，方便用户反馈「它自己崩过」
        try {
            final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
                try {
                    Diag.push(Crash.describe(ex), "warn");
                } catch (Throwable ignored) {
                }
                if (prev != null) prev.uncaughtException(th, ex);
            });
        } catch (Throwable ignored) {
        }

        buildHomeTab();
        buildRulesTab();
        buildLogsTab();
        buildTabBar(root);
        setContentView(root);

        maybeAskNotificationPermission();

        // 首次使用走 WiFi 自动拉一次规则；此后由每日后台任务接管
        Feeds.autoUpdate(getApplicationContext());
        FeedJob.schedule(this);

        // 进程级死亡后的自恢复闭环：崩溃后系统会把无障碍标成 Crashed 不再自动绑定，
        // 而 a11y 自愈原本只挂在 DnsVpnService 的守卫线程上（服务不起、守卫不跑、
        // a11y 不回，死锁）。用户打开 App 这一下就该把链路接回来：
        // selfHeal 写回 a11y → 系统重新绑定 → SkipService.onServiceConnected 的
        // reviveVpnIfNeeded 把 VPN 拉起。
        new Thread(() -> {
            try {
                A11y.selfHeal(getApplicationContext());
            } catch (Throwable ignored) {
            }
        }, "ggbot-open-heal").start();

        // 供自动化与调试使用：带 auto_start 打开界面即建立通道
        Intent it = getIntent();
        if (it != null && it.getBooleanExtra("auto_start", false) && !DnsVpnService.isRunning()) {
            handler.postDelayed(this::startServiceNow, 600);
        }
    }

    /**
     * 通知权限只自动问一次：问过就不再骚扰，剩下交给设置页入口。
     */
    private void maybeAskNotificationPermission() {
        boolean granted = checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!Notif.shouldAsk(Build.VERSION.SDK_INT, granted, Config.notifAsked(this))) return;
        Config.setNotifAsked(this, true);
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    /* ---------------- 三个页签 ---------------- */

    private void buildHomeTab() {
        homeTab = new ScrollView(this);
        homeTab.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(14), dp(20), dp(24));
        homeTab.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(homeTab, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildHero(root);
        buildSwitchCard(root);
        buildStatCard(root);
        // 首页不再展示日志摘要（入口与模块都已隐藏，日志页仍在代码里）
        buildFeedEntry(root);
    }

    private void buildRulesTab() {
        rulesTab = new ScrollView(this);
        rulesTab.setFillViewport(true);
        rulesBody = new LinearLayout(this);
        rulesBody.setOrientation(LinearLayout.VERTICAL);
        rulesBody.setPadding(dp(20), dp(10), dp(20), dp(24));
        rulesTab.addView(rulesBody, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(rulesTab, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rulesTab.setVisibility(View.GONE);
    }

    private void buildLogsTab() {
        logsTab = new ScrollView(this);
        logsTab.setFillViewport(true);
        logsBody = new LinearLayout(this);
        logsBody.setOrientation(LinearLayout.VERTICAL);
        logsBody.setPadding(dp(20), dp(10), dp(20), dp(24));
        logsTab.addView(logsBody, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(logsTab, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        logsTab.setVisibility(View.GONE);
    }

    private void buildTabBar(LinearLayout root) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setStroke(dp(1), BORDER);
        bar.setBackground(bg);
        // 首页只挂两个页签：规则与日志的入口不再显示（页面代码与数据都还在，只是没入口）
        final int[] tabs = {TAB_HOME, TAB_SETTINGS};
        String[] names = {"首页", "设置"};
        for (int i = 0; i < names.length; i++) {
            final int idx = tabs[i];
            TextView t = ui.tv(names[i], 17.5f, MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setClickable(true);
            // 高度不能写死 54dp：字体放大到 2.0 时标签会被裁到看不见（实测底部导航整排文字消失）
            t.setMinHeight(dp(56));
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(6), 0, dp(6));
            bar.addView(t, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            t.setOnClickListener(v -> switchTab(idx));
            tabViews[i] = t;
        }
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        applyTab();
    }

    private void applyTab() {
        for (int i = 0; i < tabViews.length; i++) {
            if (tabViews[i] == null) continue;
            boolean on = i == curTab;
            tabViews[i].setTextColor(on ? ACCENT : MUTED);
            tabViews[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        homeTab.setVisibility(curTab == TAB_HOME ? View.VISIBLE : View.GONE);
        rulesTab.setVisibility(curTab == TAB_RULES ? View.VISIBLE : View.GONE);
        logsTab.setVisibility(curTab == TAB_LOGS ? View.VISIBLE : View.GONE);
    }

    private void switchTab(int idx) {
        if (idx == TAB_SETTINGS) {
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        curTab = idx;
        if (idx == TAB_RULES) buildRulesBody();
        if (idx == TAB_LOGS) buildLogsBody();
        applyTab();
    }

    /* ---------------- 首页 ---------------- */

    /** 标志居中，图片占满外圆（按圆形裁剪），下面一行状态。一屏布局：整体收窄。 */
    /**
     * 品牌区：logo 圆盘 + 状态徽章融为一体——圆盘下方直接叠一枚胶囊徽章，
     * 减少一层竖排堆叠，视觉重心集中。开启时圆盘光晕与徽章同步变绿。
     */
    private void buildHero(LinearLayout root) {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(0, dp(40), 0, 0);

        FrameLayout plate = new FrameLayout(this);
        halo = new View(this);
        haloBg = new GradientDrawable();
        haloBg.setShape(GradientDrawable.OVAL);
        haloBg.setColor(0x1435D07F);
        halo.setBackground(haloBg);
        plate.addView(halo, new FrameLayout.LayoutParams(dp(168), dp(168), Gravity.CENTER));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo_hero);
        logo.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        logo.setClipToOutline(true);
        logo.setElevation(dp(10));
        plate.addView(logo, new FrameLayout.LayoutParams(dp(160), dp(160), Gravity.CENTER));
        hero.addView(plate, new LinearLayout.LayoutParams(dp(168), dp(168)));

        // 名字 + 状态徽章合成一行胶囊：品牌与状态占同一行，页面更紧凑
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = ui.tv("GGBot", 30f, FG, true);
        name.setLetterSpacing(0.02f);
        brand.addView(name);

        statusChip = new LinearLayout(this);
        statusChip.setOrientation(LinearLayout.HORIZONTAL);
        statusChip.setGravity(Gravity.CENTER_VERTICAL);
        statusChip.setPadding(dp(14), dp(8), dp(14), dp(8));
        statusChip.setBackground(ui.round(CARD2, 20, BORDER));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(12);

        View dot = new View(this);
        dot.setBackground(ui.round(DIM, 4, 0));
        statusChip.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));

        statusText = ui.tv("防护已关闭", 12f, MUTED, true);
        statusText.setPadding(dp(7), 0, 0, 0);
        statusChip.addView(statusText);
        brand.addView(statusChip, clp);

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(24);
        hero.addView(brand, blp);

        root.addView(hero);
    }

    /** 主开关：整张卡片都是热区，按下有缩放与触感反馈。 */
    private void buildSwitchCard(LinearLayout root) {
        switchCard = new LinearLayout(this);
        switchCard.setOrientation(LinearLayout.HORIZONTAL);
        switchCard.setGravity(Gravity.CENTER_VERTICAL);
        switchCard.setPadding(dp(20), dp(18), dp(16), dp(18));
        switchCard.setBackground(ui.rippleCard(CARD, 22, BORDER));
        switchCard.setClickable(true);
        switchCard.setFocusable(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(ui.tv("DNS 拦截", 17f, FG, true));
        switchCard.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        dnsToggle = new BigToggle();
        dnsToggle.setContentDescription("DNS 拦截开关");
        switchCard.addView(dnsToggle);

        switchCard.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(80)
                    .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(140).start())
                    .start();
            toggle();
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        root.addView(switchCard, lp);
    }

    /** 拦截统计 2×2：今日拦截 / 今日拦截率 / 累计拦截 / 查询总数。 */
    /**
     * 两条横向数据监控条。
     *
     * 拦截条：今日拦截率驱动进度宽度，右侧三个数字（今日/累计/查询总数）。
     * 流量条：总流量相对 50MB 的日用参考驱动进度（超出即满格，不截断语义），
     * 右侧两个数字（接收/发送）与开关状态。条形让「量级」一眼可读，
     * 数字收进右端作精确值——比 2×2 网格少了八个居中标签的视觉噪音。
     */
    private void buildStatCard(LinearLayout root) {
        LinearLayout card = ui.card();
        card.setPadding(dp(16), dp(16), dp(16), dp(18));

        // ── 拦截条 ──
        card.addView(sectionRow("拦截"));
        statRate = sectionRight;
        card.addView(buildMeter(true));
        statToday = labelRef(true, 0);
        statTotal = labelRef(true, 1);
        statQTotal = labelRef(true, 2);

        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(14));
        card.addView(new View(this), gap);

        // ── 流量条 ──
        card.addView(sectionRow("流量"));
        trafState = sectionRight;
        card.addView(buildMeter(false));
        trafRx = labelRef(false, 0);
        trafTx = labelRef(false, 1);
        trafEst = labelRef(false, 2);

        root.addView(card, cardLp());
    }

    /**
     * 监控条表头：左标题 + 右关键值。返回**整行容器**供加入卡片；
     * 右侧 TextView 通过 sectionRight 带出——绝不能把「已挂上 parent 的子 view」
     * 返回出去再 addView，那正是 v1.6.0/v1.7.0 连环闪退的根因
     *（IllegalStateException: The specified child already has a parent）。
     */
    private TextView sectionRight;

    private LinearLayout sectionRow(String title) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(ui.tv(title, 13.5f, MUTED, true), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = ui.tv("—", 13.5f, FG, true);
        head.addView(v);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(head);
        sectionRight = v;
        return wrap;
    }

    /** 一条横向监控条：底部 8dp 进度槽，上方右对齐三个数据点。 */
    private LinearLayout buildMeter(boolean isBlock) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(10), 0, 0);

        // 数据行：三个「值+标签」对
        LinearLayout nums = new LinearLayout(this);
        nums.setOrientation(LinearLayout.HORIZONTAL);
        String[] bl = {"今日", "累计", "查询"};
        String[] fl = {"接收", "发送", "估算"};
        String[] labels = isBlock ? bl : fl;
        for (int i = 0; i < 3; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            TextView val = ui.tv("—", 16f, FG, true);
            meterVals[isBlock ? 0 : 1][i] = val;
            val.setGravity(Gravity.END);
            cell.addView(val, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView lab = ui.tv(labels[i], 11f, MUTED, false);
            lab.setGravity(Gravity.END);
            cell.addView(lab, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            nums.addView(cell, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        col.addView(nums);

        // 进度槽：横向双段——fill(权重=比例) + 余量(透明)。
        // 权重方案在横向容器里分配的是宽度，高度固定 6dp 不受影响
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD2);
        bg.setCornerRadius(dp(4));
        track.setBackground(bg);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        tlp.topMargin = dp(10);
        col.addView(track, tlp);

        View fill = new View(this);
        GradientDrawable fg2 = new GradientDrawable();
        fg2.setColor(isBlock ? ACCENT : 0xFF3E8E6A);
        fg2.setCornerRadius(dp(4));
        fill.setBackground(fg2);
        LinearLayout.LayoutParams flp2 = new LinearLayout.LayoutParams(0, dp(6), 0.02f);
        track.addView(fill, flp2);
        View rest = new View(this);
        track.addView(rest, new LinearLayout.LayoutParams(0, dp(6), 0.98f));
        meterFills[isBlock ? 0 : 1] = fill;
        return col;
    }

    /** 监控条数据点引用（拦截/流量 各三个）。 */
    private TextView labelRef(boolean isBlock, int i) {
        return meterVals[isBlock ? 0 : 1][i];
    }

    /** 按比例刷新进度条权重；ratio ∈ [0,1]。fill 与透明余量互补，总和恒为 1。 */
    private void setMeter(boolean isBlock, float ratio) {
        View fill = meterFills[isBlock ? 0 : 1];
        if (fill == null) return;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) fill.getLayoutParams();
        float r = Math.max(0f, Math.min(1f, ratio));
        float w = 0.02f + 0.98f * r;   // 0 也留 2% 底，条形可见
        if (lp.weight == w) return;
        lp.weight = w;
        fill.setLayoutParams(lp);
    }

    /** 日志摘要：红绿双色条 + 已拦截 / 已放行 计数。 */
    private void buildLogCard(LinearLayout root) {
        LinearLayout card = ui.card();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView title = ui.tv("日志", 13f, MUTED, true);
        card.addView(title);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        blp.topMargin = dp(10);
        barBlock = new View(this);
        barBlock.setBackground(ui.round(0xFFE25555, 4, 0));
        bar.addView(barBlock, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        barAllow = new View(this);
        barAllow.setBackground(ui.round(ACCENT, 4, 0));
        bar.addView(barAllow, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        card.addView(bar, blp);

        LinearLayout legend = new LinearLayout(this);
        legend.setOrientation(LinearLayout.HORIZONTAL);
        legend.setGravity(Gravity.CENTER_VERTICAL);
        legend.setPadding(0, dp(9), 0, 0);
        View dotB = new View(this);
        dotB.setBackground(ui.round(0xFFE25555, 4, 0));
        legend.addView(dotB, new LinearLayout.LayoutParams(dp(8), dp(8)));
        logBlockedText = ui.tv("0 已拦截", 12f, MUTED, false);
        logBlockedText.setPadding(dp(7), 0, 0, 0);
        legend.addView(logBlockedText);
        View spacer = new View(this);
        legend.addView(spacer, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View dotA = new View(this);
        dotA.setBackground(ui.round(ACCENT, 4, 0));
        legend.addView(dotA, new LinearLayout.LayoutParams(dp(8), dp(8)));
        logAllowedText = ui.tv("0 已放行", 12f, MUTED, false);
        logAllowedText.setPadding(dp(7), 0, 0, 0);
        legend.addView(logAllowedText);
        card.addView(legend);

        root.addView(card, cardLp());
    }

    /** 规则订阅入口：点一下立即更新全部源。 */
    private void buildFeedEntry(LinearLayout root) {
        LinearLayout feed = new LinearLayout(this);
        feed.setOrientation(LinearLayout.HORIZONTAL);
        feed.setGravity(Gravity.CENTER_VERTICAL);
        feed.setMinimumHeight(dp(64));
        feed.setPadding(dp(18), dp(14), dp(14), dp(14));
        feed.setBackground(ui.rippleCard(CARD, 18, BORDER));
        feed.setClickable(true);
        feed.setFocusable(true);

        LinearLayout fcol = new LinearLayout(this);
        fcol.setOrientation(LinearLayout.VERTICAL);
        fcol.addView(ui.tv("规则更新", 14f, FG, false));
        feed.addView(fcol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        feedValue = ui.tv("", 13f, MUTED, false);
        feed.addView(feedValue);

        TextView fchev = ui.tv("›", 18f, DIM, false);
        fchev.setPadding(dp(9), 0, 0, 0);
        feed.addView(fchev);

        feed.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            updateFeedsNow();
        });

        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(16);
        root.addView(feed, flp);
    }

    private LinearLayout gridRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(4), dp(8), dp(4));
        return row;
    }

    /** 数值与说明都居中，网格里不再左对齐。 */
    private TextView addStat(LinearLayout parent, String label) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView value = ui.tv("0", 23f, FG, true);
        value.setGravity(Gravity.CENTER);
        col.addView(value);

        TextView name = ui.tv(label, 11f, MUTED, false);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, dp(4), 0, 0);
        col.addView(name);

        parent.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return value;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        return lp;
    }

    /* ---------------- 规则页签 ---------------- */

    private void buildRulesBody() {
        rulesBody.removeAllViews();
        rulesBody.addView(ui.section("规则"));
        LinearLayout card = ui.card();
        addRuleRow(card, "自定义拦截", Config.blocks(this).size() + " 条");
        addRuleRow(card, "白名单", Config.allow(this).size() + " 条");
        addRuleRow(card, "URL 规则", Config.urlBlocks(this).size() + " 条");
        addRuleRow(card, "解密域名", Config.decrypt(this).size() + " 条");
        addRuleRow(card, "排除应用", Config.bypass(this).size() + " 个");
        rulesBody.addView(card);

        rulesBody.addView(ui.section("订阅"));
        LinearLayout sub = ui.card();
        addRuleRow(sub, "规则订阅", feedSummary());
        TextView upd = ui.pill("立即更新");
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.topMargin = dp(10);
        upd.setOnClickListener(v -> updateFeedsNow());
        sub.addView(upd, ulp);
        sub.setPadding(dp(14), dp(4), dp(14), dp(14));
        rulesBody.addView(sub);
    }

    private void addRuleRow(LinearLayout parent, String title, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(50));
        row.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(ui.tv(title, 13.5f, FG, false));
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = ui.tv(value, 13f, MUTED, false);
        row.addView(v);
        parent.addView(row);
        ui.divider(parent);
    }

    /* ---------------- 日志页签 ---------------- */

    private void buildLogsBody() {
        long t0 = android.os.SystemClock.uptimeMillis();
        logsBody.removeAllViews();
        logsBody.addView(ui.section("最近拦截"));
        LinearLayout card = ui.card();
        DnsBlock[] logs = DnsVpnService.recent();
        if (logs.length == 0) {
            card.addView(emptyRow("还没有拦截记录"));
        } else {
            for (DnsBlock b : logs) {
                TextView t = ui.tv(b.line(), 11.5f, FG, false);
                t.setPadding(dp(14), dp(10), dp(14), dp(10));
                t.setMinHeight(dp(Ui.TOUCH_DP));
                t.setGravity(Gravity.CENTER_VERTICAL);
                // 拦截记录只活在这个日志页；点一下就能当场放行，不用回设置里翻白名单
                t.setClickable(true);
                t.setOnClickListener(v -> blockAction(b));
                card.addView(t);
                ui.divider(card);
            }
        }
        logsBody.addView(card);

        logsBody.addView(ui.section("最近查询"));
        logsBody.addView(queryBar());
        LinearLayout card2 = ui.card();
        QueryLog.Entry[] qs = Fold.filter(QueryLog.recent(), Config.logFilter(this));
        // 截断说明放在列表上方：不用滚到底才看到，否则等于没说
        {
            int rows = Config.foldLogs(this) ? Fold.byHost(qs).length : qs.length;
            int shown = LogView.visible(rows, LogView.CAP);
            if (LogView.truncated(rows, shown)) {
                card2.addView(noteRow(LogView.footer(rows, shown)));
                ui.divider(card2);
            }
        }
        if (qs.length == 0) {
            card2.addView(emptyRow("还没有查询记录"));
        } else if (Config.foldLogs(this)) {
            // 折叠：同域名同结果合成一行，次数挂在后面；点一下能看最近那一条的详情
            Fold.Group[] gs = Fold.byHost(qs);
            int shownFold = LogView.visible(gs.length, LogView.CAP);
            for (int gi = 0; gi < shownFold; gi++) {
                Fold.Group g = gs[gi];
                TextView t = ui.tv(g.timeText() + "  " + g.host
                        + "（" + g.label() + g.countText() + "）", 11.5f, FG, false);
                t.setPadding(dp(14), dp(10), dp(14), dp(10));
                t.setMinHeight(dp(Ui.TOUCH_DP));
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setClickable(true);
                final Fold.Group fg = g;
                t.setOnClickListener(v -> queryAction(fg.host, fg.outcome, fg.timeText()));
                card2.addView(t);
                ui.divider(card2);
            }
        } else {
            SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", Locale.US);
            int shownFlat = LogView.visible(qs.length, LogView.CAP);
            for (int qi = 0; qi < shownFlat; qi++) {
                QueryLog.Entry e = qs[qi];
                String tag = e.outcome == QueryLog.BLOCK ? "拦截"
                        : (e.outcome == QueryLog.DECRYPT ? "解密" : "放行");
                TextView t = ui.tv(tf.format(new Date(e.time)) + "  " + e.host + "（" + tag + "）",
                        11.5f, FG, false);
                t.setPadding(dp(14), dp(10), dp(14), dp(10));
                t.setMinHeight(dp(Ui.TOUCH_DP));
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setClickable(true);
                final QueryLog.Entry fe = e;
                t.setOnClickListener(v -> queryAction(fe.host, fe.outcome, fe.timeText()));
                card2.addView(t);
                ui.divider(card2);
            }
        }
        logsBody.addView(card2);
        Diag.push("日志页行数: 查询 " + qs.length + " 折叠后 "
                + (Config.foldLogs(this) ? Fold.byHost(qs).length : qs.length)
                + " / 上限 " + LogView.CAP, "info");
        Diag.push("日志页渲染 " + qs.length + " 条 / " + (android.os.SystemClock.uptimeMillis() - t0) + "ms", "info");
    }

    /** 满量时列表上方的一行说明：不让人以为记录丢了。 */
    private TextView noteRow(String text) {
        TextView t = ui.tv(text, 11.5f, DIM, false);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        return t;
    }

    /**
     * 查询列表上方的「筛选 + 折叠」条。
     *
     * 思路来自算法助手Pro 2.0.4：它的日志页有「筛选」（capture_menu_filter）和
     * 「重复日志折叠」（title_foldDuplicateLogs）两个开关。我们只接管 DNS，
     * 所以筛选项就是三种结果，折叠默认开——排查时先看「谁在刷」，再关掉折叠看时序。
     */
    private View queryBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(6), dp(4), dp(2));

        int cur = Config.logFilter(this);
        bar.addView(chip("全部", cur == Fold.ANY, v -> {
            Config.setLogFilter(this, Fold.ANY);
            buildLogsBody();
        }));
        bar.addView(chip("拦截", cur == QueryLog.BLOCK, v -> {
            Config.setLogFilter(this, QueryLog.BLOCK);
            buildLogsBody();
        }));
        bar.addView(chip("放行", cur == QueryLog.ALLOW, v -> {
            Config.setLogFilter(this, QueryLog.ALLOW);
            buildLogsBody();
        }));
        bar.addView(chip("解密", cur == QueryLog.DECRYPT, v -> {
            Config.setLogFilter(this, QueryLog.DECRYPT);
            buildLogsBody();
        }));

        boolean fold = Config.foldLogs(this);
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        bar.addView(chip(fold ? "折叠重复 开" : "折叠重复 关", fold, v -> {
            Config.setFoldLogs(this, !Config.foldLogs(this));
            buildLogsBody();
        }));
        return bar;
    }

    private TextView chip(String text, boolean on, View.OnClickListener click) {
        TextView t = ui.tv(text, 11.5f, on ? ACCENT : DIM, on);
        t.setPadding(dp(12), dp(5), dp(12), dp(5));
        // 实测原来只有约 27dp 高，拇指按下去常点空；抬到 48dp 基准
        t.setMinHeight(dp(Ui.TOUCH_DP));
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setOnClickListener(click);
        return t;
    }

    /**
     * 一条查询记录能做的事：拦截、加入解密、复制域名。
     *
     * 这些动作原在「设置 → 最近查询」，现在查询只留在日志页，所以整套搬过来：
     * 撤掉设置页那块时，用户手上的操作一个都不会少。
     *
     * 注意：AlertDialog 同时 setMessage 和 setItems 时，这一版 Android 只渲染 message，
     * 列表会被吞掉（实测截图为证）。所以动作行是自绘的点击行，不用 setItems。
     */
    private void queryAction(final String host, final int outcome, final String when) {
        final boolean decrypted = Config.decrypt(this).contains(host);
        String label = outcome == QueryLog.BLOCK ? "已拦截"
                : outcome == QueryLog.DECRYPT ? "解密名单" : "放行";

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(14), dp(20), dp(6));

        TextView head = ui.tv(host, 13.5f, FG, true);
        head.setSingleLine(true);
        head.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        box.addView(head);

        TextView sub = ui.tv((outcome == QueryLog.BLOCK ? "被拦下的" : "放行出去的")
                + " DNS 查询 · " + label, 11.5f, DIM, false);
        sub.setPadding(0, dp(3), 0, dp(8));
        box.addView(sub);

        // 真机字体放到 1.5～2.0 倍时，面板会比屏幕高；包一层可滚动容器，
        // 并限到屏幕高度的 60%，保证「关闭」永远够得到。
        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(box);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.6f);
        sc.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.min(maxH, dp(420))));
        final android.app.AlertDialog dlg = Ui.dialog(this)
                .setTitle(when)
                .setView(sc)
                .setNegativeButton("关闭", null)
                .create();
        ui.dialogBg(dlg);

        if (outcome != QueryLog.BLOCK) {
            box.addView(actionRow("拦截这个域名", () -> {
                Config.addDomains(this, false, host);
                Rules.blockNow(host);
                buildLogsBody();
                toast(host + " 已加入拦截名单，立即生效");
                dlg.dismiss();
            }));
        }
        box.addView(actionRow(decrypted ? "已在解密名单中" : "把这个域名加入解密", () -> {
            if (decrypted) {
                toast("已在解密名单中");
            } else {
                Config.addDecrypt(this, host);
                buildLogsBody();
                toast("已加入解密名单");
            }
            dlg.dismiss();
        }));
        box.addView(actionRow("复制域名", () -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("host", host));
            }
            toast("已复制 " + host);
            dlg.dismiss();
        }));
        dlg.show();
    }

    /** 弹窗里的一行可点动作；右侧留一点箭头暗示可点。 */
    private View actionRow(String text, final Runnable act) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(11), 0, dp(11));
        row.setClickable(true);
        TextView t = ui.tv(text, 13f, ACCENT, false);
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(ui.tv("›", 16f, DIM, false));
        row.setOnClickListener(v -> act.run());
        return row;
    }

    private void blockAction(final DnsBlock b) {
        final boolean allowed = Domains.matches(Config.allow(this), b.host);
        Ui.dialog(this)
                .setTitle(b.timeText() + "　" + (b.cname() ? "隐身拦截" : "已拦截"))
                .setMessage(b.detail())
                .setPositiveButton(allowed ? "已在白名单中" : "放行这个域名", (d, w) -> {
                    if (allowed) {
                        toast(b.host + " 已在白名单中");
                        return;
                    }
                    Config.addDomains(this, true, b.host);
                    // 只往白名单集合里加一条，不重排三十万条：点一下要立刻生效
                    Rules.allowNow(b.host);
                    buildLogsBody();
                    toast(b.host + " 已放行，立即生效");
                })
                .setNeutralButton("复制域名", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("host", b.host));
                    }
                    toast("已复制 " + b.host);
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private TextView emptyRow(String text) {
        TextView t = ui.tv(text, 12f, DIM, false);
        t.setPadding(dp(14), dp(12), dp(14), dp(12));
        return t;
    }

    /* ---------------- 订阅摘要与更新 ---------------- */

    /** 订阅一行摘要：总条数 + 最近更新时间，源的名字不露出。 */
    private String feedSummary() {
        java.util.List<Feeds.Source> srcs = Feeds.list(this);
        long total = 0;
        int on = 0;
        for (Feeds.Source s : srcs) {
            if (!s.enabled) continue;
            on++;
            total += s.count;
        }
        if (on == 0) return "未启用";
        if (total == 0) return "未更新";
        return (total >= 10000
                ? String.format(Locale.US, "%.1f 万", total / 10000f)
                : String.valueOf(total)) + " 条";
    }

    private void refreshFeedValue() {
        if (feedValue == null || updatingFeeds) return;
        java.util.List<Feeds.Source> srcs = Feeds.list(this);
        long total = 0;
        long latest = 0;
        int on = 0;
        for (Feeds.Source s : srcs) {
            if (!s.enabled) continue;
            on++;
            total += s.count;
            if (s.updated > latest) latest = s.updated;
        }
        if (on == 0) {
            feedValue.setText("未启用");
        } else if (total == 0) {
            feedValue.setText("未更新");
        } else {
            long days = (System.currentTimeMillis() - latest) / 86400000L;
            String when = days <= 0 ? "今天" : (days == 1 ? "昨天" : days + " 天前");
            feedValue.setText((total >= 10000
                    ? String.format(Locale.US, "%.1f 万", total / 10000f)
                    : String.valueOf(total)) + " 条 · " + when);
        }
    }

    /** 手动更新：全部启用中的源立即重新下载，完成前不重复触发。 */
    private void updateFeedsNow() {
        if (updatingFeeds) {
            ui.toast("正在更新中，请稍候");
            return;
        }
        if (Feeds.enabledCount(this) == 0) {
            ui.toast("还没有启用的订阅源");
            return;
        }
        updatingFeeds = true;
        feedValue.setText("更新中…");
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            final int[] bad = {0};
            int okCount = -1;
            try {
                okCount = Feeds.updateAll(app, (src, err) -> {
                    if (err != null) bad[0]++;
                });
            } catch (Throwable ignored) {
            }
            final int fok = okCount;
            handler.post(() -> {
                updatingFeeds = false;
                if (isFinishing()) return;
                refresh();
                if (bad[0] == 0) {
                    ui.toast("更新完成 · " + Math.max(fok, 0) + " 个源");
                } else {
                    ui.toast(fok + " 个源已更新，" + bad[0] + " 个失败");
                }
            });
        }, "ggbot-feed-now").start();
    }

    /* ---------------- 开关 ---------------- */

    private void toggle() {
        if (pending) return;
        if (DnsVpnService.isRunning()) {
            Intent i = new Intent(this, DnsVpnService.class);
            i.setAction(DnsVpnService.ACTION_STOP);
            startService(i);
            // 首页关掉 DNS 即视为不再需要 VPN：设置页的模式同步落回「只跳过」，
            // 免得两处状态对不上（模式还挂着「标准」但通道已关）
            if (!Power.current(this).equals(Power.SKIP)) {
                Config.setPowerMode(this, Power.SKIP);
            }
            applyState(false, false);
            handler.postDelayed(this::refresh, 300);
            return;
        }
        Intent prep = android.net.VpnService.prepare(this);
        if (prep != null) {
            startActivityForResult(prep, REQ_VPN);
            return;
        }
        startServiceNow();
    }

    private void startServiceNow() {
        Intent i = new Intent(this, DnsVpnService.class);
        i.setAction(DnsVpnService.ACTION_START);
        pending = true;
        pendingAt = SystemClock.uptimeMillis();
        applyState(false, true);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            pending = false;
            ui.toast("启动失败：" + e);
        }
        handler.postDelayed(this::refresh, 900);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                startServiceNow();
            } else {
                ui.toast("未获得 VPN 授权，无法开启过滤");
                refresh();
            }
        }
    }

    /* ---------------- 刷新 ---------------- */

    /** 当前是否有可用网络（VPN 自身不算，看底层连接）。 */
    private boolean onlineNow() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            // NetworkInfo 系列 29 起废弃；用 activeNetwork + capabilities 判断「有没有可用网络」
            android.net.Network nw = cm.getActiveNetwork();
            if (nw == null) return false;
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(nw);
            return caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable t) {
            return true;   // 查不到就当有网，不要让文案自己出问题
        }
    }

    /** 把「开 / 关 / 启动中」一次性落到所有相关控件上。 */
    private void applyState(boolean on, boolean starting) {
        int state = starting ? 2 : (on ? 1 : 0);
        // 网络状态也要算进「有没有变化」：只看开/关会把「无网络」的文案永远刷不出来
        boolean online = onlineNow();
        if (state == curState && online == curOnline) return;
        curState = state;
        curOnline = online;

        if (starting) {
            statusText.setText("正在建立通道…");
            statusText.setTextColor(ACCENT);
            statusChip.setBackground(ui.round(Ui.ACCENT_SOFT, 20, 0));
            statusChip.getChildAt(0).setBackground(ui.round(ACCENT, 4, 0));
            dnsToggle.setOn(true);
            haloBg.setColor(0x2635D07F);
            switchCard.setBackground(ui.rippleCard(0xFF16241D, 22, 0xFF2F5A45));
            return;
        }
        statusText.setText(Net.status(on, online));
        statusText.setTextColor(on ? ACCENT : MUTED);
        statusChip.setBackground(ui.round(on ? Ui.ACCENT_SOFT : CARD2, 20, on ? 0 : BORDER));
        statusChip.getChildAt(0).setBackground(ui.round(on ? ACCENT : DIM, 4, 0));
        dnsToggle.setOn(on);
        haloBg.setColor(on ? 0x2635D07F : 0x1435D07F);
        switchCard.setBackground(ui.rippleCard(on ? 0xFF16241D : CARD, 22,
                on ? 0xFF2F5A45 : BORDER));
    }

    private void refresh() {
        boolean on = DnsVpnService.isRunning();

        if (pending) {
            if (on) {
                pending = false;
            } else if (SystemClock.uptimeMillis() - pendingAt > 6000) {
                pending = false;
            }
        }

        applyState(on, pending && !on);

        int tq = Stats.todayQueries(this);
        int tb = Stats.todayCount(this);
        // 监控条控件在 buildStatCard 里就绪；重建/轮询竞态下可能尚未挂上，
        // 每个引用独立判空——统计刷新晚一秒无所谓，崩了就是闪退
        if (statToday != null) statToday.setText(fmt(tb));
        if (statTotal != null) statTotal.setText(fmt((int) Math.min(Stats.totalCount(this), Integer.MAX_VALUE)));
        if (statQTotal != null) statQTotal.setText(fmt((int) Math.min(Stats.totalQueries(this), Integer.MAX_VALUE)));
        int rate = tq == 0 ? 0 : Math.min(100, tb * 100 / tq);
        if (statRate != null) statRate.setText(rate + "%");
        setMeter(true, rate / 100f);

        long rx = TrafficStats.todayRx(this);
        long tx = TrafficStats.todayTx(this);
        long totalBytes = rx + tx;
        if (trafEst != null) trafEst.setText(fmtBytes((long) tb * EST_PER_BLOCK));
        if (trafRx != null) trafRx.setText(fmtBytes(rx));
        if (trafTx != null) trafTx.setText(fmtBytes(tx));
        if (trafState != null) trafState.setText(fmtBytes(totalBytes));
        // 日用参考 50MB：超出按满格处理，条形表达量级
        setMeter(false, totalBytes / (50f * 1024 * 1024));

        QueryLog.Entry[] qs = QueryLog.recent();
        int blk = 0;
        int allow = 0;
        for (QueryLog.Entry e : qs) {
            if (e.outcome == QueryLog.BLOCK) blk++;
            else if (e.outcome == QueryLog.ALLOW) allow++;
        }
        if (barBlock != null && barAllow != null && logBlockedText != null && logAllowedText != null) {
            setWeight(barBlock, Math.max(blk, 1));
            setWeight(barAllow, Math.max(allow, 1));
            logBlockedText.setText(blk + " 已拦截");
            logAllowedText.setText(allow + " 已放行");
        }

        refreshFeedValue();
    }

    private void setWeight(View v, float w) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        if (lp.weight == w) return;
        lp.weight = w;
        v.setLayoutParams(lp);
    }

    /**
     * 大号自绘开关：62×36dp，点得到、看得清，也不会被裁。
     */
    private final class BigToggle extends View {

        private boolean on;
        private float pos; // 0 = 关，1 = 开
        private android.animation.ValueAnimator anim;

        BigToggle() {
            super(MainActivity.this);
            setClickable(false);
            setFocusable(false);
        }

        void setOn(boolean v) {
            if (on == v) return;
            on = v;
            if (anim != null) anim.cancel();
            if (isShown()) {
                anim = android.animation.ValueAnimator.ofFloat(pos, v ? 1f : 0f);
                anim.setDuration(150);
                anim.addUpdateListener(a -> {
                    pos = (Float) a.getAnimatedValue();
                    invalidate();
                });
                anim.start();
            } else {
                pos = v ? 1f : 0f;
                invalidate();
            }
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(dp(62), dp(36));
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            android.graphics.RectF r = new android.graphics.RectF(0, 0, w, h);
            p.setColor(on ? ACCENT : 0xFF3A3A4C);
            cv.drawRoundRect(r, h / 2, h / 2, p);
            float d = h - dp(8);
            float cx = dp(4) + d / 2 + (w - d - dp(8)) * pos;
            p.setColor(on ? 0xFFFFFFFF : 0xFFB7B7C4);
            cv.drawCircle(cx, h / 2, d / 2, p);
        }
    }

    /* ---------------- 小工具 ---------------- */

    private static String fmt(int v) {
        if (v < 1000) return String.valueOf(v);
        if (v < 10000) return (v / 1000f == v / 1000 ? String.valueOf(v / 1000) + "k"
                : String.format(Locale.US, "%.1fk", v / 1000f));
        return v / 1000 + "k";
    }

    private static String fmtBytes(long v) {
        if (v < 1024) return v + " B";
        double k = v / 1024.0;
        if (k < 1024) return String.format(Locale.US, "%.1f KB", k);
        double m = k / 1024.0;
        if (m < 1024) return String.format(Locale.US, "%.1f MB", m);
        return String.format(Locale.US, "%.2f GB", m / 1024.0);
    }

    private int dp(int v) {
        return ui.dp(v);
    }
}

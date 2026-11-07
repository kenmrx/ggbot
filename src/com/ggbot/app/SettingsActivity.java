package com.ggbot.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 设置页：所有「偶尔才动一次」的东西都挪到这里，首页只留一个开关。
 *
 * 之前二十多张卡片全堆在首屏，找「白名单」要滑三屏，真正每天用的开关
 * 反被淹在中间。现在首页只有一个开关，其余按主题分页。
 */
public class SettingsActivity extends Activity {

    private static final int REQ_SAVE_CA = 2003;
    /** 诊断导出用 SAF 写文件。 */
    private static final int REQ_SAVE_DIAG = 2004;

    // 配色只在 Ui 里定义一份，这里做成别名，两页永远同色
    private static final int BG = Ui.BG;
    private static final int CARD = Ui.CARD;
    private static final int CARD2 = Ui.CARD2;
    private static final int BORDER = Ui.BORDER;
    private static final int FG = Ui.FG;
    private static final int MUTED = Ui.MUTED;
    private static final int DIM = Ui.DIM;
    private static final int ACCENT = Ui.ACCENT;
    private static final int DARK = Ui.DARK;

    private Ui ui;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView valueRules;
    private TextView valueBlocks;
    private TextView valueAllow;
    private TextView valueUpstream;
    private TextView valueDoh;
    private TextView valueMode;
    private TextView valueUrl;
    private TextView valueBypass;
    private TextView valueDecrypt;
    private TextView valueTcp;
    private TextView valueHttps;
    private TextView valueMitmBypass;
    private TextView valueProto;
    private TextView valueFeeds;
    private TextView valueFeedState;
    private TextView valueMitmStats;
    private Switch bootSwitch;
    private Switch mitmSwitch;
    private Switch feedSwitch;
    private Switch skipSwitch;
    private TextView valueA11y;
    private TextView valueBattery;
    private boolean updatingSkip = false;
    private volatile boolean updatingFeeds = false;
    private LinearLayout mitmLogBox;
    private LinearLayout reqBox;
    private String lastMitmKey = "init";
    private String lastReqKey = "init";

    private final class Tick implements Runnable {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    }

    private final Tick tick = new Tick();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = new Ui(this);
        Config.init(this);
        // 规则库要排序几十万条，放后台；载入期间匹配一律放行，不会断网
        final android.content.Context app = getApplicationContext();
        Rules.loadAsync(app, () -> handler.post(this::refresh));

        // 返回栏放在滚动容器外面：这一页很长，滑到「关于」时还要能一键回去，
        // 让返回按钮跟着内容滚走是自找麻烦
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), 0, dp(16), dp(20));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildTopBar(page);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(page);


        buildPower(root);
        buildEssentials(root);
        buildAdvanced(root);
        buildBottom(root);

        // 通知权限与首次拉规则都归首页管：设置页只是个配置面板，
        // 被从后台唤起时不该顺手再拉一遍订阅
        FeedJob.schedule(this);
    }

    /** 隐藏区里的行要被 refresh() 摸到，字段必须继续可用（View 不在树上也能 setText）。 */
    private LinearLayout advancedBody;

    /** 高级设置整张卡片：默认从界面上消失，「关于」连点 5 次才解锁显示。 */
    private LinearLayout advancedWrap;

    /** 底部脚注连点计数，凑满 9 次解锁高级设置。 */
    private int aboutTaps;

    /** 底部脚注右侧的小号「关于」入口（右下角小标）。 */
    private void buildBottom(LinearLayout root) {
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, dp(16), 0, 0);

        TextView foot = footNote();
        bottom.addView(foot, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 右下角小号关于：比胶囊小一档，不抢视觉
        TextView about = new TextView(this);
        about.setText("关于");
        about.setTextSize(11.5f);
        about.setTextColor(MUTED);
        about.setGravity(Gravity.CENTER);
        about.setMinHeight(dp(30));
        about.setPadding(dp(12), dp(6), dp(12), dp(6));
        about.setBackground(ui.rippleCard(CARD2, 10, BORDER));
        about.setClickable(true);
        about.setFocusable(true);
        about.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            showAbout();
        });
        bottom.addView(about, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(bottom);
    }

    private TextView footNote() {
        TextView foot = tv(appVersion(), 11.5f, DIM, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(18), 0, 0);
        // 暗门挪到脚注：连点 9 次显示高级设置。中途不弹窗、不提示，
        // 点够之前看起来就是个死文本；解锁那一刻才有 toast。
        foot.setOnClickListener(v -> {
            boolean unlocked = advancedWrap != null
                    && advancedWrap.getVisibility() == View.VISIBLE;
            if (unlocked) return;
            aboutTaps++;
            if (aboutTaps >= 9) {
                advancedWrap.setVisibility(View.VISIBLE);
                aboutTaps = 0;
                toast("高级设置已显示");
            }
        });
        return foot;
    }

    /**
     * 常用三行：开机自启 / 开屏自动跳过 / 电池优化。
        LinearLayout mitmLogCard = card();
        adv.addView(mitmLogCard);
        mitmLogBox = new LinearLayout(this);
        mitmLogBox.setOrientation(LinearLayout.VERTICAL);
        mitmLogBox.setPadding(dp(16), dp(6), dp(16), dp(10));
        mitmLogCard.addView(mitmLogBox);

        adv.addView(section("请求记录"));
        LinearLayout reqCard = card();
        adv.addView(reqCard);
        reqBox = new LinearLayout(this);
        reqBox.setOrientation(LinearLayout.VERTICAL);
        reqBox.setPadding(dp(16), dp(6), dp(16), dp(10));
        reqCard.addView(reqBox);

        adv.addView(section("应用控制"));
        LinearLayout appCard = card();
        adv.addView(appCard);
        valueBypass = addRow(appCard, "排除应用", "不经过过滤",
                v -> bypassDialog());
        divider(appCard);
        addRow(appCard, "导出诊断", "文本，便于排障", v -> exportDiag());
    }

    private TextView footNote() {
        TextView foot = tv(appVersion(), 11.5f, DIM, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(18), 0, 0);
        return foot;
    }

    /**
     * 常用三行：开机自启 / 开屏自动跳过 / 电池优化。
     * 从原「应用控制」卡里拆出来放在模式卡下面——这三个是每天都要看的，
     * 排除应用与导出诊断属于低频，进了折叠区。
     */
    private void buildEssentials(LinearLayout root) {
        root.addView(section("常用"));
        LinearLayout appCard = card();
        root.addView(appCard);
        bootSwitch = new Switch(this);
        bootSwitch.setChecked(Config.boot(this));
        bootSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> Config.setBoot(this, isChecked));
        addSwitchRow(appCard, "开机自动启动", "上次开着，开机就继续拦", bootSwitch);
        divider(appCard);
        skipSwitch = new Switch(this);
        skipSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingSkip) return;
            if (isChecked) {
                toast("在系统「无障碍」里打开 GGBot · 开屏自动跳过");
                try {
                    startActivity(new android.content.Intent(
                            android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception ignored) {
                }
            } else if (!SkipService.turnOff()) {
                toast("请在系统「无障碍」里关闭 GGBot");
            }
            updatingSkip = true;
            skipSwitch.setChecked(false);
            updatingSkip = false;
        });
        addSwitchRow(appCard, "开屏自动跳过", "自动点掉跳过（需无障碍）", skipSwitch);
        divider(appCard);
        valueA11y = addRow(appCard, "无障碍服务", "", v -> a11yDialog());
        divider(appCard);
        valueBattery = addRow(appCard, "电池优化", "", v -> batteryDialog());
    }

    /**
     * 高级设置：规则 / 解密 / 日志 / 应用控制整块收进一张卡片。
     * 1.6 起默认整卡隐藏（不再只是折叠）——底部脚注「无云端 · 不采集数据」
     * 连点 9 次才显示；中途无任何提示，避免好奇用户误触。
     * 显示后仍保留展开/收起行为，展开状态记住（SharedPreferences）。
     */
    private void buildAdvanced(LinearLayout root) {
        final boolean open = Config.advancedOpen(this);
        LinearLayout wrap = card();
        root.addView(wrap);
        advancedWrap = wrap;
        // 默认整卡隐藏；连点解锁只在本会话内可见，下次启动重新从隐藏开始
        wrap.setVisibility(View.GONE);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(dp(56));
        head.setPadding(dp(16), dp(12), dp(14), dp(12));
        head.setClickable(true);
        head.setFocusable(true);
        head.setBackground(ui.rippleCard(CARD, 18, 0));
        LinearLayout hcol = new LinearLayout(this);
        hcol.setOrientation(LinearLayout.VERTICAL);
        hcol.addView(tv("高级设置", 14f, FG, false));
        hcol.addView(tv("规则 · 订阅 · 解密 · 日志", 11.5f, MUTED, false));
        head.addView(hcol, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView arrow = tv(open ? "⌄" : "›", 18f, DIM, false);
        head.addView(arrow);
        head.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            boolean next = !Config.advancedOpen(this);
            Config.setAdvancedOpen(this, next);
            arrow.setText(next ? "⌄" : "›");
            advancedBody.setVisibility(next ? View.VISIBLE : View.GONE);
        });
        wrap.addView(head);

        advancedBody = new LinearLayout(this);
        advancedBody.setOrientation(LinearLayout.VERTICAL);
        advancedBody.setVisibility(open ? View.VISIBLE : View.GONE);
        wrap.addView(advancedBody);
        LinearLayout adv = advancedBody;

        adv.addView(section("规则"));
        LinearLayout ruleCard = card();
        adv.addView(ruleCard);
        valueRules = addRow(ruleCard, "域名拦截库", "内置与订阅合并", null);
        divider(ruleCard);
        valueBlocks = addRow(ruleCard, "自定义拦截", "点击添加",
                v -> domainDialog(false));
        divider(ruleCard);
        valueAllow = addRow(ruleCard, "白名单", "优先级最高",
                v -> domainDialog(true));
        divider(ruleCard);
        valueUrl = addRow(ruleCard, "URL 规则", "支持 * 通配",
                v -> urlRuleDialog());
        divider(ruleCard);
        valueUpstream = addRow(ruleCard, "上游 DNS", "",
                v -> upstreamDialog());
        divider(ruleCard);
        valueDoh = addRow(ruleCard, "上游加密（DoH）", "",
                v -> dohDialog());
        divider(ruleCard);
        valueMode = addRow(ruleCard, "拦截方式", "",
                v -> modeDialog());

        adv.addView(section("HTTPS 解密"));
        LinearLayout mitmCard = card();
        adv.addView(mitmCard);
        mitmSwitch = new Switch(this);
        mitmSwitch.setChecked(Config.mitm(this));
        mitmSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Config.setMitm(this, isChecked);
            refresh();
            if (DnsVpnService.isRunning()) {
                restartService();
                toast(isChecked ? "已开启，正在重建通道" : "已关闭解密");
            }
        });
        addSwitchRow(mitmCard, "解密 HTTPS", "只解密下列域名", mitmSwitch);
        divider(mitmCard);
        valueDecrypt = addRow(mitmCard, "解密域名", "点击添加",
                v -> decryptDialog());
        divider(mitmCard);
        // 四个计数行并成一个入口：这一页的行数已经影响找东西了
        valueMitmStats = addRow(mitmCard, "解密统计",
                "连接 · 协议收敛 · 直通 · 拦截计数", v -> mitmStatsDialog());
        divider(mitmCard);
        addRow(mitmCard, "导出 CA 证书", "装到系统证书区",
                v -> exportCa());

        divider(mitmCard);
        Switch dbgSwitch = new Switch(this);
        dbgSwitch.setChecked(Config.debugAll(this));
        dbgSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Config.setDebugAll(this, isChecked);
            refresh();
            if (DnsVpnService.isRunning()) {
                restartService();
                toast(isChecked ? "排查模式已开启，正在解密全部域名" : "排查模式已关闭");
            }
        });
        addSwitchRow(mitmCard, "排查模式", "临时解密全部域名定位漏拦", dbgSwitch);
        divider(mitmCard);
        addRow(mitmCard, "证书", "CA 状态与安装指引",
                v -> startActivity(new android.content.Intent(this, CertActivity.class)));
        Switch rwSwitch = new Switch(this);
        rwSwitch.setChecked(Config.rewrite(this));
        rwSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Config.setRewrite(this, isChecked);
            toast(isChecked ? "内容重写已开启" : "内容重写已关闭，配置已保留");
        });
        addSwitchRow(mitmCard, "内容重写", "按规则改写解密域名的响应", rwSwitch);
        addRow(mitmCard, "重写规则", "每行：域名|查找|替换", v -> editRewriteRules());

        adv.addView(section("解密事件"));
        LinearLayout mitmLogCard = card();
        adv.addView(mitmLogCard);
        mitmLogBox = new LinearLayout(this);
        mitmLogBox.setOrientation(LinearLayout.VERTICAL);
        mitmLogBox.setPadding(dp(16), dp(6), dp(16), dp(10));
        mitmLogCard.addView(mitmLogBox);

        adv.addView(section("请求记录"));
        LinearLayout reqCard = card();
        adv.addView(reqCard);
        reqBox = new LinearLayout(this);
        reqBox.setOrientation(LinearLayout.VERTICAL);
        reqBox.setPadding(dp(16), dp(6), dp(16), dp(10));
        reqCard.addView(reqBox);

        adv.addView(section("应用控制"));
        LinearLayout appCard = card();
        adv.addView(appCard);
        valueBypass = addRow(appCard, "排除应用", "不经过过滤",
                v -> bypassDialog());
        divider(appCard);
        addRow(appCard, "导出诊断", "文本，便于排障", v -> exportDiag());
    }

    /* ---------------- 界面搭建 ---------------- */

    /** 顶部返回栏。固定在滚动容器之外，滑到最底部也还在。 */
    private void buildTopBar(LinearLayout page) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BG);
        bar.setPadding(dp(16), dp(16), dp(16), dp(10));

        TextView back = tv("‹", 25f, FG, false);
        back.setGravity(Gravity.CENTER);
        back.setBackground(ui.rippleCard(CARD2, 12, BORDER));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            finish();
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), 0, 0, 0);
        box.addView(tv("设置", 19f, FG, true));
        bar.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 右上角公益入口：宝宝回家——「♥→宝宝回家」引导样式，距赞赏一掌远
        TextView baby = tv("♥→ 宝宝回家", 13f, FG, false);
        baby.setGravity(Gravity.CENTER);
        baby.setPadding(dp(10), 0, dp(10), 0);
        baby.setBackground(ui.rippleCard(CARD2, 12, BORDER));
        baby.setClickable(true);
        baby.setFocusable(true);
        baby.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            showBabyHome();
        });
        // 按钮自占权重+右外边距：与赞赏(最右)之间留出呼吸距离
        LinearLayout.LayoutParams babyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        babyLp.setMarginEnd(dp(14));
        bar.addView(baby, babyLp);

        // 右上角赞赏入口：收款码随 APK 签名分发，点按弹本地二维码
        TextView donate = tv("赞赏", 14f, FG, false);
        donate.setGravity(Gravity.CENTER);
        donate.setBackground(ui.rippleCard(CARD2, 12, BORDER));
        donate.setClickable(true);
        donate.setFocusable(true);
        donate.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            showDonateQr();
        });
        bar.addView(donate, new LinearLayout.LayoutParams(dp(64), dp(42)));

        page.addView(bar);

        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        page.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
    }

    /** 重写规则编辑：多行文本，每行「域名|查找|替换」。 */
    private void editRewriteRules() {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setText(Config.rewriteRules(this));
        et.setTextSize(12f);
        et.setMinLines(6);
        et.setGravity(android.view.Gravity.TOP);
        Ui.dialog(this)
                .setTitle("重写规则")
                .setMessage("每行：域名|查找|替换（替换留空即删除）")
                .setView(et)
                .setPositiveButton("保存", (d, w) ->
                        Config.setRewriteRules(this, et.getText().toString()))
                .setNegativeButton("取消", null)
                .show();
    }

    private TextView section(String text) {
        return ui.section(text);
    }

    private LinearLayout card() {
        return ui.card();
    }

    /**
     * 模式三卡片：标准 / 轻量 / 只跳过。
     *
     * 为什么不做“关掉软件也能拦”：VPN 拦截靠常驻 VpnService，进程死了隧道就断，
     * 这是系统约束，不是实现懒。能省的只有“跑多少活”——所以给轻量与只跳过两档。
     *
     * 早先是三行带 ●/○ 前缀的列表行——用户反馈要「模式卡片」：三张独立卡片竖排，
     * 选中的描边高亮 + 勾标，能力边界整句写在卡片副标题里。点卡片即切换，行为
     * 与旧版完全一致（Power.needsVpn 决定停/重建 VPN）。
     */
    private void buildPower(LinearLayout root) {
        root.addView(section("模式"));
        final String cur = Power.current(this);
        for (int i = 0; i < Power.MODES.length; i++) {
            final String m = Power.MODES[i];
            boolean on = m.equals(cur);
            LinearLayout card = card();
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setMinimumHeight(dp(72));
            card.setPadding(dp(16), dp(14), dp(14), dp(14));
            card.setClickable(true);
            card.setFocusable(true);
            // 选中：绿色描边 + 微绿底；未选：普通卡。描边宽度差异用底色补齐，避免选中卡变高
            card.setBackground(ui.rippleCard(on ? 0xFF16241D : CARD, 18,
                    on ? ACCENT : BORDER));
            if (i > 0) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(10);
                root.addView(card, lp);
            } else {
                root.addView(card);
            }

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView title = tv(Power.title(m), 14.5f, on ? ACCENT : FG, on);
            col.addView(title);
            TextView detail = tv(Power.detail(m), 11.5f, MUTED, false);
            detail.setPadding(0, dp(4), 0, 0);
            col.addView(detail);
            card.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView mark = tv(on ? "✓" : "", 18f, ACCENT, true);
            card.addView(mark);

            card.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                if (m.equals(Power.current(this))) return;
                Config.setPowerMode(this, m);
                Diag.push("省电模式切换：" + Power.title(m), "info");
                if (!Power.needsVpn(m)) {
                    if (DnsVpnService.isRunning()) {
                        Intent stop = new Intent(this, DnsVpnService.class);
                        stop.setAction(DnsVpnService.ACTION_STOP);
                        startService(stop);
                    }
                    toast("已切到「只跳过」：VPN 已关，DNS 拦截停用");
                } else if (DnsVpnService.isRunning()) {
                    DnsVpnService.restartSoon(this);
                    toast("正在按「" + Power.title(m) + "」重建通道");
                } else {
                    // 选了需要 VPN 的模式但通道没跑：直接拉起，省得用户再去首页开一次
                    Intent start = new Intent(this, DnsVpnService.class);
                    start.setAction(DnsVpnService.ACTION_START);
                    try {
                        startService(start);
                        toast("已选「" + Power.title(m) + "」：通道已启动");
                    } catch (Exception e) {
                        toast("已选：" + Power.title(m));
                    }
                }
                recreate();
            });
        }
    }

    private TextView addRow(LinearLayout parent, String title, String sub,
                            View.OnClickListener onClick) {
        return ui.addRow(parent, title, sub, onClick);
    }

    private void addSwitchRow(LinearLayout parent, String title, String sub, Switch sw) {
        ui.addSwitchRow(parent, title, sub, sw);
    }

    private void divider(LinearLayout parent) {
        ui.divider(parent);
    }

    private TextView pill(String text) {
        TextView t = ui.pill(text);
        t.setBackground(ui.ripplePill(CARD2, 12, BORDER));
        t.setOnClickListener(v -> v.performHapticFeedback(
                android.view.HapticFeedbackConstants.VIRTUAL_KEY));
        return t;
    }

    /* ---------------- 服务启停 ---------------- */

    private void startServiceNow() {
        Intent i = new Intent(this, DnsVpnService.class);
        i.setAction(DnsVpnService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            toast("启动失败：" + e);
        }
        handler.postDelayed(this::refresh, 900);
    }

    /**
     * 重建通道：交给 DnsVpnService.restartSoon，用 application context 的 handler。
     *
     * 原来这里是 Activity.handler.postDelayed(this::startServiceNow, 900)——设置页点完
     * 常常紧跟着 recreate()，Activity 的 handler 一销毁，那个延时任务就废了，
     * 实测表现是「模式切了、隧道没了」。挪进 restartSoon 之后与界面生命周期无关。
     */
    private void restartService() {
        DnsVpnService.restartSoon(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SAVE_CA && resultCode == RESULT_OK && data != null) {
            saveCaTo(data.getData());
        }
        if (requestCode == REQ_SAVE_DIAG && resultCode == RESULT_OK && data != null) {
            saveDiagTo(data.getData());
        }
    }

    private void saveCaTo(Uri uri) {
        // SAF 的目标可能是云文档提供器，openOutputStream/write 是网络调用，不能占主线程
        final byte[] pem;
        try {
            pem = CaStore.caPem().getBytes("US-ASCII");
        } catch (Exception e) {
            toast("证书未就绪");
            return;
        }
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            java.io.OutputStream os = null;
            try {
                os = app.getContentResolver().openOutputStream(uri);
                if (os == null) {
                    toast("写入失败");
                    return;
                }
                os.write(pem);
                toast("已保存，去「设置 → 安全 → 加密与凭据」安装");
            } catch (Exception e) {
                toast("保存失败：" + e.getMessage());
            } finally {
                if (os != null) {
                    try {
                        os.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "ggbot-save-ca").start();
    }

    /* ---------------- 刷新 ---------------- */

    private void refresh() {
        int blocks = Config.blocks(this).size();
        int allow = Config.allow(this).size();
        int bypass = Config.bypass(this).size();

        // 顺带把 $important / $redirect 的条数摆出来：这两类规则有没有真的从订阅源里收到，
        // 光看总条数是看不出来的
        String extra = "";
        if (Rules.importantSize() > 0) extra += " · 强制 " + Rules.importantSize();
        if (Rules.redirectSize() > 0) extra += " · 替换 " + Rules.redirectSize();
        valueRules.setText(Rules.ready() ? fmt(Rules.baseSize()) + " 条" + extra : "载入中…");
        valueBlocks.setText(blocks == 0 ? "未添加" : blocks + " 条");
        valueAllow.setText(allow == 0 ? "未添加" : allow + " 条");
        valueUpstream.setText(Config.upstream());
        valueDoh.setText(Config.doh() ? Config.dohUrl() : "明文 UDP");
        valueMode.setText(Config.mode() == Config.MODE_NULL ? "返回 0.0.0.0" : "NXDOMAIN");
        valueBypass.setText(bypass == 0 ? "全部应用生效" : "已排除 " + bypass + " 个");

        if (valueUrl != null) {
            int ub = Config.urlBlocks(this).size();
            int ua = Config.urlAllow(this).size();
            int fu = Rules.feedUrlSize();
            String own = ub + ua == 0 ? "内置 " + Rules.urlRuleSize() + " 条"
                    : "拦截 " + ub + " · 放行 " + ua;
            valueUrl.setText(fu == 0 ? own : own + " · 订阅 " + fmt(fu));
        }

        if (valueDecrypt != null) {
            int dec = Config.decrypt(this).size();
            valueDecrypt.setText(dec == 0 ? "未配置" : dec + " 条");
        }
        updatingSkip = true;
        skipSwitch.setChecked(skipServiceEnabled());
        updatingSkip = false;
        if (valueA11y != null) {
            valueA11y.setText(A11y.stateText(this));
        }
        if (valueBattery != null) {
            boolean ok = Battery.ignoring(this);
            valueBattery.setText(ok ? Battery.label(true) : "受限，点这里看怎么改");
        }

        if (valueMitmStats != null) {
            valueMitmStats.setText(TcpProxy.activeCount() + " 活跃 · 拦截 "
                    + TcpProxy.blockedCount() + " 条");
        }

        if (mitmLogBox != null) {
            String[] events = Diag.recent();
            String mkey = events.length == 0 ? "" : events[0] + "#" + events.length;
            if (!mkey.equals(lastMitmKey)) {
                lastMitmKey = mkey;
                mitmLogBox.removeAllViews();
                if (events.length == 0) {
                    TextView t = tv("暂无解密记录", 11.5f, DIM, false);
                    t.setGravity(Gravity.CENTER);
                    t.setPadding(0, dp(20), 0, dp(20));
                    mitmLogBox.addView(t);
                } else {
                    int n = Math.min(events.length, 8);
                    for (int i = 0; i < n; i++) {
                        TextView t = tv(events[i], 12f, FG, false);
                        t.setPadding(0, dp(5), 0, dp(5));
                        mitmLogBox.addView(t);
                    }
                }
            }
        }

        if (reqBox != null) {
            Events.Event[] reqs = Events.recent();
            String rkey = reqs.length == 0 ? "" : reqs[0].time + "#" + reqs.length;
            if (!rkey.equals(lastReqKey)) {
                lastReqKey = rkey;
                reqBox.removeAllViews();
                if (reqs.length == 0) {
                    TextView t = tv("暂无请求记录 · 开启解密后这里会出现每一条请求", 11.5f, DIM, false);
                    t.setGravity(Gravity.CENTER);
                    t.setPadding(0, dp(20), 0, dp(20));
                    reqBox.addView(t);
                } else {
                    int n = Math.min(reqs.length, 8);
                    for (int i = 0; i < n; i++) {
                        reqBox.addView(reqRow(reqs[i]));
                    }
                }
            }
        }
    }

    /**
     * 一条 DNS 拦截记录。
     *
     * 和 HTTP 请求记录一样可以点开处理——这一层才是绝大多数人唯一生效的那层。
     * 早先这里只是一行文本，用户看到某个域名被误拦，只能退出界面去白名单里手打，
     * 等于没有出口，最后只能关掉整个防护。
     */
    private boolean skipServiceEnabled() {
        try {
            String s = android.provider.Settings.Secure.getString(
                    getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains("SkipService");
        } catch (Exception e) {
            return false;
        }
    }

    private View reqRow(final Events.Event e) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        row.setClickable(true);
        row.setBackgroundColor(0x00000000);

        View bar = new View(this);
        bar.setBackground(round(e.blocked ? ACCENT : DIM, 2, 0));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(3), dp(26));
        blp.rightMargin = dp(10);
        row.addView(bar, blp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView main = tv(e.url(), 12f, FG, false);
        main.setSingleLine(true);
        main.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(main);

        String sub = e.timeText() + " · " + (e.blocked ? "已拦截" : "放行")
                + (e.rule == null ? "" : " · " + e.rule);
        TextView meta = tv(sub, 11.5f, DIM, false);
        meta.setPadding(0, dp(2), 0, 0);
        col.addView(meta);

        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(v -> eventDialog(e));
        return row;
    }

    /** 从一条请求记录出发：放行、拦截、加入解密，或者复制地址。 */
    private void eventDialog(final Events.Event e) {
        String detail = (e.blocked ? "这条请求被拦截" : "这条请求被放行")
                + (e.rule == null ? "" : "\n依据：" + e.rule)
                + "\n" + e.url();

        final boolean decrypted = Config.decrypt(this).contains(e.host);

        actionSheet(e.timeText() + "　" + (e.blocked ? "已拦截" : "放行"), detail,
                new String[]{
                        "放行这个地址",
                        "拦截这个地址",
                        decrypted ? "已在解密名单中" : "把这个域名加入解密",
                        "复制地址",
                }, i -> {
                    if (i == 0) addUrlRuleQuick(true, e.host + e.path);
                    else if (i == 1) addUrlRuleQuick(false, e.host + e.path);
                    else if (i == 2) {
                        if (decrypted) {
                            toast("已在解密名单中");
                        } else {
                            Config.addDecrypt(this, e.host);
                            refresh();
                            toast("已加入解密名单");
                        }
                    } else {
                        copy(e.url());
                    }
                });
    }

    private interface Pick {
        void on(int index);
    }

    /**
     * 详情 + 操作列表的自绘面板。
     * 框架的 AlertDialog 同时给 message 和 items 时会把列表吞掉，所以这里自己拼。
     */
    private void actionSheet(String title, String detail, String[] actions, final Pick pick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(2), dp(22), dp(6));

        if (detail != null && !detail.isEmpty()) {
            TextView d = tv(detail, 12.5f, MUTED, false);
            d.setPadding(0, dp(6), 0, dp(10));
            box.addView(d);
        }

        final AlertDialog[] ref = new AlertDialog[1];
        for (int i = 0; i < actions.length; i++) {
            final int idx = i;
            TextView a = tv(actions[i], 14.5f, ACCENT, false);
            a.setPadding(dp(2), dp(15), dp(2), dp(15));
            a.setClickable(true);
            a.setOnClickListener(v -> {
                if (ref[0] != null) ref[0].dismiss();
                pick.on(idx);
            });
            box.addView(a);

            if (i < actions.length - 1) {
                View line = new View(this);
                line.setBackgroundColor(BORDER);
                box.addView(line, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
        }

        ref[0] = Ui.dialog(this)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("关闭", null)
                .show();
    }

    private void addUrlRuleQuick(boolean allow, String rule) {
        String r = UrlRule.normalize(rule);
        if (r == null) {
            toast("这条地址无法转成规则");
            return;
        }
        Config.addUrlRules(this, allow, r);
        Rules.reloadUrl(this);
        refresh();
        toast((allow ? "已放行 " : "已拦截 ") + r);
    }

    private void copy(String text) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("GGBot", text));
            toast("已复制");
        } catch (Exception ignored) {
        }
    }

    private static String fmt(int v) {
        String s = String.valueOf(v);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            sb.append(s.charAt(i));
            if (++count % 3 == 0 && i > 0) sb.append(',');
        }
        return sb.reverse().toString();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Config.init(this);
        Rules.loadAsync(getApplicationContext(), () -> handler.post(this::refresh));
        refresh();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    /* ---------------- 对话框 ---------------- */

    private void domainDialog(final boolean allow) {
        final EditText et = new EditText(this);
        et.setHint(allow ? "example.com" : "ads.example.com");
        et.setHintTextColor(DIM);
        et.setTextColor(FG);
        et.setTextSize(13f);
        et.setMinLines(3);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(et);

        Ui.dialog(this)
                .setTitle(allow ? "添加白名单域名" : "添加拦截域名")
                .setView(wrap)
                .setPositiveButton("保存", (d, w) -> {
                    int added = Config.addDomains(this, allow, et.getText().toString());
                    // 首次载入内置库要一到两秒，不能放主线程；载完再刷新计数
                    Rules.loadAsync(getApplicationContext(), () ->
                            handler.post(this::refresh));
                    refresh();
                    toast(added > 0 ? "已添加 " + added + " 条" : "没有识别到有效域名");
                })
                .setNeutralButton("查看列表", (d, w) -> domainListDialog(allow))
                .setNegativeButton("取消", null)
                .show();
    }

    private void domainListDialog(final boolean allow) {
        final List<String> list = new ArrayList<>(allow ? Config.allow(this) : Config.blocks(this));
        Collections.sort(list);
        if (list.isEmpty()) {
            toast("列表还是空的");
            return;
        }
        Ui.dialog(this)
                .setTitle((allow ? "白名单 " : "自定义拦截 ") + list.size() + " 条")
                .setItems(list.toArray(new String[0]), (d, which) -> confirmRemove(allow, list.get(which)))
                .setPositiveButton("关闭", null)
                .show();
    }

    private void confirmRemove(final boolean allow, final String domain) {
        Ui.dialog(this)
                .setTitle("删除规则")
                .setMessage("从" + (allow ? "白名单" : "拦截列表") + "移除 " + domain + "？")
                .setPositiveButton("删除", (d, w) -> {
                    Config.removeDomain(this, allow, domain);
                    Rules.loadAsync(getApplicationContext(), () ->
                            handler.post(this::refresh));
                    refresh();
                    toast("已移除 " + domain);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ---------------- 规则订阅 ---------------- */

    private String feedSummary() {
        int n = Feeds.enabledCount(this);
        if (n == 0) return "未启用";
        int total = Feeds.totalCount(this);
        if (total == 0) return n + " 个 · 待下载";
        return n + " 个 · " + fmt(total) + " 条";
    }
    private void feedDialog() {
        final List<Feeds.Source> list = Feeds.list(this);
        if (list.isEmpty()) {
            addFeedDialog();
            return;
        }
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Feeds.Source s = list.get(i);
            items[i] = (s.enabled ? "● " : "○ ") + s.name + "　" + s.updatedText();
        }
        actionSheet("规则订阅",
                "● 启用　○ 停用。点一行可单独更新、启停或删除。\n"
                        + "订阅只从这些地址下载，解析与保存在应用内完成。",
                items, i -> sourceActions(list.get(i)));
    }

    private void sourceActions(final Feeds.Source s) {
        String detail = s.url + "\n\n" + s.updatedText()
                + (s.note == null || s.note.isEmpty() ? "" : "\n上次错误：" + s.note);
        actionSheet(s.name, detail,
                new String[]{
                        "立即更新",
                        s.enabled ? "停用这个源" : "启用这个源",
                        "删除这个源",
                }, i -> {
                    if (i == 0) {
                        updateFeed(s, true);
                    } else if (i == 1) {
                        Feeds.setEnabled(this, s.id, !s.enabled);
                        reloadRulesAsync();
                        refresh();
                        toast((s.enabled ? "已停用 " : "已启用 ") + s.name);
                    } else {
                        confirmRemoveFeed(s);
                    }
                });
    }

    private void confirmRemoveFeed(final Feeds.Source s) {
        Ui.dialog(this)
                .setTitle("删除订阅源")
                .setMessage("同时删除「" + s.name + "」已下载的规则？\n" + s.url)
                .setPositiveButton("删除", (d, w) -> {
                    Feeds.remove(this, s.id);
                    reloadRulesAsync();
                    refresh();
                    toast("已删除 " + s.name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void addFeedDialog() {
        final EditText url = input("https://example.com/hosts.txt", 2);
        final EditText name = input("留空就用域名当名字", 1);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), 0);
        box.addView(url);
        TextView gap = tv("给这个源起个名字（可选）", 11f, MUTED, false);
        gap.setPadding(0, dp(12), 0, dp(2));
        box.addView(gap);
        box.addView(name);

        Ui.dialog(this)
                .setTitle("添加订阅源")
                .setView(box)
                .setPositiveButton("添加", (d, w) -> {
                    String u = url.getText().toString().trim();
                    if (u.isEmpty()) {
                        toast("请填写地址");
                        return;
                    }
                    Feeds.Source s = Feeds.add(this, u, name.getText().toString());
                    if (s == null) {
                        toast("这个地址已经订阅过了");
                        return;
                    }
                    if (Feeds.insecure(u)) {
                        toast("明文订阅：规则可被链路上的人改写，建议改用 https");
                        Diag.push("添加了明文(http)订阅源: " + u, "warn");
                    }
                    refresh();
                    updateFeed(s, true);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateFeed(final Feeds.Source s, final boolean notify) {
        if (updatingFeeds) {
            toast("正在更新中，请稍候");
            return;
        }
        updatingFeeds = true;
        if (valueFeedState != null) valueFeedState.setText("更新中…");
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String err;
            try {
                err = Feeds.update(app, s);
                Rules.reloadAll(app);
            } catch (Throwable t) {
                err = t.getClass().getSimpleName();
            }
            final String ferr = err;
            handler.post(() -> {
                updatingFeeds = false;
                if (isFinishing() || isDestroyed()) return;   // 界面没了就不能再碰 UI
                if (valueFeedState != null) valueFeedState.setText("逐个源重新下载并解析");
                refresh();
                if (notify) {
                    toast(ferr == null
                            ? s.name + " 已更新 " + s.count + " 条"
                            : s.name + " 更新失败：" + ferr);
                }
            });
        }, "ggbot-feed-one").start();
    }

    private void updateFeeds() {
        if (updatingFeeds) {
            toast("正在更新中，请稍候");
            return;
        }
        if (Feeds.enabledCount(this) == 0) {
            toast("还没有启用的订阅源");
            return;
        }
        updatingFeeds = true;
        if (valueFeedState != null) valueFeedState.setText("更新中…");
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            final StringBuilder bad = new StringBuilder();
            int okCount;
            try {
                okCount = Feeds.updateAll(app, (src, err) -> {
                    if (err != null) {
                        if (bad.length() > 0) bad.append('\n');
                        bad.append(src.name).append("：").append(err);
                    }
                });
            } catch (Throwable t) {
                okCount = -1;
            }
            final int fok = okCount;
            handler.post(() -> {
                updatingFeeds = false;
                // 用户在下载期间退出是常态：Activity 已销毁时弹 AlertDialog 必然
                // 抛 BadTokenException 崩掉整个应用，这里必须先拦
                if (isFinishing() || isDestroyed()) return;
                if (valueFeedState != null) valueFeedState.setText("逐个源重新下载并解析");
                refresh();
                if (bad.length() == 0) {
                    toast("更新完成，拦截库共 " + fmt(Rules.baseSize()) + " 条");
                } else {
                    Ui.dialog(this)
                            .setTitle(Math.max(fok, 0) + " 个源已更新，其余失败")
                            .setMessage(bad.toString())
                            .setPositiveButton("好", null)
                            .show();
                }
            });
        }, "ggbot-feed-update").start();
    }

    /** 重建规则库要排序三十万条，不能放在主线程。 */
    private void reloadRulesAsync() {
        new Thread(() -> {
            Rules.reloadAll(this);
            handler.post(this::refresh);
        }, "ggbot-rules-reload").start();
    }

    private EditText input(String hint, int lines) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(DIM);
        et.setTextColor(FG);
        et.setTextSize(13f);
        et.setSingleLine(lines <= 1);
        et.setMinLines(lines);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return et;
    }

    private void urlRuleDialog() {
        int block = Config.urlBlocks(this).size();
        int allow = Config.urlAllow(this).size();
        actionSheet("URL 规则",
                "域名 + 路径，支持 * 通配，只在开启了 HTTPS 解密的域名上生效。\n"
                        + "例：example.com/ad/*　*.example.com/ads/*　/cpro/",
                new String[]{
                        "添加拦截规则（" + block + " 条）",
                        "添加放行规则（" + allow + " 条）",
                        "查看拦截规则",
                        "查看放行规则",
                }, i -> {
                    if (i == 0) urlEditDialog(false);
                    else if (i == 1) urlEditDialog(true);
                    else urlListDialog(i == 3);
                });
    }

    private void urlEditDialog(final boolean allow) {
        final EditText et = new EditText(this);
        et.setHint(allow ? "example.com/ad/*" : "example.com/ad/*");
        et.setHintTextColor(DIM);
        et.setTextColor(FG);
        et.setTextSize(13f);
        et.setMinLines(3);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(et);

        Ui.dialog(this)
                .setTitle(allow ? "添加放行规则" : "添加拦截规则")
                .setView(wrap)
                .setPositiveButton("保存", (d, w) -> {
                    int added = Config.addUrlRules(this, allow, et.getText().toString());
                    Rules.reloadUrl(this);
                    refresh();
                    if (added == 0) {
                        toast("没有识别到有效规则");
                        return;
                    }
                    // 规则只对解密名单里的域名生效，没开解密的要提醒一句
                    int miss = 0;
                    Set<String> dec = Config.decrypt(this);
                    for (String r : allow ? Config.urlAllow(this) : Config.urlBlocks(this)) {
                        String dom = UrlRule.domainOf(r);
                        if (dom != null && !dec.contains(dom)) miss++;
                    }
                    toast(miss == 0 ? "已添加 " + added + " 条"
                            : "已添加 " + added + " 条，" + miss + " 条待开启解密");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void urlListDialog(final boolean allow) {
        final List<String> list = new ArrayList<>(allow ? Config.urlAllow(this) : Config.urlBlocks(this));
        Collections.sort(list);
        if (list.isEmpty()) {
            toast("列表还是空的");
            return;
        }
        Ui.dialog(this)
                .setTitle((allow ? "放行规则 " : "拦截规则 ") + list.size() + " 条")
                .setItems(list.toArray(new String[0]), (d, which) -> confirmRemoveUrl(allow, list.get(which)))
                .setPositiveButton("关闭", null)
                .show();
    }

    private void confirmRemoveUrl(final boolean allow, final String rule) {
        Ui.dialog(this)
                .setTitle("删除规则")
                .setMessage("从" + (allow ? "放行" : "拦截") + "规则中移除\n" + rule + "？")
                .setPositiveButton("删除", (d, w) -> {
                    Config.removeUrlRule(this, allow, rule);
                    Rules.reloadUrl(this);
                    refresh();
                    toast("已移除");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void decryptDialog() {
        final EditText et = new EditText(this);
        et.setHint("api.example.com");
        et.setHintTextColor(DIM);
        et.setTextColor(FG);
        et.setTextSize(13f);
        et.setMinLines(3);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(et);

        Ui.dialog(this)
                .setTitle("添加解密域名")
                .setView(wrap)
                .setPositiveButton("保存", (d, w) -> {
                    int added = Config.addDecrypt(this, et.getText().toString());
                    refresh();
                    if (DnsVpnService.isRunning()) restartService();
                    toast(added > 0 ? "已添加 " + added + " 条，正在重建通道" : "没有识别到有效域名");
                })
                .setNeutralButton("查看列表", (d, w) -> decryptListDialog())
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 解密统计：一行入口，点开看全量计数，比四行常驻省一大截。
     * 同时允许「清空直通名单」：用户可能只是当时没装 CA，装好之后希望立刻重试，而不是等十分钟。
     */
    private void mitmStatsDialog() {
        long quic = DnsVpnService.quicDenied();
        long v6 = DnsVpnService.ipv6Suppressed();
        int bp = MitmBypass.size();
        long blk = TcpProxy.blockedCount();
        String detail = "隧道连接　" + TcpProxy.activeCount() + " 活跃 / " + TcpProxy.opened()
                + " 累计 / " + TcpProxy.decrypted() + " 已解密\n"
                + "协议收敛　QUIC 回落 " + quic + " · IPv6 抑制 " + v6 + "\n"
                + "HTTPS 拦截　" + (blk == 0 ? "尚未拦截" : "已拦截 " + blk + " 条请求")
                + " · URL 规则 " + Rules.urlRuleSize() + " 条\n"
                + "自动直通　" + (bp == 0 ? "无暂停解密的域名" : bp + " 个域名");
        actionSheet("解密统计", detail,
                new String[]{"直通名单"}, i -> mitmBypassDialog());
    }

    private void mitmBypassDialog() {
        final List<String> list = MitmBypass.active();
        if (list.isEmpty()) {
            Ui.dialog(this)
                    .setTitle("自动直通")
                    .setMessage("目前没有域名被跳过。\n\n"
                            + "只有客户端在握手阶段就断开，并且同一域名十分钟内失败两次，"
                            + "才会进这个名单。通常是应用做了证书固定，"
                            + "或者是系统里还没装 GGBot 的 CA。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String h : list) sb.append(h).append('\n');
        Ui.dialog(this)
                .setTitle("自动直通 " + list.size() + " 个")
                .setMessage(sb.toString().trim())
                .setPositiveButton("清空重试", (d, w) -> {
                    MitmBypass.clear();
                    refresh();
                    toast("已清空，下次连接会重新尝试解密");
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void decryptListDialog() {
        final List<String> list = new ArrayList<>(Config.decrypt(this));
        Collections.sort(list);
        if (list.isEmpty()) {
            toast("列表还是空的");
            return;
        }
        Ui.dialog(this)
                .setTitle("解密域名 " + list.size() + " 条")
                .setItems(list.toArray(new String[0]), (d, which) -> confirmRemoveDecrypt(list.get(which)))
                .setPositiveButton("关闭", null)
                .show();
    }

    private void confirmRemoveDecrypt(final String domain) {
        Ui.dialog(this)
                .setTitle("移除解密域名")
                .setMessage("不再解密 " + domain + "？")
                .setPositiveButton("移除", (d, w) -> {
                    Config.removeDecrypt(this, domain);
                    refresh();
                    if (DnsVpnService.isRunning()) restartService();
                    toast("已移除 " + domain);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void exportCa() {
        if (!CaStore.ready()) {
            toast("证书还没生成，先开启一次防护");
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/x-x509-ca-cert");
        i.putExtra(Intent.EXTRA_TITLE, "ggbot-ca.crt");
        try {
            startActivityForResult(i, REQ_SAVE_CA);
        } catch (Exception e) {
            toast("没有可用的文件选择器");
        }
    }

    private void upstreamDialog() {        String cur = Config.upstream();
        int idx = 0;
        for (int i = 0; i < Config.UPSTREAMS.length; i++) {
            if (Config.UPSTREAMS[i].equals(cur)) idx = i;
        }
        Ui.dialog(this)
                .setTitle("上游 DNS")
                .setSingleChoiceItems(Config.UPSTREAM_LABELS, idx, (d, which) -> {
                    Config.setUpstream(this, Config.UPSTREAMS[which]);
                    d.dismiss();
                    refresh();
                    toast("上游已切换为 " + Config.UPSTREAMS[which]);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 导出诊断：主线程只拼文本，写文件放后台线程（SAF 目标可能是云文档）。 */
    private void exportDiag() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, Export.fileName(System.currentTimeMillis()));
        try {
            startActivityForResult(i, REQ_SAVE_DIAG);
        } catch (Exception e) {
            toast("没有可用的文件选择器");
        }
    }

    /** 设置页底部脚注：只放版本号。 */
    private String appVersion() {
        try {
            return "GGBot v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "GGBot";
        }
    }

    /** 当前现场：防护状态、统计、上游、暂停、无障碍、电池、MITM，加诊断事件与拦截记录。 */
    private String diagText() {
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        DnsBlock[] logs = DnsVpnService.recent();
        int n = logs == null ? 0 : Math.min(logs.length, 300);
        String[][] qs = new String[n][3];
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
        for (int i = 0; i < n; i++) {
            DnsBlock d = logs[n - 1 - i];
            qs[i][0] = f.format(new java.util.Date(d.time));
            qs[i][1] = d.host;
            qs[i][2] = d.reason != null ? d.reason
                    : (d.kind == DnsBlock.KIND_CNAME ? "CNAME 链拦截" : "拦截");
        }
        String[] notes = new String[]{
                "防护：" + (DnsVpnService.isRunning() ? "运行中" : "已停止"),
                "查询/拦截：" + DnsVpnService.queries() + "/" + DnsVpnService.blocked()
                        + "，CNAME " + DnsVpnService.cnameBlocked(),
                "上游：" + Config.upstream() + (Config.doh() ? " · DoH " + Config.dohUrl() : " · 明文 UDP"),
                "暂停：" + Pause.text(System.currentTimeMillis(), Config.pausedUntil(this)),
                "无障碍：" + (A11y.contains(A11y.currentList(this)) ? "已开启" : "未开启")
                        + (A11y.canWrite(this) ? "（可自动写回）" : "（未授权写回）"),
                "电池优化：" + Battery.label(Battery.ignoring(this)),
                "MITM 活跃/拦截：" + TcpProxy.activeCount() + "/" + TcpProxy.blockedCount()
        };
        return Export.build(System.currentTimeMillis(), ver, "DNS 拦截 + MITM 网关",
                Diag.recent(), qs, notes);
    }

    private void saveDiagTo(Uri uri) {
        final String text = diagText();
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            java.io.OutputStream os = null;
            try {
                os = app.getContentResolver().openOutputStream(uri);
                if (os == null) {
                    toast("写入失败");
                    return;
                }
                os.write(text.getBytes("UTF-8"));
                toast("诊断已导出");
            } catch (Exception e) {
                toast("导出失败：" + e.getMessage());
            } finally {
                if (os != null) {
                    try {
                        os.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "ggbot-diag-export").start();
    }

    private void batteryDialog() {
        boolean ok = Battery.ignoring(this);
        StringBuilder sb = new StringBuilder();
        sb.append(ok ? "当前：已加入白名单。\n\n" : "当前：仍受电池优化限制，息屏后可能被系统冻结，过滤会静默中断。\n\n");
        if (ok) {
            sb.append("荣耀/华为还有一层：系统的后台策略会单独管，建议再走一遍下面的第 4 步。\n\n");
        }
        sb.append("第 1 步：点「打开电池优化设置」，系统会跳到电池优化列表。\n");
        sb.append("第 2 步：列表上方切到「未优化」或「所有应用」，找到 GGBot。\n");
        sb.append("第 3 步：点 GGBot，选「不限制」/「允许后台活动」。\n");
        sb.append("第 4 步（荣耀/华为等）：点「应用详情」→「耗电管理」或「应用启动管理」→ ");
        sb.append("把「自动管理」关掉，勾上自启动与后台活动。\n\n");
        sb.append("做完回到这里，状态行会变成「已加入白名单」。");
        Ui.dialog(this)
                .setTitle("电池优化 · 怎么改")
                .setMessage(sb.toString())
                .setPositiveButton("打开电池优化设置", (d, w) -> openBatterySettings())
                .setNeutralButton("应用详情", (d, w) -> {
                    try {
                        startActivity(Battery.appDetails(this));
                    } catch (Exception ignored) {
                        toast("打不开应用详情页");
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 电池优化列表 → 个别机型没有这一页就退回「请求白名单」弹窗 → 再退回应用详情。 */
    private void openBatterySettings() {
        try {
            startActivity(Battery.settingsIntent());
        } catch (Exception e) {
            try {
                startActivity(Battery.requestIntent(this));
            } catch (Exception e2) {
                try {
                    startActivity(Battery.appDetails(this));
                } catch (Exception ignored) {
                    toast("打不开系统设置，请手动到「设置 → 电池」里找 GGBot");
                }
            }
        }
    }

    private void a11yDialog() {
        int st = A11y.state(this);
        int removals = A11y.removals(this);
        String vendor = Vendor.brand();
        StringBuilder sb = new StringBuilder();
        sb.append("状态：").append(A11y.stateText(this)).append("\n\n");
        sb.append("开屏广告要自动点掉，必须打开 GGBot 的无障碍服务。\n\n");
        sb.append("怎么开：点下面「").append(st == A11y.ST_ON ? "查看设置" : "去开启").append("」，");
        sb.append("系统会跳到 GGBot 那一条，把开关拨开即可。\n\n");

        if (st == A11y.ST_REMOVED) {
            sb.append("注意：这个开关是被系统关掉的，不是你没开。");
            if (removals >= 1) {
                sb.append("累计已 ").append(removals).append(" 次。");
            }
            if (!A11y.canWrite(this)) {
                sb.append("本机没有写权限，GGBot 补不回来，只能靠下面的厂商设置从根上解决。");
            } else {
                sb.append("本机已授权写回，会自己补，但被反复摘说明厂商策略仍在拦。");
            }
            sb.append("\n\n");
        }

        if (st != A11y.ST_ON && (st == A11y.ST_REMOVED || Vendor.HONOR.equals(vendor)
                || Vendor.HUAWEI.equals(vendor))) {
            sb.append(Vendor.name(vendor)).append("要把 GGBot 从后台清理里放出来，按这几步：\n");
            for (String line : Vendor.steps(vendor)) {
                sb.append(line).append("\n");
            }
            sb.append("\n点「厂商设置」会依次尝试打开这些页面，打不开会退到应用详情页。\n\n");
        }

        sb.append("说明：Android 不允许应用自己打开无障碍开关，这是系统安全边界；");
        sb.append(A11y.canWrite(this)
                ? "本机已授权写回，被摘掉时 GGBot 会直接补回。"
                : "所以这里只能把你送到开关跟前。");
        sb.append("\n\n拦截（DNS）不依赖无障碍：开关没开时，防广告照样生效。");

        // 没有写权限时，给出两条路：adb 一次 或 装 Shizuku 免电脑
        if (!A11y.canWrite(this)) {
            int szk = ShizukuHeal.stateOf(
                    ShizukuHeal.installed(this),
                    ShizukuHeal.installed(this) && safePingShizuku(),
                    safeShizukuGranted(this));
            sb.append("\n\n═══ 让它被摘掉后自己补回 ═══\n");
            sb.append("两条路选一条，都只需做一次：\n\n");
            sb.append("【A · 装 Shizuku，免电脑】");
            sb.append("当前状态：").append(ShizukuHeal.textOf(szk)).append("\n");
            if (szk == ShizukuHeal.ST_READY) {
                sb.append("已经就绪，什么都不用做了。\n");
            } else if (szk == ShizukuHeal.ST_NONE) {
                sb.append("装好 Shizuku 并用电脑激活一次（激活后就不用再碰电脑），");
                sb.append("回来点「Shizuku 授权」。\n");
            } else if (szk == ShizukuHeal.ST_NOT_GRANTED) {
                sb.append("点下面「Shizuku 授权」，在弹出的对话框里选「始终允许」。\n");
            } else {
                sb.append("先把 Shizuku 打开（它需要先跑起来才能接收请求）。\n");
            }
            sb.append("\n【B · 用电脑跑一条命令】\n");
            sb.append(A11y.GRANT_CMD).append("\n");
            sb.append("（手机开 USB 调试、连电脑，执行一次即可；重装应用后要再来一次）");
        }
        if (st != A11y.ST_ON) {
            sb.append("\n\n").append(Alt.text(Alt.shizukuInstalled(this), A11y.canWrite(this)));
        }

        AlertDialog.Builder b = Ui.dialog(this)
                .setTitle("无障碍服务")
                .setMessage(sb.toString())
                .setPositiveButton(st == A11y.ST_ON ? "查看设置" : "去开启", (d, w) -> openA11ySettings());
        if (!A11y.canWrite(this)) {
            int szk = ShizukuHeal.stateOf(
                    ShizukuHeal.installed(this),
                    ShizukuHeal.installed(this) && safePingShizuku(),
                    safeShizukuGranted(this));
            if (szk == ShizukuHeal.ST_READY) {
                b.setNeutralButton("已就绪", null);
            } else if (szk == ShizukuHeal.ST_NONE) {
                b.setNeutralButton("装 Shizuku", (d, w) -> openShizukuPage());
            } else if (szk == ShizukuHeal.ST_NOT_GRANTED) {
                b.setNeutralButton("Shizuku 授权", (d, w) -> requestShizuku());
            } else {
                b.setNeutralButton("打开 Shizuku", (d, w) -> openShizukuApp());
            }
        } else if (st != A11y.ST_ON) {
            b.setNeutralButton("厂商设置", (d, w) -> openVendorSettings());
        }
        b.setNegativeButton("关闭", null).show();
    }

    /** Shizuku 在跑吗（binder 已送达且活着）；任何异常都当"没跑"。 */
    private boolean safePingShizuku() {
        try {
            return ShizukuGate.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** GGBot 拿到 Shizuku 授权了吗：平台权限由 server 在授权时 pm grant 给我们。 */
    private boolean safeShizukuGranted(Context c) {
        try {
            return c.checkSelfPermission(ShizukuHeal.PERM) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 打开 Shizuku 的应用详情/安装页。 */
    private void openShizukuPage() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("market://details?id=" + ShizukuHeal.PKG));
            startActivity(i);
        } catch (Exception e) {
            toast("打不开应用商店，请手动搜索 Shizuku");
        }
    }

    /** 直接拉起 Shizuku 主界面（已装但没启动时用）。 */
    private void openShizukuApp() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(ShizukuHeal.PKG);
            if (i != null) startActivity(i);
            else toast("Shizuku 没有启动入口，请手动打开");
        } catch (Exception e) {
            toast("打不开 Shizuku");
        }
    }

    /**
     * 向 Shizuku 申请授权。
     *
     * 关键是结果回调：Shizuku.requestPermission 是**异步**的——它只负责弹它自己的
     * 授权框，立刻返回。用户点「始终允许」之后的结果通过 listener 回来。
     * 原来这里只调 requestPermission 然后 refresh()，refresh 跑在弹框之前，
     * 拿到的还是旧状态，用户点完允许界面不会有任何变化，看起来像没生效。
     */
    private void requestShizuku() {
        try {
            // 不走 Shizuku.requestPermission：那份 api jar 缺 moe.shizuku.server 的
            // AIDL stub，整类加载都失败。ShizukuHeal 直接对 server binder 发事务 15，
            // manager 授权对话框照常弹出。
            boolean sent = ShizukuHeal.requestPermission(0);
            toast(sent ? "已发出授权请求，请在弹窗里选「始终允许」" : "Shizuku 没在运行，先把它跑起来");
            // server 的授权结果没有客户端回调通道（回调走 IShizukuApplication，
            // 同样在残缺 jar 里）；用轮询补：授权成功后平台权限随之变化，
            // 刷新两轮，最后再验一次写回通路
            if (sent) {
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.postDelayed(this::refresh, 2500);
                h.postDelayed(() -> {
                    refresh();
                    if (safeShizukuGranted(this)) verifyShizukuPath();
                }, 7000);
            }
        } catch (Throwable t) {
            toast("授权请求失败：" + t.getClass().getSimpleName());
        }
    }

    /**
     * 授权后立刻实测一次写回，别让用户以为好了结果没通。
     *
     * 做法是把当前列表原样写回一次（值不变，所以不会真的改动任何东西，
     * 只是走一遍 transact）。这能一次验出：Shizuku 活着、权限够、settings
     * 服务可达。三项里任一不通，都会在这里暴露，而不是等到真被摘了才发现。
     */
    private void verifyShizukuPath() {
        new Thread(() -> {
            boolean ok = ShizukuHeal.putSecure(
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    A11y.currentList(this));
            runOnUiThread(() -> {
                Diag.push(ok ? "Shizuku 写回通路已验证通过" : "Shizuku 写回通路验证失败",
                        ok ? "info" : "error");
                toast(ok ? "写回通路已验证通过" : "通路验证失败，看「诊断」里的记录");
                refresh();
            });
        }, "shizuku-verify").start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    /**
     * 依次尝试厂商入口，第一个能打开的就停；全失败退到本应用详情页，再失败给一句人话。
     * 为什么按顺序试：华为/荣耀不同 MagicOS/EMUI 版本页面名不一样，写死一个必然有机器打不开。
     */
    private void openVendorSettings() {
        for (Intent it : Vendor.entries(this)) {
            try {
                startActivity(it);
                return;
            } catch (Exception ignored) {
                // 试下一个
            }
        }
        toast("这台手机没有找到对应页面，请按上面的步骤手动进设置");
    }

    /** 跳到本服务的无障碍详情页；个别机型不认详情页就退回总列表。 */
    private void openA11ySettings() {
        try {
            startActivity(A11y.detailsIntent(this));
        } catch (Exception e) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception ignored) {
                toast("打不开系统设置，请手动到「设置 → 无障碍」里找 GGBot");
            }
        }
    }

    private void dohDialog() {
        String[] items = new String[Config.DOH_URLS.length + 1];
        items[0] = "关闭（明文 UDP）";
        int idx = Config.doh() ? 1 : 0;
        for (int i = 0; i < Config.DOH_URLS.length; i++) {
            items[i + 1] = Config.DOH_LABELS[i];
            if (Config.doh() && Config.DOH_URLS[i].equals(Config.dohUrl())) idx = i + 1;
        }
        Ui.dialog(this)
                .setTitle("上游加密（DoH）")
                .setSingleChoiceItems(items, idx, (d, which) -> {
                    Config.setDoh(this, which > 0);
                    if (which > 0) Config.setDohUrl(this, Config.DOH_URLS[which - 1]);
                    RealResolver.clear();
                    d.dismiss();
                    refresh();
                    toast(which > 0 ? "上游走 DoH：" + Config.DOH_URLS[which - 1] : "上游回落明文 UDP");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void modeDialog() {
        final String[] items = {"NXDOMAIN（域名不存在，推荐）", "返回 0.0.0.0（部分 App 更稳）"};
        Ui.dialog(this)
                .setTitle("拦截方式")
                .setSingleChoiceItems(items, Config.mode(), (d, which) -> {
                    Config.setMode(this, which);
                    d.dismiss();
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void bypassDialog() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        final List<String[]> rows = new ArrayList<>();
        for (ApplicationInfo ai : apps) {
            if (ai.packageName.equals(getPackageName())) continue;
            if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
            if (pm.checkPermission("android.permission.INTERNET", ai.packageName)
                    != PackageManager.PERMISSION_GRANTED) continue;
            String label = String.valueOf(pm.getApplicationLabel(ai));
            rows.add(new String[]{label, ai.packageName});
        }
        Collections.sort(rows, (a, b) -> a[0].compareToIgnoreCase(b[0]));

        final List<String> pkgs = new ArrayList<>();
        final List<CharSequence> labels = new ArrayList<>();
        Set<String> cur = Config.bypass(this);
        final boolean[] sel = new boolean[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            labels.add(rows.get(i)[0]);
            pkgs.add(rows.get(i)[1]);
            sel[i] = cur.contains(rows.get(i)[1]);
        }
        if (rows.isEmpty()) {
            toast("没有可排除的应用");
            return;
        }

        Ui.dialog(this)
                .setTitle("排除应用（" + rows.size() + "）")
                .setMultiChoiceItems(labels.toArray(new CharSequence[0]), sel,
                        (d, which, isChecked) -> sel[which] = isChecked)
                .setPositiveButton("保存", (d, w) -> {
                    Set<String> out = new HashSet<>();
                    for (int i = 0; i < sel.length; i++) {
                        if (sel[i]) out.add(pkgs.get(i));
                    }
                    Config.setBypass(this, out);
                    refresh();
                    if (DnsVpnService.isRunning()) {
                        restartService();
                        toast("已应用，正在重启过滤");
                    } else {
                        toast("已保存");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAbout() {
        String ver;
        try {
            ver = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            ver = "1.6";
        }
        String text = "GGBot " + ver + "\n\n"
                + "注意事项：\n\n"
                + "· 支付时建议先关闭本应用（首页按钮停止过滤），避免支付页"
                + "被拦截或解密影响；付款完成再打开即可。\n\n"
                + "· 只想用「开屏自动跳过」的话，可以不开 VPN：模式选"
                + "「只跳过：不开 VPN」，最省电。\n\n"
                + "· 某个 App 打开白屏或加载不出时，把它的域名加进白名单，"
                + "或切换到轻量模式。\n\n"
                + "· 隐私：应用没有服务器、没有账号、不采集任何数据。";
        Ui.dialog(this)
                .setTitle("关于 GGBot")
                .setMessage(text)
                .setPositiveButton("知道了", null)
                .setNeutralButton("赞赏作者", (d, w) -> showDonateQr())
                .setNegativeButton("系统 VPN 设置", (d, w) -> {
                    try {
                        startActivity(new Intent("android.net.vpn.SETTINGS"));
                    } catch (Exception ignored) {
                    }
                })
                .show();
    }

    /** 「宝宝回家」会话状态：刷新=下一页（循环），省份过滤记忆在 Config。 */
    private int babyPage = 1;
    private String babyProvince;
    private boolean babyAutoPaged;

    private void showBabyHome() {
        showBabyHome(1);
    }

    private void showBabyHome(int page) {
        babyPage = page;
        babyProvince = Config.babyProvince(this);
        android.app.AlertDialog dlg = Ui.dialog(this)
                .setTitle("宝宝回家")
                .setMessage(page == 1 ? "正在从全国救助寻亲网拉取…"
                        : "正在拉取第 " + page + " 批…")
                .setNegativeButton("关闭", null)
                .show();
        final android.app.AlertDialog[] holder = {dlg};
        final int fPage = page;
        final String fProv = babyProvince;
        new Thread(() -> {
            BabyHome.Result r = BabyHome.fetch(fPage, fProv);
            // 省份过滤后可能为空：翻下一页再试一次，仍空才回落提示
            if (r.children.isEmpty() && fProv != null && !fProv.isEmpty()) {
                BabyHome.Result all = BabyHome.fetch(fPage, null);
                final BabyHome.Result merged = new BabyHome.Result(all.children, all.error);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    holder[0].dismiss();
                    showBabyHomeResult(merged);
                });
                return;
            }
            final BabyHome.Result fr = r;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                holder[0].dismiss();
                showBabyHomeResult(fr);
            });
        }, "ggbot-babyhome").start();
    }

    private void showBabyHomeResult(BabyHome.Result r) {
        if (r.children.isEmpty()) {
            String msg = "实时数据暂时拉取不到（" + r.error + "）。\n\n"
                    + "寻亲认亲请走官方渠道：\n"
                    + "· 全国救助寻亲网（民政部）\n"
                    + "· 发现疑似走失儿童：拨打 110 或全国打拐热线\n"
                    + "· 宝贝回家志愿者协会：baobeihuijia.com\n\n"
                    + "每次打开都会重新尝试实时数据。";
            Ui.dialog(this)
                    .setTitle("宝宝回家 · 团圆行动")
                    .setMessage(msg)
                    .setPositiveButton("重试", (d, w) -> showBabyHome())
                    .setNeutralButton("打开官网", (d, w) -> openBabySite())
                    .setNegativeButton("关闭", null)
                    .show();
            return;
        }

        // 自绘滚动列表：每条 = 照片(异步拉) + 信息行。数据是政府主动公开的寻亲信息
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(4), dp(6), dp(4));
        scroll.addView(box);

        TextView head = new TextView(this);
        head.setText("民政部全国救助寻亲网 · 本批 " + r.children.size() + " 人"
                + "\n这些孩子正在各地救助站等待家人 · 发现地=被救助地点，不等于走失地点"
                + "\n认出孩子请拨打 110 或联系记录上的救助站 · 照片实时加载不本地缓存");
        head.setTextSize(11.5f);
        head.setTextColor(MUTED);
        head.setPadding(dp(10), dp(8), dp(10), dp(8));
        box.addView(head);

        for (int i = 0; i < r.children.size() && i < 20; i++) {
            box.addView(babyCard(r.children.get(i)));
        }

        new android.app.AlertDialog.Builder(this, dialogThemeId())
                .setTitle("宝宝回家 · 团圆行动")
                .setView(scroll)
                .setPositiveButton("换一批", (d, w) -> showBabyHome(babyPage >= 6 ? 1 : babyPage + 1))
                .setNeutralButton("选省份", (d, w) -> pickBabyProvince())
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 省份选择：34 省级行政区单选，记忆到 Config；「全部」清空过滤。 */
    private void pickBabyProvince() {
        String[] items = new String[BabyHome.PROVINCES.length + 1];
        items[0] = "全部省份";
        System.arraycopy(BabyHome.PROVINCES, 0, items, 1, BabyHome.PROVINCES.length);
        new android.app.AlertDialog.Builder(this, dialogThemeId())
                .setTitle("按发现地筛选省份（当前：" + (babyProvince == null ? "全部" : babyProvince) + "）")
                .setItems(items, (d, w) -> {
                    String sel = w == 0 ? null : items[w];
                    Config.setBabyProvince(this, sel);
                    showBabyHome(1);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 单条寻亲卡片：照片左侧异步加载，右侧文字信息。 */
    private LinearLayout babyCard(BabyHome.Child c) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));

        final ImageView pic = new ImageView(this);
        pic.setBackgroundColor(CARD2);
        pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pic.setContentDescription("寻亲照片");
        row.addView(pic, new LinearLayout.LayoutParams(dp(96), dp(128)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        addBabyLine(col, c.name.isEmpty() ? "（信息待完善）" : c.name, 15f, FG, true);
        if (!c.gender.isEmpty() || !c.age.isEmpty()) {
            addBabyLine(col, join(" · ", c.gender, c.age.isEmpty() ? "" : "约 " + c.age + " 岁",
                    c.height.isEmpty() ? "" : "身高 " + c.height + "cm"), 12f, MUTED, false);
        }
        if (!c.place.isEmpty()) addBabyLine(col, "发现于 " + c.place, 12f, FG, false);
        if (!c.time.isEmpty()) addBabyLine(col, "时间 " + c.time, 12f, MUTED, false);
        if (!c.unit.isEmpty()) addBabyLine(col, c.unit, 11f, DIM, false);
        if (!c.number.isEmpty()) addBabyLine(col, "编号 " + c.number, 10.5f, DIM, false);
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        loadBabyPhoto(pic, c);
        return row;
    }

    private void addBabyLine(LinearLayout col, String text, float sp, int color, boolean bold) {
        if (text == null || text.isEmpty()) return;
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(2), 0, dp(2));
        col.addView(t);
    }

    private static String join(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p2 : parts) {
            if (p2 == null || p2.isEmpty()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p2);
        }
        return sb.toString();
    }

    /** 照片实时拉取：成功即填，失败留底色。不做磁盘缓存。 */
    private void loadBabyPhoto(final ImageView pic, BabyHome.Child c) {
        final String url = BabyHome.photoUrl(c);
        new Thread(() -> {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                        new java.net.URI(url).toURL().openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) GGBot/1.0");
                if (conn.getResponseCode() != 200) return;
                java.io.InputStream in = new java.io.BufferedInputStream(conn.getInputStream(), 8192);
                final android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in);
                try { in.close(); } catch (Exception ignored) {}
                if (bmp == null) return;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    pic.setImageBitmap(bmp);
                });
            } catch (Exception ignored) {
            }
        }, "ggbot-babyhome-pic").start();
    }

    private void openBabySite() {
        try {
            startActivity(new android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(BabyHome.OFFICIAL_SITE)));
        } catch (Exception e) {
            toast("没有可用的浏览器");
        }
    }

    /** 取应用弹窗主题 id（Ui.dialog 的 getIdentifier 逻辑同步到这里供 setView 弹窗用）。 */
    private int dialogThemeId() {
        int id = getResources().getIdentifier("GgDialog", "style", getPackageName());
        return id != 0 ? id : android.R.style.Theme_Material_Dialog_Alert;
    }

    /** 赞赏弹窗：本地打包的收款码，不加载任何网页内容。 */
    private void showDonateQr() {
        // 防拆包换码：非官方签名或收款码被替换的构建一律不展示二维码，
        // 只提示风险，避免用户扫到被调包的收款码造成损失
        if (!Integrity.isOfficialBuild(this)) {
            Ui.dialog(this)
                    .setTitle("无法验证构建")
                    .setMessage("当前安装包不是官方签名构建，或收款码已被篡改。\n"
                            + "为防止扫码被骗，赞赏功能已停用；请从官方渠道重新下载。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(android.view.Gravity.CENTER);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageResource(R.drawable.donate_qr);
        iv.setAdjustViewBounds(true);
        iv.setMaxWidth(dp(280));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        box.addView(iv, lp);
        TextView hint = tv("支付宝 / 微信扫码 · 金额随意 · 感谢支持", 12.5f, MUTED, false);
        hint.setGravity(android.view.Gravity.CENTER);
        hint.setPadding(0, dp(10), 0, 0);
        box.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Ui.dialog(this)
                .setTitle("赞赏作者")
                .setView(box)
                .setPositiveButton("关闭", null)
                .show();
    }

    /* ---------------- 小工具 ---------------- */

    private TextView tv(String text, float sp, int color, boolean bold) {
        return ui.tv(text, sp, color, bold);
    }

    private GradientDrawable round(int color, int radiusDp, int strokeColor) {
        return ui.round(color, radiusDp, strokeColor);
    }

    private Drawable selectable() {
        return ui.selectable();
    }

    private void toast(String s) {
        ui.toast(s);
    }

    private int dp(int v) {
        return ui.dp(v);
    }
}

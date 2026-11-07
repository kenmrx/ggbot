package com.ggbot.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 首页与设置页共用的一套配色和控件工厂。
 *
 * 抽出来不只是为了少写几行：两个页面的行高、圆角、按压反馈必须完全一致，
 * 否则从首页跳进设置会有「换了个应用」的错觉。
 */
final class Ui {

    /**
     * 最小可点高度（dp）。Material 与 WCAG 2.2 都要求 48dp；
     * 之前筛选 chip 与日志行只有 27～31dp，手指按下去会点空，靠肉眼看不出来。
     */
    static final int TOUCH_DP = Tone.TOUCH_DP;

    // 颜色统一定义在 Tone（纯常量、可被单元测试断言对比度），这里只做转发，
    // 保证既有调用点 Ui.DIM 之类不用改一行。
    static final int BG = Tone.BG;
    static final int CARD = Tone.CARD;
    static final int CARD2 = Tone.CARD2;
    static final int BORDER = Tone.BORDER;
    static final int LINE = Tone.LINE;
    static final int FG = Tone.FG;
    static final int MUTED = Tone.MUTED;
    static final int DIM = Tone.DIM;
    static final int ACCENT = Tone.ACCENT;
    static final int ACCENT_DEEP = Tone.ACCENT_DEEP;
    static final int ACCENT_SOFT = Tone.ACCENT_SOFT;
    static final int DARK = Tone.DARK;
    /** 按下时的水波纹：白得克制一点，暗色底上太亮会像闪屏。 */
    static final int RIPPLE = Tone.RIPPLE;

    private final Activity a;

    Ui(Activity a) {
        this.a = a;
    }

    /**
     * 弹窗工厂。
     *
     * 为什么不用应用级 android:alertDialogTheme：加了资源、也编进了 APK，但实测对话框依然是系统灰底
     * （见 21:41 截图），说明这条继承链在本工程的主题组合下没生效。
     * 改为构造时显式指定主题——这是 AlertDialog 唯一可靠的入口；主题 id 用 getIdentifier 运行时查，
     * 免得让纯 javac 的门闸去依赖 R.java。
     */
    static android.app.AlertDialog.Builder dialog(android.content.Context c) {
        int id = c.getResources().getIdentifier("GgDialog", "style", c.getPackageName());
        return id != 0 ? new android.app.AlertDialog.Builder(c, id)
                : new android.app.AlertDialog.Builder(c);
    }

    /**
     * 统一弹窗底色。默认 AlertDialog 是系统灰底（截图里与我们的深绿卡片差得很明显），
     * 看起来像「弹出别人的界面」。改成卡片色 + 18dp 圆角，和页面融为一体。
     */
    void dialogBg(android.app.AlertDialog d) {
        if (d == null || d.getWindow() == null) return;
        d.getWindow().setBackgroundDrawable(round(CARD, 18, 0));
    }

    /* ---------------- 基础控件 ---------------- */

    TextView tv(String text, float sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    LinearLayout vertical() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** 一张卡片：圆角 18，细边框，上下各留 4dp 让首尾行不贴边。 */
    LinearLayout card() {
        LinearLayout l = vertical();
        l.setBackground(round(CARD, 18, BORDER));
        l.setPadding(0, dp(4), 0, dp(4));
        return l;
    }

    TextView section(String text) {
        TextView t = tv(text, 12.5f, MUTED, true);
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(4), dp(20), 0, dp(8));
        return t;
    }

    /**
     * 一行设置项：左标题 + 副标题，右侧当前值，可点则带箭头。
     *
     * 行高按 56dp 起步——之前 13dp 上下内边距在小屏上只有 44dp，
     * 手指略偏就点到隔壁那行去了。
     */
    TextView addRow(LinearLayout parent, String title, String sub, View.OnClickListener onClick) {
        boolean clickable = onClick != null;
        LinearLayout row = horizontal();
        row.setMinimumHeight(dp(54));
        row.setPadding(dp(16), dp(11), dp(14), dp(11));

        LinearLayout col = vertical();
        col.addView(tv(title, 14f, FG, false));
        if (sub != null) {
            TextView s = tv(sub, 11.5f, MUTED, false);
            s.setPadding(0, dp(3), 0, 0);
            col.addView(s);
        }
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = tv("", 13f, MUTED, false);
        value.setGravity(Gravity.END);
        // 数值和箭头之间留一口气，不然「4,325 ›」看着像连在一起的一个词
        value.setPadding(dp(14), 0, 0, 0);
        value.setMaxWidth((int) (a.getResources().getDisplayMetrics().widthPixels * 0.5f));
        value.setSingleLine(true);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(value);

        if (clickable) {
            TextView chev = tv("›", 18f, DIM, false);
            chev.setPadding(dp(9), 0, 0, 0);
            row.addView(chev);
            touchable(row, 0);
            row.setOnClickListener(onClick);
        }
        parent.addView(row);
        return value;
    }

    void addSwitchRow(LinearLayout parent, String title, String sub, Switch sw) {
        tintSwitch(sw);
        LinearLayout row = horizontal();
        row.setMinimumHeight(dp(54));
        row.setPadding(dp(16), dp(10), dp(14), dp(10));

        LinearLayout col = vertical();
        col.addView(tv(title, 14f, FG, false));
        TextView s = tv(sub, 11.5f, MUTED, false);
        s.setPadding(0, dp(3), 0, 0);
        col.addView(s);
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(sw);

        // 整行都能拨：开关本身体积小，单手拿着手机时经常点不中
        row.setClickable(true);
        row.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
        parent.addView(row);
    }

    void divider(LinearLayout parent) {
        View v = new View(a);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.leftMargin = dp(16);
        parent.addView(v, lp);
    }

    /**
     * 把系统开关染成品牌绿。
     *
     * 默认的 Switch 用的是主题的次要色（这台机器上是青色），
     * 和整页的绿色放在一起像是从别的应用里抠来的。选中态跟 ACCENT 对齐，
     * 未选中态压到接近背景的深灰绿，避免一排开关把注意力全吸走。
     */
    static void tintSwitch(Switch sw) {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        sw.setThumbTintList(new ColorStateList(states,
                new int[]{0xFFFFFFFF, 0xFFA8B7AF}));
        sw.setTrackTintList(new ColorStateList(states,
                new int[]{ACCENT, 0xFF2A342E}));
    }

    TextView pill(String text) {
        TextView t = tv(text, 12.5f, FG, false);
        t.setGravity(Gravity.CENTER);
        t.setMinimumHeight(dp(42));
        t.setPadding(dp(16), dp(11), dp(16), dp(11));
        t.setBackground(ripplePill(CARD2, 12, BORDER));
        t.setClickable(true);
        return t;
    }

    /* ---------------- 背景 ---------------- */

    GradientDrawable round(int color, int radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    /** 带描边的卡片水波纹，圆角跟卡片一致，按下不会溢出到圆角外面。 */
    Drawable rippleCard(int fill, int radiusDp, int strokeColor) {
        GradientDrawable content = round(fill, radiusDp, strokeColor);
        return withRipple(content, radiusDp, RIPPLE);
    }

    Drawable ripplePill(int fill, int radiusDp, int strokeColor) {
        return withRipple(round(fill, radiusDp, strokeColor), radiusDp, 0x33FFFFFF);
    }

    Drawable withRipple(Drawable content, int radiusDp, int color) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(color), content, mask);
    }

    /** 给任意 View 挂上「可点 + 圆角水波纹」。 */
    void touchable(View v, int radiusDp) {
        v.setBackground(rippleCard(CARD, radiusDp, BORDER));
        v.setClickable(true);
        v.setFocusable(true);
    }

    Drawable selectable() {
        TypedValue out = new TypedValue();
        if (a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
            return a.getDrawable(out.resourceId);
        }
        return null;
    }

    /** 包一层只为把 setView 的废弃告警关在一个函数里；抛错由调用方的 catch 兜底。 */
    @SuppressWarnings("deprecation")
    private static boolean safeSetView(Toast tt, android.view.View v) {
        tt.setView(v);
        return true;
    }

    /**
     * 自绘 toast：系统 toast 用的是系统主题，在本应用深色界面上会弹出一块白色胶囊
     * （截图里那条「证书还没生成」就是），看起来像别的应用的提示。
     * 这里改成与卡片同色、圆角、带描边的自己画一个；万一系统不允许自定义视图（后台等）
     * 就退回系统 toast，至少不会丢提示。
     *
     * 整体必须回到主线程执行：调用方有工作线程（导出诊断、保存 CA 的收尾提示），
     * API 24–29 上 Toast 的 TN 在构造时要当前线程的 Looper，工作线程里连系统 toast
     * 兜底都会抛「Can't create handler inside thread」——兜底若在 try 外就直接崩进程。
     */
    void toast(String s) {
        if (s == null || s.isEmpty()) return;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            a.runOnUiThread(() -> toast(s));
            return;
        }
        try {
            TextView t = new TextView(a);
            t.setText(s);
            t.setTextSize(12.5f);
            t.setTextColor(FG);
            t.setPadding(dp(16), dp(11), dp(16), dp(11));
            t.setBackground(round(CARD2, 14, BORDER));
            t.setMaxWidth((int) (a.getResources().getDisplayMetrics().widthPixels * 0.86f));
            Toast tt = new Toast(a);
            tt.setDuration(Toast.LENGTH_SHORT);
            // setView 30 起废弃但 30 以下仍是唯一自定义样式途径；直接反射调用消不掉告警，
            // 这里用 @SuppressWarnings 压掉，兜底路径（系统 toast）保持不变
            @SuppressWarnings("deprecation")
            boolean shown = safeSetView(tt, t);
            if (!shown) return;
            tt.setGravity(android.view.Gravity.BOTTOM, 0, dp(96));
            tt.show();
            return;
        } catch (Throwable ignored) {
            // 落到下面用系统 toast
        }
        try {
            Toast.makeText(a, s, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // API 30+ 后台自定义 toast 被系统静默丢弃；到这里不再抛就是底线
        }
    }

    int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                a.getResources().getDisplayMetrics());
    }
}

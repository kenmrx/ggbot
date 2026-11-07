package com.ggbot.app;

/**
 * “怎么装证书才能用”的分步引导（纯逻辑，可单测）。
 *
 * 为什么不能只写一段死文案：用户卡在哪一步是变化的。没导出就没得选文件；
 * 装成用户证书后 Android 7+ 的应用默认不信任它（除非应用自己声明信任用户 CA）；
 * 想装进系统信任库又需要 root 且 /system 可写。这四种状态对应四组话，
 * 引导必须说“你现在是哪一步、下一步点哪里”，而不是把四步全列一遍。
 */
public final class CertGuide {

    /** 从设备现状探到的事实，UI 只负责把这些事实填进来。 */
    public static final class State {
        public boolean exported;        // 下载目录里有导出的 .crt
        public boolean userInstalled;   // 已在用户信任库（AndroidCAStore 能查到）
        public boolean systemInstalled; // 已在系统信任库（cacerts 目录里有对应条目）
        public boolean systemWritable;  // 本机是否可写系统信任库（root + 非只读分区）
        public boolean root;            // 设备上有 su
    }

    public static final String[] STEPS = {
            "导出 CA 到下载目录（得到 GGBotCA.crt）",
            "系统设置 → 安全 → 加密与凭据 → 安装证书 → CA 证书 → 选 GGBotCA.crt",
            "Android 7 起应用默认不信任用户证书：装系统信任库，或在目标应用里启用",
            "打开要拦截的 https 站点，看日志里该域名标为「解密」",
    };

    public static boolean stepDone(int i, State s) {
        switch (i) {
            case 0: return s.exported;
            case 1: return s.userInstalled;
            case 2: return s.systemInstalled;
            case 3: return false;   // 第四步靠实际流量验证，不靠静态状态
            default: return false;
        }
    }

    public static int doneCount(State s) {
        int n = 0;
        for (int i = 0; i < STEPS.length - 1; i++) if (stepDone(i, s)) n++;
        return n;
    }

    /** 每行前面的标记：已完成 ✓，当前该做 ●，还没轮到 ○。 */
    public static String mark(int i, State s) {
        if (stepDone(i, s)) return "✓";
        return i == nextIndex(s) ? "●" : "○";
    }

    public static int nextIndex(State s) {
        for (int i = 0; i < STEPS.length - 1; i++) if (!stepDone(i, s)) return i;
        return STEPS.length - 1;
    }

    /** 针对当前状态的一句话指引（含弹窗里该点哪一项）。 */
    public static String hint(State s) {
        if (!s.exported) {
            return "第一步：点「导出证书到下载目录」。导出失败会在下面直接报原因，不会静默。";
        }
        if (!s.userInstalled) {
            return "第二步：点下面「打开安全设置」，按顺序点 加密与凭据 → 安装证书 → CA 证书 → 仍要安装 → "
                    + "在文件里选 下载/GGBotCA.crt → 系统会提示「您的数据可能不安全」，点「仍然安装」→ 命名确认。";
        }
        if (!s.systemInstalled) {
            String tail = s.systemWritable
                    ? "本机可写系统信任库：点「复制 root 安装命令」，在终端里粘贴执行，重启后对所有应用生效。"
                    : (s.root
                        ? "本机有 root，但 /system 是只读的（dm-verity）：先执行 adb disable-verity 并重启，或把证书放进 /data/misc/user/0/cacerts-added 后重启框架。"
                        : "本机既无 root 也不可写系统信任库：只能停在用户证书这一步，Android 7+ 的应用（浏览器、多数 App）默认不信任它。");
            return "第三步：用户证书已装，但 Android 7 起应用默认只信任系统 CA。" + tail;
        }
        return "已就绪：现在打开任意 https 站点，日志里出现「解密」标记就说明这条链走通了。";
    }

    /** root 安装命令（只在可写时给）：告诉用户到底该贴什么。 */
    public static String rootCommand(String hash) {
        if (hash == null || hash.isEmpty()) return "";
        return "su -c 'mount -o rw,remount /system; cp /sdcard/Download/GGBotCA.crt /system/etc/security/cacerts/"
                + hash + ".0; chmod 644 /system/etc/security/cacerts/" + hash + ".0' && adb reboot";
    }
}

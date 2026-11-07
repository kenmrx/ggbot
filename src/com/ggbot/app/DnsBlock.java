package com.ggbot.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * DNS 层的一条拦截记录。
 *
 * 早先这里只是一个字符串（域名，或者「域名 ⇢ 跳转目标」），界面上只能看、不能点。
 * 但 DNS 层是绝大多数人唯一生效的那一层：被它拦坏的站点，用户当场就想放行，
 * 却只能退出界面、翻到白名单里手打域名——这条路太远，实际上等于没有出口。
 *
 * 所以记录必须带上「拦的是谁、凭什么拦、经过了哪一跳」，
 * 界面才能直接给出动作，而不是让用户自己抄域名。
 *
 * 注意 host 与 via 的分工：加白名单要加 host（客户端真正查询的域名），
 * 加 via 是无效的——那个域名根本没有出现在查询里。
 */
public final class DnsBlock {

    /** 命中了域名库或通配规则。 */
    public static final int KIND_RULE = 0;
    /** 查询的域名本身干净，是 CNAME 链上某一跳落在黑名单里。 */
    public static final int KIND_CNAME = 1;

    public final long time;
    /** 客户端实际查询的域名。 */
    public final String host;
    /** 命中依据，给人看的一句话；可能为 null。 */
    public final String reason;
    /** CNAME 链上命中的那一跳；普通拦截为 null。 */
    public final String via;
    public final int kind;

    DnsBlock(long time, String host, String reason, String via, int kind) {
        this.time = time;
        this.host = host;
        this.reason = reason;
        this.via = via;
        this.kind = kind;
    }

    /** 命中域名规则的记录。 */
    public static DnsBlock rule(String host, String reason) {
        return new DnsBlock(System.currentTimeMillis(), host, reason, null, KIND_RULE);
    }

    /** CNAME 链上命中的记录。via 是链上落在黑名单里的那一跳。 */
    public static DnsBlock cname(String host, String via) {
        return new DnsBlock(System.currentTimeMillis(), host, null, via, KIND_CNAME);
    }

    public String timeText() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(time));
    }

    public boolean cname() {
        return kind == KIND_CNAME;
    }

    /** 列表里的一行。隐身记录把跳转目标也带上，否则看不出为什么被拦。 */
    public String line() {
        StringBuilder sb = new StringBuilder(64);
        sb.append(timeText()).append("  ");
        sb.append(cname() ? "隐身" : "拦截").append("  ").append(host);
        if (via != null && !via.isEmpty()) sb.append(" → ").append(via);
        return sb.toString();
    }

    /** 点开后的详细说明。 */
    public String detail() {
        StringBuilder sb = new StringBuilder(160);
        sb.append(cname() ? "经 CNAME 链拦下" : "命中域名规则");
        sb.append('\n').append(host);
        if (via != null && !via.isEmpty()) {
            sb.append("\n↳ ").append(via);
            sb.append("\n\n查询的域名本身不在任何名单里，");
            sb.append("但上游应答把它指向了上面这个追踪域。");
        }
        if (reason != null && !reason.isEmpty()) sb.append("\n\n依据：").append(reason);
        return sb.toString();
    }
}

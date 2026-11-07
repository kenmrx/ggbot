package com.ggbot.app;

/**
 * 订阅更新的条件请求判定。
 *
 * 参考 AdAway SourceModel：有上次的修改时间就带 If-Modified-Since，响应里存下 ETag。
 * 我们每次刷新要下 5-8 个列表，服务端说「没变」时就没必要再下再解析。
 */
public final class Cond {

    private Cond() {
    }

    /** 验证器可用（非 null、非空白）。 */
    public static boolean usable(String v) {
        return v != null && !v.trim().isEmpty();
    }

    /** 304：内容未变更，算成功而不是失败。 */
    public static boolean fresh(int code) {
        return code == 304;
    }

    public static boolean ok(int code) {
        return code == 200;
    }

    public static String ifNoneMatch(String etag) {
        return usable(etag) ? etag.trim() : null;
    }

    public static String ifModifiedSince(String lastMod) {
        return usable(lastMod) ? lastMod.trim() : null;
    }
}

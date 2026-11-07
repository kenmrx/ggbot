package com.ggbot.app;

/**
 * $redirect 用的内置替代资源。
 *
 * uBlock Origin 处理广告脚本时不是回 204，而是回一个「空但合法」的资源：
 * 空 JS、1×1 透明 GIF、空文本。204 对 <script> 和 <img> 都不算正常响应，
 * 一部分站点会因此抛错或反复重试，页面反而卡住；空资源跟「加载成功但什么都没做」
 * 等价，行为最接近真实。
 *
 * 这里只带最小的几个，不追求把 uBO 的资源库搬过来——用得上的就这几类。
 */
public final class Noop {

    /** 1×1 全透明 GIF，43 字节，比任何 PNG 都小。 */
    private static final byte[] GIF_1X1 = {
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, (byte) 0x80, 0x00,
            0x00, 0x00, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            0x21, (byte) 0xF9, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0x02, 0x02, 0x44, 0x01, 0x00, 0x3B
    };

    private static final byte[] EMPTY = {};

    public static final class Res {
        public final String type;
        public final byte[] body;

        Res(String type, byte[] body) {
            this.type = type;
            this.body = body;
        }
    }

    private static final Res JS = new Res("text/javascript; charset=utf-8", EMPTY);
    private static final Res GIF = new Res("image/gif", GIF_1X1);
    private static final Res TEXT = new Res("text/plain; charset=utf-8", EMPTY);

    private Noop() {
    }

    /**
     * 按 $redirect= 的参数挑资源。参数可能是文件名（noop.js）、
     * 别名（noopjs / nooptext），也可能是 uBO 的数字资源 id。
     */
    public static Res of(String param) {
        if (param == null || param.isEmpty()) return TEXT;
        String p = param.trim().toLowerCase(java.util.Locale.US);
        int eq = p.indexOf(':');
        if (eq > 0) p = p.substring(0, eq);          // 有些列表写成 redirect=noop.js:5

        if (p.endsWith(".js") || p.equals("noopjs") || p.contains("javascript")) return JS;
        if (p.endsWith(".gif") || p.endsWith(".png") || p.endsWith(".webp")
                || p.contains("transparent") || p.contains("1x1") || p.contains("2x2")) {
            return GIF;
        }
        if (p.equals("empty") || p.endsWith(".txt") || p.equals("nooptext")
                || p.endsWith(".html") || p.endsWith(".json")) {
            return TEXT;
        }
        // 音视频、vmap、数字 id 之类：给空文本，比 204 温和，也不会让播放器卡住
        return TEXT;
    }

    /** 没有 $redirect 时，按请求路径猜一个更合适的替代资源。 */
    public static Res byPath(String path) {
        if (path == null) return null;
        String p = path.toLowerCase(java.util.Locale.US);
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.endsWith(".js") || p.endsWith(".mjs")) return JS;
        if (p.endsWith(".gif") || p.endsWith(".png") || p.endsWith(".jpg")
                || p.endsWith(".jpeg") || p.endsWith(".webp") || p.endsWith(".bmp")) return GIF;
        return null;
    }
}

package com.ggbot.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 「宝宝回家」数据层：民政部全国救助寻亲网（lljz）真实接口。
 *
 * 接口规格（2026-11-07 对照 lljz 官网页面前端 index.js 实测确认）：
 *   列表  GET https://www.mca.gov.cn/lljz/jmgc-relief-seeking/familySeekers/querySeekingList
 *         ?personType=&releaseTime=&pageNum=1&pageSize=20
 *         （游客可访问，access_token 留空；返回 JSON: data.pageBean.list[]）
 *   照片  https://www.mca.gov.cn/lljz/lljz_files/{picUrl}
 *   字段  name/sex/age/height/hair/findRescuePlace/findRescueTime/
 *         rescueUnit/childNumber/picUrl/releaseTime
 *
 * 数据是政府公开的救助寻亲信息（主动发布、鼓励传播），照片按 URL 实时拉取、
 * 不在本地持久缓存——应用只做展示入口，不做二次分发。
 *
 * 防御：响应 ≤2MB、字段截断 120 字符、控制字符过滤、条数上限 50——
 * 公益接口返回同样按不可信输入处理。
 */
public final class BabyHome {

    /** 列表接口（游客 GET）。 */
    private static final String LIST_URL =
            "https://www.mca.gov.cn/lljz/jmgc-relief-seeking/familySeekers/querySeekingList"
                    + "?personType=&releaseTime=&pageNum=1&pageSize=20";
    /** 照片前缀。 */
    public static final String PHOTO_BASE = "https://www.mca.gov.cn/lljz/lljz_files/";
    /** 官网入口（lljz 壳页，公网直达）。 */
    public static final String OFFICIAL_SITE = "https://www.mca.gov.cn/lljz/";
    public static final String POLICE_PHONE = "110";

    private static final int CONNECT_MS = 6000;
    private static final int READ_MS = 8000;
    private static final int MAX_RESP = 2 * 1024 * 1024;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    public static final class Child {
        public final String name;
        public final String gender;
        public final String age;
        public final String height;
        public final String place;
        public final String time;
        public final String unit;
        public final String number;
        public final String photoUrl;

        Child(String name, String gender, String age, String height,
              String place, String time, String unit, String number, String photoUrl) {
            this.name = name;
            this.gender = gender;
            this.age = age;
            this.height = height;
            this.place = place;
            this.time = time;
            this.unit = unit;
            this.number = number;
            this.photoUrl = photoUrl;
        }
    }

    public static final class Result {
        public final List<Child> children;
        public final String error;

        Result(List<Child> c, String e) {
            children = c;
            error = e;
        }
    }

    private BabyHome() {
    }

    /** 同步拉取；必须在后台线程调用。失败原因镜像到 Diag（logcat 可查）。 */
    public static Result fetch() {
        try {
            List<Child> list = fetchList();
            Diag.push("宝宝回家：拉取到 " + list.size() + " 条", "info");
            return new Result(list, null);
        } catch (Exception e) {
            String why = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            Diag.push("宝宝回家拉取失败: " + why, "warn");
            return new Result(new ArrayList<>(), why);
        }
    }

    private static List<Child> fetchList() throws Exception {
        // 两路参数都试：不带任何参数（与网页首屏一致）→ 带 pageSize 参数
        try {
            List<Child> r = fetchOne("https://www.mca.gov.cn/lljz/jmgc-relief-seeking/familySeekers/querySeekingList");
            if (!r.isEmpty()) return r;
        } catch (Exception ignored) {
        }
        return fetchOne("https://www.mca.gov.cn/lljz/jmgc-relief-seeking/familySeekers/querySeekingList"
                + "?personType=&releaseTime=&pageNum=1&pageSize=20");
    }

    private static List<Child> fetchOne(String url) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setRequestMethod("GET");
            c.setInstanceFollowRedirects(true);
            // 与系统 curl 成功行为逐字段对齐：只带 UA，其余全默认——
            // 之前自定义的 access_token/identity/no-cache 头反而可能触发
            // 网关的 WAF 分支（curl 不带这些头能拿到 200）
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) GGBot/1.0");
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) {
                throw new IllegalStateException("HTTP " + c.getResponseCode());
            }
            String json = readAll(c.getInputStream());
            List<Child> list = parseJson(json);
            if (list.isEmpty()) throw new IllegalStateException("空数据");
            return list;
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] rb = new byte[8192];
        int n;
        while ((n = in.read(rb)) > 0) {
            if (buf.size() + n > MAX_RESP) break;
            buf.write(rb, 0, n);
        }
        return buf.toString("UTF-8");
    }

    /**
     * 极简 JSON 解析：用「花括号配平 + 字符串感知」扫出完整对象，再逐对象提字段。
     *
     * 为什么不能用「找下一个 }」：真实数据里有嵌套对象（cellStyleMap:{}），
     * 简易扫描会把对象在嵌套的 } 处截断，body 里永远见不到 picUrl——
     * v1.8.1 在真机上解析恒为 0 条的根因（桌面 happy-path 没测嵌套，漏了）。
     */
    static List<Child> parseJson(String json) {
        List<Child> out = new ArrayList<>();
        if (json == null) return out;
        // 锚定 "list": [ —— 顶层容器对象(含 pageBean)不能当记录扫描,
        // 否则整包会出一条「巨型对象」把真实记录全吞掉
        int anchor = json.indexOf("\"list\"");
        int i = anchor < 0 ? 0 : json.indexOf('[', anchor);
        if (i < 0) return out;
        int n = json.length();
        while (i < n && out.size() < 50) {
            if (json.charAt(i) != '{') {
                i++;
                continue;
            }
            // 配平扫描：depth 计数, 字符串内不参与
            int depth = 0;
            boolean inStr = false;
            int j = i;
            while (j < n) {
                char ch = json.charAt(j);
                if (inStr) {
                    if (ch == '\\') {
                        j += 2;
                        continue;
                    }
                    if (ch == '"') inStr = false;
                } else if (ch == '"') {
                    inStr = true;
                } else if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0) break;
                }
                j++;
            }
            if (j >= n) break;                       // 截断的 JSON，到此为止
            String body = json.substring(i + 1, j);  // 不含首尾花括号
            i = j + 1;
            // pageBean 等容器对象没有 picUrl，靠它过滤
            String pic = pick(body, "picUrl");
            if (pic.isEmpty()) continue;
            String name = pick(body, "name");
            String gender = pick(body, "sex");
            String age = pick(body, "age");
            String height = pick(body, "height");
            String place = pick(body, "findRescuePlace");
            String time = pick(body, "findRescueTime");
            if (time.isEmpty()) time = pick(body, "releaseTime");
            String unit = pick(body, "rescueUnit");
            String number = pick(body, "childNumber");
            out.add(new Child(name, gender, age, height, place, time, unit, number, pic));
        }
        return out;
    }

    /** 拼照片绝对 URL。 */
    public static String photoUrl(Child c) {
        if (c.photoUrl.startsWith("http")) return c.photoUrl;
        return PHOTO_BASE + c.photoUrl;
    }

    /** 取 "key":"value" 或裸值；截断 120 字符、去控制字符、还原 \/。 */
    private static String pick(String body, String key) {
        int k = body.indexOf("\"" + key + "\"");
        if (k < 0) return "";
        int colon = body.indexOf(':', k + key.length() + 2);
        if (colon < 0) return "";
        int i = colon + 1;
        while (i < body.length() && body.charAt(i) == ' ') i++;
        if (i >= body.length()) return "";
        StringBuilder sb = new StringBuilder();
        if (body.charAt(i) == '"') {
            i++;
            while (i < body.length() && body.charAt(i) != '"') {
                char ch = body.charAt(i);
                if (ch == '\\' && i + 1 < body.length()) {
                    i++;
                    ch = body.charAt(i);
                }
                if (ch >= 32 && ch != 127) sb.append(ch);
                i++;
            }
        } else {
            while (i < body.length() && body.charAt(i) != ',' && body.charAt(i) != '}') {
                char ch = body.charAt(i);
                if (ch >= 32 && ch != 127) sb.append(ch);
                i++;
            }
        }
        String v = sb.toString().trim().replace("\\/", "/");
        return v.length() > 120 ? v.substring(0, 120) : v;
    }
}

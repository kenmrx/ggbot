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
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create(LIST_URL).toURL().openConnection();
            c.setConnectTimeout(CONNECT_MS);
            c.setReadTimeout(READ_MS);
            c.setRequestMethod("GET");
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) GGBot/1.0");
            c.setRequestProperty("access_token", "");
            // 系统证实能拿到数据；应用内失败的常见差异是压缩流与缓存池，
            // 全部显式关掉，保证行为与 curl 一致
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Cache-Control", "no-cache");
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

    /** 极简 JSON 解析：逐对象提取需要的字段。不可信输入，尽力而为。 */
    static List<Child> parseJson(String json) {
        List<Child> out = new ArrayList<>();
        if (json == null) return out;
        int pos = 0;
        while (out.size() < 50) {
            int obj = json.indexOf('{', pos);
            if (obj < 0) break;
            int end = json.indexOf('}', obj);
            if (end < 0) break;
            String body = json.substring(obj + 1, end);
            pos = end + 1;
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

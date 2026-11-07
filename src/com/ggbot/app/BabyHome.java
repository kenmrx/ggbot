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

    /**
     * 列表接口（游客 GET）。personType 分类（2026-11-07 大样本实测）：
     * 0=以未成年人为主但混有历史成年记录（实测见 35/45/65/70 岁）、
     * 1=成年人、2=老年人。服务端过滤不可靠——本功能聚焦儿童，
     * 默认查 0 档并在客户端硬过滤 <18 岁（见 UNDER_AGE_MAX）。
     */
    private static final String LIST_URL =
            "https://www.mca.gov.cn/lljz/jmgc-relief-seeking/familySeekers/querySeekingList"
                    + "?personType=0&releaseTime=&pageNum=1&pageSize=24";
    /** 照片前缀。 */
    public static final String PHOTO_BASE = "https://www.mca.gov.cn/lljz/lljz_files/";
    /** 全国省级行政区（findRescuePlace 以这些名称开头）。 */
    public static final String[] PROVINCES = {
            "北京", "天津", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江",
            "上海", "江苏", "浙江", "安徽", "福建", "江西", "山东", "河南",
            "湖北", "湖南", "广东", "广西", "海南", "重庆", "四川", "贵州",
            "云南", "西藏", "陕西", "甘肃", "青海", "宁夏", "新疆",
            "香港", "澳门", "台湾",
    };

    /** 官网入口（lljz 壳页，公网直达）。 */
    public static final String OFFICIAL_SITE = "https://www.mca.gov.cn/lljz/";
    public static final String POLICE_PHONE = "110";

    /** 儿童判定上限（周岁，含 17）。 */
    static final int UNDER_AGE_MAX = 17;

    /** 每批展示的目标条数：省份过滤后也尽量凑满。 */
    static final int BATCH_SIZE = 10;

    /** 同 id 去重（翻页补齐时可能重叠）。 */
    static boolean containsChild(List<Child> list, Child c) {
        for (Child o : list) {
            if (o.number != null && o.number.equals(c.number)) return true;
        }
        return false;
    }

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
        return fetch(1, null);
    }

    /**
     * 带页码与省份过滤的拉取。
     *
     * @param pageNum  页码（刷新翻页用）
     * @param province 省份名（如 "河南"）；null=不过滤。在客户端按
     *                 findRescuePlace 前缀匹配——服务端无地域参数。
     */
    public static Result fetch(int pageNum, String province) {
        try {
            List<Child> list = fetchList(pageNum);
            if (province != null && !province.isEmpty()) {
                // 省份模式：跨页扫捞该省全部记录（单省总量少，两三页就完，
                // 一次给全比「每批10个」更符合「看看我们省的孩子」的预期）。
                // 从用户选定的页开始，向后最多扫 10 页或 total 上限。
                List<Child> local = new ArrayList<>();
                int page = pageNum;
                int attempts = 0;
                while (attempts < 10) {
                    for (Child c : list) {
                        if (c.place != null && c.place.startsWith(province)
                                && !containsChild(local, c)) local.add(c);
                    }
                    if (list.size() < 24) break;        // 不足一页=到头了
                    page++;
                    attempts++;
                    list = fetchList(page);
                }
                list = local;
            }
            Diag.push("宝宝回家：第" + pageNum + "页拉到 " + list.size() + " 条"
                    + (province == null ? "" : "（" + province + "）"), "info");
            return new Result(list, null);
        } catch (Exception e) {
            String why = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            Diag.push("宝宝回家拉取失败: " + why, "warn");
            return new Result(new ArrayList<>(), why);
        }
    }

    private static List<Child> fetchList(int pageNum) throws Exception {
        // 儿童(personType=0)为主；异常时回落全量再过滤未成年
        try {
            List<Child> r = fetchOne(LIST_URL.replace("pageNum=1", "pageNum=" + pageNum));
            if (!r.isEmpty()) return r;
        } catch (Exception ignored) {
        }
        List<Child> all = fetchOne("https://www.mca.gov.cn/lljz/jmgc-relief-seeking/familySeekers"
                + "/querySeekingList?personType=&releaseTime=&pageNum=" + pageNum + "&pageSize=24");
        List<Child> kids = new ArrayList<>();
        for (Child c : all) {
            if (isUnderAge(c.age)) kids.add(c);
        }
        return kids;
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
            if (!isUnderAge(age)) continue;        // 服务端混排，客户端硬过滤
            out.add(new Child(name, gender, age, height, place, time, unit, number, pic));
        }
        return out;
    }

    /**
     * 儿童年龄判定。age 来自服务端，形态不稳：空串、小数（"6.5"）、
     * 异常大值都见过——解析失败一律当非儿童丢弃，宁缺毋滥。
     */
    public static boolean isUnderAge(String age) {
        if (age == null || age.isEmpty()) return false;
        try {
            double v = Double.parseDouble(age.trim());
            if (v < 0 || v > UNDER_AGE_MAX + 1) return false;   // 边界容错：四舍五入到 18 的 17.5 保留
            return Math.round(v) <= UNDER_AGE_MAX;
        } catch (NumberFormatException e) {
            return false;
        }
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

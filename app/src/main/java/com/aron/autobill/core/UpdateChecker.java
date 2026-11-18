package com.aron.autobill.core;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 远程更新：检查版本 → 下载 APK → 校验哈希。
 *
 * ==================== 支持两种托管方式 ====================
 *
 * 【方式一】自建 update.json（局域网 NAS / 自己的服务器）
 *   {
 *     "versionCode": 10,
 *     "versionName": "2.0",
 *     "apkUrl": "autobill-final.apk",   // 绝对地址或相对 update.json 的地址
 *     "sha256": "ABCDEF...",            // 可选；给了就必须匹配
 *     "notes": "更新说明"                // 可选，可含换行
 *   }
 *
 * 【方式二】GitHub Releases（推荐，不需要局域网）
 *   地址填 `owner/repo` 或完整的 api.github.com/releases 地址。
 *   发布时：打 tag `v2.0`，把 APK 上传为 Release 资产即可。
 *
 *   GitHub 不提供 versionCode，所以从 tag 抠主版本号：
 *     v2.0 → 2   2.1.3 → 2    release-3 → 失败（需要 body 里写 versionCode）
 *
 *   想要精确控制，可在 Release 说明里加一行：
 *     versionCode: 11
 *     sha256: <64位hex>
 *   这两行不会展示给用户（stripMetaLines 会过滤掉）。
 *
 * 设计取舍：
 *  1. 不引任何 JSON 库 —— 协议字段少，手写定点提取器（正则）足够，
 *     GitHub 的响应也只需要 tags/body/assets 三处，没必要为一个字段引整个依赖。
 *  2. 本类不依赖 Android API，全部逻辑（解析/比较/哈希/HTTP 下载）都能在
 *     纯 JVM 自检里跑（HttpURLConnection 支持 file://，自检用本地文件当服务器）。
 *  3. APK 先下到 .part 临时名，哈希通过才改名 —— 半截文件永远不会被当安装包。
 *  4. 必须跟随 302：GitHub 的 Release 资产直链一律重定向到 objects.githubusercontent.com。
 *  5. 必须带 User-Agent：GitHub API 对无 UA 请求直接 403。
 */
public final class UpdateChecker {

    private UpdateChecker() {}

    /** GitHub 需要 UA；也用来表明这是哪个客户端的请求 */
    private static final String UA = "AutoBill-Android-UpdateChecker/2.0";

    /** 重定向最多跟几跳（GitHub 一般 1~2 跳） */
    private static final int MAX_REDIRECT = 5;

    /** 服务器上的版本描述 */
    public static class UpdateInfo {
        public int versionCode;
        public String versionName = "";
        public String apkUrl = "";
        public String sha256 = "";    // 空 = 服务器没给（不校验）
        public String notes = "";
        public boolean hasVersion;    // versionCode 字段是否存在且合法

        @Override public String toString() {
            return "v" + versionName + "(" + versionCode + ") url=" + apkUrl
                    + " sha256=" + (sha256.isEmpty() ? "(无)" : sha256.substring(0, Math.min(12, sha256.length())) + "…");
        }
    }

    /** 下载进度回调（主线程外调用） */
    public interface ProgressListener {
        /** total < 0 表示服务器没给总长度（未知进度） */
        void onProgress(long read, long total);
    }

    // ==================== ① JSON 解析 ====================

    /**
     * 解析 update.json。返回 null = JSON 不可用（缺 versionCode 或格式坏）。
     *
     * 手写定点提取：找 "key" 后面的值，字符串带转义处理，数字直接取。
     * 不做完整 JSON 语法树 —— 只认协议里的 5 个字段，多余字段忽略。
     */
    public static UpdateInfo parse(String json) {
        if (json == null || json.isEmpty()) return null;

        String codeStr = scalar(json, "versionCode");
        if (codeStr == null) return null;
        int code;
        try {
            code = Integer.parseInt(codeStr.trim());
        } catch (NumberFormatException e) {
            return null;               // versionCode 不是整数 = 协议坏了
        }
        if (code <= 0) return null;

        UpdateInfo info = new UpdateInfo();
        info.versionCode = code;
        info.hasVersion = true;

        String v = scalar(json, "versionName");
        if (v != null) info.versionName = v.trim();
        String u = scalar(json, "apkUrl");
        if (u != null) info.apkUrl = u.trim();
        String h = scalar(json, "sha256");
        if (h != null) info.sha256 = h.trim().toUpperCase();
        String n = scalar(json, "notes");
        if (n != null) info.notes = unescape(n);

        return info;
    }

    /**
     * 取 "key": value 中的 value 原文（字符串含引号与转义、数字裸值）。
     *
     * ==================== 为什么不用正则 ====================
     *
     * 原来的实现：
     *   Pattern.compile("\"key\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d+...)")
     *
     * 同样的嵌套量词回溯问题。在自写的 update.json（几百字节）上没事，
     * 但 GitHub 的 release body 里常有大量引号、代码块、URL，
     * 配合 13KB 的响应长度足以让正则引擎爆栈。
     *
     * 改成手写扫描后是确定性的 O(n)，再长的响应也不会出问题。
     * 这类「合成数据测不出、真实数据一跑就炸」的 bug，只有拿真响应喂才暴露。
     */
    private static String scalar(String json, String key) {
        if (json == null || key == null) return null;
        String needle = "\"" + key + "\"";
        int from = 0;
        while (true) {
            int k = json.indexOf(needle, from);
            if (k < 0) return null;
            from = k + needle.length();

            // 跳过空白找冒号
            int p = from;
            while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
            if (p >= json.length() || json.charAt(p) != ':') continue;
            p++;
            while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
            if (p >= json.length()) return null;

            char c = json.charAt(p);

            // ---- 字符串值 ----
            if (c == '"') {
                p++;
                StringBuilder sb = new StringBuilder(64);
                while (p < json.length()) {
                    char ch = json.charAt(p);
                    if (ch == '\\' && p + 1 < json.length()) {
                        // 保留转义序列原文（含反斜杠），交给 unescape 处理
                        sb.append(ch).append(json.charAt(p + 1));
                        p += 2;
                        continue;
                    }
                    if (ch == '"') break;
                    sb.append(ch);
                    p++;
                }
                return sb.toString();
            }

            // ---- 数字/布尔/null ----
            int q = p;
            while (q < json.length()) {
                char ch = json.charAt(q);
                if (ch == ',' || ch == '}' || ch == ']' || Character.isWhitespace(ch)) break;
                q++;
            }
            return q > p ? json.substring(p, q) : null;
        }
    }

    /** 解掉 JSON 字符串转义（引号、反斜杠、n/t/r，以及 4 位 hex 的 unicode 转义） */
    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { sb.append(c); continue; }
            char n = s.charAt(++i);
            switch (n) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) { sb.append('u'); }
                    } else sb.append('u');
                    break;
                default: sb.append(n); break;              // \" \\ \/ 直接取后一个字符
            }
        }
        return sb.toString();
    }

    // ==================== ② 版本比较 ====================

    /**
     * 服务器上有没有更新的版本。
     * 严格大于：同版本号不算更新（防止服务器没动版本号时反复提示）。
     */
    public static boolean hasNewer(int localCode, UpdateInfo info) {
        return info != null && info.hasVersion && info.versionCode > localCode;
    }

    /**
     * 把 update.json 里相对的 apkUrl 解析成绝对地址（相对 update.json 所在目录）。
     * 已经是 http/https/file 开头的原样返回。
     */
    public static String resolveApkUrl(String jsonUrl, String apkUrl) {
        if (apkUrl == null || apkUrl.isEmpty()) return "";
        String u = apkUrl.trim();
        if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("file://")) return u;
        int cut = jsonUrl.lastIndexOf('/');
        if (cut < 0) return u;
        return jsonUrl.substring(0, cut + 1) + u;
    }

    // ==================== ③ HTTP 拉取 ====================

    /**
     * 统一的 HTTP GET 连接构造 —— 把「GitHub 需要什么」集中在一处。
     *
     * 三条都是踩出来的，少一条就拉不到：
     *
     *  1) **跟随重定向**。GitHub Release 的资产地址一律 302 跳到
     *     objects.githubusercontent.com，默认 URLConnection 不跟（JDK 的
     *     HttpURLConnection.setInstanceFollowRedirects 对 HttpsURLConnection
     *     在某些版本上不生效）。手动 while 循环最稳，也顺手解决跨协议跳转。
     *
     *  2) **User-Agent**。GitHub API 拒绝没有 UA 的请求，直接 403。
     *     Java 默认 UA 是 "Java/17.0.x"，会被当成脚本流量的特征。
     *
     *  3) **Accept**。API 端要 application/vnd.github+json，
     *     不给我们认识的格式时会回 415 或老版本 schema。
     */
    private static HttpURLConnection openHttp(String url, String accept, int timeoutMs) throws Exception {
        return openHttp(url, accept, timeoutMs, -1);
    }

    /**
     * @param rangeFrom &gt;= 0 时带 Range 头做断点续传（从该字节偏移开始）；
     *                  -1 表示不续传，取完整内容。
     */
    private static HttpURLConnection openHttp(String url, String accept, int timeoutMs, long rangeFrom) throws Exception {
        java.net.URL current = new URL(url);
        for (int hop = 0; hop < MAX_REDIRECT; hop++) {
            java.net.URLConnection raw = current.openConnection();
            raw.setConnectTimeout(timeoutMs);
            raw.setReadTimeout(timeoutMs);
            raw.setRequestProperty("User-Agent", UA);
            if (accept != null) raw.setRequestProperty("Accept", accept);
            if (rangeFrom >= 0) raw.setRequestProperty("Range", "bytes=" + rangeFrom + "-");
            if (!(raw instanceof HttpURLConnection)) {
                throw new IllegalStateException("非 HTTP 协议: " + current);
            }
            HttpURLConnection http = (HttpURLConnection) raw;
            http.setInstanceFollowRedirects(false);
            http.setRequestMethod("GET");

            int code = http.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = http.getHeaderField("Location");
                http.disconnect();
                if (loc == null || loc.isEmpty()) throw new IllegalStateException("重定向无 Location");
                // Location 可能是相对路径，按当前 URL 解析
                current = new URL(current, loc);
                continue;
            }
            if (code != 200 && code != 206) {
                http.disconnect();
                throw new IllegalStateException("HTTP " + code);
            }
            return http;    // 200 = 完整内容；206 = 断点续传响应
        }
        throw new IllegalStateException("重定向超过 " + MAX_REDIRECT + " 次");
    }

    /** GET 文本（UTF-8）。失败抛异常，由调用方转成用户提示。 */
    public static String fetch(String url, int timeoutMs) throws Exception {
        // 自检用 file:// 当服务器，不走 HTTP 协议栈
        if (url.startsWith("file:")) {
            try (InputStream in = new URL(url).openStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toString(StandardCharsets.UTF_8.name());
            }
        }
        HttpURLConnection conn = openHttp(url, "application/vnd.github+json, application/json;q=0.9, */*;q=0.8", timeoutMs);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString(StandardCharsets.UTF_8.name());
        } finally {
            conn.disconnect();
        }
    }

    // ==================== ③b GitHub Releases 适配 ====================

    /**
     * GitHub Releases API 地址。
     *
     *   owner/repo → https://api.github.com/repos/{owner}/{repo}/releases/latest
     *
     * 认不出 owner/repo 就原样返回（调用方贴完整 URL 的情况）。
     */
    public static String githubApiUrl(String ownerRepo) {
        String s = ownerRepo == null ? "" : ownerRepo.trim();
        if (s.isEmpty()) return "";
        if (s.startsWith("http://") || s.startsWith("https://")) return s;
        // 容忍贴成 github.com/owner/repo 或带结尾斜杠
        s = s.replaceFirst("^github\\.com/", "");
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.matches("[^/]+/[^/]+")) return "";
        return "https://api.github.com/repos/" + s + "/releases/latest";
    }

    /**
     * 判断这个 URL 是不是 GitHub Releases API（决定用哪套解析）。
     * 用户也可以直接贴 api.github.com 的完整地址。
     */
    public static boolean isGithubRelease(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains("api.github.com/repos/") && u.contains("/releases");
    }

    /**
     * 解析 GitHub Releases API 的响应为 UpdateInfo。
     *
     * 与自建 update.json 的关键差异 —— **GitHub 不给你 versionCode**。
     * GitHub 的版本标识是 tag_name（如 "v2.0"），而我们用来比较的是整数
     * versionCode。所以必须从 tag 里抠数字：
     *
     *   "v2.0" → 2   "2.0" → 2   "v2.1.3" → 2
     *
     * 这套规则和 build.gradle 的 versionName 对齐：主版本号就是 versionCode。
     * 如果你以后改成 versionCode 11 / versionName "2.1"，tag 也打 v2.1 即可。
     *
     * 另一种更稳的做法：把 versionCode 写进 release body 的固定行，
     * 这里也支持 —— 见 parseCodeFromNotes。tag 解析失败时用它兜底。
     */
    public static UpdateInfo parseGithubRelease(String json) {
        if (json == null || json.isEmpty()) return null;

        String tag = scalar(json, "tag_name");
        String name = scalar(json, "name");
        String body = scalar(json, "body");
        String bodyStr = body == null ? null : unescape(body);

        // 版本号优先级：**body 里显式写死的 versionCode 最高**
        //
        // 为什么不是 tag 优先：tag 是人起的名字，容易被写成 v2.0 而
        // build.gradle 的 versionCode 已经是 10 —— 两者一打架，
        // 用户就会看到「永远有更新」或「永远没更新」。
        // 显式声明是作者主动写的意图，比从名字里猜可靠。
        //
        // 顺序：body 的 versionCode > tag 的第一个数字段
        //
        // 注意判定用 **< 0** 而不是 <= 0：
        // versionCodeFromTag 用 -1 表示「解析失败」，而 **0 是一个合法版本号**。
        // 真实案例：termux 的 tag 是 v0.118.3，第一个数字段是 0 —— 
        // 如果写 <= 0 就会把它当解析失败，直接返回 null，更新永远不可用。
        // （这个 bug 是在拿真实 GitHub 响应测试时才发现的，合成数据里没有 v0.x 的 tag。）
        int code = parseCodeFromNotes(bodyStr);
        if (code < 0 && tag != null) code = versionCodeFromTag(unescape(tag));
        if (code < 0) return null;

        UpdateInfo info = new UpdateInfo();
        info.versionCode = code;
        info.hasVersion = true;

        String tagStr = tag == null ? "" : unescape(tag).trim();
        // versionName 优先用 tag（去掉前导 v），没有就用 name
        if (!tagStr.isEmpty()) {
            info.versionName = tagStr.replaceFirst("^[vV]", "");
        } else if (name != null) {
            info.versionName = unescape(name).trim();
        }

        // APK 下载地址：browser_download_url 是稳定直链（会 302 到 CDN）
        String apk = pickApkAsset(json);
        if (apk == null || apk.isEmpty()) return null;   // 没有 APK 资产 = 这次 release 不发布安装包
        info.apkUrl = apk;

        if (bodyStr != null) {
            // 如果 body 里明确写了 sha256，就拿来校验
            String sh = extractSha256(bodyStr);
            if (sh != null) info.sha256 = sh;
            info.notes = stripMetaLines(bodyStr);
        }
        return info;
    }

    /**
     * 从 tag 抠 versionCode：取第一个数字段。
     * v2.0 → 2 ; 2.1.3 → 2 ; release-3 → -1（没有前导数字，认不出）
     */
    public static int versionCodeFromTag(String tag) {
        if (tag == null) return -1;
        Matcher m = Pattern.compile("^[^0-9]*(\\d+)").matcher(tag.trim());
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** body 里找 "versionCode: 10" / "versionCode=10" 这类显式声明 */
    public static int parseCodeFromNotes(String body) {
        if (body == null) return -1;
        Matcher m = Pattern.compile("versionCode\\s*[:=]\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(body);
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** body 里找 64 位 hex 且前面有 sha256 字样 */
    public static String extractSha256(String body) {
        if (body == null) return null;
        Matcher m = Pattern.compile("sha256\\s*[:=]?\\s*([0-9a-fA-F]{64})", Pattern.CASE_INSENSITIVE).matcher(body);
        return m.find() ? m.group(1).toUpperCase() : null;
    }

    /** 去掉 notes 里给程序看的那几行（versionCode / sha256），只留给用户看的说明 */
    public static String stripMetaLines(String body) {
        if (body == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.toLowerCase().matches("^sha256\\s*[:=].*")) continue;
            if (t.toLowerCase().matches("^versioncode\\s*[:=].*")) continue;
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 在 release 的 assets 里挑 APK。
     *
     * ==================== 为什么不用正则 ====================
     *
     * 最初这里写的是：
     *   Pattern.compile("\"browser_download_url\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
     *
     * 在我手写的 1KB 假响应上工作正常。但换上**真实的 GitHub 响应（13.5KB）**
     * 直接 StackOverflowError —— 嵌套量词 (?:[^"\\]|\\.)* 在长文本遇到
     * 大量引号时会指数级回溯。
     *
     * 这类 bug 只在真实数据上暴露：合成测试的 JSON 太干净了。
     * 现在改成手写扫描：线性时间，无回溯，13KB 也好 1MB 也好都是 O(n)。
     *
     * 挑选逻辑：
     *   1. 找到所有 "browser_download_url" 后面的字符串值
     *   2. 过滤出 .apk 结尾的
     *   3. 优先含 "autobill" 的（仓库里可能挂了别的 apk）
     */
    public static String pickApkAsset(String json) {
        if (json == null) return null;
        final String KEY = "\"browser_download_url\"";
        String first = null;
        int from = 0;
        while (true) {
            int k = json.indexOf(KEY, from);
            if (k < 0) break;
            from = k + KEY.length();

            // 找冒号
            int p = from;
            while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
            if (p >= json.length() || json.charAt(p) != ':') continue;
            p++;

            // 找起始引号
            while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
            if (p >= json.length() || json.charAt(p) != '"') continue;
            p++;

            // 读到未转义的结束引号
            StringBuilder sb = new StringBuilder(128);
            while (p < json.length()) {
                char c = json.charAt(p);
                if (c == '\\' && p + 1 < json.length()) {
                    sb.append(json.charAt(p + 1));   // \" \\ \/ 都取后一个字符本身
                    p += 2;
                    continue;
                }
                if (c == '"') break;
                sb.append(c);
                p++;
            }

            String url = sb.toString();
            if (!url.toLowerCase().endsWith(".apk")) continue;
            if (first == null) first = url;
            if (url.toLowerCase().contains("autobill")) return url;
        }
        return first;
    }

    // ==================== ③c 统一入口 ====================

    /**
     * 按配置的地址取版本信息 —— 自动识别 GitHub Releases 或自建 update.json。
     *
     * 这样设置页只要存一个字符串，两种托管方式都能用，用户不用关心细节。
     *
     * owner/repo 形式的地址走「双通道」：
     *   ① 先试 raw.githubusercontent.com/{repo}/main/update.json
     *      —— CDN 静态分发、无 API 速率限制、国内连通性也好。
     *   ② 失败再试 api.github.com 的 Releases API
     *      —— 该 API 按 IP 限流（未登录 60 次/小时），VPN 共享出口、
     *      公司 NAT 出口很容易撞 403，所以只做兜底，不做首选。
     *   仓库根目录放一份 update.json（apkUrl 填 Release 的永久直链
     *   github.com/{repo}/releases/download/{tag}/{file}.apk，这条链接
     *   本身不过期），即可全程绕开限流。
     */
    public static UpdateInfo fetchInfo(String url, int timeoutMs) throws Exception {
        String ownerRepo = normalizeOwnerRepo(url);
        if (ownerRepo != null) {
            // ① raw 静态 update.json：优先
            Exception rawError = null;
            String raw = "https://raw.githubusercontent.com/" + ownerRepo + "/main/update.json";
            UpdateInfo info = null;
            try {
                info = parse(fetch(raw, timeoutMs));
            } catch (Exception e) {
                rawError = e;
            }
            if (info != null && !info.apkUrl.isEmpty()) return info;

            // ② Releases API：兜底
            try {
                UpdateInfo gh = parseGithubRelease(fetch(githubApiUrl(ownerRepo), timeoutMs));
                if (gh != null) return gh;
            } catch (Exception apiError) {
                String m = String.valueOf(apiError.getMessage());
                if (m.contains("403")) {
                    throw new IllegalStateException(
                            "GitHub API 限流（403）：该接口每小时约 60 次未登录请求，"
                                    + "VPN/共享网络出口容易触发。"
                                    + "请在仓库 main 分支放一份 update.json 后重试（走静态通道，不限流）。",
                            apiError);
                }
                if (rawError != null) throw rawError;
                throw apiError;
            }
            if (rawError != null) throw rawError;
            return null;
        }
        if (isGithubRelease(url)) {
            return parseGithubRelease(fetch(url, timeoutMs));
        }
        return parse(fetch(url, timeoutMs));
    }

    /**
     * 认「owner/repo」写法（容忍 github.com/ 前缀与结尾斜杠）。
     * 完整 URL / 空串返回 null，交给其他分支处理。
     */
    public static String normalizeOwnerRepo(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || t.startsWith("http://") || t.startsWith("https://")) return null;
        t = t.replaceFirst("^github\\.com/", "");
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t.matches("[^/ ]+/[^/ ]+") ? t : null;
    }

    // ==================== ④ 下载 APK ====================

    /**
     * 下载到 dest（先写 dest.part，校验通过才由调用方改名）。
     * 进度回调 throttle 到 ~120ms 一次，避免刷爆 UI。
     *
     * @return 实际写入的字节数
     */
    public static long download(String url, File dest, ProgressListener progress, int timeoutMs) throws Exception {
        File part = new File(dest.getPath() + ".part");

        // 自检用 file:// 当服务器 —— 不走 HTTP 协议栈，也不做重定向。
        // 真实更新永远是 http(s)，这条分支只为让全套断言能在无网络环境跑完。
        if (url.startsWith("file:")) {
            URL src = new URL(url);
            File srcFile = new File(src.toURI());
            long total = srcFile.length();
            // 支持断点续传：跳过已在 .part 里的部分
            long already = part.exists() ? part.length() : 0;
            if (already > total) { already = 0; part.delete(); }    // 源文件变小了，重来
            try (InputStream in = src.openStream();
                 FileOutputStream out = new FileOutputStream(part, already > 0)) {
                long skipped = 0;
                while (skipped < already) {
                    long s = in.skip(already - skipped);
                    if (s <= 0) break;
                    skipped += s;
                }
                byte[] buf = new byte[64 * 1024];
                long read = skipped;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    read += n;
                }
                if (progress != null) progress.onProgress(read, total);
                return read;
            } catch (Exception e) {
                part.delete();
                throw e;
            }
        }

        HttpURLConnection conn = null;
        try {
            // ==================== 断点续传 ====================
            //
            // 手机网络断流太常见了（切 WiFi、地铁进隧道、MIUI 后台限制）。
            // 47MB 的包下到 90% 断了，如果没有续传就得从 0 开始 —— 用户很可能直接放弃。
            //
            // 做法：.part 已存在就带 Range 头从断点续。
            // 服务器返回 206 Partial Content 才是真支持；返回 200 说明不支持 Range，
            // 那就必须清空重下，否则会把整个文件追加到半截文件后面 —— 得到一个更大的坏包。
            long already = part.exists() ? part.length() : 0;
            conn = openHttp(url, "application/octet-stream, */*;q=0.8", timeoutMs,
                    already > 0 ? already : -1);

            int code = conn.getResponseCode();
            boolean resumed = (code == HttpURLConnection.HTTP_PARTIAL);

            if (already > 0 && !resumed) {
                // 服务器不认 Range：丢弃已有部分，从头来
                part.delete();
                already = 0;
                conn.disconnect();
                conn = openHttp(url, "application/octet-stream, */*;q=0.8", timeoutMs);
                code = conn.getResponseCode();
            }

            long contentLen = conn.getContentLengthLong();
            long total = contentLen > 0 ? contentLen + already : -1;

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(part, already > 0)) {  // append 模式
                byte[] buf = new byte[64 * 1024];
                long read = already;
                long lastNotify = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    read += n;
                    long now = System.currentTimeMillis();
                    if (progress != null && now - lastNotify > 120) {
                        lastNotify = now;
                        progress.onProgress(read, total);
                    }
                }
                if (progress != null) progress.onProgress(read, total);
                return read;
            }
        } catch (Exception e) {
            // 失败**保留** .part —— 下次可以从断点继续，而不是白下几十兆。
            // 但如果是"服务器不认 Range"之外的原因导致文件明显不对，
            // promote() 的哈希校验会拦住，不会装坏包。
            throw e;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 下载完成：哈希通过 → part 改名为 dest；不通过 → 删除 part，返回 false */
    public static boolean promote(File dest, String expectSha256) {
        File part = new File(dest.getPath() + ".part");
        if (!part.exists()) return false;
        if (expectSha256 != null && !expectSha256.isEmpty()) {
            String actual = sha256Hex(part);
            if (actual == null || !actual.equalsIgnoreCase(expectSha256.trim())) {
                part.delete();          // 哈希不对 = 包被篡改或下残了，坚决不用
                return false;
            }
        }
        if (dest.exists()) dest.delete();
        return part.renameTo(dest);
    }

    // ==================== ⑤ SHA-256 ====================

    /** 文件的 SHA-256（大写 hex），读失败返回 null */
    public static String sha256Hex(File f) {
        if (f == null || !f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString().toUpperCase();
    }

    /** 校验：期望值为空 = 服务器没提供哈希，跳过校验放行 */
    public static boolean verify(File f, String expectSha256) {
        if (expectSha256 == null || expectSha256.trim().isEmpty()) return true;
        String actual = sha256Hex(f);
        return actual != null && actual.equalsIgnoreCase(expectSha256.trim());
    }
}

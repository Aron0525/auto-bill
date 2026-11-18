package com.aron.autobill.selftest;

import com.aron.autobill.core.UpdateChecker;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 远程更新自检：JSON 解析 / 版本比较 / 相对地址 / SHA-256 / 拉取下载 / 防篡改。
 * 全部用 file:// 本地文件当「服务器」，纯 JVM 可跑，不需要真机。
 */
public class UpdateSelftest {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name + "  -> " + detail); }
    }

    static void eq(String name, Object want, Object got) {
        boolean c = want == null ? got == null : want.equals(got);
        ok(name, c, "期望=" + want + " 实际=" + got);
    }

    public static void main(String[] args) throws Exception {
        testParse();
        testHasNewer();
        testResolve();
        testRawChannel();
        testSha256();
        testFetchDownload();
        testTamper();
        System.out.println();
        System.out.println("==== 远程更新自检: " + pass + " 通过 / " + fail + " 失败 ====");
        if (fail > 0) System.exit(1);
    }

    // ---------- JSON 解析 ----------
    static void testParse() {
        System.out.println("\n【JSON 解析】");

        UpdateChecker.UpdateInfo info = UpdateChecker.parse(
                "{\n" +
                "  \"versionCode\": 6,\n" +
                "  \"versionName\": \"1.6\",\n" +
                "  \"apkUrl\": \"autobill-1.6.apk\",\n" +
                "  \"sha256\": \"abcdef0123456789\",\n" +
                "  \"notes\": \"修复\\\"小数点\\\"\\n新增模板\"\n" +
                "}");
        ok("完整 JSON 能解析", info != null, "null");
        if (info != null) {
            eq("versionCode", 6, info.versionCode);
            eq("versionName", "1.6", info.versionName);
            eq("apkUrl", "autobill-1.6.apk", info.apkUrl);
            eq("sha256 转大写", "ABCDEF0123456789", info.sha256);
            eq("notes 转义还原", "修复\"小数点\"\n新增模板", info.notes);
        }

        // 字段顺序乱、多余字段
        UpdateChecker.UpdateInfo i2 = UpdateChecker.parse(
                "{\"apkUrl\":\"x.apk\",\"foo\":123,\"versionCode\":3,\"notes\":\"无\"}");
        ok("乱序+多余字段", i2 != null && i2.versionCode == 3 && "x.apk".equals(i2.apkUrl),
                String.valueOf(i2));

        // 必填字段缺失 / 非法
        ok("缺 versionCode 返回 null", UpdateChecker.parse("{\"notes\":\"hi\"}") == null, "非 null");
        ok("versionCode 非数字返回 null",
                UpdateChecker.parse("{\"versionCode\":\"abc\"}") == null, "非 null");
        ok("versionCode=0 返回 null",
                UpdateChecker.parse("{\"versionCode\":0}") == null, "非 null");
        ok("空串返回 null", UpdateChecker.parse("") == null, "非 null");
        ok("null 返回 null", UpdateChecker.parse(null) == null, "非 null");

        // 可选字段缺失
        UpdateChecker.UpdateInfo i3 = UpdateChecker.parse("{\"versionCode\":9}");
        ok("可选字段可缺省", i3 != null && i3.apkUrl.isEmpty() && i3.notes.isEmpty()
                && i3.sha256.isEmpty(), String.valueOf(i3));
    }

    // ---------- 版本比较 ----------
    static void testHasNewer() {
        System.out.println("\n【版本比较】");
        UpdateChecker.UpdateInfo newer = UpdateChecker.parse("{\"versionCode\":7}");
        UpdateChecker.UpdateInfo same = UpdateChecker.parse("{\"versionCode\":5}");
        UpdateChecker.UpdateInfo older = UpdateChecker.parse("{\"versionCode\":4}");

        ok("服务器更高 → 有更新", UpdateChecker.hasNewer(5, newer), "false");
        ok("同版本 → 不提示", !UpdateChecker.hasNewer(5, same), "true");
        ok("服务器更低 → 不提示", !UpdateChecker.hasNewer(5, older), "true");
        ok("null → 不提示", !UpdateChecker.hasNewer(5, null), "true");
    }

    // ---------- 相对地址 ----------
    static void testResolve() {
        System.out.println("\n【地址解析】");
        String base = "http://192.168.1.8:8000/autobill/update.json";
        eq("相对路径拼接", "http://192.168.1.8:8000/autobill/a.apk",
                UpdateChecker.resolveApkUrl(base, "a.apk"));
        eq("绝对 http 原样", "http://cdn/x.apk", UpdateChecker.resolveApkUrl(base, "http://cdn/x.apk"));
        eq("绝对 https 原样", "https://cdn/x.apk", UpdateChecker.resolveApkUrl(base, "https://cdn/x.apk"));
        eq("file 原样", "file:///tmp/a.apk", UpdateChecker.resolveApkUrl(base, "file:///tmp/a.apk"));
        eq("空地址", "", UpdateChecker.resolveApkUrl(base, ""));
        eq("null 地址", "", UpdateChecker.resolveApkUrl(base, null));

        // 基准不是地址：用户填的是 owner/repo。以前会拼出 "Aron0525/a.apk"
        // （无 scheme），下载时报 "no protocol"，排查全靠猜。
        eq("owner/repo 当基准 → 无法解析", "",
                UpdateChecker.resolveApkUrl("Aron0525/auto-bill", "a.apk"));
        eq("空基准 → 无法解析", "", UpdateChecker.resolveApkUrl("", "a.apk"));
    }

    // ---------- raw 静态通道（相对 apkUrl 必须在这里补全） ----------
    static void testRawChannel() throws Exception {
        System.out.println("\n【raw 静态通道】");

        eq("raw 地址 main", "https://raw.githubusercontent.com/a/b/main/update.json",
                UpdateChecker.rawUpdateUrl("a/b", "main"));
        eq("raw 地址 master", "https://raw.githubusercontent.com/a/b/master/update.json",
                UpdateChecker.rawUpdateUrl("a/b", "master"));

        File dir = tempDir();
        File json = new File(dir, "update.json");
        String body = "{\"versionCode\":13,\"versionName\":\"2.3\",\"apkUrl\":\"autobill-v2.3.apk\"}";
        try (FileOutputStream out = new FileOutputStream(json)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }

        UpdateChecker.UpdateInfo info = UpdateChecker.fetchFromRaw(json.toURI().toString(), 5000);
        ok("raw 通道能解析", info != null && info.versionCode == 13, String.valueOf(info));
        if (info != null) {
            // 必须是以 update.json 所在目录为基准的绝对地址
            String want = dir.toURI().toString() + "autobill-v2.3.apk";
            eq("相对 apkUrl 补成绝对地址", want, info.apkUrl);
            // 再被 UI 层解析一次也不能变（绝对地址原样返回）
            eq("二次解析不重复拼接", want, UpdateChecker.resolveApkUrl("Aron0525/auto-bill", info.apkUrl));
        }
    }

    // ---------- SHA-256 ----------
    static void testSha256() throws Exception {
        System.out.println("\n【SHA-256】");
        File dir = tempDir();
        // "abc" 的 SHA-256（公开标准值）
        File abc = new File(dir, "abc.txt");
        try (FileOutputStream out = new FileOutputStream(abc)) {
            out.write("abc".getBytes(StandardCharsets.UTF_8));
        }
        eq("已知向量 SHA-256(abc)",
                "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
                UpdateChecker.sha256Hex(abc));

        ok("不存在的文件 → null", UpdateChecker.sha256Hex(new File(dir, "nope")) == null, "非 null");
        ok("verify 空期望值 → 放行", UpdateChecker.verify(abc, ""), "false");
        ok("verify null 期望值 → 放行", UpdateChecker.verify(abc, null), "false");
        ok("verify 正确哈希 → 放行", UpdateChecker.verify(abc,
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "false");
        ok("verify 错误哈希 → 拒绝", !UpdateChecker.verify(abc,
                "0000000000000000000000000000000000000000000000000000000000000000"), "true");
    }

    // ---------- 拉取 + 下载（file:// 当服务器） ----------
    static void testFetchDownload() throws Exception {
        System.out.println("\n【拉取与下载】");
        File dir = tempDir();

        // 假服务器：update.json + apk
        File json = new File(dir, "update.json");
        String body = "{\"versionCode\":7,\"versionName\":\"1.7\",\"apkUrl\":\"app.apk\",\"sha256\":\"\"}";
        try (FileOutputStream out = new FileOutputStream(json)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        File apkSrc = new File(dir, "app.apk");
        byte[] payload = new byte[300_000];          // 300KB，跨多个 64KB buffer
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        try (FileOutputStream out = new FileOutputStream(apkSrc)) {
            out.write(payload);
        }

        String jsonUrl = json.toURI().toString();    // file:///...
        String fetched = UpdateChecker.fetch(jsonUrl, 5000);
        eq("fetch 拉到内容", body, fetched);

        UpdateChecker.UpdateInfo info = UpdateChecker.parse(fetched);
        ok("fetch+parse 串起来", info != null && info.versionCode == 7, String.valueOf(info));

        String apkUrl = UpdateChecker.resolveApkUrl(jsonUrl, info.apkUrl);
        File dest = new File(dir, "dl.apk");
        final long[] lastProgress = {-1, -1};
        long read = UpdateChecker.download(apkUrl, dest, (r, t) -> {
            lastProgress[0] = r; lastProgress[1] = t;
        }, 5000);
        eq("下载字节数", 300_000L, read);
        ok("进度回调被调用", lastProgress[0] == 300_000L, "last=" + lastProgress[0]);
        eq("part 临时文件存在", true, new File(dest.getPath() + ".part").exists());
        ok("还没 promote，正式文件不存在", !dest.exists(), "存在");

        // promote：无哈希 → 直接改名
        ok("无哈希 promote 成功", UpdateChecker.promote(dest, ""), "false");
        ok("part 已改名", !new File(dest.getPath() + ".part").exists() && dest.exists(), "part 还在");
        eq("下载内容与源头一致", UpdateChecker.sha256Hex(apkSrc), UpdateChecker.sha256Hex(dest));
    }

    // ---------- 防篡改：哈希不匹配必须拒绝 ----------
    static void testTamper() throws Exception {
        System.out.println("\n【防篡改】");
        File dir = tempDir();
        File dest = new File(dir, "v.apk");
        File part = new File(dest.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write("evil".getBytes(StandardCharsets.UTF_8));
        }
        String rightHash = UpdateChecker.sha256Hex(part);

        // 正确哈希 → 通过
        ok("哈希正确 promote 通过", UpdateChecker.promote(dest, rightHash), "false");
        ok("promote 后正式文件存在", dest.exists(), "不存在");

        // 错误哈希 → 拒绝且删除 part
        File dest2 = new File(dir, "v2.apk");
        File part2 = new File(dest2.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(part2)) {
            out.write("evil".getBytes(StandardCharsets.UTF_8));
        }
        ok("哈希错误 promote 拒绝",
                !UpdateChecker.promote(dest2, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"), "通过了");
        ok("拒绝后 part 已删除", !part2.exists(), "还在");
        ok("拒绝后正式文件未生成", !dest2.exists(), "生成了");

        // 下载失败不留 part（download 直接异常时 part 删除）
        try {
            UpdateChecker.download("file:///nonexistent-xyz/none.apk", dest2, null, 3000);
            ok("下载不存在的文件抛异常", false, "没抛");
        } catch (Exception e) {
            ok("下载不存在的文件抛异常", true, "");
        }
        ok("失败后没有残留 part", !new File(dest2.getPath() + ".part").exists(), "有残留");
    }

    static File tempDir() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "autobill_upd_" + System.nanoTime());
        dir.mkdirs();
        dir.deleteOnExit();
        return dir;
    }
}

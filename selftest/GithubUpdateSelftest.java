package com.aron.autobill.selftest;

import com.aron.autobill.core.UpdateChecker;

/**
 * GitHub Releases 更新适配考卷。
 *
 * 这轮新增的能力：App 能从 GitHub Releases 直接更新，不再依赖局域网。
 *
 * 三个必须验证的点（都是真机上会踩的坑）：
 *
 *   ① GitHub **不提供 versionCode**，只有 tag_name（"v2.0"）。
 *      而更新判断靠整数版本号比较 —— 所以必须能从 tag 正确抠出数字。
 *      抠错了 → 要么永远提示有更新（刷屏），要么永远提示已最新（不能更新）。
 *
 *   ② Release 的下载地址是 **302 重定向**到 objects.githubusercontent.com。
 *      老的 fetch() 不跟随重定向，会拿到 302 的空 body。这个必须能跟。
 *
 *   ③ 仓库里可能挂了多个 apk 资产，要挑对的那个。
 *
 * 用本地 file:// 当服务器，测纯解析逻辑（不依赖网络）。
 */
public class GithubUpdateSelftest {

    static int pass = 0, fail = 0;

    static void eq(String id, String desc, Object got, Object want) {
        StringBuilder b = new StringBuilder(id);
        while (b.length() < 30) b.append(' ');
        String g = String.valueOf(got), w = String.valueOf(want);
        if (g.equals(w)) {
            pass++;
            System.out.println("  [PASS] " + b + desc + "  = " + g);
        } else {
            fail++;
            System.out.println("  [FAIL] " + b + desc);
            System.out.println("         期望=" + w + "  实际=" + g);
        }
    }

    static void ok(String id, String desc, boolean cond, String detail) {
        StringBuilder b = new StringBuilder(id);
        while (b.length() < 30) b.append(' ');
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + b + desc);
        } else {
            fail++;
            System.out.println("  [FAIL] " + b + desc + "  ← " + detail);
        }
    }

    /** 一份贴近真实的 GitHub Releases latest 响应（只保留用得上的字段） */
    static final String RELEASE_JSON =
        "{\n" +
        "  \"url\": \"https://api.github.com/repos/aron/AutoBill/releases/123\",\n" +
        "  \"tag_name\": \"v2.0\",\n" +
        "  \"name\": \"AutoBill 2.0\",\n" +
        "  \"body\": \"## 更新内容\\n- 新增应用读屏权限\\n- 修复刷商品页假账\\n\\nversionCode: 10\\nsha256: " +
        "D8322FACAB1356DD25148C326519F3E3406FA45C654EEE0C29717B1FB4CFFA59\",\n" +
        "  \"prerelease\": false,\n" +
        "  \"assets\": [\n" +
        "    {\n" +
        "      \"name\": \"autobill-final.apk\",\n" +
        "      \"browser_download_url\": \"https://github.com/aron/AutoBill/releases/download/v2.0/autobill-final.apk\"\n" +
        "    }\n" +
        "  ]\n" +
        "}";

    public static void main(String[] args) {
        System.out.println("=== GitHub Releases 更新适配考卷 ===");
        System.out.println();

        // ---------- A 组：URL 识别 ----------

        eq("api_from_owner_repo", "owner/repo 转 API 地址",
                UpdateChecker.githubApiUrl("aron/AutoBill"),
                "https://api.github.com/repos/aron/AutoBill/releases/latest");

        eq("api_trim_slash", "结尾斜杠被去掉",
                UpdateChecker.githubApiUrl("aron/AutoBill/"),
                "https://api.github.com/repos/aron/AutoBill/releases/latest");

        eq("api_from_github_com", "贴 github.com 前缀也能认",
                UpdateChecker.githubApiUrl("github.com/aron/AutoBill"),
                "https://api.github.com/repos/aron/AutoBill/releases/latest");

        eq("api_full_url_passthru", "完整 API 地址原样返回",
                UpdateChecker.githubApiUrl("https://api.github.com/repos/a/b/releases/latest"),
                "https://api.github.com/repos/a/b/releases/latest");

        eq("api_invalid", "格式不对返回空",
                UpdateChecker.githubApiUrl("no-slash-here"), "");

        ok("is_github_true", "识别 API 地址",
                UpdateChecker.isGithubRelease("https://api.github.com/repos/a/b/releases/latest"),
                "应识别为 GitHub");

        ok("is_github_false", "普通 http 地址不误判",
                !UpdateChecker.isGithubRelease("http://192.168.1.5:8899/update.json"),
                "不应识别为 GitHub");

        // ---------- B 组：解析 release ----------

        {
            UpdateChecker.UpdateInfo info = UpdateChecker.parseGithubRelease(RELEASE_JSON);
            ok("parse_not_null", "能解析出 UpdateInfo", info != null, "返回 null");

            if (info != null) {
                eq("parse_version_code", "versionCode 从 body 覆盖 tag", info.versionCode, 10);
                eq("parse_version_name", "versionName 从 tag 去 v", info.versionName, "2.0");
                eq("parse_apk_url", "APK 直链",
                        info.apkUrl,
                        "https://github.com/aron/AutoBill/releases/download/v2.0/autobill-final.apk");
                eq("parse_sha256", "sha256 从 body 提取",
                        info.sha256,
                        "D8322FACAB1356DD25148C326519F3E3406FA45C654EEE0C29717B1FB4CFFA59");
                ok("parse_notes_clean", "notes 不含 versionCode 行",
                        !info.notes.contains("versionCode"), "notes=" + info.notes);
                ok("parse_notes_clean_sha", "notes 不含 sha256 行",
                        !info.notes.contains("sha256"), "notes=" + info.notes);
                ok("parse_notes_kept", "notes 保留了真正的内容",
                        info.notes.contains("应用读屏权限"), "notes=" + info.notes);
            }
        }

        // ---------- C 组：tag 抠版本号 ----------

        eq("tag_v2_0", "v2.0 → 2", UpdateChecker.versionCodeFromTag("v2.0"), 2);
        eq("tag_2_0", "2.0 → 2", UpdateChecker.versionCodeFromTag("2.0"), 2);
        eq("tag_v2_1_3", "v2.1.3 → 2", UpdateChecker.versionCodeFromTag("v2.1.3"), 2);
        eq("tag_v10", "v10 → 10", UpdateChecker.versionCodeFromTag("v10"), 10);
        eq("tag_V2", "V2 大写也认", UpdateChecker.versionCodeFromTag("V2"), 2);
        eq("tag_plain_digit", "10 → 10", UpdateChecker.versionCodeFromTag("10"), 10);
        eq("tag_no_digit", "无数字 → -1", UpdateChecker.versionCodeFromTag("release-latest"), -1);
        eq("tag_null", "null → -1", UpdateChecker.versionCodeFromTag(null), -1);

        // ---------- D 组：body 里的 versionCode ----------

        eq("body_code", "versionCode: 11",
                UpdateChecker.parseCodeFromNotes("更新说明\nversionCode: 11"), 11);
        eq("body_code_eq", "versionCode=7",
                UpdateChecker.parseCodeFromNotes("versionCode=7"), 7);
        eq("body_code_case", "大小写不敏感",
                UpdateChecker.parseCodeFromNotes("VERSIONCODE: 5"), 5);
        eq("body_code_missing", "没有则 -1",
                UpdateChecker.parseCodeFromNotes("就是一段普通说明"), -1);

        // ---------- E 组：挑 APK 资产 ----------

        {
            // 多个 apk 时优先带 autobill 的
            String multi =
                "{\"assets\":[" +
                "\"browser_download_url\":\"https://x.com/other.apk\"," +
                "\"browser_download_url\":\"https://x.com/autobill-v2.apk\"" +
                "]}";
            eq("pick_prefer_autobill", "多个 apk 优先 autobill",
                    UpdateChecker.pickApkAsset(multi), "https://x.com/autobill-v2.apk");
        }

        {
            String noApk = "{\"assets\":[\"browser_download_url\":\"https://x.com/notes.txt\"]}";
            eq("pick_no_apk", "没有 apk 返回 null",
                    UpdateChecker.pickApkAsset(noApk), null);
        }

        // ---------- F 组：边界 ----------

        eq("parse_empty", "空字符串返回 null",
                UpdateChecker.parseGithubRelease(""), null);
        eq("parse_null", "null 返回 null",
                UpdateChecker.parseGithubRelease(null), null);

        {
            // 有 tag 有 versionCode，但没有 apk 资产 → 视为该 release 不发版
            String noAsset = "{\"tag_name\":\"v3.0\",\"body\":\"versionCode: 11\",\"assets\":[]}";
            eq("parse_no_asset", "无 APK 资产返回 null（不误导用户）",
                    UpdateChecker.parseGithubRelease(noAsset), null);
        }

        {
            // 完全没有 versionCode 来源 → null
            String noCode = "{\"tag_name\":\"latest\",\"assets\":[{\"browser_download_url\":\"https://x.com/a.apk\"}]}";
            eq("parse_no_code", "tag 无数字且 body 无 versionCode → null",
                    UpdateChecker.parseGithubRelease(noCode), null);
        }

        // ---------- G 组：老协议不能退化 ----------

        {
            String oldJson = "{\"versionCode\":9,\"versionName\":\"1.9\","
                    + "\"apkUrl\":\"autobill-final.apk\",\"sha256\":\"ABC\"}";
            UpdateChecker.UpdateInfo info = UpdateChecker.parse(oldJson);
            ok("legacy_still_works", "自建 update.json 仍能解析",
                    info != null && info.versionCode == 9, "解析失败");
        }

        // ---------- H 组：版本比较 ----------

        {
            UpdateChecker.UpdateInfo cur = new UpdateChecker.UpdateInfo();
            cur.versionCode = 10;
            cur.hasVersion = true;
            ok("newer_false", "同版本不算更新",
                    !UpdateChecker.hasNewer(10, cur), "10 vs 10 应为 false");

            UpdateChecker.UpdateInfo next = new UpdateChecker.UpdateInfo();
            next.versionCode = 11;
            next.hasVersion = true;
            ok("newer_true", "更高版本算更新",
                    UpdateChecker.hasNewer(10, next), "11 vs 10 应为 true");
        }

        // ---------- I 组：真实数据暴露的 bug（回归防线） ----------
        //
        // 下面两条都不是我想出来的，是拿真实 GitHub 响应喂给解析器时才炸出来的。
        // 合成数据太干净，测不出这两类问题 —— 所以必须固化成断言。

        // I-1: v0.x 的 tag（termux 就是 v0.118.3）
        //
        //      versionCodeFromTag("v0.118.3") → 0
        //      而 0 是**合法版本号**，不是解析失败。
        //      如果判定写 <= 0，就会把它当失败 → 返回 null → 更新永远不可用。
        //      「解析失败」的哨兵值必须是 -1，判定必须用 < 0。
        eq("tag_v0_x", "v0.118.3 → 0（0 是合法版本号）",
                UpdateChecker.versionCodeFromTag("v0.118.3"), 0);
        eq("tag_v0", "v0 → 0", UpdateChecker.versionCodeFromTag("v0"), 0);

        {
            String v0Release = "{\"tag_name\":\"v0.5\",\"name\":\"v0.5\",\"body\":\"普通说明\","
                    + "\"assets\":[{\"browser_download_url\":\"https://x.com/a.apk\"}]}";
            UpdateChecker.UpdateInfo i = UpdateChecker.parseGithubRelease(v0Release);
            ok("parse_v0_not_null", "v0.5 的 release 不能返回 null（曾被 <=0 误判）",
                    i != null && i.versionCode == 0,
                    i == null ? "返回 null（把 0 当成解析失败）" : "versionCode=" + i.versionCode);
        }

        // I-2: 大响应不能爆栈
        //
        //      原实现用正则 "(?:[^"\\]|\\.)*" 取字段值，
        //      13KB 真实响应直接 StackOverflowError（嵌套量词指数回溯）。
        //      这里造 200 个资产 + 大量引号体，验证线性扫描扛得住。
        {
            StringBuilder big = new StringBuilder(100_000);
            big.append("{\"tag_name\":\"v3.0\",\"body\":\"");
            for (int k = 0; k < 2000; k++) big.append("\"quoted\\\"text\\\" ").append(k).append(" ");
            big.append("\",\"assets\":[");
            for (int k = 0; k < 200; k++) {
                if (k > 0) big.append(',');
                big.append("{\"browser_download_url\":\"https://x.com/f").append(k).append(".txt\"}");
            }
            big.append(",{\"browser_download_url\":\"https://x.com/autobill.apk\"}]}");

            long t0 = System.currentTimeMillis();
            UpdateChecker.UpdateInfo i = UpdateChecker.parseGithubRelease(big.toString());
            long ms = System.currentTimeMillis() - t0;

            ok("parse_big_no_crash", "100KB 响应不爆栈（正则回溯问题）",
                    i != null, "解析返回 null");
            if (i != null) {
                eq("parse_big_picks_apk", "大量非 apk 资产里挑出对的",
                        i.apkUrl, "https://x.com/autobill.apk");
            }
            ok("parse_big_fast", "解析 100KB 在 500ms 内完成",
                    ms < 500, "耗时 " + ms + "ms");
        }

        // I-3: body 里有大量引号/转义时 scalar 仍能取到 tag
        {
            StringBuilder b = new StringBuilder();
            b.append("{\"body\":\"");
            for (int k = 0; k < 500; k++) b.append("他说\\\"你好\\\" then \"x\" ");
            b.append("\",\"tag_name\":\"v7\",\"assets\":[{\"browser_download_url\":\"https://x.com/a.apk\"}]}");
            UpdateChecker.UpdateInfo i = UpdateChecker.parseGithubRelease(b.toString());
            ok("scalar_heavy_quotes", "body 满是引号时仍取到 tag=v7",
                    i != null && i.versionCode == 7,
                    i == null ? "解析失败" : "versionCode=" + i.versionCode);
        }

        // ---------- J 组：断点续传（手机网络断流是常态） ----------
        //
        // 场景：47MB 的包下到一半断网了。用户重试时如果从 0 开始，
        // 很容易再次断在同一个地方，最后谁都装不上。
        //
        // 用 file:// 模拟服务器：先下完整包，再人为截断 .part，看续传是否接得上。

        try {
            java.io.File dir = java.io.File.createTempFile("autobill_resume", "");
            dir.delete();
            dir.mkdirs();
            java.io.File srcF = new java.io.File(dir, "src.bin");
            // 造 1MB 测试数据
            byte[] data = new byte[1024 * 1024];
            for (int k = 0; k < data.length; k++) data[k] = (byte) (k % 251);
            java.nio.file.Files.write(srcF.toPath(), data);

            java.io.File dest = new java.io.File(dir, "out.bin");
            java.io.File part = new java.io.File(dir, "out.bin.part");

            // J-1: 正常下载
            long n1 = UpdateChecker.download(srcF.toURI().toString(), dest, null, 5000);
            ok("dl_full", "完整下载 1MB", n1 == data.length, "下了 " + n1);

            // J-2: 模拟断流 —— 把 .part 截断到 300KB（download 只写 .part，不生成 dest）
            ok("dl_part_exists", "download 只写 .part，不直接生成 dest",
                    part.exists() && !dest.exists(),
                    "part=" + part.exists() + " dest=" + dest.exists());

            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(part, "rw");
            raf.setLength(300 * 1024);
            raf.close();
            ok("dl_truncate_setup", "构造半截 .part（300KB）",
                    part.length() == 300 * 1024, "part=" + part.length());

            // J-3: 续传 —— 应从 300KB 处接上，最终文件必须是完整的
            long n2 = UpdateChecker.download(srcF.toURI().toString(), dest, null, 5000);
            ok("dl_resumed_size", "续传后总字节数正确",
                    n2 == data.length, "续传返回 " + n2 + "（应为 " + data.length + "）");

            byte[] got = java.nio.file.Files.readAllBytes(part.toPath());
            ok("dl_resumed_len", "续传后文件长度正确",
                    got.length == data.length, "文件 " + got.length);
            ok("dl_resumed_content", "续传后内容跟源文件逐字节一致",
                    java.util.Arrays.equals(got, data), "内容不一致（续传拼接错位）");

            // J-4: 续传结果也能通过哈希校验
            String srcHash = UpdateChecker.sha256Hex(srcF);
            ok("dl_resume_hash", "续传结果哈希与源一致（promote 会放行）",
                    srcHash != null && srcHash.equals(UpdateChecker.sha256Hex(part)),
                    "哈希不匹配");

            dir.delete();
        } catch (Exception e) {
            ok("dl_resume_exception", "断点续传测试无异常", false, e.toString());
        }

        // ============================================================
        // K. owner/repo 归一化（双通道入口的判定）
        // ============================================================
        eq("norm_plain", "owner/repo 原样识别", "aron/AutoBill",
                UpdateChecker.normalizeOwnerRepo("aron/AutoBill"));
        eq("norm_trim", "前后空格去掉", "a/b",
                UpdateChecker.normalizeOwnerRepo("  a/b  "));
        eq("norm_slash", "结尾斜杠去掉", "a/b",
                UpdateChecker.normalizeOwnerRepo("a/b/"));
        eq("norm_prefix", "容忍 github.com/ 前缀", "a/b",
                UpdateChecker.normalizeOwnerRepo("github.com/a/b"));
        eq("norm_http_null", "完整 http(s) 地址不当 owner/repo", null,
                UpdateChecker.normalizeOwnerRepo("https://api.github.com/repos/a/b/releases/latest"));
        eq("norm_bare_null", "光秃秃一个词不是 owner/repo", null,
                UpdateChecker.normalizeOwnerRepo("AutoBill"));
        eq("norm_empty_null", "空串返回 null", null,
                UpdateChecker.normalizeOwnerRepo("   "));
        eq("norm_null_null", "null 安全", null,
                UpdateChecker.normalizeOwnerRepo(null));

        System.out.println();
        System.out.println("==== GitHub 更新自检: " + pass + " 通过 / " + fail + " 失败 ====");
        if (fail > 0) System.exit(1);
    }
}

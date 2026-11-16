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

        System.out.println();
        System.out.println("==== GitHub 更新自检: " + pass + " 通过 / " + fail + " 失败 ====");
        if (fail > 0) System.exit(1);
    }
}

package com.aron.autobill.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.aron.autobill.core.Prefs;
import com.aron.autobill.core.UpdateChecker;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;

/**
 * 远程更新的完整交互：
 *   设置 → 检查更新 → 填/改更新地址 → 检查 → 有新版 → 下载(进度) → 哈希校验 → 系统安装器
 *
 * 安装走系统 PackageInstaller（ACTION_VIEW + FileProvider），
 * 不需要任何第三方依赖；Android 8+ 先引导用户授予「安装未知应用」。
 */
public final class UpdateFlow {

    private static final String TAG = "AutoBillUpdate";
    private static final int TIMEOUT_MS = 15_000;

    private UpdateFlow() {}

    /** 当前版本号（versionCode） */
    public static int localCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return (int) pi.getLongVersionCode();
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 当前版本名（versionName） */
    public static String localName(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /** 是否允许安装 APK（Android 8+ 是按应用授权；23~25 用全局未知来源开关） */
    public static boolean canInstall(Activity a) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return a.getPackageManager().canRequestPackageInstalls();
        }
        return true;    // 低版本走全局设置，无法按应用查，直接进安装流程再看
    }

    /** 跳到「安装未知应用」授权页 */
    public static void requestInstallPerm(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception e) {
            Toast.makeText(a, "请到系统设置里允许安装未知应用", Toast.LENGTH_LONG).show();
        }
    }

    // ==================== 入口：检查更新 ====================

    public static void show(final Activity activity) {
        final EditText et = new EditText(activity);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setHint("owner/repo 或 https://…/update.json");
        et.setText(Prefs.updateUrl(activity));
        et.setSelectAllOnFocus(true);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tip = new TextView(activity);
        tip.setText("填两种地址都行：\n"
                + "· GitHub 仓库：owner/repo（如 aron/AutoBill）—— 不需要局域网\n"
                + "· 自建服务器：http(s)://…/update.json\n"
                + "当前版本：" + localName(activity) + " (" + localCode(activity) + ")");
        tip.setTextSize(12);
        tip.setPadding(0, pad / 2, 0, 0);
        box.addView(tip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(activity)
                .setTitle("检查更新")
                .setView(box)
                .setPositiveButton("检查", (d, w) -> {
                    String url = et.getText().toString().trim();
                    if (url.isEmpty()) {
                        Toast.makeText(activity, "请先填更新地址", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Prefs.setUpdateUrl(activity, url);
                    check(activity, url);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 检查（后台线程） ====================

    private static void check(final Activity activity, final String jsonUrl) {
        Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                // fetchInfo 自动识别两种托管方式：
                //   · owner/repo 或 api.github.com/... → 按 GitHub Releases 解析
                //   · 其他 http(s)/file 地址 → 按自建 update.json 解析
                final UpdateChecker.UpdateInfo info = UpdateChecker.fetchInfo(jsonUrl, TIMEOUT_MS);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    if (info == null) {
                        fail(activity, "更新信息不可用。\n自建服务器：缺 versionCode 字段或格式不对。\n"
                                + "GitHub：没有找到带 APK 的最新 Release，或 tag 不是 v<数字> 格式。");
                        return;
                    }
                    Log.i(TAG, "检查到服务器版本: " + info);
                    int local = localCode(activity);
                    String localNm = localName(activity);
                    // compare 一次给出三种结论。不再先比 versionCode 是否相等：
                    // GitHub 的 versionCode 是从 tag 猜的，跟本机 12 不是同一个量纲，
                    // 相等判断会得出「已是最新」的假结论。
                    int cmp = UpdateChecker.compare(local, localNm, info);
                    if (cmp == 0) {
                        new MaterialAlertDialogBuilder(activity)
                                .setTitle("已是最新版本")
                                .setMessage("当前 " + localNm + " (" + local + ") 就是服务器上的最新版本。")
                                .setPositiveButton("知道了", null)
                                .show();
                    } else if (cmp > 0) {
                        offerDownload(activity, jsonUrl, info, local, localNm);
                    } else {
                        // 服务器版本比本机小 —— 多见于回滚服务器或地址填错
                        new MaterialAlertDialogBuilder(activity)
                                .setTitle("没有可用更新")
                                .setMessage("服务器版本 " + label(info)
                                        + " 不高于当前版本 " + localNm + " (" + local + ")。")
                                .setPositiveButton("知道了", null)
                                .show();
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "检查更新失败", e);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    fail(activity, "检查更新失败：" + e.getMessage());
                });
            }
        }, "update-check").start();
    }

    private static void fail(Activity a, String msg) {
        new MaterialAlertDialogBuilder(a)
                .setTitle("检查更新失败")
                .setMessage(msg)
                .setPositiveButton("确定", null)
                .show();
    }

    /**
     * 服务器版本的显示文案。
     *
     * tag 派生的 versionCode 只是「从 v2.3 里抠出来的第一个数字」，
     * 跟本机 versionCode 不是一套编号，把它印在括号里会让人以为
     * 新版本(2) 比当前(12) 还老。这种就不显示数字。
     */
    private static String label(UpdateChecker.UpdateInfo info) {
        if (info.codeFromTag) return info.versionName.isEmpty() ? "未知" : info.versionName;
        return info.versionName + " (" + info.versionCode + ")";
    }

    // ==================== 有新版：确认下载 ====================

    private static void offerDownload(final Activity activity, final String jsonUrl,
                                      final UpdateChecker.UpdateInfo info, int local, String localNm) {
        StringBuilder msg = new StringBuilder();
        msg.append("发现新版本：").append(label(info)).append("\n")
           .append("当前版本：").append(localNm).append(" (").append(local).append(")\n");
        if (!info.notes.isEmpty()) {
            msg.append("\n更新说明：\n").append(info.notes).append("\n");
        }
        msg.append("\n校验：").append(info.sha256.isEmpty() ? "服务器未提供哈希，不校验" : "SHA-256");

        new MaterialAlertDialogBuilder(activity)
                .setTitle("发现新版本")
                .setMessage(msg.toString())
                .setPositiveButton("下载并安装", (d, w) -> {
                    if (!canInstall(activity)) {
                        // 先去授权，回来后由 onResume 里保存的状态继续？——
                        // 简化：授权后用户再点一次下载（不自动续，避免状态机）
                        new MaterialAlertDialogBuilder(activity)
                                .setTitle("需要授权")
                                .setMessage("Android 8+ 需要先允许「自动记账」安装未知应用。授权后返回本页，重新走一遍检查更新即可。")
                                .setPositiveButton("去授权", (d2, w2) -> requestInstallPerm(activity))
                                .setNegativeButton("取消", null)
                                .show();
                        return;
                    }
                    download(activity, jsonUrl, info);
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    // ==================== 下载（带进度） ====================

    private static void download(final Activity activity, final String jsonUrl,
                                 final UpdateChecker.UpdateInfo info) {
        final String apkUrl = UpdateChecker.resolveApkUrl(jsonUrl, info.apkUrl);
        if (apkUrl.isEmpty()) {
            fail(activity, "更新描述里没有 apkUrl");
            return;
        }
        final File dir = new File(activity.getExternalFilesDir("update") != null
                ? activity.getExternalFilesDir("update")
                : activity.getFilesDir(), "");
        if (!dir.exists()) dir.mkdirs();
        // 文件名必须能区分版本：tag 派生的 versionCode 里 v2.3 和 v2.4 都是 2，
        // 用数字命名会让两版共用 same.apk —— 断点续传把旧包当半截包续，
        // 或者直接把上一版当这一版装上。
        final File dest = new File(dir, "autobill-" + versionTagOf(info) + ".apk");

        // 进度对话框
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);
        final TextView tvMsg = new TextView(activity);
        tvMsg.setText("准备下载…");
        box.addView(tvMsg, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final ProgressBar bar = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 2;
        box.addView(bar, lp);

        final MaterialAlertDialogBuilder dlg = new MaterialAlertDialogBuilder(activity)
                .setTitle("下载更新")
                .setView(box)
                .setNegativeButton("取消", null)
                .setCancelable(false);
        // 用 show() 拿到 dialog，取消时置标志让线程停下
        final androidx.appcompat.app.AlertDialog dialog = dlg.show();
        final boolean[] cancelled = {false};
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> {
            cancelled[0] = true;
            dialog.dismiss();
        });

        new Thread(() -> {
            try {
                UpdateChecker.download(apkUrl, dest, (read, total) ->
                        activity.runOnUiThread(() -> {
                            if (total > 0) {
                                int pct = (int) (read * 100 / total);
                                bar.setProgress(pct);
                                tvMsg.setText(pct + "%  " + (read / 1024) + " / " + (total / 1024) + " KB");
                            } else {
                                tvMsg.setText((read / 1024) + " KB…");
                            }
                        }), TIMEOUT_MS);

                if (cancelled[0]) return;
                // 哈希校验（.part → .apk）
                if (!UpdateChecker.promote(dest, info.sha256)) {
                    activity.runOnUiThread(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        dialog.dismiss();
                        fail(activity, "安装包校验未通过（SHA-256 不匹配），已删除下载文件。"
                                + "\n可能是传输损坏或包被篡改，请重试。");
                    });
                    return;
                }
                final File apk = dest;
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    dialog.dismiss();
                    Log.i(TAG, "下载完成并校验通过: " + apk + " sha=" + UpdateChecker.sha256Hex(apk));
                    install(activity, apk);
                });
            } catch (Exception e) {
                Log.w(TAG, "下载失败", e);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    dialog.dismiss();
                    fail(activity, "下载失败：" + e.getMessage());
                });
            }
        }, "update-download").start();
    }

    /**
     * 下载文件名里的版本标识：优先 versionName（v2.3 / v2.4 分得开），
     * 没有可用版本名时退回 versionCode。只留安全字符。
     */
    private static String versionTagOf(UpdateChecker.UpdateInfo info) {
        String s = info.versionName == null ? "" : info.versionName.trim();
        if (s.isEmpty()) s = String.valueOf(info.versionCode);
        s = s.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() ? String.valueOf(info.versionCode) : s;
    }

    // ==================== 唤起系统安装器 ====================

    public static void install(Activity activity, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!apk.exists() || !UpdateChecker.verify(apk, null)) {
                fail(activity, "安装包不存在");
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(i);
        } else {
            // Android 6 直接给文件路径（系统安装器接受 file://）
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        }
    }
}

package com.aron.autobill;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.aron.autobill.core.Prefs;

/**
 * 开机自启 —— 手机重启后把保活服务拉回来。
 *
 * 没有这个，每次重启手机后自动记账就静默失效了，
 * 而用户完全看不出来（App 打开一切正常，只是不再记账）。
 *
 * ==================== MIUI 的现实 ====================
 *
 * 光有这个 Receiver **不够**。MIUI 的「自启动」开关关着时，
 * 系统根本不会把这个广播投递给我们。实测日志：
 *
 *   AutoStartManagerServiceStubImpl: MIUILOG- Reject service : ... uid : 10264
 *
 * 所以真正的兜底是两条腿走路：
 *   1. 这个 Receiver（自启动允许时生效）
 *   2. MainActivity.onCreate 里也调 KeepAliveService.start()
 *      —— 用户只要打开过 App，服务就回来了
 *
 * 设置页里有引导用户去打开 MIUI 自启动的入口，
 * 那是唯一能同时修好两者的动作。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "AutoBillBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (action == null) return;

        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);

        if (!boot) return;

        if (!Prefs.autoEnabled(context)) {
            Log.i(TAG, "开机：自动记账已关闭，不启动");
            return;
        }

        Log.i(TAG, "开机：启动保活服务");
        KeepAliveService.start(context);
    }
}

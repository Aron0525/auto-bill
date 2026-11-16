package com.aron.autobill;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.aron.autobill.core.BillParser;
import com.aron.autobill.core.BillStore;

/**
 * 端到端自检入口：把一段文本按指定「来源包名」丢进真实的落账管道，
 * 验证解析 → 去重 → 置信度分档 → 落库 → 待确认队列的完整链路。
 *
 * 用法：
 *   adb shell am broadcast -a com.aron.autobill.TEST_PIPE \
 *       --es text "微信支付 收款到账 ¥36.50 华莱士" \
 *       --es pkg com.tencent.mm \
 *       -p com.aron.autobill
 *
 * 存在的理由：真机没法伪造「其他应用发来的通知」，
 * 而这套管道是这个 App 的全部价值所在，必须能单独验证。
 */
public class TestPipeReceiver extends BroadcastReceiver {

    private static final String TAG = "AutoBillTest";

    @Override public void onReceive(Context c, Intent i) {
        String text = i.getStringExtra("text");
        String pkg = i.getStringExtra("pkg");
        if (pkg == null || pkg.isEmpty()) pkg = "com.tencent.mm";
        if (text == null || text.isEmpty()) {
            Log.w(TAG, "TEST_PIPE 缺少 --es text");
            return;
        }
        Log.i(TAG, "===== TEST_PIPE 开始 =====");
        Log.i(TAG, "来源=" + pkg + " 文本=" + text);

        BillStore store = new BillStore(c);
        BillParser parser = new BillParser();
        try {
            BillProcessor.process(c, store, parser, pkg, text, "test");
            int total = store.count();
            com.aron.autobill.core.PendingStore ps = new com.aron.autobill.core.PendingStore(c);
            int pend = ps.count();
            ps.close();
            Log.i(TAG, "===== TEST_PIPE 结束：账本 " + total + " 条，待确认 " + pend + " 条 =====");
        } catch (Throwable t) {
            Log.e(TAG, "TEST_PIPE 异常: " + t, t);
        } finally {
            store.close();
        }
    }
}

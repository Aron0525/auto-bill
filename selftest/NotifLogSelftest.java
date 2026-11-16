import com.aron.autobill.core.NotifLog;

/**
 * NotifLog 自检 —— 通知监控面板的运行时记录器。
 *
 * 为什么要单独测它：这一页是用户判断「到底有没有在收到通知」的唯一依据。
 * 如果记录器本身有 bug（比如计数错、环形缓冲溢出、healthLine 结论说反），
 * 会把用户引向完全错误的排查方向 —— 那比不做监控更糟。
 */
public class NotifLogSelftest {

    private static int pass = 0, fail = 0;

    public static void main(String[] args) {
        testBasicRecord();
        testVerdictCounting();
        testRingOverflow();
        testOrderNewestFirst();
        testHealthLineStates();
        testClearList();
        testNullSafety();
        testSummaryTruncate();

        System.out.println("==== NotifLog 自检: " + pass + " 通过 / " + fail + " 失败 ====");
        if (fail > 0) System.exit(1);
    }

    // ---------- 基础记录 ----------

    private static void testBasicRecord() {
        NotifLog.markStart(1000L);
        check("basic_started", NotifLog.started());
        check("basic_startAt", NotifLog.startAt() == 1000L);
        check("basic_empty", NotifLog.size() == 0);

        NotifLog.record(2000L, "com.tencent.mm", "微信支付",
                "微信支付 支付成功 88.50 元", NotifLog.ACCEPT, "PARSE");

        check("basic_size1", NotifLog.size() == 1);
        check("basic_total1", NotifLog.total() == 1);
        check("basic_accepted1", NotifLog.accepted() == 1);
        check("basic_filtered0", NotifLog.filtered() == 0);
        check("basic_lastAt", NotifLog.lastAt() == 2000L);
        check("basic_lastPkg", "com.tencent.mm".equals(NotifLog.lastPkg()));
    }

    // ---------- 判定分类计数 ----------

    private static void testVerdictCounting() {
        NotifLog.markStart(1000L);
        NotifLog.record(2000L, "a", "t", "x", NotifLog.ACCEPT, "r");
        NotifLog.record(3000L, "b", "t", "y", NotifLog.FILTER, "r");
        NotifLog.record(4000L, "c", "t", "z", NotifLog.FILTER, "r");
        NotifLog.record(5000L, "d", "t", "w", NotifLog.DEDUP, "r");
        NotifLog.record(6000L, "e", "t", "v", NotifLog.SKIP, "r");

        check("count_total", NotifLog.total() == 5);
        check("count_accepted", NotifLog.accepted() == 1);
        check("count_filtered", NotifLog.filtered() == 2);   // 只数 FILTER，不含 DEDUP/SKIP
        check("count_size", NotifLog.size() == 5);
    }

    // ---------- 环形缓冲：超容量丢最老的 ----------

    private static void testRingOverflow() {
        NotifLog.markStart(1000L);
        // 塞 CAPACITY + 10 条
        for (int i = 0; i < NotifLog.CAPACITY + 10; i++) {
            NotifLog.record(1000L + i, "pkg" + i, "t", "text" + i, NotifLog.ACCEPT, "r");
        }
        check("ring_size_capped", NotifLog.size() == NotifLog.CAPACITY);
        // 计数器不受容量影响
        check("ring_total_full", NotifLog.total() == NotifLog.CAPACITY + 10);
        // 最新的那条应该是最后一个塞进去的
        check("ring_newest_kept",
                NotifLog.recent().get(0).pkg.equals("pkg" + (NotifLog.CAPACITY + 9)));
        // 最老的那条已被丢弃
        boolean hasOldest = false;
        for (NotifLog.Entry e : NotifLog.recent()) {
            if (e.pkg.equals("pkg0")) { hasOldest = true; break; }
        }
        check("ring_oldest_dropped", !hasOldest);
    }

    // ---------- 顺序：最新在前 ----------

    private static void testOrderNewestFirst() {
        NotifLog.markStart(1000L);
        NotifLog.record(1000L, "first", "t", "a", NotifLog.ACCEPT, "r");
        NotifLog.record(2000L, "second", "t", "b", NotifLog.ACCEPT, "r");
        NotifLog.record(3000L, "third", "t", "c", NotifLog.ACCEPT, "r");

        java.util.List<NotifLog.Entry> l = NotifLog.recent();
        check("order_0_newest", l.get(0).pkg.equals("third"));
        check("order_1_mid", l.get(1).pkg.equals("second"));
        check("order_2_oldest", l.get(2).pkg.equals("first"));
    }

    // ---------- healthLine 的四种结论 ----------

    private static void testHealthLineStates() {
        // 1. 未启动
        NotifLog.markStart(0L);   // 重置
        NotifLog.markStart(1000L);
        // 模拟未启动：直接用一个新状态 —— 但 markStart 会置 started。
        // 用 startAt==0 的路径无法从外部构造，改测「已启动但 0 条」
        String l1 = NotifLog.healthLine(1000L);
        check("health_zero", l1.contains("还没收到"));

        // 2. 收到但全过滤
        NotifLog.record(2000L, "a", "t", "x", NotifLog.FILTER, "r");
        String l2 = NotifLog.healthLine(2000L);
        check("health_all_filtered", l2.contains("全部被过滤"));

        // 3. 有收下
        NotifLog.record(3000L, "b", "t", "y", NotifLog.ACCEPT, "r");
        String l3 = NotifLog.healthLine(3000L);
        check("health_some_accepted", l3.contains("进入解析"));
        check("health_no_alarm_when_ok", !l3.contains("未启动"));

        // 4. 运行时长出现在 0 条的情况
        NotifLog.markStart(0L);
        NotifLog.markStart(60_000L);
        String l4 = NotifLog.healthLine(60_000L + 5 * 60_000L);   // 5 分钟后
        check("health_shows_minutes", l4.contains("5 分钟"));
    }

    // ---------- 清空列表但保留计数 ----------

    private static void testClearList() {
        NotifLog.markStart(1000L);
        NotifLog.record(2000L, "a", "t", "x", NotifLog.ACCEPT, "r");
        NotifLog.record(3000L, "b", "t", "y", NotifLog.ACCEPT, "r");
        int beforeTotal = NotifLog.total();

        NotifLog.clearList();

        check("clear_list_empty", NotifLog.size() == 0);
        check("clear_keeps_total", NotifLog.total() == beforeTotal);   // 计数是老实的
        check("clear_keeps_accepted", NotifLog.accepted() == 2);
    }

    // ---------- null 安全 ----------

    private static void testNullSafety() {
        NotifLog.markStart(1000L);
        // 全 null 不该崩（通知字段确实可能为 null）
        NotifLog.record(2000L, null, null, null, NotifLog.SKIP, null);
        check("null_no_crash", NotifLog.size() == 1);

        NotifLog.Entry e = NotifLog.recent().get(0);
        check("null_pkg_empty", "".equals(e.pkg));
        check("null_title_empty", "".equals(e.title));
        check("null_text_empty", "".equals(e.text));
        check("null_reason_empty", "".equals(e.reason));
        check("null_summary_ok", e.summary() != null);

        check("verdict_name_accept", "收下".equals(NotifLog.verdictName(NotifLog.ACCEPT)));
        check("verdict_name_filter", "过滤".equals(NotifLog.verdictName(NotifLog.FILTER)));
        check("verdict_name_unknown", "?".equals(NotifLog.verdictName(99)));
    }

    // ---------- summary 截断 ----------

    private static void testSummaryTruncate() {
        NotifLog.markStart(1000L);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("长");
        NotifLog.record(2000L, "p", "标题", sb.toString(), NotifLog.ACCEPT, "r");

        NotifLog.Entry e = NotifLog.recent().get(0);
        String s = e.summary();
        check("summary_truncated", s.length() <= 95);
        check("summary_has_ellipsis", s.endsWith("…"));
        check("summary_has_title", s.startsWith("标题"));

        // 换行应被压成空格（面板一行显示）
        NotifLog.markStart(1000L);
        NotifLog.record(2000L, "p", "", "第一行\n第二行", NotifLog.ACCEPT, "r");
        String s2 = NotifLog.recent().get(0).summary();
        check("summary_no_newline", !s2.contains("\n"));
    }

    // ---------- 工具 ----------

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  [PASS] " + name);
        } else {
            fail++;
            System.out.println("  [FAIL] " + name);
        }
    }
}

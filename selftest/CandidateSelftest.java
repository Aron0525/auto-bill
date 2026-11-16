import com.aron.autobill.core.BillParser;
import com.aron.autobill.model.ParseResult;
import java.util.List;

/**
 * 多金额候选解析自检（parseAll）：
 *  账单列表截图 OCR 后含多笔交易，需逐行拆成候选，方向/商户/金额各自正确。
 */
public class CandidateSelftest {

    static int pass = 0, fail = 0;

    /** 期望候选数 + 每笔的 金额(分)/方向 */
    static void check(String label, String text, int expectCount, long[] fens, int[] types) {
        BillParser p = new BillParser();
        List<ParseResult> list = p.parseAll(text, "ocr");
        boolean countOk = list.size() == expectCount;
        boolean detailOk = countOk;
        if (countOk && fens != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).amountFen != fens[i]) detailOk = false;
                if (types != null && list.get(i).type != types[i]) detailOk = false;
            }
        }
        if (countOk && detailOk) {
            pass++;
            System.out.println("PASS  " + label + "  =>  " + render(list));
        } else {
            fail++;
            System.out.println("FAIL  " + label + "  =>  " + render(list)
                    + "  [期望 " + expectCount + " 笔]");
        }
    }

    static String render(List<ParseResult> list) {
        StringBuilder sb = new StringBuilder();
        for (ParseResult r : list) {
            sb.append("[").append(r.type == 2 ? "+" : "-").append(r.amountFen / 100.0)
              .append(" ").append(r.merchant).append("] ");
        }
        return sb.length() == 0 ? "(空)" : sb.toString().trim();
    }

    public static void main(String[] args) {
        // 账单列表：三笔，含 +100 收入（不能被整段"支出"语境带偏）
        check("账单列表3笔",
                "微信支付账单详情\n美团外卖 -36.50元 今天12:30\n滴滴出行 -25.00元 今天09:15\n朋友转账 +100.00元 昨天20:00",
                3,
                new long[]{3650, 2500, 10000},
                new int[]{1, 1, 2});

        // 单笔支付页：只有一个金额
        check("单笔支付",
                "你已成功支付 ¥25.00\n收款方：华莱士",
                1, new long[]{2500}, new int[]{1});

        // 商户名从金额前文本提取
        BillParser p = new BillParser();
        List<ParseResult> l = p.parseAll("星巴克咖啡 -32.00元", "ocr");
        if (l.size() == 1 && l.get(0).merchant.contains("星巴克")) {
            pass++; System.out.println("PASS  商户提取 => " + l.get(0).merchant);
        } else {
            fail++; System.out.println("FAIL  商户提取 => " + render(l));
        }

        // 千分位：1,280.00 应识别为 128000 分，且不被拆成两个
        l = p.parseAll("信用卡消费 1,280.00元", "ocr");
        if (l.size() == 1 && l.get(0).amountFen == 128000) {
            pass++; System.out.println("PASS  千分位 => " + l.get(0).amountFen);
        } else {
            fail++; System.out.println("FAIL  千分位 => " + render(l));
        }

        // 日期/时间/数量不能成为候选
        check("纯噪音无候选",
                "2026年11月8日 12:30 共3笔 订单号2026110812345678",
                0, null, null);

        // 营销短信整体拦截
        check("营销拦截",
                "【中国银行】绑卡首笔消费至高返5美元、消费笔笔返1%消费金",
                0, null, null);

        // 同金额多行只保留一个候选（去重）
        check("同额去重",
                "美团外卖 -36.50元\n滴滴打车 -36.50元",
                1, new long[]{3650}, new int[]{1});

        System.out.println("\n======== 多候选自检：通过 " + pass + " / 失败 " + fail + " ========");
        if (fail > 0) System.exit(1);
    }
}

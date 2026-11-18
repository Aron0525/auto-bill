#!/usr/bin/env bash
# 一键跑完全部纯 JVM 自检（不需要 Android SDK、不需要模拟器）。
#
# 用法
#   bash selftest/run.sh            # 全部跑一遍
#   bash selftest/run.sh Parser     # 只跑类名含 Parser 的
#
# 为什么需要它
#   README 里原来那条 javac 命令漏了 NotifLog 等文件，且 java -cp 用的类名
#   是简名（多数自检类在 com.aron.autobill.selftest 包下），照抄会全部
#   ClassNotFoundException。这里把编译产物、类名、统计口径都固定下来，
#   任何人（和任何 AI）跑出来的数字必须一致。

set -u

cd "$(dirname "$0")/.." || exit 1

OUT=selftest/classes
mkdir -p "$OUT"
# 清掉上一次的 class，避免源文件删了但旧 class 还在、自检跑到已不存在的用例。
# 不用 rm -rf（本机有删除拦截），find -delete 足够且只作用于编译产物目录。
find "$OUT" -type f -name '*.class' -delete 2>/dev/null

# 纯 JVM 依赖：解析器 + 策略 + 模型（凡是 import android.* 的一律不能进来）
CORE="
app/src/main/java/com/aron/autobill/model/Bill.java
app/src/main/java/com/aron/autobill/model/ParseResult.java
app/src/main/java/com/aron/autobill/core/BillParser.java
app/src/main/java/com/aron/autobill/core/Category.java
app/src/main/java/com/aron/autobill/core/OcrTemplates.java
app/src/main/java/com/aron/autobill/core/OcrLine.java
app/src/main/java/com/aron/autobill/core/NotifPolicy.java
app/src/main/java/com/aron/autobill/core/ScreenPolicy.java
app/src/main/java/com/aron/autobill/core/UpdateChecker.java
app/src/main/java/com/aron/autobill/core/NotifLog.java
"

echo "== 编译 =="
if ! javac -encoding UTF-8 -nowarn -d "$OUT" $CORE selftest/*.java; then
    echo "编译失败，自检未运行。"
    exit 1
fi

CLASSES="
ParserSelftest
CategorySelftest
CandidateSelftest
NotifLogSelftest
com.aron.autobill.selftest.OcrSelftest
com.aron.autobill.selftest.TemplateSelftest
com.aron.autobill.selftest.RealImageSelftest
com.aron.autobill.selftest.UpdateSelftest
com.aron.autobill.selftest.UpdateHttpSelftest
com.aron.autobill.selftest.NotifPrecisionSelftest
com.aron.autobill.selftest.ChannelPolicySelftest
com.aron.autobill.selftest.NotifPipelineSelftest
com.aron.autobill.selftest.NoticeSelftest
com.aron.autobill.selftest.GithubUpdateSelftest
com.aron.autobill.selftest.LlmBenchBaselineSelftest
com.aron.autobill.selftest.VisualSortSelftest
com.aron.autobill.selftest.ScreenPolicySelftest
"

FILTER="${1:-}"

PASS=0
FAIL=0
BAD=""

for c in $CLASSES; do
    if [ -n "$FILTER" ] && [[ "$c" != *"$FILTER"* ]]; then continue; fi
    LOG=$(java -Dfile.encoding=UTF-8 -cp "$OUT" "$c" 2>&1)
    # 各套自检的统计行写法不统一（「通过 30 / 0 失败」「19 通过 / 0 失败」
    # 「通过 5 / 失败 0」三种都有），所以按「紧挨着通过/失败的那个数」取。
    LINE=$(printf '%s\n' "$LOG" | grep -E '通过.*失败|失败.*通过' | tail -1)
    if [ -z "$LINE" ]; then
        echo "---- $c"
        printf '%s\n' "$LOG" | tail -5
        BAD="$BAD $c(未输出统计)"
        continue
    fi
    p=$(printf '%s' "$LINE" | grep -oE '([0-9]+ *通过|通过 *[0-9]+)' | grep -oE '[0-9]+' | head -1)
    f=$(printf '%s' "$LINE" | grep -oE '([0-9]+ *失败|失败 *[0-9]+)' | grep -oE '[0-9]+' | head -1)
    p=${p:-0}
    f=${f:-0}
    printf '  %-52s %3s 通过 / %s 失败\n' "$c" "$p" "$f"
    PASS=$((PASS + p))
    FAIL=$((FAIL + f))
    if [ "$f" -ne 0 ]; then
        printf '%s\n' "$LOG" | grep -E "FAIL" | head -10
        BAD="$BAD $c"
    fi
done

echo
echo "================ 合计：通过 $PASS / 失败 $FAIL ================"
if [ -n "$BAD" ]; then
    echo "有问题的自检：$BAD"
    exit 1
fi
exit 0

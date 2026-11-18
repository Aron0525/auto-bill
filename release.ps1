<#
.SYNOPSIS
    AutoBill 一键发布：构建 → GitHub Release → 静态 update.json

.DESCRIPTION
    用法：
        .\release.ps1 -VersionName 2.3 -VersionCode 13 -Notes "修复了xxx"

    流程顺序是有讲究的（避免中间态 404，旧 App 拉到打不开的链接）：
        1. 改 build.gradle 版本号 → assembleDebug
        2. 建 Release（tag = vX.Y）
        3. 直连 uploads.github.com 上传 APK
           —— 实测走代理上传会卡在 state=starter（响应 408，字节没传完），
              uploads.github.com 在本机可直连，所以这里强制直连。
        4. 下载直链回来算 sha256，与本地比对 —— 确认外部真的拿得到包
        5. 最后一步才写 update.json 并 push
           —— raw.githubusercontent.com 上的这份文件一旦被旧 App 读到，
              就会触发下载，所以它必须晚于 Release 生效。

    网络前提（本机实测）：
        github.com:443          被阻断  → git push / api 需走代理 127.0.0.1:7897
        api.github.com          可达，但未登录限流 60 次/小时 → 全程带 token
        uploads.github.com      可达且走代理会失败 → 直连
        raw.githubusercontent.com  走代理可达

    签名：产物用 %USERPROFILE%\.android\debug.keystore。
    线上包与它同证书，故可覆盖安装、账本数据保留。
    ⚠ 这个 keystore 一旦丢失或更换，老用户将无法覆盖升级（只能卸载重装、数据清空）。
      请务必备份该文件。
#>
param(
    [Parameter(Mandatory = $true)][string]$VersionName,
    [Parameter(Mandatory = $true)][int]$VersionCode,
    [string]$Notes = "",
    [string]$Repo = "Aron0525/auto-bill",
    # 本机阻断 github.com 时需要代理；不需要就传空字符串
    [string]$GitProxy = "http://127.0.0.1:7897"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$tag = "v$VersionName"
$assetName = "autobill-v$VersionName.apk"
$gradle = "E:\DSH\REA\小米钱包\android-toolchain\gradle-8.13\bin\gradle.bat"
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot"

function Step($m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }

<#
原生命令的安全调用。

脚本头顶是 $ErrorActionPreference = "Stop"，而 PowerShell 5.1 会把原生命令的
**stderr 一行**当成错误记录；Stop 之下第一行 stderr 就抛异常，$LASTEXITCODE
还没轮到检查。后果很荒唐：gradle 打一行进度、git 打一句 "LF will be replaced"
就能把发版打断，而且报出来是个空 message，看不出任何线索。

所以统一走这个函数：临时把 EAP 放回 Continue，把 stdout/stderr 一起收下来，
按退出码判定成败，失败时把最后 20 行原样打出来。
#>
function Exec([string]$exe, [string[]]$argv, [string]$label) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $out = & $exe @argv 2>&1
        $rc = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    $out | ForEach-Object { Write-Host $_ }
    if ($rc -ne 0) {
        $tail = ($out | Select-Object -Last 20) -join "`n"
        throw "$label 失败（exit=$rc）`n$tail"
    }
    return $out
}

# ---------- ① 版本号 + 构建 ----------
Step "① 构建 v$VersionName (versionCode $VersionCode)"
Set-Location $root
$bg = Join-Path $root "app\build.gradle"
# 必须写成**无 BOM** 的 UTF-8。
# Set-Content -Encoding UTF8 在 PS 5.1 下会加 BOM，Gradle 的 Groovy 解析器读到
# 行首那个 U+FEFF 直接报 "Unexpected character: '?' @ line 1, column 1"，
# 整个构建在这一步就死，而报错信息里看不出跟版本号有任何关系。
$bgText = (Get-Content $bg -Raw -Encoding UTF8) `
    -replace 'versionCode\s+\d+', "versionCode $VersionCode" `
    -replace 'versionName\s+"[^"]+"', "versionName `"$VersionName`""
[System.IO.File]::WriteAllText($bg, $bgText, (New-Object System.Text.UTF8Encoding($false)))

Exec $gradle @("-p", $root, "--offline", "assembleDebug") "构建" | Out-Null

$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash
$size = (Get-Item $apk).Length
Write-Host "APK: $([math]::Round($size/1MB,2)) MB"
Write-Host "SHA256: $sha"

# ---------- token ----------
# 取凭据时 EAP=Stop 会因为 git 往 stderr 说话而中断，这里临时放宽
$prevEap = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$cred = "protocol=https`nhost=github.com`n" | git credential fill 2>$null
$ErrorActionPreference = $prevEap
$tok = ($cred | Select-String -Pattern '^password=(.+)$').Matches.Groups[1].Value
if (-not $tok) { throw "未能从 Git 凭据管理器取到 GitHub token" }
$H = @{ Authorization = "Bearer $tok"; Accept = "application/vnd.github+json";
        "User-Agent" = "autobill-release" }

# ---------- ② 建 Release ----------
Step "② 创建 Release $tag"
# body 里带两行给程序看的元数据（App 的 stripMetaLines 会把它们从更新说明里剔掉）：
#   versionCode —— GitHub 没有这个字段，不写的话 App 只能从 tag 里抠第一段数字，
#                  v2.3 抠出来是 2，跟真实的 12 不是一套编号，只能靠版本名比较。
#   sha256      —— 让走 API 通道的客户端也能校验安装包。
$body = @{ tag_name = $tag; name = $tag
           body = "## 更新内容`n$Notes`n`nversionCode: $VersionCode`nsha256: $sha"
           draft = $false; prerelease = $false } | ConvertTo-Json
try {
    $rel = Invoke-RestMethod -Method Post -Proxy $GitProxy -TimeoutSec 40 -Headers $H `
        -Uri "https://api.github.com/repos/$Repo/releases" -Body $body `
        -ContentType "application/json; charset=utf-8"
} catch {
    if ($_.Exception.Response.StatusCode -eq 422) { throw "tag $tag 已存在，请换版本号" }
    throw
}
Write-Host "Release id=$($rel.id)"

# ---------- ③ 上传 APK（直连，代理会卡 starter）----------
Step "③ 上传 APK（直连 uploads.github.com）"
$tmp = Join-Path $env:TEMP "asset.json"
Exec "curl.exe" @("-sS", "--connect-timeout", "20", "--max-time", "600",
    "-H", "Authorization: Bearer $tok",
    "-H", "Content-Type: application/vnd.android.package-archive",
    "--data-binary", "@$apk",
    "-o", $tmp,
    "-w", "HTTP %{http_code}  %{size_upload} bytes  %{time_total}s",
    "https://uploads.github.com/repos/$Repo/releases/$($rel.id)/assets?name=$assetName") | Out-Null
$a = Get-Content $tmp -Raw | ConvertFrom-Json
if ($a.state -ne "uploaded") {
    throw "上传未完成（state=$($a.state)）。可稍后重试或删除该资产重传：`n  https://api.github.com/repos/$Repo/releases/assets/$($a.id)"
}
Write-Host "state=uploaded id=$($a.id)"

# ---------- ④ 回读直链校验 ----------
Step "④ 校验外部可下载 + 哈希一致"
$dlUrl = "https://github.com/$Repo/releases/download/$tag/$assetName"
$r = Invoke-WebRequest -Uri $dlUrl -Proxy $GitProxy -UseBasicParsing -TimeoutSec 300
$remote = (Get-FileHash -InputStream ([System.IO.MemoryStream]::new($r.Content)) -Algorithm SHA256).Hash
if ($remote -ne $sha) { throw "远端哈希不一致：$remote ≠ $sha" }
Write-Host "直链可用，哈希一致 ✔"

# ---------- ⑤ 最后才发布 update.json ----------
Step "⑤ 写 update.json 并推送（更新此刻对外生效）"
# 不用 Set-Content -Encoding UTF8：Windows PowerShell 5.1 会带 BOM，
# 裸 BOM 会让部分严格 JSON 读取方报错。用无 BOM 的显式编码写。
$u = @{ versionCode = $VersionCode; versionName = $VersionName
       apkUrl = $dlUrl; sha256 = $sha; notes = $Notes } | ConvertTo-Json -Depth 5
[System.IO.File]::WriteAllText((Join-Path $root "update.json"), $u,
    (New-Object System.Text.UTF8Encoding($false)))

# git 在 add 时会往 stderr 打 "LF will be replaced by CRLF" 之类警告，
# EAP=Stop 下能把发版打断在半路（此时 Release 已经建好、APK 已经上传，
# 只剩 update.json 没生效，状态很难看）。所以一律走 Exec。
Exec "git" @("add", "build.gradle", "app/build.gradle", "update.json") "git add" | Out-Null
Exec "git" @("commit", "-m", "release: v$VersionName (versionCode $VersionCode)") "git commit" | Out-Null
if ($GitProxy) {
    Exec "git" @("-c", "http.proxy=$GitProxy", "push", "origin", "main") "git push" | Out-Null
} else {
    Exec "git" @("push", "origin", "main") "git push" | Out-Null
}

Write-Host "`n✅ v$VersionName 发布完成。旧版 App 打开「检查更新」即可看到。" -ForegroundColor Green

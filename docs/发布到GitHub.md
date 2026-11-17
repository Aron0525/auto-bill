# 发布到 GitHub（一次性 + 后续发版）

> 本地仓库已经准备好：3 个提交、90 个文件、`.gitignore` 已排除敏感文件。
> 你只需要在 GitHub 网页建仓库，然后执行下面的命令。

---

## 一、首次发布（只做一次）

### 1. 在 GitHub 建仓库

1. 打开 https://github.com/new
2. **Repository name**：`AutoBill`
3. **Visibility**：选 **Public**（必须公开，私有仓库的 Release 下载需要 token，App 里没法存 token）
4. **不要**勾选 "Add a README file" / ".gitignore" / "license" —— 本地已经有了，勾了会冲突
5. 点 **Create repository**

### 2. 推送代码

把下面的 `你的用户名` 换成你的 GitHub 用户名：

```powershell
cd E:\DSH\REA\小米钱包\AutoBill

git remote add origin https://github.com/你的用户名/AutoBill.git
git push -u origin main
```

第一次推送会弹窗要求登录。推荐用 **Personal Access Token** 当密码：

1. 打开 https://github.com/settings/tokens
2. **Generate new token (classic)**
3. **Expires**：90 days 或 No expiration
4. 勾选 **`repo`**（整个 repo 组，含 public_repo）
5. 生成后复制 `ghp_xxxxx`（只显示一次）
6. 推送时：
   - Username：你的 GitHub 用户名
   - Password：**粘贴那个 token**（不是账号密码）

> 如果之前存过错的凭据，先清掉：
> ```powershell
> cmdkey /delete:git:https://github.com
> ```

### 3. 验证推送成功

浏览器打开 `https://github.com/你的用户名/AutoBill`，应该能看到 README、`app/`、`docs/`、`selftest/`。

**确认这些没有上传**（`.gitignore` 已排除，但要亲眼确认一下）：
- ❌ `local.properties`（含本机 SDK 路径）
- ❌ 任何 `.apk` / `.jks` / `keystore`
- ❌ `selftest/classes/`

---

## 二、发布第一个 Release（让 App 能更新）

推送完代码后，还差一个 Release —— App 的更新功能读的是 Release，不是源码。

### 方式 A：网页操作（推荐，最直观）

1. 打开 `https://github.com/你的用户名/AutoBill/releases/new`
2. **Choose a tag** → 输入 `v2.1` → 点 **Create new tag: v2.1 on publish**
3. **Release title**：`AutoBill 2.1`
4. **Describe this release** 填：

```
## 更新内容
- 新增应用读屏权限：逐个 App 三档控制（信任 / 需凭证 / 不读屏）
- 修复刷商品页产生假账：商品列表页不再误记
- 更新支持 GitHub Releases，不再依赖局域网
- 下载支持断点续传，网络中断后可从断点继续

versionCode: 11
sha256: D1C2937976F5BEDA87C2E6CB107DE39F6A101468706E895F0C75F921259DBEA2
```

> ⚠️ `versionCode` 和 `sha256` 这两行**必须保留**。
> App 从 `versionCode` 判断要不要更新（GitHub 本身没有这个字段），
> 从 `sha256` 校验安装包。这两行由程序读取，不会展示给用户。

5. **Attach binaries**：把 `E:\DSH\REA\小米钱包\out\autobill-v2.1.apk` 拖进去
6. 点 **Publish release**

### 方式 B：命令行（装了 GitHub CLI 的话）

```powershell
cd E:\DSH\REA\小米钱包\AutoBill

gh release create v2.1 "E:\DSH\REA\小米钱包\out\autobill-v2.1.apk" `
  --title "AutoBill 2.1" `
  --notes "## 更新内容
- 新增应用读屏权限：逐个 App 三档控制
- 修复刷商品页产生假账
- 更新支持 GitHub Releases，不再依赖局域网
- 下载支持断点续传

versionCode: 11
sha256: D1C2937976F5BEDA87C2E6CB107DE39F6A101468706E895F0C75F921259DBEA2"
```

---

## 三、手机上配置更新地址

App 里：**设置 → 检查更新**，填：

```
你的用户名/AutoBill
```

就这一行，不用写 `https://`。App 会自动转成
`https://api.github.com/repos/你的用户名/AutoBill/releases/latest`。

点「检查」：
- 如果当前装的版本低于 Release 的 `versionCode` → 提示「发现新版本」
- 同版本或更高 → 提示「没有可用更新」

---

## 四、后续发版（每次更新都走这个流程）

```powershell
# 1. 改版本号
#    AutoBill\app\build.gradle:
#      versionCode 11 → 12
#      versionName "2.1" → "2.2"

# 2. 构建
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot"
$env:ANDROID_HOME = "E:\DSH\REA\小米钱包\android-toolchain\sdk"
robocopy "E:\DSH\REA\小米钱包\AutoBill\app\src" "E:\DSH\REA\autobill-build\app\src" /MIR /NFL /NDL /NJH /NJS
Copy-Item "E:\DSH\REA\小米钱包\AutoBill\app\build.gradle" "E:\DSH\REA\autobill-build\app\build.gradle" -Force
cmd /c "E:\DSH\REA\小米钱包\android-toolchain\gradle-8.13\bin\gradle.bat -p E:\DSH\REA\autobill-build --no-daemon assembleDebug > build.log 2>&1"

# 3. 算哈希（Release 说明里要用）
(Get-FileHash "E:\DSH\REA\autobill-build\app\build\outputs\apk\debug\app-debug.apk" -Algorithm SHA256).Hash

# 4. 提交推送
cd E:\DSH\REA\小米钱包\AutoBill
git add -A
git commit -m "release: v2.2"
git push

# 5. 打 tag 并推送
git tag v2.2
git push origin v2.2

# 6. 去 GitHub 建 Release，上传 APK，说明里写 versionCode 和 sha256
```

---

## 五、常见问题

| 问题 | 原因 / 解决 |
|---|---|
| 推送时提示 `Authentication failed` | 用 token 当密码，不是账号密码；也可能是存了旧凭据，`cmdkey /delete:git:https://github.com` |
| App 提示 `检查更新失败：HTTP 403` | GitHub 对短时间密集请求限流（60 次/小时）。等几分钟，或换个网络 |
| App 提示 `没有可用更新` 但明明发了新版 | Release 说明里漏了 `versionCode`，且 tag 数字不比本机大。补上即可 |
| App 提示 `更新信息不可用` | Release 没上传 APK 资产；或 `versionCode` 写错格式 |
| 下载卡在某个百分比 | 网络断了。重试会从断点继续（本版已支持） |
| 下载完成提示「校验未通过」 | Release 说明里的 `sha256` 和实际上传的 APK 不一致。重新算一次 |
| 安装时提示「未查询到 ICP 备案」 | MIUI 对非商店 App 的常规提示，不影响安装。点「继续安装」 |
| 想让仓库私有又能更新 | 不可行。私有仓库的 Release 下载需要 token，而 token 不能存在 App 里。改用自建服务器（见 [远程更新](远程更新.md)）|

---

## 六、仓库内容说明

推上去的只有 `AutoBill/` 目录：

```
AutoBill/
├── README.md               项目介绍
├── LICENSE                 MIT
├── .gitignore              排除 build/、apk、keystore、local.properties
├── app/                    源码
│   ├── build.gradle        versionCode 11 / versionName 2.1
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/aron/autobill/
│       └── res/
├── docs/                   8 篇设计文档
└── selftest/               16 个自检（365 条断言）
```

**没有上传的**（在上级目录，与本仓库无关）：
- `REPORT-AI记账-逆向分析.md` — 小米钱包逆向分析报告
- `unpack/` — 214 MB 反编译产物
- `out/` — 82 MB 构建输出
- `base.apk` — 样本 APK

这些留在本地，不进仓库。

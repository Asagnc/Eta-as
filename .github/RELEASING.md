# Sta 发布流程

## 配置签名 Secrets

发布证书和密码不得提交到 Git。首次使用前，在仓库的
`Settings > Secrets and variables > Actions` 中添加：

- `STA_RELEASE_KEYSTORE_BASE64`：发布证书的 Base64 文本
- `STA_RELEASE_STORE_PASSWORD`：KeyStore 密码
- `STA_RELEASE_KEY_ALIAS`：Key alias
- `STA_RELEASE_KEY_PASSWORD`：Key 密码

macOS 可以用下面的命令复制证书的 Base64 文本：

```bash
base64 < /path/to/Sta-release.jks | tr -d '\n' | pbcopy
```

也可以使用 GitHub CLI。密码类 Secret 不要直接写在命令参数中，运行命令后按提示输入：

```bash
base64 < /path/to/Sta-release.jks | gh secret set STA_RELEASE_KEYSTORE_BASE64
gh secret set STA_RELEASE_STORE_PASSWORD
gh secret set STA_RELEASE_KEY_ALIAS
gh secret set STA_RELEASE_KEY_PASSWORD
```

## 版本号

- `versionCode` = `yyyyMMdd` + 两位当日序号，日期部分取构建当天的北京时间（Asia/Shanghai），
  由 CI 与本地脚本调用 Gradle 时传入。
  它取日期码与 `gradle.properties` 里 `staVersionCodeFloor` 的较大值。
- `staVersionCodeFloor` 记录**已发布版本**的下界，保证任何一次构建都不会低过已发布的包。
  平时不用改；同一天第二次发版需要把序号推进到下一个时，改成目标序号（例如
  `2026092601` → `2026092602`），随后这个序号会成为之后所有构建的下界。
- `versionName` = `sta-as<N>`，`N` 是 GitHub run number；手动触发时可在 `build_number`
  输入框里指定 `N`，留空则用 run number。本地备用出包是 `sta-ac<N>`，序号由
  `scripts/build-release-local.sh` 维护，不与 CI 的编号互相顶替。

## 构建与发布

工作流 `.github/workflows/android-release.yml` 在下列情况运行：

- 向 `sta` 分支推送提交
- 在 GitHub 的 `Actions > Sta Build` 中手动运行

它只构建并上传经过签名与验签的 Release APK，作为 Actions Artifact 保存 14 天；
未配置发布证书的仓库（例如 fork）会退回构建 Debug 包，同样只上传一个 APK。
工作流不创建、修改或发布 GitHub Release，也不消费 git 标签。

发版步骤：

1. 需要把 `versionCode` 推进到当天下一个序号时，先更新 `gradle.properties` 的
   `staVersionCodeFloor` 并提交。
2. 在 `Actions > Sta Build` 手动运行，`build_number` 填本次发版的序号 `N`。
3. 等工作流结束，从 `Artifacts` 下载 `app-release.apk`。
4. 在仓库的 `Releases > Draft a new release` 中新建标签（与 `versionName` 对应，
   例如 `v2.2.2`）、填写 Release Notes 并上传 APK。
5. 检查版本、说明和附件后，由维护者手动发布。

## 签名

Release APK 固定使用 V3 签名方案（`minSdk 36`，V1 服务 API<24、V2 服务 API 24-27
都没有验证场景）。CI 在打包后重签并验签，配置里也显式关闭 V1/V2，两种路径产出的包
签名方案一致，均可用同一个证书覆盖安装。
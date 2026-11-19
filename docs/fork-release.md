# DataBackup 网盘备份 fork：发版准备

## 上游与分支

- DataBackup 只从 `2.0.12` 标签浅克隆，不克隆 `main`，不拉 3.0 开发记录。
- YunX 只从最新正式版 `v1.3.1` 标签浅克隆，作为独立参考目录；不会推送到 YunX。
- GitHub 的 fork 仍共享上游仓库网络。把 fork 的默认分支设为 `cloud-backup`，不要在上游 `main` 开发。

网络和 GitHub CLI 认证正常后执行：

```bash
set -e
login=$(gh api user --jq .login)
gh repo fork XayahSuSuSu/Android-DataBackup --default-branch-only --clone=false
fork="$login/Android-DataBackup"
test "$(gh api "repos/$fork" --jq .parent.full_name)" = XayahSuSuSu/Android-DataBackup
git clone --depth 1 --single-branch --no-tags --branch 2.0.12 \
  https://github.com/XayahSuSuSu/Android-DataBackup.git databackup-fork
git clone --depth 1 --single-branch --no-tags --branch v1.3.1 \
  https://github.com/CYQawa/YunX.git YunX-reference
git -C databackup-fork switch -c cloud-backup
git -C databackup-fork remote rename origin upstream
git -C databackup-fork remote add origin "https://github.com/$fork.git"
```

在 fork 安装两个工作流模板、审核构建配置并提交后，推送 `cloud-backup`，再用 `gh repo edit "$fork" --default-branch cloud-backup` 设置默认分支。推送工作流可能要求额外 `workflow` 权限。检查 fork 的 Actions 是否已启用；按需运行 `foss-ci.yml` 并检查产物。

## 构建与签名

- PR/开发分支使用 `foss-ci.yml`，仅生成四种 ABI 的 FOSS 调试 APK，不需要发布密钥。
- 手动运行 `foss-release.yml`，需要 `release-signing` 环境及 `STORE_BASE64`、`STORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。沿用上游 Gradle 签名环境变量，使用 fork 自己的长期签名密钥；密钥不要提交进 Git。只产出验签后的 APK 和 SHA-256，不自动建 tag 或 Release。
- 正式发版前用独立的 application ID 并确认 APK、版本号和权限不与官方版冲突；保留上游许可和复用代码的来源记录。待功能验收后，在已验证的同一个 commit 打 fork 专用版本 tag，并手动建 **Draft release** 附上 APK、校验文件和发行说明；审核后再公开。

当前状态：本地模板，尚未推送、上传密钥或实跑 GitHub Actions。

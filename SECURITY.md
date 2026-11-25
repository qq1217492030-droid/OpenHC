# 安全说明

- 仓库里只有两处例外，其余凭据一律不进仓库，每次提交都有自动检查
  （[`tools/check_public_tree.py`](tools/check_public_tree.py)）：
  - `tools/platform-key/keys/`：AOSP **公开**测试密钥（私钥是公开的，见该目录 README），
    用于给系统整包也用这把测试密钥签名的车机出平台签名包；
  - `tools/identity/data/openhc-identity.json`：**真实**连接参数（配网和认证用的密钥）。
    **这个仓库必须保持私有**，不要改成公开、不要 fork 成公开仓库。
- 其余密钥、证书、连接参数、安装包都不进仓库；从源码构建要换掉自带那份连接参数时，
  用环境变量 `OPENHC_IDENTITY_DIR` 或 `local.properties` 的 `openhc.identity.dir` 覆盖（见 README「构建」）。
- 往 Issue 里贴日志前先看一遍，别贴配对记录之类的内容。
- 发现安全问题，请用 GitHub 的私密漏洞报告（仓库页「Security」→「Report a vulnerability」），不要公开发 Issue。

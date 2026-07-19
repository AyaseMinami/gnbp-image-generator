# Security Policy / 安全策略

## 中文

GNBP 在本地处理 API Key、提示词和参考图片。提交安全问题时，请勿在
Issue、日志或截图中粘贴真实 API Key、私有端点凭据或个人图片。

Windows 桌面版将 API Key 以明文保存在 Git 忽略的 `GNBP_config.json` 中，
并为了兼容用户提供的中转服务全局关闭 HTTPS 证书验证。该行为已在 README
中明确说明；桌面版使用者应仅连接可信端点并尽量使用可信网络。

尚未公开发布的 Android 版使用 Android Keystore 支持的加密密钥存储，并默认
执行系统 TLS 验证。私有 CA、证书固定、信任全部证书和明文 HTTP 都是按
Profile/端点隔离的显式选择；后两者需要不可忽略的风险确认。详情见
[`docs/ANDROID-RELAY-TRANSPORT-SECURITY.md`](docs/ANDROID-RELAY-TRANSPORT-SECURITY.md)。

发现安全问题时，请优先使用 GitHub 的私密安全报告功能。如果该功能不可用，
可以创建不包含敏感细节的 Issue，请维护者建立私密沟通渠道。

## English

GNBP handles API keys, prompts, and reference images locally. Never include real
API keys, private endpoint credentials, or personal images in issues, logs, or
screenshots.

The Windows desktop edition stores API keys in plain text in the Git-ignored
`GNBP_config.json` and disables HTTPS certificate verification globally for
compatibility with user-provided relay services. This behavior is documented in
the README. Desktop users should connect only to trusted endpoints, preferably
over a trusted network.

The not-yet-public Android edition encrypts keys with an Android
Keystore-backed key and verifies system TLS by default. Private CA, certificate
pinning, trust-all, and cleartext modes are isolated to an explicit profile and
endpoint; the two unsafe fallbacks require a visible acknowledgement. See
[`docs/ANDROID-RELAY-TRANSPORT-SECURITY.md`](docs/ANDROID-RELAY-TRANSPORT-SECURITY.md).

Report vulnerabilities through GitHub private vulnerability reporting when it
is available. Otherwise, open an issue without sensitive details and ask the
maintainer to establish a private communication channel.

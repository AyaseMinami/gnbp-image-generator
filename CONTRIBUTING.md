# Contributing / 贡献指南

## 中文

1. 从 `dev` 创建功能分支。
2. 不要提交 `GNBP_config.json`、`test_api.txt`、API Key、私有端点、签名文件、
   生成图片或构建产物。
3. 保持 API 客户端行为兼容，并为行为修复添加离线测试。
4. 修改桌面代码时运行 `py -3.11 -m unittest discover -s tests -v`。
5. 修改 Android 代码时在 `android/` 运行
   `.\gradlew.bat check assembleDebugAndroidTest`。自动化测试不得调用真实 API。
6. 修改 `contracts/` 中的共享契约时同时运行桌面和 Android 测试。
7. 将 Pull Request 合并目标设为 `dev`。

提交安全问题前请阅读 [SECURITY.md](SECURITY.md)。

## English

1. Create feature branches from `dev`.
2. Never commit `GNBP_config.json`, `test_api.txt`, API keys, private endpoints,
   signing files, generated images, or build output.
3. Preserve API-client compatibility and add offline tests for behavioral fixes.
4. For desktop changes, run `py -3.11 -m unittest discover -s tests -v`.
5. For Android changes, run `.\gradlew.bat check assembleDebugAndroidTest` from
   `android/`. Automated tests must never call a real API.
6. Changes to shared contracts under `contracts/` must run both test suites.
7. Target pull requests at `dev`.

Read [SECURITY.md](SECURITY.md) before reporting security issues.

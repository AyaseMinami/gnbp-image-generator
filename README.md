# GNBP Image Generator

[中文](#中文) | [English](#english)

## 中文

GNBP 是一个 Windows 桌面批量生图工具，通过 Gemini 和兼容 OpenAI
格式的图像 API 生成或编辑图片。当前界面使用 Python 3.11 和 PySide6。

> GNBP 是独立的非官方项目，与 Google、OpenAI 或任何 API 中转服务均无
> 隶属或背书关系。Gemini 和 OpenAI 是其各自权利人的商标。

> **使用前须知：** GNBP 只是 API 客户端，不内置任何生图模型、服务、
> 调用额度或 API Key。使用者必须自行准备兼容的 API 地址、Key 和模型名，
> 并自行承担第三方服务的费用和使用条款。

### 功能

- 管理 Gemini 和 GPT 兼容 API 配置
- 保存提示词预设并添加多张参考图
- 批量生成及动态并发控制
- 实时预览、缩放、历史图库和生成参数复用
- 多套界面主题
- 使用 PyInstaller 打包为单文件 Windows 应用

### 下载与运行

- Windows 10 或更高版本

普通用户可从 [GitHub Releases](https://github.com/AyaseMinami/gnbp-image-generator/releases/latest)
下载最新的 `GNBP-Image-Generator_V<版本号>.exe`，无需安装 Python。

发布的 EXE 尚未进行代码签名，Windows 可能显示 SmartScreen 提示。请只从本仓库
的 Releases 页面下载，并使用 Release notes 中的 SHA-256 校验文件。

### 从源码运行

源码运行需要 Python 3.11。安装经过测试的依赖版本：

```powershell
py -3.11 -m pip install -r requirements.txt
```

然后运行应用：

```powershell
py -3.11 main.py
```

首次启动后，展开 API 配置并填写你自己的 API 类型、地址、Key 和模型名，
保存配置后即可提交生成任务。仓库不会提供或上传任何 API Key。

### 配置与安全说明

API 配置、提示词、界面设置和 API Key 保存在本地的
`GNBP_config.json` 中。该文件已被 Git 忽略，但其中的 API Key 以明文
保存，请将它作为本地凭据文件妥善保护。

为了兼容用户自行配置、可能无法通过 Python 默认证书校验的 API
中转服务，GNBP 当前会关闭 HTTPS 证书验证。这也意味着程序无法防范
中间人攻击。请只使用你信任的 API 地址，并尽量在可信网络环境中运行。

生成的 PNG 会保存提示词和生成参数元数据。写入元数据前，程序会移除
API Key，并将参考图完整路径缩减为文件名。

### 测试

自动化测试完全离线，不会请求真实图像 API：

```powershell
py -3.11 -m unittest discover -s tests -v
```

### 版本与打包

版本号统一定义在 `app_info.py` 的 `APP_VERSION` 中。修改这一处即可同步
更新窗口标题、Qt 应用版本、构建提示和 EXE 文件名。

双击 `打包脚本.bat`，或执行：

```powershell
py -3.11 -m PyInstaller build.spec --clean --noconfirm
```

构建结果为 `dist/GNBP-Image-Generator_V<版本号>.exe`。打包脚本会清理旧的 `build` 和
`dist` 目录、重新生成图标，并在缺少 Python 3.11 时给出明确错误。

### Android 开发状态

原生 Kotlin/Compose Android 版本正在 `android/` 目录开发，目前尚未发布可用的
移动版。构建要求和当前状态请参阅 `android/README.md`；开发计划请参阅
`docs/ANDROID-DEVELOPMENT-PLAN.md`。

### 分支

- `main`：稳定版本，也是 GitHub 默认分支
- `dev`：当前开发分支

### 许可证

GNBP 自有源代码采用 [MIT License](LICENSE) 开源。第三方库和组件继续
遵循其各自的许可证。

参与开发前请阅读 [贡献指南](CONTRIBUTING.md) 和
[开发说明](docs/DEVELOPMENT.md)。安全问题请参阅 [SECURITY.md](SECURITY.md)。

## English

GNBP is a Windows desktop tool for batch image generation and editing through
Gemini and OpenAI-compatible image APIs. The current interface uses Python 3.11
and PySide6.

> GNBP is an independent, unofficial project. It is not affiliated with or
> endorsed by Google, OpenAI, or any API relay provider. Gemini and OpenAI are
> trademarks of their respective owners.

> **Before you start:** GNBP is an API client only. It does not include an image
> generation model, hosted service, usage quota, or API key. You must supply a
> compatible endpoint, key, and model name and accept the provider's costs and terms.

### Features

- Gemini and GPT-compatible API profiles
- Prompt presets and multiple reference images
- Batch generation with adjustable concurrency
- Live preview, zooming, history, and reusable generation metadata
- Multiple UI themes
- Single-file Windows builds with PyInstaller

### Download And Run

- Windows 10 or newer

Download the latest `GNBP-Image-Generator_V<version>.exe` from
[GitHub Releases](https://github.com/AyaseMinami/gnbp-image-generator/releases/latest).
The packaged application does not require Python.

The EXE is not currently code-signed, so Windows may display a SmartScreen
warning. Only download it from this repository's Releases page and verify it
against the SHA-256 value in the release notes.

### Run From Source

Source builds require Python 3.11. Install the tested dependency versions:

```powershell
py -3.11 -m pip install -r requirements.txt
```

Then run the application:

```powershell
py -3.11 main.py
```

On first launch, expand the API settings and enter your own API type, endpoint,
key, and model name. Save the profile before submitting a generation task. This
repository does not provide or upload API keys.

### Configuration And Security

API profiles, prompts, UI settings, and API keys are stored locally in
`GNBP_config.json`. This file is excluded from Git, but its API keys are stored
as plain text. Protect it as you would any other local credential file.

GNBP disables HTTPS certificate verification to support user-provided relay
services whose certificates may not pass Python's default validation. This also
removes protection against man-in-the-middle attacks. Only use API endpoints you
trust, preferably over a trusted network.

Generated PNG files contain the prompt and generation parameters as metadata.
API keys are removed and reference-image paths are reduced to file names before
the metadata is written.

### Tests

The automated tests are fully offline and never contact a real image API:

```powershell
py -3.11 -m unittest discover -s tests -v
```

### Versioning And Build

`APP_VERSION` in `app_info.py` is the single source of truth. Changing that one
value updates the window title, Qt application version, build output, and EXE
file name.

Double-click `打包脚本.bat`, or run:

```powershell
py -3.11 -m PyInstaller build.spec --clean --noconfirm
```

The output is `dist/GNBP-Image-Generator_V<version>.exe`. The build script removes stale
`build` and `dist` directories, regenerates the icon, and reports a clear error
when Python 3.11 is unavailable.

### Android Development Status

A native Kotlin/Compose Android edition is under development in `android/`. It
is not yet a functional mobile release. See `android/README.md` for the current
build requirements and `docs/ANDROID-DEVELOPMENT-PLAN.md` for the roadmap.

### Branches

- `main`: stable releases and the GitHub default branch
- `dev`: active development

### License

GNBP's original source code is released under the [MIT License](LICENSE).
Third-party libraries and components remain subject to their respective
licenses.

Read [CONTRIBUTING.md](CONTRIBUTING.md) and
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) before contributing. See
[SECURITY.md](SECURITY.md) for security reporting guidance.

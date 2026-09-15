# bbai

本仓库现包含一个独立的 Android 原型工程：`/home/runner/work/bbai/bbai/android-app`。

## 项目目标

这是一个 **只读** 的 Nintendo Switch 容器与特殊文件浏览器原型，用于：

- 合法拥有的容器文件识别
- 容器条目浏览与基础元数据查看
- 常见图片/音频/视频预览
- 原始文件导出

本项目**不会**：

- 复制或反编译闭源二进制
- 内置、提取、下载或绕过任何密钥/DRM
- 实现盗版下载、联网抓取内容或自动寻找密钥
- 对加密内容伪造解密结果

对加密或未实现格式，本项目只显示明确状态，并保留未来由用户合法提供密钥后的接口边界。

## 当前工程结构

```text
android-app/
├── app/            # Android Compose 应用
├── nxreader-core/  # 纯 Kotlin/JVM 容器识别与基础解析核心
└── docs/           # Android 子工程补充文档

docs/
├── architecture.md
└── third_party_licenses_template.md
```

## 当前已实现

- Android 最低 API 26、目标 API 35 的 Compose 应用骨架
- Storage Access Framework 文件/目录选择与持久化 URI 权限
- 统一检测接口：扩展名 + magic bytes
- 只读识别：NSP/PFS0、XCI/HFS0、NCA、NCZ、RomFS、ExeFS、CNMT、NACP、NPDM、NRO、NSO、KIP，以及常见图片/音频/视频
- 真实目录读取：PFS0/NSP、HFS0，以及 XCI 根 HFS0
- 浏览功能：目录树、搜索、排序、详情页
- 媒体流程：PNG/JPEG/WebP、MP3/WAV/OGG/FLAC、MP4/WebM 预览；无法直接预览时支持导出回退
- 原始文件导出：带进度、取消、错误提示和日志
- 安全边界：相对路径净化、长整型偏移、流式复制、不联网、不执行导出文件

## 当前未实现

- RomFS / ExeFS 的真实目录读取
- NCA / NCZ 的合法密钥接入后受控解析
- Switch 专有纹理、音频、视频编码的实际解码
- 更持久的后台任务调度（例如 WorkManager 版本）
- 样本验证、格式兼容性验证和设备侧人工测试

## 构建

在仓库根目录执行：

```bash
cd /home/runner/work/bbai/bbai/android-app
./gradlew :app:assembleDebug
```

## 手工验证建议（由用户自行执行）

- 使用 SAF 选择一个合法拥有的 `.nsp`、`.xci` 或特殊文件
- 选择输出目录并确认 URI 权限可持久化
- 浏览条目列表、搜索、排序、查看详情
- 对 PNG/JPEG/WebP、MP3/WAV/OGG/FLAC、MP4/WebM 分别尝试预览
- 导出一个原始文件并确认可取消、可查看日志
- 对 `.nca`、`.ncz`、`.romfs`、`.exefs` 确认显示为不支持或需合法密钥状态

## 第三方依赖许可证清单模板

参见：`/home/runner/work/bbai/bbai/docs/third_party_licenses_template.md`

## 参考与重写策略

参见：`/home/runner/work/bbai/bbai/docs/architecture.md`

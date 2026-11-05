# Tigris 文档

本目录是 Tigris 中文文档的统一入口。根目录 [README](../README.md) 提供产品概览、下载选择和安装入口；英文说明见 [README.en.md](../README.en.md)。

> 当前正式版为 v2.0.16。安装、主页接管、待机唤醒和冷启动是三组独立验收项；历史实机记录不等于对所有同品牌机型或固件的认证。

## 安装、恢复与兼容性

- [安装、升级与还原](installation.md)：APK 能力边界、升级和还原原则。
- [安装兼容性](installation-compatibility.md)：手动、AI、安装器共用规则，以及品牌排查。
- [AI 自适应安装策略](ai-adaptive-installation.md)：能力优先的安装策略和安全门槛。
- [完整替换模式](full-replacement.md)：开发阶段的分阶段目标、限制和验证要求。
- [Aerial 跨品牌适配研究](aerial-cross-vendor-install-study.md)：第三方方案只作为诊断证据，不构成授权。
- [近期问题修复记录](device-issues-45-47-53-54.md)：Sony、HY300 等问题的证据边界。

## 桌面守护、模块与运行机制

- [桌面守护稳定性](guard-stability.md)：Home 接管、服务恢复和清理策略。
- [模块架构](modules.md)：安装器、设备能力和品牌 Adapter 的边界。
- [模块化验收](modular-validation.md)：模块化安装的开发验证记录。
- [Top Shelf / 顶部内容](top-shelf.md)：Dock 顶部内容的数据格式和回退。
- [动态壁纸与可选素材](live-wallpapers.md)：动态壁纸、手机上传和素材边界。
- [视频壁纸玻璃效果](video-wallpaper-glass.md)：视频背景下的玻璃渲染说明。

## 界面、更新与本地化

- [软件更新与按需安装](updater.md)：Tigris、MiracastReceiver、HGTV 的更新策略。
- [欢迎动画验证](welcome-validation.md)：首次欢迎动画的实现和验收记录。
- [设置页设计检查](settings-design-audit.md)：改版前审计记录。
- [设置页液态玻璃验证](settings-glass-validation.md)：改版后的界面行为和验证。
- [更新页液态玻璃验收](update-page-validation.md)：更新页布局和渲染验证。
- [电视端本地化](LOCALIZATION.md)：系统语言跟随和单一 APK 策略。
- [手机伴侣页本地化](PHONE_LOCALIZATION.md)：扫码上传页面的语言识别。

## LG webOS

- [webOS 开发预览安装](webos-preview-install.md)：IPK 下载、安装、启动和还原。
- [webOS AI 安装安全预检](WEBOS_AI_INSTALL.md)：Developer Mode、SSH/Luna 和 Home Helper 的边界。

## 验证记录与发布历史

- [2.0.7 发布候选验证](release-validation-2.0.7.md)
- [2026-10-26 问题与 PR 审查](review-issues-2026-10-26.md)
- [v2.0.11 基线与三项优先事项](three-priorities-validation.md)
- [v2.0.3 预览版](releases/v2.0.3-preview.md)
- [v2.0.4](releases/v2.0.4.md) · [v2.0.5](releases/v2.0.5.md) · [v2.0.6](releases/v2.0.6.md) · [v2.0.7](releases/v2.0.7.md) · [v2.0.8](releases/v2.0.8.md) · [v2.0.9](releases/v2.0.9.md)
- [v2.0.10](releases/v2.0.10.md) · [v2.0.11](releases/v2.0.11.md) · [v2.0.12](releases/v2.0.12.md) · [v2.0.13](releases/v2.0.13.md) · [v2.0.14](releases/v2.0.14.md) · [v2.0.15](releases/v2.0.15.md) · [v2.0.16](releases/v2.0.16.md)
- [webOS 0.1.0 预览版](releases/webos-v0.1.0-preview.1.md)

## 其他

- [Jellyfin / Emby Top Shelf 示例](examples/jellyfin-emby-shelf.py)
- [文档截图目录](screenshots/)

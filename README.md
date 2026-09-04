# 轻解析 MediaParser

一款简洁流畅的媒体解析 Android 应用：粘贴分享链接即可解析短视频 / 图集，支持收藏管理、多线程分片下载与断点续传。

> 纯原生 Kotlin + View 系统实现，零第三方 UI 依赖，安装包仅约 5MB。

---

## 功能特性

- **一键解析**：粘贴 / 输入分享链接，自动识别平台并解析媒体信息（封面、标题、作者、清晰度）。
- **播放与预览**：内置视频播放器与图片查看器，支持缩放、翻页、保存。
- **收藏管理**：
  - 收藏列表按分类组织，支持新建 / 移动分类。
  - **长按媒体**弹出操作面板：下载、移动到分类、取消收藏、删除记录、多选。
  - 多选模式批量操作。
- **多线程分片下载**：通过 HTTP `Range` 请求分块并行下载，断点续传，前台服务保活并展示进度通知。
- **回收站**：软删除可恢复，管理更加安心。
- **沉浸式动效**：底部导航采用阻尼弹簧动画（stiffness / damping ratio 物理模型），Tab 切换与选中态丝滑顺滑。
- **下载管理**：等待 / 下载中 / 暂停 / 完成 / 失败状态可视化，可暂停、恢复、删除任务。

## 界面结构

| Tab | 功能 |
| --- | ---- |
| 解析 | 输入链接解析、历史记录、播放预览 |
| 收藏 | 分类收藏列表、长按操作面板、多选 | 
| 我的 | 下载管理、回收站、设置 |

## 安装

直接下载最新 APK 安装（支持 Android 7.0+，`minSdk 24`）：

- [app-release.apk](release/app-release.apk)（或前往 Releases 页下载）

> 安装包使用 debug 签名，首次安装需允许"未知来源"。

## 构建

环境要求：JDK 17、Android SDK 34+。

```bash
# 生成 release APK（输出在 app/build/outputs/apk/release/）
./gradlew :app:assembleRelease
```

项目无三方 Maven 依赖，除 Kotlin 标准库外均为平台 API，`settings.gradle.kts` 已配置 `google()` / `mavenCentral()` 仓库。

## 技术要点

- **液态玻璃 / 动效**：`Fx.kt` 内置 `GlassBlurView`（Android 12+ RenderEffect 反射兼容，背景降采样后高斯模糊，模糊半径 24dp）与 `Springy` 阻尼弹簧动画器（半隐式欧拉积分驱动，接近临界阻尼）。
- **解析与下载核心**：`MediaService.kt` 前台服务，多线程分片 + `Range` 断点续传 + 原子计数进度同步。
- **数据层**：`DB.kt` 基于 SQLite，覆盖历史 / 收藏 / 分类 / 下载任务 / 回收站五类数据。
- **原生能力**：`jniLibs/` 内置 `libmediaparse.so`（arm64-v8a）提供解析能力。

## 目录结构

```
app/
├── src/main/
│   ├── java/com/qingjiexi/app/
│   │   ├── MainActivity.kt   # 主界面：三 Tab、收藏、操作面板、播放预览
│   │   ├── MediaService.kt   # 解析 + 多线程分片下载（前台服务）
│   │   ├── DB.kt             # SQLite 数据层
│   │   ├── Fx.kt             # 动效引擎：GlassBlurView / Springy 弹簧动画
│   │   ├── Ui.kt             # 通用 UI 组件
│   │   ├── NativeLib.kt      # JNI 桥接
│   │   └── ImageLoader.kt    # 图片加载缓存
│   ├── jniLibs/arm64-v8a/    # 原生解析库
│   └── res/                  # 图标、主题、drawable
└── build.gradle.kts
```

## 版本

- 当前版本：`1.0.1`（详见 [CHANGELOG.md](CHANGELOG.md)）
- 包名：`com.qingjiexi.app`
# 智能天气墨水屏

面向 Android 墨水屏设备的天气信息看板，使用原生 Java 和 Canvas 绘制黑白界面。支持实时天气、空气质量、七日预报、黄历与设备状态展示，可通过自定义 JSON 服务获取数据，也可使用离线演示模式查看界面。

## 功能

- 显示城市、日期、分钟时钟、天气、温度、体感、湿度与风速。
- 显示 PM2.5、七日天气预报及黄历信息。
- 提供 13 个预置地点（含北京市通州区朗清园），支持配置服务器地址和刷新间隔。
- 按城市和服务器保存本地缓存，网络请求失败时保留已有画面。
- 根据接口更新时间、实际观测时间和服务端标记提示数据过期。
- 支持离线演示，并明确标注演示数据。
- 显示网络类型、Wi-Fi 信号与电量，运行期间保持屏幕常亮。
- 使用 1072×1448 基准画布，等比缩放并居中显示，支持应用层黑白切换刷新。

数据轮询仅在应用前台运行时执行，默认每 30 分钟刷新一次，可设置为 60–604800 秒。

## Android 与应用版本

| 配置 | 值 |
| --- | --- |
| applicationId | `com.github.ronghuaxueleng.kindleaidashboard` |
| namespace | `com.github.ronghuaxueleng.remoteeink` |
| minSdk | 17（Android 4.2） |
| targetSdk | 28（Android 9） |
| compileSdk | 33（Android 13） |
| versionCode | 15 |
| versionName | 1.0.5 |

构建工具使用 Android Gradle Plugin 8.7.3、Gradle 8.9、JDK 17。JDK 17 是电脑上的构建要求，设备最低系统版本为 Android 4.2。应用生产代码使用 Android 和 Java 平台 API，无第三方运行时依赖。

## 工程结构

```text
天气/
├── settings.gradle
├── build.gradle
├── gradle.properties
├── gradlew / gradlew.bat
├── local.properties.example
├── tools/                         # Gradle 下载与项目检查脚本
└── app/
    ├── build.gradle
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/github/ronghuaxueleng/remoteeink/
        │   │   ├── MainActivity.java          # 生命周期、设置与刷新调度
        │   │   ├── DashboardCanvasView.java   # 看板绘制与触摸交互
        │   │   ├── DashboardLayout.java       # 排版、格式化与过期判断
        │   │   ├── DashboardFetcher.java      # JSON 网络请求
        │   │   ├── DashboardCache.java        # 本地缓存
        │   │   ├── DashboardJson.java         # 协议解析与数据模型
        │   │   └── DemoData.java              # 离线演示数据
        │   └── res/
        └── test/java/com/github/ronghuaxueleng/remoteeink/
```

## 构建与运行

1. 安装 Android Studio、Android SDK Platform 33 及构建工具，将 Gradle JDK 设为 17。
2. 在本目录首次运行 `gradlew.bat --version`（Windows），或 `sh gradlew --version`（macOS/Linux）。启动脚本会从华为云镜像下载 Gradle 8.9 分发包，根据镜像提供的 SHA-256 校验后解压到 `.gradle-dist/`。Android 插件及 Maven 依赖优先使用阿里云镜像，官方仓库作为回退。
3. 用 Android Studio 打开本目录，等待 Gradle 同步。若未自动生成 SDK 路径，可参考 `local.properties.example` 创建 `local.properties`。
4. 选择 `app`，连接 Android 4.2+ 设备或启动模拟器，点击 Run。

命令行构建前，确认 `JAVA_HOME` 指向 JDK 17；Android Studio 中的 Gradle JDK 设置不会自动改变终端使用的 Java 版本。

Windows：

```bat
gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

macOS/Linux：

```sh
sh gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

构建成功后的调试安装包为 `app/build/outputs/apk/debug/app-debug.apk`，使用本机调试证书签名。

项目的 `gradlew` / `gradlew.bat` 使用本地 Gradle 分发包启动，无需 `gradle-wrapper.jar`。Android Studio 可选择 `.gradle-dist/gradle-8.9` 作为本地 Gradle 安装目录。

如果 Windows PowerShell 本机策略禁止执行下载脚本，可使用 Git Bash 中的 `sh gradlew`，或通过 Android Studio 的 Gradle 配置下载 Gradle。项目不会修改机器执行策略。

Windows / JDK 17 下，Gradle 守护进程保留系统默认编码，以匹配 Java 启动器读取测试进程 `@argfile` 的编码，避免中文目录导致测试类加载失败。Java 源码编译和测试进程显式使用 UTF-8；不要在 `org.gradle.jvmargs` 中强制设置 `-Dfile.encoding=UTF-8`。

## 使用与数据接口

- 启动先读取对应城市与服务器的缓存，没有缓存时展示明确标注的演示数据。
- 长按画面或点击右上角打开设置；点击左上角切换城市。
- 设置中可保存服务器地址、刷新间隔，测试连接与 JSON 协议，或切换离线演示。
- 服务器地址填写基础地址，应用会请求 `/v1/dashboard?city=<中文城市名>`。
- JSON 的 `schema` 必须是 `kindle-dashboard-json/1`，数据区包含 `current`、`air`、`forecast` 和 `almanac`。

本仓库提供 Android 客户端，天气及黄历数据需要由兼容协议的服务端提供。默认地址为 `https://kindle.pptxgen.com`。已保存 HTTP 地址的安装需要在设置中手动更新；Manifest 仍允许明文流量，以支持自定义 HTTP 服务。

内置地点“北京市通州区朗清园（北京参考）”目前使用北京城市级数据，列表与画面会标注参考范围。当前服务对朗清园及通州查询均返回北京，尚不能提供经核实的小区级天气；该地点拥有独立缓存键。

时钟与日期使用 `Asia/Shanghai` 时区。天气与空气质量按接口更新时间和实际观测时间中较早的有效时间计算新鲜度，超过 30 分钟或服务端标记过期时显示提示。

## 验证状态

2026-11-27：在 Windows、JDK 17、Gradle 8.9 环境完成以下验证：

- `python -I tools/verify-project.py`：通过项目结构、XML、包名和版本配置检查。
- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug --offline`：`BUILD SUCCESSFUL`。
- 单元测试：14 个通过，0 失败、0 错误、0 跳过（JSON 与地点配置 7 个，布局与过期判断 7 个），覆盖协议解析、日期校验、预报整理、格式化、过期判断、刷新策略，以及朗清园的北京参考映射。
- lint：0 errors、8 warnings，涉及低版本忽略 `usesCleartextTraffic` 属性、SDK/测试依赖版本提示、未使用资源和设置页字符串国际化提示。
- 已生成 `app/build/outputs/apk/debug/app-debug.apk`，应用 ID、版本和最低系统要求与上述配置一致。

2026-11-27 接口实测：默认服务 HTTP 请求超时；HTTPS 返回 HTTP 200，协议匹配，后续请求返回北京、上海的实时天气、七日预报、空气质量及当日黄历字段。首次北京请求曾返回 `weather_error=fetch_failed`、天气字段为空，后续恢复，说明上游存在间歇性失败。空气观测时间可能早于接口更新时间，仍按客户端过期规则提示；黄历仅验证字段完整和日期一致，未独立核验历法内容。

尚未进行设备安装运行验证。当前全刷实现是 View 层黑白切换；清残影、功耗和休眠行为需在目标墨水屏设备上实测。

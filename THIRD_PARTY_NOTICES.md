# 第三方组件与参考

酷DO 是独立的 Linux.do 第三方客户端，与 Linux.do、酷安及下列项目不存在官方关联。各依赖的版权和许可归其权利人所有。

项目主体代码由 Bang Liu 及贡献者以 [Apache License 2.0](LICENSE) 授权。该协议只适用于本项目自行创作的代码和文件；下表依赖、图标、参考实现及其随包附带的资源仍受各自许可证约束。

| 项目 | 许可 | 用途 |
| --- | --- | --- |
| [AndroidX / Jetpack Compose](https://android.googlesource.com/platform/frameworks/support/) | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | 原生界面、生命周期、导航与测试 |
| [Kotlin](https://github.com/JetBrains/kotlin) / [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache-2.0 | 语言运行库与协程 |
| [Kyant Backdrop 1.0.6](https://github.com/Kyant0/AndroidLiquidGlass) | Apache-2.0 | 背景采样、模糊、玻璃折射及官方底栏、按钮、Slider 与 Dialog 示例源码 |
| [Kyant Shapes 1.2.0](https://github.com/Kyant0/Shapes) | Apache-2.0 | 官方示例的 Capsule 胶囊形状 |
| [OkHttp](https://github.com/square/okhttp) / [Okio](https://github.com/square/okio) | Apache-2.0 | HTTP 与 I/O |
| [Coil](https://github.com/coil-kt/coil) | Apache-2.0 | 图片加载 |
| [Sentry Java / Android Core 8.63.0](https://github.com/getsentry/sentry-java/tree/8.63.0) | MIT | 用户授权后采集必要崩溃与 ANR 信息；[随包许可](app/src/main/assets/licenses/sentry-LICENSE.txt) |
| [Epitaph 0.2.0](https://github.com/abovevacant/epitaph/tree/v0.2.0) | MIT | Sentry Core 的传递依赖，未启用 tombstone 采集；许可见同一随包文件 |
| [Jsoup](https://jsoup.org/) | [MIT](https://jsoup.org/license) | HTML 内容解析 |
| [Font Awesome Free 6.7.2](https://github.com/FortAwesome/Font-Awesome/tree/6.7.2) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)（图标） | 与 Linux.do 官网一致的23种分类图标 |
| [JUnit 4](https://junit.org/junit4/) | [EPL-1.0](https://www.eclipse.org/legal/epl-v10.html) | 测试，不进入正式应用运行代码 |
| [rustls](https://github.com/rustls/rustls)、[Hyper](https://github.com/hyperium/hyper)、[Tokio](https://github.com/tokio-rs/tokio)、[Hickory](https://github.com/hickory-dns/hickory-dns)、[jni-rs](https://github.com/jni-rs/jni-rs) | Apache-2.0 / MIT，具体见各组件 | 原生 DoH、ECH TLS、DNS HTTPS 传输与 JNI |
| [AWS-LC](https://github.com/aws/aws-lc) | Apache-2.0 / ISC 及保留的 OpenSSL、BoringSSL 等许可 | TLS、HPKE / ECH 密码学实现 |
| [webpki-roots](https://github.com/rustls/webpki-roots) | CDLA-Permissive-2.0 | 原生 TLS 公共信任根 |

接口行为研究参考 [FluxDO](https://github.com/Lingyan000/fluxdo)（GPL-3.0），没有复制其 Flutter 实现。酷安 16.6.4 仅作为界面比例和交互研究参考，没有复制其图标、图片或反编译实现。应用图标使用本项目绘制的黄色 K 标记。

DoH / ECH 原生代码由本项目独立实现，没有复用 FluxDO 的 Rust 子模块。确切版本固定在 `native/doh/Cargo.lock`；[完整原生组件许可与 NOTICE](app/src/main/assets/licenses/native-doh-NOTICES.txt)随 APK 分发，含 AWS-LC 保留的第三方条款与 Fiat 许可。`python scripts/native_notices.py` 根据 Android 依赖图重新生成，`--check` 校验同步。jni-sys-macros 0.4.1 发布包缺少工作区许可，补存自其 `.cargo_vcs_info.json` 指定提交 `64d77b7a5f119d7b55b4e2c169a4668067ff59e6` 的原始 LICENSE-MIT / LICENSE-APACHE。

底部导航与详情操作栏改编自 [AndroidLiquidGlass catalog 的 Android 1.0.6 版本](https://github.com/Kyant0/AndroidLiquidGlass/tree/896a94a3ade1cc1a940b92365f942a34971fecda/catalog/src/main/java/com/kyant/backdrop/catalog)。接入 LiquidBottomTabs、LiquidBottomTab、LiquidButton、LiquidSlider、DampedDragAnimation、InteractiveHighlight、DragGestureInspector 七份源码，作者为 Kyant0，许可为 Apache-2.0；修改包名、主题与尺寸、兼容材质、滑动预选、发布隔离和采样层语义，高频拖动直接跟随触点并保留点击弹性与停顿速度复位，按钮增加禁用状态与独立子操作容器，滑块适配主题黄色、整行点击、禁用状态与单值范围。Surface Liquid Button 为官方 LiquidButton 的30%表面色样式。源码位于 `app/src/main/java/com/kudo/android/ui/components/liquid/`，文件头保留来源版本和修改说明；[完整许可](app/src/main/assets/licenses/backdrop-demo-LICENSE.txt) 随 APK 放入 `assets/licenses/`。

设置页的 LiquidModal 材质另外改编自同一版本 `destinations/DialogContent.kt`，保留色彩控制、模糊、深度折射与Plain高光；增加同窗口模态关闭、焦点和背景可访问隔离、底部ActionSheet、主题与兼容材质、安全区及内容滚动。来源与修改说明保留在文件头，使用上述完整Apache-2.0许可。

依赖的确切版本见 `app/build.gradle.kts`；传递依赖可用 `gradlew.bat :app:dependencies --configuration debugRuntimeClasspath` 查询。正式分发时应连同各依赖要求的 LICENSE / NOTICE 保留完整许可文本。

分类图标版权归 Fonticons, Inc.（2024）所有。选取 Font Awesome Free 6.7.2 的23个 SVG，逐一核对与 Linux.do 分类 sprite 的路径及 viewBox 一致，再转换为 Compose ImageVector；原始路径不变，尺寸按比例适配，颜色使用分类配置。实现位于 `ui/components/CategoryIcons.kt`，署名及[完整 CC BY 4.0 许可](app/src/main/assets/licenses/fontawesome-icons-LICENSE.txt)随 APK 分发。

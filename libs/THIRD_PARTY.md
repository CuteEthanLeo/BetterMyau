# libs/ 第三方库

`run-dev.bat` 是离线构建（`--offline` + 独立缓存 `.gradle-ui`），所以音乐播放器用到的两个库以 jar 形式放在这里，
由 `build.gradle.kts` 的 `shadowImpl(files(...))` 打进成品，并重定位到 `myau.deps.*`。

| 文件 | 用途 | 许可证 | 来源 |
|---|---|---|---|
| `jlayer-1.0.1.4.jar` | MP3 解码（`javazoom.jl.decoder`） | LGPL-2.1 | Maven Central `com.googlecode.soundlibs:jlayer:1.0.1.4`（JavaZOOM JLayer 1.0.1） |
| `qrcodegen-1.8.0.jar` | 网易云扫码登录的二维码生成 | MIT | Maven Central `io.nayuki:qrcodegen:1.8.0`，已删去其中的 `module-info.class`（Forge 1.8.9 扫描 jar 时读不了 Java 9 模块描述，会报错） |

JLayer 按 LGPL-2.1 以独立库形式随附（未修改源码）；替换为其它版本只需换掉这个 jar。

## 跨版本 Via（1.13.0 起）

以下库不放在 `libs/`，由 Gradle 从 `repo.viaversion.com` / Maven Central 取得（首次需联网，之后离线缓存在 `.gradle-ui`），
在构建期用 JvmDowngrader 降级到 Java 8、修补后打进成品，并重定位到 `myau.deps.*`。

| 库 | 用途 | 许可证 | 来源 |
|---|---|---|---|
| ViaVersion 5.12.2（含其内置的 fastutil / Gson / SnakeYAML / MCStructs / OpenNBT 精简副本） | 协议转换核心 | GPL-3.0（内置库各自 Apache-2.0 / MIT） | `com.viaversion:viaversion-common:5.12.2` |
| ViaBackwards 5.12.2 | 1.9–1.20.x 客户端进更新服务器 | GPL-3.0 | `com.viaversion:viabackwards-common:5.12.2` |
| ViaRewind 4.2.0 | 1.8 客户端进 1.9+ 服务器 | GPL-3.0 | `com.viaversion:viarewind-common:4.2.0` |
| ViaLegacy 3.1.0 | 1.7.10 及更老（直到经典版）的服务器 | GPL-3.0 | `net.raphimc:ViaLegacy:3.1.0` |
| ViaAprilFools 4.2.3 | 愚人节快照版本 | GPL-3.0 | `com.viaversion:viaaprilfools-common:4.2.3` |
| SeedFinding mc_core / mc_biome / mc_noise / mc_seed / mc_math | ViaLegacy 的远古版本地形 / 群系数据 | MIT | `com.seedfinding:*`（ViaLegacy 的传递依赖） |
| JvmDowngrader java-api 2.0.1（downgraded-8） | 降级后代码用到的 Java 9+ API 兼容桩 | LGPL-2.1 | `xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:2.0.1:downgraded-8` |

BetterMyau 本身是 GPL-3.0，与上述 GPL-3.0 库兼容；分发成品时须同时提供源码（脱敏源码包即可）。
`myau/via/platform/ClientProviders.java` 中 BetaCraft 认证部分改编自 ViaForge（GPL-3.0）。


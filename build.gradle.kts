import org.apache.commons.lang3.SystemUtils
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
plugins {
    idea
    java
    id("gg.essential.loom") version "0.10.0.5"
    id("dev.architectury.architectury-pack200") version "0.1.3"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    // 把 Java 17 的 Via 库降级成 Java 8 字节码（游戏跑在 Java 8 上）。
    id("xyz.wagyourtail.jvmdowngrader") version "2.0.1"
}
//Constants:
val baseGroup: String by project
val mcVersion: String by project
val version: String by project
val mixinGroup = "$baseGroup.mixin"
val modid: String by project
val jarName: String by project
val transformerFile = file("src/main/resources/accesstransformer.cfg")
// Toolchains:
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
}
// Minecraft configuration:
loom {
    launchConfigs {
        "client" {
            // If you don't want mixins, remove these lines
            property("mixin.debug", "true")
            arg("--tweakClass", "org.spongepowered.asm.launch.MixinTweaker")
        }
    }
    runConfigs {
        "client" {
            if (SystemUtils.IS_OS_MAC_OSX) {
                // This argument causes a crash on macOS
                vmArgs.remove("-XstartOnFirstThread")
            }
        }
        remove(getByName("server"))
    }
    forge {
        pack200Provider.set(dev.architectury.pack200.java.Pack200Adapter())
        // If you don't want mixins, remove this lines
        mixinConfig("mixins.$modid.json")
	    if (transformerFile.exists()) {
			println("Installing access transformer")
		    accessTransformer(transformerFile)
	    }
    }
    // If you don't want mixins, remove these lines
    mixin {
        defaultRefmapName.set("mixins.$modid.refmap.json")
    }
}
sourceSets.main {
    output.setResourcesDir(sourceSets.main.flatMap { it.java.classesDirectory })
}
// Dependencies:
repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/maven/")
    // If you don't want to log in with your real minecraft account, remove this line
    maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
    // 跨版本 Via：ViaVersion 全家。只读 POM，不读 Gradle 模块元数据——后者声明 Java 17，
    // 而这些 jar 会在构建期被降级到 Java 8（见下方 via 配置）。
    maven("https://repo.viaversion.com") {
        metadataSources {
            mavenPom()
            artifact()
        }
    }
}
val shadowImpl: Configuration by configurations.creating {
    configurations.implementation.get().extendsFrom(this)
}
// 跨版本 Via 的库（Java 17 字节码）。游戏自带的 netty / guava / gson / log4j 不打包。
// jvmdg.dg 让这个配置解析出来的就是降级到 Java 8 的 jar；兼容桩（java-api）单独打进去，
// 所以这里不让它往每个库里各塞一份（shade = false）。
val viaPatchedJar = layout.buildDirectory.file("via/via-java8.jar")
val via: Configuration by configurations.creating {
    isTransitive = true
    exclude(group = "com.google.guava")
    exclude(group = "com.google.code.gson")
    exclude(group = "org.apache.logging.log4j")
}
jvmdg.downgradeTo.set(JavaVersion.VERSION_1_8)
jvmdg.dg(via, false)

// ── BetterMyau-Inject：构建期改写工具的源码集（独立于主源码集，用 ASM + gson，不碰游戏类） ──
val injectBuild = sourceSets.create("injectBuild")
// 离线自检源码集：只用 JDK，反射驱动重定位后的引擎。
val injectCheck = sourceSets.create("injectCheck")
// 注入器（控制器）源码集：Java 21 + jdk.attach，独立于游戏的 Java 8 工具链。
val injector = sourceSets.create("injector")

dependencies {
    minecraft("com.mojang:minecraft:1.8.9")
    mappings("de.oceanlabs.mcp:mcp_stable:22-1.8.9")
    forge("net.minecraftforge:forge:1.8.9-11.15.1.2318-1.8.9")
    // If you don't want mixins, remove these lines
    shadowImpl("org.spongepowered:mixin:0.7.11-SNAPSHOT") {
        isTransitive = false
    }
    annotationProcessor("org.spongepowered:mixin:0.8.5-SNAPSHOT")
    // Music player: MP3 decoding (JLayer, LGPL-2.1) and QR codes for NetEase sign-in (Nayuki, MIT).
    // Kept as local jars because run-dev.bat builds offline; see libs/THIRD_PARTY.md.
    shadowImpl(files("libs/jlayer-1.0.1.4.jar", "libs/qrcodegen-1.8.0.jar"))
    // If you don't want to log in with your real minecraft account, remove this line
    runtimeOnly("me.djtheredstoner:DevAuth-forge-legacy:1.2.1")

    // 跨版本 Via（全部 GPL-3.0，见 libs/THIRD_PARTY.md）。
    via("com.viaversion:viaversion-common:5.12.2")
    via("com.viaversion:viabackwards-common:5.12.2")
    via("com.viaversion:viarewind-common:4.2.0")
    via("net.raphimc:ViaLegacy:3.1.0")
    via("com.viaversion:viaaprilfools-common:4.2.3")
    // 只给降级器当类路径（解析 Via 里继承的 netty 类型）；游戏自带同版本 netty，不打进成品。
    via("io.netty:netty-all:4.0.23.Final")
    // 降级后的 jar（合并并修补过，见 viaPatchJars）参与编译（javac 8 读不了 61 版 class）并打进成品；
    // 外加 JvmDowngrader 的 Java 8 兼容桩。
    shadowImpl(files(viaPatchedJar).builtBy("viaPatchJars"))
    // 只要兼容桩本身；它的传递依赖（ASM 9、降级器本体）运行时用不到，且 ASM 会和 Forge 自带的 ASM 5 撞类。
    shadowImpl("xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:2.0.1:downgraded-8") {
        isTransitive = false
    }

    // InjectBuilder：离线缓存里已有的 ASM 与 gson。
    "injectBuildImplementation"("org.ow2.asm:asm-debug-all:5.0.3")
    "injectBuildImplementation"("com.google.code.gson:gson:2.2.4")
}
// Tasks:
tasks.withType(JavaCompile::class) {
    options.encoding = "UTF-8"
}
tasks.withType(org.gradle.jvm.tasks.Jar::class) {
    archiveBaseName.set(jarName)
    // Release name: BetterMyau-<mc>-v<version>.jar, e.g. BetterMyau-1.8.9-v1.0.0.jar
    archiveVersion.set("$mcVersion-v$version")
    manifest.attributes.run {
        this["FMLCorePluginContainsFMLMod"] = "true"
        this["ForceLoadAsMod"] = "true"
        // If you don't want mixins, remove these lines
        this["TweakClass"] = "org.spongepowered.asm.launch.MixinTweaker"
        this["MixinConfigs"] = "mixins.$modid.json"
	    if (transformerFile.exists())
			this["FMLAT"] = "${modid}_at.cfg"
    }
}
tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("mcversion", mcVersion)
    inputs.property("modid", modid)
    inputs.property("basePackage", baseGroup)
    filesMatching(listOf("mcmod.info", "mixins.$modid.json","version.json")) {
        expand(inputs.properties)
    }
    rename("accesstransformer.cfg", "META-INF/${modid}_at.cfg")
}
val remapJar by tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    archiveClassifier.set("")
    from(tasks.shadowJar)
    input.set(tasks.shadowJar.get().archiveFile)
}
tasks.jar {
    archiveClassifier.set("without-deps")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates"))
}
tasks.shadowJar {
    destinationDirectory.set(layout.buildDirectory.dir("intermediates"))
    archiveClassifier.set("non-obfuscated-with-deps")
    configurations = listOf(shadowImpl)
    doLast {
        configurations.forEach {
            println("Copying dependencies into mod: ${it.files}")
        }
    }
    // If you want to include other dependencies and shadow them, you can relocate them in here
    fun relocate(name: String) = relocate(name, "$baseGroup.deps.$name")
    relocate("javazoom")
    relocate("io.nayuki.qrcodegen")
    // 跨版本 Via 全家及其依赖、JvmDowngrader 兼容桩：挪进 myau.deps，不和玩家装的 ViaForge 等撞类。
    // 资源（assets/via*/）路径不受影响。
    relocate("com.viaversion")
    relocate("net.raphimc")
    relocate("com.seedfinding")
    relocate("xyz.wagyourtail.jvmdg")
    // Java 9 module descriptor of qrcodegen: Forge 1.8.9's class scanner cannot read it.
    exclude("module-info.class")
    // 多版本 jar 的 Java 9+ 变体、签名、Maven 元数据：Forge 1.8.9 用不上，有的还读不了。
    exclude("META-INF/versions/**")
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("META-INF/maven/**", "META-INF/coverage/**")
    // JvmDowngrader 的构建期降级器（依赖 ASM 9）：运行时用不到，只要兼容桩。
    exclude("xyz/wagyourtail/jvmdg/providers/**", "META-INF/services/xyz.wagyourtail.jvmdg.*")
}
tasks.assemble.get().dependsOn(tasks.remapJar)

// Gradle 8 removed JavaExec.getMain(), so Loom 0.10's AbstractRunTask.getMain() became a
// property without an input/output annotation, which Gradle 8 rejects when running
// runClient. Replace the task with a subclass that marks the property @Internal.
object LoomClientRunSettings {
    lateinit var settings: net.fabricmc.loom.configuration.ide.RunConfigSettings
}
abstract class Gradle8RunClientTask : net.fabricmc.loom.task.RunGameTask(LoomClientRunSettings.settings) {
    @Internal
    override fun getMain(): String = super.getMain()
}
afterEvaluate {
    LoomClientRunSettings.settings = loom.runConfigs.getByName("client")
    tasks.replace("runClient", Gradle8RunClientTask::class.java).apply {
        description = "Starts the Minecraft client (Gradle 8 compatible)"
        dependsOn("downloadAssets")
        // Gradle 8's JavaExec launches from its internal spec and ignores the overridden
        // getMain()/getJvmArgs() that Loom 0.10 uses, so copy their values in explicitly.
        mainClass.set(main)
        setJvmArgs(jvmArgs)
        // Run the game on the Java 8 toolchain, not on the JDK that runs Gradle.
        javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    }
}

// ─────────────────────────────── 跨版本 Via：修补与离线检查（不启动游戏） ───────────────────────────────
val viaPatchJars by tasks.registering(JavaExec::class) {
    group = "bettermyau-via"
    description = "把降级后的 Via jar 合并成一个，并修好 JvmDowngrader 写错的 StringBuilder.append(byte/short) 调用。"
    dependsOn("compileInjectBuildJava")
    classpath = injectBuild.runtimeClasspath
    mainClass.set("myau.injectbuild.ViaJarFix")
    val inputJars = via.filter { !it.name.startsWith("netty-") }
    inputs.files(inputJars)
    outputs.file(viaPatchedJar)
    doFirst {
        setArgs(listOf(viaPatchedJar.get().asFile.absolutePath) + inputJars.files.map { it.absolutePath })
    }
}


/** Java 8 运行库：lib 与 lib/ext 下的所有 jar（rt.jar、jce.jar 等）。 */
fun java8RuntimeJars(): List<File> {
    val home = javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath.asFile
    val lib = if (File(home, "jre/lib/rt.jar").isFile) File(home, "jre/lib") else File(home, "lib")
    return listOf(lib, File(lib, "ext")).flatMap { dir -> dir.listFiles()?.filter { it.name.endsWith(".jar") } ?: emptyList() }
}

/** 游戏运行时的其余类路径：SRG 名的 Minecraft / Forge + 游戏自带的库（不含打进成品的东西）。 */
fun gameLibraryJars(): List<File> {
    val loomCache = file("${gradle.gradleUserHomeDir}/caches/essential-loom/$mcVersion")
    val srg = listOf("minecraft-srg.jar", "forge-srg.jar").map { name ->
        fileTree(loomCache) { include("**/$name") }.files.firstOrNull() ?: throw GradleException("找不到 $name（$loomCache）")
    }
    val bundled = Regex("""^(minecraft-mapped|forge-mapped|mixin-|jlayer-|qrcodegen-|jvmdowngrader-).*|.*-downgraded-1\.8\.jar$""")
    return srg + configurations.getByName("compileClasspath").files.filter { !bundled.matches(it.name) }
}

val viaLinkCheck by tasks.registering(JavaExec::class) {
    group = "bettermyau-via"
    description = "离线链接检查：成品 jar 全是 Java 8 字节码，且每个类/方法/字段引用都能在 Java 8 + 游戏自带库上解析。"
    dependsOn(remapJar, "compileInjectBuildJava")
    classpath = injectBuild.runtimeClasspath
    mainClass.set("myau.injectbuild.LinkCheck")
    val jar = remapJar.archiveFile
    inputs.file(jar)
    doFirst {
        setArgs(listOf(jar.get().asFile.absolutePath) + (java8RuntimeJars() + gameLibraryJars()).map { it.absolutePath })
    }
}
tasks.assemble.get().dependsOn(viaLinkCheck)

val viaSelfTest by tasks.registering(JavaExec::class) {
    group = "bettermyau-via"
    description = "离线自检：在 Java 8 + 游戏自带库上加载打包的 ViaVersion 全家，检查所有版本的协议路径与映射数据，并走一遍握手。"
    dependsOn(remapJar)
    mainClass.set("myau.via.ViaSelfTest")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    val workDir = layout.buildDirectory.dir("via-selftest")
    doFirst {
        val dir = workDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        workingDir = dir // log4j writes logs/ into the working directory
        classpath = files(remapJar.archiveFile) + files(gameLibraryJars())
        setArgs(listOf(File(dir, "via").absolutePath))
    }
}

// 端到端（需要本机开一个离线服务器）：无头 1.8 客户端经内置 Via 登录、玩几秒。
// 用法：gradle viaServerTest -PviaHost=127.0.0.1 -PviaPort=25599 -PviaVersion=1.21.9-1.21.10
val viaServerTest by tasks.registering(JavaExec::class) {
    group = "bettermyau-via"
    description = "端到端：无头 1.8 客户端经内置 Via 登录一个真实（离线）服务器并玩几秒。"
    dependsOn(remapJar)
    mainClass.set("myau.via.ViaServerTest")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    val workDir = layout.buildDirectory.dir("via-servertest")
    doFirst {
        val dir = workDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        workingDir = dir
        classpath = files(remapJar.archiveFile) + files(gameLibraryJars())
        setArgs(listOf(
            (findProperty("viaHost") ?: "127.0.0.1").toString(),
            (findProperty("viaPort") ?: "25565").toString(),
            (findProperty("viaVersion") ?: "26.3").toString(),
            (findProperty("viaStay") ?: "8").toString(),
            (findProperty("viaPackDelay") ?: "0").toString()
        ))
    }
}

// ─────────────────────────────── BetterMyau-Inject（热注入版） ───────────────────────────────
// 载荷流水线：remapJar(SRG) → injectTransform(改写) → injectShadowJar(重定位 org.spongepowered + Agent 清单)。
// 全程离线可用，和模组版共用同一批 SRG class。
val injectDir = layout.buildDirectory.dir("inject")
val injectIntermediate = injectDir.map { it.file("inject-intermediate.jar") }

val injectTransform by tasks.registering(JavaExec::class) {
    group = "bettermyau-inject"
    description = "把 SRG 模组 jar 改写成注入中间 jar（Accessor 桥接、字段旁路、注入配置、目标清单）。"
    dependsOn(remapJar, "compileInjectBuildJava", viaLinkCheck)
    classpath = injectBuild.runtimeClasspath
    mainClass.set("myau.injectbuild.InjectBuilder")
    val input = remapJar.archiveFile
    inputs.file(input)
    outputs.file(injectIntermediate)
    doFirst {
        args(
            input.get().asFile.absolutePath,
            "mixins.$modid.refmap.json",
            injectIntermediate.get().asFile.absolutePath,
            "$mcVersion-v$version"
        )
    }
}

val injectShadowJar by tasks.registering(com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar::class) {
    group = "bettermyau-inject"
    description = "重定位 org.spongepowered → myau.inject.shadow，产出最终注入载荷 jar（带 Agent 清单）。"
    dependsOn(injectTransform)
    from(zipTree(injectIntermediate))
    // 隔离出自己的一套 Mixin 引擎，避免和游戏里已加载的 org.spongepowered 冲突。
    relocate("org.spongepowered", "myau.inject.shadow")
    mergeServiceFiles() // 把服务文件名按重定位改到 myau.inject.shadow.*
    archiveBaseName.set("BetterMyau-Inject")
    archiveClassifier.set("")
    archiveVersion.set("$mcVersion-v$version")
    destinationDirectory.set(injectDir.map { it.dir("libs") })
    manifest.attributes(
        mapOf(
            "Agent-Class" to "myau.inject.agent.InjectAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "BetterMyau-Inject-Build" to "$mcVersion-v$version"
        )
    )
    // tasks.withType(Jar) 给所有 jar 盖了模组清单属性；注入载荷不能被当模组加载，执行前去掉它们。
    doFirst {
        val attrs = (this as com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar).manifest.attributes
        listOf("FMLCorePluginContainsFMLMod", "ForceLoadAsMod", "TweakClass", "MixinConfigs", "FMLAT")
            .forEach { attrs.remove(it) }
    }
}

val injectJar by tasks.registering {
    group = "bettermyau-inject"
    description = "构建注入载荷 jar（别名）。"
    dependsOn(injectShadowJar)
}

// 注入器资源：窗口 Logo / 任务栏图标用模组同一张 TUBIAO（实际是 JPEG）。
tasks.named<Copy>("processInjectorResources") {
    from("src/main/resources/TUBIAO.png") {
        into("myau/injector")
        rename { "logo.jpg" }
    }
}

// exe 图标：把 TUBIAO 缩成 256/64/48/32/24/16 的圆角 PNG，拼成一个 .ico 给 jpackage。
val injectIconFile = injectDir.map { it.file("BetterMyau-Inject.ico") }
val injectIcon by tasks.registering {
    group = "bettermyau-inject"
    description = "用 TUBIAO.png 生成注入器 exe 图标（多尺寸 PNG 条目的 .ico）。"
    val src = file("src/main/resources/TUBIAO.png")
    inputs.file(src)
    outputs.file(injectIconFile)
    doLast {
        val source = ImageIO.read(src)
        val sizes = listOf(256, 64, 48, 32, 24, 16)
        val pngs = sizes.map { size ->
            // 逐次减半缩放，小尺寸也不发虚
            var cur: BufferedImage = source
            var w = source.width
            while (w / 2 >= size) {
                w /= 2
                val next = BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB)
                val g = next.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(cur, 0, 0, w, w, null)
                g.dispose()
                cur = next
            }
            val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val r = size * 0.46f
            g.fill(RoundRectangle2D.Float(0f, 0f, size.toFloat(), size.toFloat(), r, r))
            g.setComposite(AlphaComposite.SrcIn)
            g.drawImage(cur, 0, 0, size, size, null)
            g.dispose()
            val bytes = ByteArrayOutputStream()
            ImageIO.write(out, "png", bytes)
            bytes.toByteArray()
        }
        val header = 6 + 16 * sizes.size
        val buf = ByteBuffer.allocate(header + pngs.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0); buf.putShort(1); buf.putShort(sizes.size.toShort())
        var offset = header
        sizes.forEachIndexed { i, size ->
            val dim = if (size >= 256) 0 else size
            buf.put(dim.toByte()); buf.put(dim.toByte()); buf.put(0); buf.put(0)
            buf.putShort(1); buf.putShort(32)
            buf.putInt(pngs[i].size); buf.putInt(offset)
            offset += pngs[i].size
        }
        pngs.forEach { buf.put(it) }
        val f = injectIconFile.get().asFile
        f.parentFile.mkdirs()
        f.writeBytes(buf.array())
    }
}

// 注入器编译：Java 21 工具链 + jdk.attach（Attach API）。
tasks.named<JavaCompile>("compileInjectorJava") {
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    options.release.set(21)
    options.compilerArgs.addAll(listOf("--add-modules", "jdk.attach"))
}

val injectorJar by tasks.registering(Jar::class) {
    group = "bettermyau-inject"
    description = "打包注入器控制器 jar（Main-Class 入口）。"
    dependsOn("compileInjectorJava")
    archiveBaseName.set("BetterMyau-Inject-Controller")
    archiveClassifier.set("")
    archiveVersion.set("$mcVersion-v$version")
    destinationDirectory.set(injectDir.map { it.dir("libs") })
    from(injector.output)
    manifest.attributes(
        "Main-Class" to "myau.injector.InjectorMain",
        "Implementation-Version" to version.toString()   // 窗口标题栏显示的版本号
    )
    doFirst {
        // 去掉 tasks.withType(Jar) 盖上的模组清单属性（控制器不是模组）。
        val attrs = (this as Jar).manifest.attributes
        listOf("FMLCorePluginContainsFMLMod", "ForceLoadAsMod", "TweakClass", "MixinConfigs", "FMLAT")
            .forEach { attrs.remove(it) }
    }
}

// 用 jpackage 打成自带精简运行时的 Windows app-image：控制器 jar + 载荷 jar 放一起，AgentJarLocator 能就近找到。
val injectPackageInput = injectDir.map { it.dir("package-input") }
val preparePackageInput by tasks.registering(Sync::class) {
    group = "bettermyau-inject"
    dependsOn(injectorJar, injectShadowJar)
    into(injectPackageInput)
    from(injectorJar.flatMap { it.archiveFile })
    from(injectShadowJar.flatMap { it.archiveFile })
}

val packageInject by tasks.registering(Exec::class) {
    group = "bettermyau-inject"
    description = "用 jpackage 打成 BetterMyau-Inject app-image（含精简运行时）。"
    dependsOn(preparePackageInput, injectIcon)
    val outDir = injectDir.map { it.dir("image") }
    doFirst {
        val jpackage = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
            .get().metadata.installationPath.file("bin/jpackage.exe").asFile.absolutePath
        val target = outDir.get().asFile
        // jpackage 不允许输出目录已存在同名 app，先清掉。
        val app = File(target, "BetterMyau-Inject")
        if (app.exists()) app.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        target.mkdirs()
        commandLine(
            jpackage,
            "--type", "app-image",
            "--name", "BetterMyau-Inject",
            "--input", injectPackageInput.get().asFile.absolutePath,
            "--dest", target.absolutePath,
            "--main-jar", "BetterMyau-Inject-Controller-$mcVersion-v$version.jar",
            "--main-class", "myau.injector.InjectorMain",
            "--add-modules", "java.desktop,java.management,jdk.attach",
            "--java-options", "--add-modules=jdk.attach",
            "--java-options", "-Dfile.encoding=UTF-8",
            "--icon", injectIconFile.get().asFile.absolutePath,
            "--app-version", version.toString(),
            "--description", "BetterMyau 1.8.9 热注入控制器",
            "--vendor", "BetterMyau"
        )
    }
}

val releaseInject by tasks.registering(Zip::class) {
    group = "bettermyau-inject"
    description = "把 app-image 打成发布 zip：BetterMyau-Inject-<mc>-v<ver>.zip。"
    dependsOn(packageInject)
    archiveFileName.set("BetterMyau-Inject-$mcVersion-v$version.zip")
    destinationDirectory.set(injectDir)
    from(injectDir.map { it.dir("image") })
}

// 离线自检：对真实 SRG 目标字节套用注入配置，再用 JVM 校验器验证每个产物。跑在 Java 8 工具链上（与游戏一致）。
val injectSelfCheck by tasks.registering(JavaExec::class) {
    group = "bettermyau-inject"
    description = "离线验证注入载荷：套用到 minecraft-srg/forge-srg 并逐类校验，不启动游戏。"
    dependsOn(injectShadowJar, "compileInjectCheckJava")
    classpath = injectCheck.output
    mainClass.set("myau.injectcheck.SelfCheck")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    doFirst {
        val loomCache = file("${gradle.gradleUserHomeDir}/caches/essential-loom/$mcVersion")
        val mcSrg = fileTree(loomCache) { include("**/minecraft-srg.jar") }.files.firstOrNull()
            ?: throw GradleException("找不到 minecraft-srg.jar（$loomCache）")
        val forgeSrg = fileTree(loomCache) { include("**/forge-srg.jar") }.files.firstOrNull()
            ?: throw GradleException("找不到 forge-srg.jar（$loomCache）")
        val payload = injectShadowJar.get().archiveFile.get().asFile
        // 引擎依赖：日志/集合/json/IO，以及 launchwrapper（重定位后的 Proxy 仍 implements 它，游戏里本就有）。
        // authlib：跨版本 Via 的登录 Mixin（MixinNetHandlerLoginClient）引用 MinecraftSessionService。
        val depRegex = Regex("(log4j-api|log4j-core|guava|gson|commons-io|commons-lang3|launchwrapper|lwjgl|netty|authlib).*\\.jar")
        val deps = configurations.getByName("compileClasspath").files
            .filter { depRegex.containsMatchIn(it.name) }.toMutableList()
        if (deps.none { it.name.contains("launchwrapper") }) {
            fileTree("${gradle.gradleUserHomeDir}/caches/modules-2") { include("**/launchwrapper-*.jar") }
                .files.firstOrNull()?.let { deps.add(it) }
        }
        val all = mutableListOf(payload.absolutePath, mcSrg.absolutePath, forgeSrg.absolutePath)
        deps.forEach { all.add(it.absolutePath) }
        setArgs(all)
        println("[injectSelfCheck] 载荷=${payload.name} mcSrg=${mcSrg.name} forgeSrg=${forgeSrg.name} deps=${deps.size}")
    }
}

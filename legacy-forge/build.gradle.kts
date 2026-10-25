plugins {
    id("java")
    // 本子构建刻意只装 Unimined: 它与 Loom 的 tiny-remapper 版本互斥(0.8.7 vs 0.14.0),
    // 放到独立构建里才能各自拿到需要的类路径。
    id("xyz.wagyourtail.unimined") version "1.4.1"
}

group = "io.tythee"

/** 目标 MC 版本: ./gradlew -p legacy-forge buildAndCollect -Pmc=1.10.2 */
val mc: String = (findProperty("mc") as String?) ?: "1.12.2"

/** 版本号与 Forge/MCP 版本仍然只在主构建的 stonecutter.properties.toml 里维护, 这里读出来用。 */
val tomlLines: List<String> = rootProject.file("../stonecutter.properties.toml").readLines()

fun tomlValue(section: String, key: String): String? {
    var inSection = false
    for (line in tomlLines) {
        val t = line.trim()
        if (t.startsWith("[") && t.endsWith("]")) {
            inSection = (t == "[$section]")
            continue
        }
        if (inSection && t.startsWith("$key =")) {
            return t.substringAfter('=').trim().trim('"')
        }
    }
    return null
}

val modVersion: String = tomlLines
    .first { it.trimStart().startsWith("mod.version") }
    .substringAfter('"')
    .substringBefore('"')

val forgeVersion: String = tomlValue("forge.\"$mc\"", "deps.forge")
    ?: error("stonecutter.properties.toml 里缺少 [forge.\"$mc\"] deps.forge")

val mcpChannel: String = tomlValue("forge.\"$mc\"", "deps.mcp")
    ?: error("stonecutter.properties.toml 里缺少 [forge.\"$mc\"] deps.mcp")

/** 由主构建的 :<mc>-forge:stonecutterGenerate 产出(条件块已按该版本解析完毕)。 */
val generatedMain = rootProject.file("../versions/$mc-forge/build/generated/stonecutter/main")

require(generatedMain.isDirectory) {
    "缺少 $mc 的 Stonecutter 产物: $generatedMain\n" +
        "请先运行主构建的 ./gradlew :$mc-forge:stonecutterGenerate (或 buildAll)。"
}

version = "$modVersion+$mc"
base.archivesName = "reflex-forge"

repositories {
    maven("https://maven.wagyourtail.xyz/releases")
    maven("https://repo.spongepowered.org/repository/maven-public/")
    maven("https://maven.minecraftforge.net/")
    mavenCentral()
}

unimined.minecraft {
    version(mc)

    // 这些版本没有 Mojang 官方映射(1.14.4 才有), 走 MCP: searge 名 + mcp 名两层
    mappings {
        searge()
        mcp("stable", mcpChannel)
    }

    minecraftForge {
        loader(forgeVersion)
        // Unimined 会接上 Mixin 库/注解处理器并把 MixinConfigs 写进 manifest
        mixinConfig("reflex.mixins.json")
    }
}

dependencies {
    // 这些老 Forge 不自带 Mixin; 生产环境还需要 MixinBootstrap 引导(见 mcmod.info 声明)
    implementation("org.spongepowered:mixin:0.8.5") { isTransitive = false }
}

/** 1.7.10 的 FML 包名/F3 结构都与 1.8.9+ 不同, 因此入口与 mixin 各有一套实现, 按版本二选一。 */
val is1710 = (mc == "1.7.10")

sourceSets {
    main {
        java.srcDirs(
            generatedMain.resolve("java"),
            rootProject.file("../src/legacy/java")
        )
        resources.srcDirs(generatedMain.resolve("resources"))

        // 1.14+ 的两份 mixin 实现与 Cloth Config 配置界面在这些版本上不成立,
        // 分别由 src/legacy 的 *Legacy 实现与 config/reflex.json 取代。
        java.exclude("io/tythee/mixin/MinecraftClientMixin.java")
        java.exclude("io/tythee/mixin/DebugOverlayMixin.java")
        java.exclude("io/tythee/config/ConfigScreen.java")

        if (is1710) {
            java.exclude("io/tythee/forge/ReflexClientForge.java")
            java.exclude("io/tythee/mixin/DebugOverlayMixinLegacy.java")
            // 1.8.9+ 的帧实现(渲染点带 long、呈递走 updateDisplay())在 1.7.10 上不成立
            java.exclude("io/tythee/mixin/MinecraftClientMixinLegacy.java")
        } else {
            java.exclude("io/tythee/forge/ReflexClientForge1710.java")
            java.exclude("io/tythee/mixin/MinecraftClientMixinLegacy1710.java")
        }
    }
}

java {
    toolchain {
        vendor = JvmVendorSpec.ADOPTIUM
        languageVersion = JavaLanguageVersion.of(17)
    }
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(8)
}

tasks {
    processResources {
        val props = mapOf(
            "id" to "reflex",
            "name" to "Reflex AntiLag",
            "version" to modVersion,
            "mcversion" to mc
        )

        inputs.property("version", modVersion)
        inputs.property("mc", mc)

        filesMatching("mcmod.info") { expand(props) }
        filesMatching("*.mixins.json") {
            expand(
                mapOf(
                    "java" to "JAVA_8",
                    "mixins_client" to if (is1710) {
                        """["MinecraftClientMixinLegacy1710"]"""
                    } else {
                        """["MinecraftClientMixinLegacy","DebugOverlayMixinLegacy"]"""
                    }
                )
            )
        }

        exclude("fabric.mod.json")
        exclude("META-INF/neoforge.mods.toml")
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "构建 <mc> Forge jar, 并复制到主构建的 build/libs/<mod version>/"

        dependsOn("build")
        inputs.property("version", modVersion)
        inputs.property("mc", mc)
        // Unimined 产物: <name>-<version>-dev.jar(开发名空间) 与 <name>-<version>.jar(已 remap 到 SRG)
        from(layout.buildDirectory.dir("libs")) {
            include("reflex-forge-*+$mc.jar")
            exclude("*-dev.jar", "*-sources.jar")
        }
        into(rootProject.file("../build/libs/$modVersion"))
    }
}

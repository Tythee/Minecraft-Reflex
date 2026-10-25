plugins {
    id("java")
    // 本子构建刻意只装 Unimined: 它与 Loom 的 tiny-remapper 版本互斥(0.8.7 vs 0.14.0),
    // 放到独立构建里才能各自拿到需要的类路径。
    id("xyz.wagyourtail.unimined") version "1.4.1"
}

group = "io.tythee"

/** 版本号仍然只在主构建的 stonecutter.properties.toml 里维护, 这里读出来用。 */
val modVersion: String = rootProject.file("../stonecutter.properties.toml")
    .readLines()
    .first { it.trimStart().startsWith("mod.version") }
    .substringAfter('"')
    .substringBefore('"')

/** 由主构建的 :1.12.2-forge:stonecutterGenerate 产出(条件块已按 1.12.2 解析完毕)。 */
val generatedMain = rootProject.file("../versions/1.12.2-forge/build/generated/stonecutter/main")

require(generatedMain.isDirectory) {
    "缺少 1.12.2 的 Stonecutter 产物: $generatedMain\n" +
        "请先运行主构建的 ./gradlew :1.12.2-forge:stonecutterGenerate (或 buildAll)。"
}

version = "$modVersion+1.12.2"
base.archivesName = "reflex-forge"

repositories {
    maven("https://maven.wagyourtail.xyz/releases")
    maven("https://repo.spongepowered.org/repository/maven-public/")
    maven("https://maven.minecraftforge.net/")
    mavenCentral()
}

unimined.minecraft {
    version("1.12.2")

    // 1.12.2 没有 Mojang 官方映射(1.14.4 才有), 走 MCP: searge 名 + mcp 名两层
    mappings {
        searge()
        mcp("stable", "39-1.12")
    }

    minecraftForge {
        loader("14.23.5.2860")
        // Unimined 会接上 Mixin 库/注解处理器并把 MixinConfigs 写进 manifest
        mixinConfig("reflex.mixins.json")
    }
}

dependencies {
    // 1.12.2 的 Forge 不自带 Mixin; 生产环境还需要 MixinBootstrap 引导(见 mcmod.info 声明)
    implementation("org.spongepowered:mixin:0.8.5") { isTransitive = false }
}

sourceSets {
    main {
        java.srcDirs(
            generatedMain.resolve("java"),
            rootProject.file("../src/legacy/java")
        )
        resources.srcDirs(generatedMain.resolve("resources"))

        // 1.14+ 的两份 mixin 实现与 Cloth Config 配置界面在 1.12.2 上不成立,
        // 分别由 src/legacy 的 *Legacy 实现与 config/reflex.json 取代。
        java.exclude("io/tythee/mixin/MinecraftClientMixin.java")
        java.exclude("io/tythee/mixin/DebugOverlayMixin.java")
        java.exclude("io/tythee/config/ConfigScreen.java")
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
            "minecraft" to "[1.12,1.12.2]"
        )

        inputs.property("version", modVersion)

        filesMatching("mcmod.info") { expand(props) }
        filesMatching("*.mixins.json") {
            expand(
                mapOf(
                    "java" to "JAVA_8",
                    "mixins_client" to """["MinecraftClientMixinLegacy","DebugOverlayMixinLegacy"]"""
                )
            )
        }

        exclude("fabric.mod.json")
        exclude("META-INF/neoforge.mods.toml")
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "构建 1.12.2 Forge jar, 并复制到主构建的 build/libs/<mod version>/"

        dependsOn("build")
        inputs.property("version", modVersion)
        // Unimined 产物: <name>-<version>-dev.jar(开发名空间) 与 <name>-<version>.jar(已 remap 到 SRG)
        from(layout.buildDirectory.dir("libs")) {
            include("reflex-forge-*.jar")
            exclude("*-dev.jar", "*-sources.jar")
        }
        into(rootProject.file("../build/libs/$modVersion"))
    }
}

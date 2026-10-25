pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "FabricMC" }
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
        // Unimined: legacy(1.12.2 及更早)的 Forge/Rift 等环境
        maven("https://maven.wagyourtail.xyz/releases") { name = "Wagyourtail Releases" }
    }
}

plugins {
    // Check the latest version on https://stonecutter.kikugie.dev/blog/changes/0.9
    id("dev.kikugie.stonecutter") version "0.9.6"

    // Used for cross-compat for 26.1+ and older versions (https://codeberg.org/KikuGie/loom-back-compat)
    id("dev.kikugie.loom-back-compat") version "0.4.1"

    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    create(rootProject) {
        /**
         * Creates version nodes for multiple loaders.
         *
         * This function creates subprojects named `versions/{project}-{loader}`.
         * Each project has a logical [version], which should match the Minecraft version,
         * whereas [project] is the arbitrary name part of the folder.
         *
         * Each project also gets a separate build script depending on the loader,
         * named `build.{loader}.gradle.kts`.
         */
        fun match(project: String, vararg loaders: String, version: String = project) {
            for (loader in loaders) version("$project-$loader", version).buildscript("build.$loader.gradle.kts")
        }

        // See https://stonecutter.kikugie.dev/wiki/start/#choosing-minecraft-versions
        // 一个「兼容组」只建一个代表版本; 组内其它小版本靠 mod.mc_compat 声明覆盖。
        match("1.21.11", "fabric", "neoforge")
        match("1.21.4", "fabric", "neoforge")
        match("1.21.1", "fabric", "neoforge")
        match("26.1.2", "fabric", "neoforge")
        match("26.2", "fabric", "neoforge")
        match("26.3", "fabric", "neoforge")
        // NeoForge 自 1.20.2 才存在, 故 1.20.1 及更早只有 fabric 节点
        match("1.20.6", "fabric", "neoforge")
        match("1.20.1", "fabric")
        match("1.19.4", "fabric")
        match("1.18.2", "fabric")
        match("1.17.1", "fabric")
        // 1.16.5 及更早: 无 NeoForge, 且客户端跑在 Java 8 上
        match("1.16.5", "fabric")
        match("1.15.2", "fabric")
        match("1.14.4", "fabric")
        // 1.13.2 及更早只有 Forge(MCP 映射)。这个节点只生成源码, 编译/打包在 legacy-forge 子构建里
        // (Unimined 与 Loom 的 tiny-remapper 版本互斥, 不能共处一个插件类路径)。
        match("1.12.2", "forge")
        match("1.10.2", "forge")
        vcsVersion = "26.1.2-fabric"
    }
}

rootProject.name = "reflex"

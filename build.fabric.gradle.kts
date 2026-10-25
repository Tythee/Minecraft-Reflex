plugins {
    // Applies the correct loom variant based on the Minecraft version
    id("dev.kikugie.loom-back-compat")
    id("tools-regression")
}

// DO NOT set group = ...!
version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = "${property("mod.id") as String}-fabric"

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    sc.current.parsed >= "1.17" -> JavaVersion.VERSION_17
    // 1.16.5 及更早跑在 Java 8 上, 编译目标必须跟着降, 否则老版本客户端加载不了
    else -> JavaVersion.VERSION_1_8
}

repositories {
    maven("https://maven.shedaniel.me/") { name = "Shedaniel" }
    maven("https://maven.terraformersmc.com/releases/") { name = "TerraformersMC" }
}

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    // Applies Mojang Mappings, shared with the NeoForge side to avoid remapping
    loomx.applyMojangMappings()

    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")

    // Cloth Config 的 maven 坐标换过代: 1.14/1.15 时代叫 config-2, 1.16 起才叫 cloth-config-fabric。
    // 老版本的 POM 还挂着当年的 fabric-loader 0.6.x(+build.NNN 这种老坐标), 那个仓库路径早已不存在,
    // 所以 loader 一律排除掉 —— 我们自己的 loader 由上面的 modImplementation 提供。
    val clothArtifact = (findProperty("deps.cloth_config_artifact") as String?) ?: "cloth-config-fabric"
    modApi("me.shedaniel.cloth:$clothArtifact:${property("deps.cloth_config")}") {
        exclude(group = "net.fabricmc.fabric-api")
        exclude(group = "net.fabricmc", module = "fabric-loader")
        // config-2 2.x 的 POM 还把当年的 ModMenu(io.github.prospector, +build.NNN 老坐标)挂成依赖
        exclude(group = "io.github.prospector")
    }

    // ModMenu 换过两次坐标: 1.15 及更早是 io.github.prospector.modmenu, 1.16 起才是
    // com.terraformersmc.modmenu; 1.14 连 artifact 都不在可达的 maven 上, 那一版不做 ModMenu 集成
    // (配置仍可手改 config/reflex.json)。按版本二选一编译, 避免同时存在两个 ModMenuApi 实现。
    (findProperty("deps.modmenu") as String?)?.let { modImplementation("com.terraformersmc:modmenu:$it") }

    if (sc.current.parsed < "1.15") {
        // 1.14 没有任何可达的 ModMenu artifact, 两个集成类都不编译 (配置仍可手改 config/reflex.json)
        sourceSets.named("main") {
            java.exclude("io/tythee/fabric/config/ModMenuIntegration.java")
            java.exclude("io/tythee/fabric/config/ModMenuIntegrationLegacy.java")
        }
    } else if (sc.current.parsed < "1.16") {
        sourceSets.named("main") { java.exclude("io/tythee/fabric/config/ModMenuIntegration.java") }
    } else {
        sourceSets.named("main") { java.exclude("io/tythee/fabric/config/ModMenuIntegrationLegacy.java") }
    }
}

val obfuscated = sc.current.parsed < "26"

loom {
    if (obfuscated) {
        mixin {
            // Legacy AP is off by default in modern Loom; we need it to generate
            // the refmap that remaps mixin targets on obfuscated (pre-26.1) versions.
            // No-remap versions (26.1+) don't have a "mappingsFinal" configuration,
            // so this must not be enabled there.
            useLegacyMixinAp = true
        }
    }

    decompilerOptions.named("vineflower") {
        options.put("mark-corresponding-synthetics", "1") // Adds names to lambdas - useful for mixins
    }

    runConfigs.all {
        preferGradleTask = true
        generateRunConfig = true
        runDirectory = rootProject.file("run") // Shares the run directory between versions
        jvmArguments.add("-Dmixin.debug.export=true")
    }
}

java {
    withSourcesJar()
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava

    toolchain {
        vendor = JvmVendorSpec.ADOPTIUM
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

tasks {
    processResources {
        fun MutableMap<String, String>.register(key: String, property: String) {
            val value = project.property(property) as String
            inputs.property(key, value)
            set(key, value)
        }

        val props = buildMap {
            register("id", "mod.id")
            register("name", "mod.name")
            register("version", "mod.version")
            register("minecraft", "mod.mc_compat")
        }

        // Java 需求随版本而变 (1.20.5+ 要 21, 1.17+ 要 17, 1.16.5 及更早要 8)
        // ModMenu 的入口类也随版本换 (1.14 无 ModMenu, 1.15 是 prospector 包, 1.16+ 是 terraformersmc 包)
        filesMatching("fabric.mod.json") {
            expand(
                props
                    + ("java" to ">=${requiredJava.majorVersion}")
                    + ("modmenu_entrypoint" to when {
                        sc.current.parsed < "1.15" -> "[]"
                        sc.current.parsed < "1.16" -> """["io.tythee.fabric.config.ModMenuIntegrationLegacy"]"""
                        else -> """["io.tythee.fabric.config.ModMenuIntegration"]"""
                    })
            )
        }

        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        // 1.12.2 用另一套注入实现(Legacy 后缀), 由各 loaders 的构建脚本给出对应清单
        filesMatching("*.mixins.json") {
            expand(
                mapOf(
                    "java" to mixinJava,
                    "mixins_client" to """["MinecraftClientMixin","DebugOverlayMixin"]"""
                )
            )
        }

        exclude("META-INF/neoforge.mods.toml")
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds the mod jar and copies it to build/libs/{mod version}/"

        inputs.property("version", project.property("mod.version"))
        from(loomx.modJar.flatMap { it.archiveFile }, loomx.modSourcesJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }
}

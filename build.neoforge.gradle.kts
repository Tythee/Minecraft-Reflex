plugins {
    id("net.neoforged.moddev") version "2.0.142"
    id("neoforge-mutex")
    id("tools-regression")
}

version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = "${property("mod.id") as String}-neoforge"

val requiredJava = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    else -> JavaVersion.VERSION_17
}

repositories {
    maven("https://maven.neoforged.net/releases/")
    maven("https://maven.shedaniel.me/") { name = "Shedaniel" }
}

dependencies {
    implementation("me.shedaniel.cloth:cloth-config-neoforge:${property("deps.cloth_config")}")
}

neoForge {
    version = property("deps.neo_loader") as String

    mods {
        register("reflex") {
            sourceSet(sourceSets.main.get())
        }
    }

    runs {
        register("client") {
            gameDirectory = file("../../run/")
            systemProperty("neoforge.disableGlValidation", "true")
            client()
        }

        register("server") {
            gameDirectory = file("../../run/")
            server()
        }
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
            // FancyModLoader 自己的版本号逐代递增 (20.6 → 3.x, 1.21.4 → 6.x, 26.x → 11~12.x)。
            // 老版本上写死 [4,) 会让 20.6 (loader 3.0.45) 直接拒绝加载本 mod。
            set("loader_range", if (sc.current.parsed < "1.21") "[3,)" else "[4,)")
        }

        filesMatching("META-INF/neoforge.mods.toml") { expand(props) }

        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        filesMatching("*.mixins.json") { expand("java" to mixinJava) }

        exclude("fabric.mod.json")
    }

    named("createMinecraftArtifacts") {
        dependsOn("stonecutterGenerate")
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds the mod jar and copies it to build/libs/{mod version}/"

        inputs.property("version", project.property("mod.version"))
        from(jar.flatMap { it.archiveFile }, named<Jar>("sourcesJar").flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }
}

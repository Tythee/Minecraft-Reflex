plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.1.2-fabric" /* [SC] DO NOT EDIT */

// See https://stonecutter.kikugie.dev/wiki/config/params
stonecutter parameters {
    val (version, loader) = current.project.split('-', limit = 2)

    // Makes version- and loader-specific properties from `stonecutter.properties.toml` apply
    properties {
        tags(version, loader)
    }

    // Adds constants to Stonecutter comments (i.e. for `//? if fabric {...`)
    constants {
        match(loader, "fabric", "neoforge")
    }

    swaps["mod_version"] = "\"${properties.get<String>("mod.version")}\";"
    swaps["minecraft"] = "\"${node.metadata.version}\";"

    // Mojang renamed Minecraft#renderFrame(Z)V from runTick(Z)V before 26.1
    swaps["render_frame_method"] = "\"${if (current.parsed < "26") "runTick(Z)V" else "renderFrame(Z)V"}\";"

    // The frame-present call site keeps getting renamed by Mojang release to release
    // (verified by disassembling Minecraft#runTick/renderFrame on each target version):
    //   <26     Window#updateDisplay(TracyFrameCapture)
    //   26.1.x  RenderSystem#flipFrame(TracyFrameCapture)
    //   26.2+   GpuSurface#present()  (no longer takes the capture object)
    swaps["flip_frame_target"] = "\"${
        when {
            current.parsed < "26" -> "Lcom/mojang/blaze3d/platform/Window;updateDisplay(Lcom/mojang/blaze3d/TracyFrameCapture;)V"
            current.parsed < "26.2" -> "Lcom/mojang/blaze3d/systems/RenderSystem;flipFrame(Lcom/mojang/blaze3d/TracyFrameCapture;)V"
            else -> "Lcom/mojang/blaze3d/systems/GpuSurface;present()V"
        }
    }\";"
}

// Builds every version/loader combo and copies jars into `build/libs/{mod version}/`
tasks.register("buildAll") {
    group = "project"
    dependsOn(stonecutter.tasks.named("buildAndCollect"))
}

// Builds only the given loader's versions
for (branch in setOf("fabric", "neoforge")) {
    val name = branch.replaceFirstChar { it.uppercase() }
    tasks.register("buildAll$name") {
        group = "project"
        dependsOn(stonecutter.tasks.named("buildAndCollect") { metadata.project.endsWith("-$branch") })
    }
}

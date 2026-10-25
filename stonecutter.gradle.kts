plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.2-fabric" /* [SC] DO NOT EDIT */

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
        put("gte_26_3", current.parsed >= "26.3")
        put("gte_26_2", current.parsed >= "26.2")
        put("gte_26_1", current.parsed >= "26.1")
        put("lt_26", current.parsed < "26")
        // 渲染管线分界: 1.21.5 起 blaze3d 迁到 com.mojang.blaze3d.opengl
        put("gte_1_21_5", current.parsed >= "1.21.5")
        // 1.20.5 起 GameRenderer#render 收 DeltaTracker, Window#updateDisplay 收 TracyFrameCapture
        put("gte_1_20_5", current.parsed >= "1.20.5")
        // 1.20 起 DebugScreenOverlay#renderLines 收 GuiGraphics (此前是 PoseStack),
        // 且 1.17~1.19 只有 getSystemInformation()/drawSystemInformation() 这一对。
        put("gte_1_20", current.parsed >= "1.20")
        // 1.19 起才有 Component.literal (更早是 TextComponent)
        put("lt_1_19", current.parsed < "1.19")
    }

    swaps["mod_version"] = "\"${properties.get<String>("mod.version")}\";"
    swaps["minecraft"] = "\"${node.metadata.version}\";"

    // Mojang renamed Minecraft#renderFrame(Z)V from runTick(Z)V before 26.1
    swaps["render_frame_method"] = "\"${if (current.parsed < "26") "runTick(Z)V" else "renderFrame(Z)V"}\";"

    swaps["frame_start_target"] = "\"${
        when {
            current.parsed < "26" -> "Lnet/minecraft/client/Minecraft;runTick(Z)V"
            current.parsed < "26.3" -> "Lcom/mojang/blaze3d/systems/RenderSystem;pollEvents()V"
            else -> "Lcom/mojang/blaze3d/systems/RenderSystem;pollEvents(Lcom/mojang/blaze3d/platform/SDLEventHandler;)V"
        }
    }\";"

    // The frame-present call site keeps getting renamed by Mojang release to release:
    // (verified by disassembling Minecraft#runTick/renderFrame on each target version):
    //   <1.21.2 Window#updateDisplay()                      (1.20.6 与 1.21.1 实测如此)
    //   1.21.2+ Window#updateDisplay(TracyFrameCapture)     (TracyFrameCapture 形参自 1.21.2 出现)
    //   26.1.x  RenderSystem#flipFrame(TracyFrameCapture)
    //   26.2    GpuSurface#present() (Blaze3D)
    //   26.3+   GpuSurface#present() (RenderPearl)
    swaps["flip_frame_target"] = "\"${
        when {
            current.parsed < "1.21.2" -> "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V"
            current.parsed < "26" -> "Lcom/mojang/blaze3d/platform/Window;updateDisplay(Lcom/mojang/blaze3d/TracyFrameCapture;)V"
            current.parsed < "26.2" -> "Lcom/mojang/blaze3d/systems/RenderSystem;flipFrame(Lcom/mojang/blaze3d/TracyFrameCapture;)V"
            current.parsed < "26.3" -> "Lcom/mojang/blaze3d/systems/GpuSurface;present()V"
            else -> "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"
        }
    }\";"

    // GameRenderer#render 在 1.21 从 (float, long, boolean) 换成 (DeltaTracker, boolean) —— 注意分界线是
    // 1.21 而不是 1.20.5: 1.20.5/1.20.6 实测仍是 (FJZ)V。26.3 起又收掉全部参数 (RenderPearl 自管 DeltaTracker)。
    swaps["game_renderer_render_target"] = "\"${
        when {
            current.parsed < "1.21" -> "Lnet/minecraft/client/renderer/GameRenderer;render(FJZ)V"
            current.parsed < "26.3" -> "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"
            else -> "Lnet/minecraft/client/renderer/GameRenderer;render()V"
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
plugins {
    id("java")
}

/**
 * 1.12.2(Forge)节点 —— **只生成源码, 不编译不打包**。
 *
 * 原因: Unimined(1.4.1) 的 mixin remapper 需要 tiny-remapper 0.8.7, 而 Loom(1.17) 用 0.14.0,
 * 两者在同一个 Gradle 插件类路径上互斥(remapJar 会抛 NoClassDefFoundError:
 * net/fabricmc/tinyremapper/extension/mixin/common/Logger)。因此真正的编译与 remap 放在
 * `legacy-forge/` 独立子构建里(那里只有 Unimined, 没有 Loom), 它直接消费本节点生成的源码。
 *
 * 所以本文件的职责只有一件: 让 Stonecutter 把 `//? if` 条件块解析成 1.12.2 的源码/资源。
 */
version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = "${property("mod.id") as String}-forge"

tasks.named<JavaCompile>("compileJava") {
    enabled = false
}

tasks.register("buildAndCollect") {
    group = "build"
    description = "只生成 1.12.2 的 Stonecutter 产物; 编译与打包由 legacy-forge 子构建完成"
    dependsOn("stonecutterGenerate")
}

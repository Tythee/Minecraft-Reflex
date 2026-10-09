import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer

/**
 * 把 tools/ 下的控制律回归仿真接进构建。
 *
 * 为什么必须显式接: 该仿真反射调用 ReflexScheduler 的私有方法与字段
 * (processCompletedFrame / estimateSimTime / estimateSubmissionTime / lastFrameGpuEndTimeSystem),
 * 所以它既不在任何 MC 版本的 main 源集里(否则会被打进 mod jar), 也不会被 gradle 自动编译。
 * 一旦哪天改了签名或字段名, 它会无声失效 —— 而它恰恰是唯一能证明"估计器与投影不撒谎"的东西。
 *
 * 它同时依赖 MC 类路径(ModConfig/ReflexClient 的静态初始化会碰到), 因此挂在具体版本工程上,
 * 复用该版本 main 源集的 classpath, 而不是单独拼一条 brittle 的 jar 列表。
 */
plugins.withId("java") {
    val sourceSets = extensions.getByType(SourceSetContainer::class.java)
    val main = sourceSets.getByName("main")

    val tools = sourceSets.create("tools")
    tools.java.srcDir(rootProject.file("tools"))
    tools.compileClasspath += main.output + main.compileClasspath
    tools.runtimeClasspath += main.output

    // 注: java 插件会为名为 "tools" 的源集自动注册 toolsClasses 生命周期任务, 不要再自建同名任务。
    tasks.register("controllerRegression", JavaExec::class.java) {
        group = "verification"
        description = "Runs the synthetic control-law regression (estGpu outlier resistance, step-load recovery)."
        dependsOn(tools.compileJavaTaskName, main.compileJavaTaskName)
        classpath = tools.runtimeClasspath + main.runtimeClasspath
        mainClass.set("ControllerRecoverySim")
    }
}

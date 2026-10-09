# Minecraft-Reflex

[English](#english) | [中文说明](#中文说明)

---

## 中文说明

**Minecraft-Reflex** 是一个在 Minecraft 中深度实现 **NVIDIA Reflex 动态低延迟技术** 的性能优化模组。支持任意显卡（NVIDIA / AMD / Intel），在不降低帧率的前提下消除 GPU 排队延迟，大幅提升操作响应速度与跟手度。

### 核心原理与解决的问题

1. **消灭渲染队列积压（Render Queue Elimination）**：
   在显卡负载较高（GPU-Bound）场景下，传统的游戏引擎往往超前跑满 CPU，导致生成的渲染指令在驱动和 GPU 队列中积压 1~2 帧甚至更多。
   Reflex 调度器会在每帧起点（pollEvents 输入采样前）智能计算休眠时间，使 CPU 刚好在 GPU 渲染完上一帧的临界点醒来，完成“新鲜输入采样 -> 游戏逻辑模拟 -> 渲染提交”，做到“即提即渲”（Just-in-Time），彻底消灭排队延迟。
2. **输入采样时刻对齐**：
   将帧休眠彻底移至 RenderSystem.pollEvents() 之前，休眠结束后立即采样鼠标键盘输入，根除休眠期间操作冻结问题。

---

### 功能特性

* **全显卡通用**：基于纯物理时序闭环调度，不仅支持 NVIDIA GeForce，同样原生支持 AMD Radeon 与 Intel Arc 显卡。
* **跨渲染后端支持（26.2+ 原生 API 与兼容分支）**：
  * **26.2+**：通过 Mojang GpuDevice 原生时间戳查询池，同时无缝兼容 **OpenGL 与 Vulkan**。
  * **< 26.2**：通过 LWJGL OpenGL 异步查询池（GL_TIMESTAMP）提供高精度纳秒级硬件测量。
* **单一带符号对齐目标**：
  控制律只追一个量——把本帧 RENDER_SUBMIT_END 对齐到上一帧 GPU 完成时刻，每帧都用新的实测重新锚定，相位不累积。唤醒时长完全由实测估计量（estGpu / estSim / estSub）算出，不设人为上下限；用户仅可通过「手动休眠偏置」叠加固定偏移。
* **F3 实时延迟指标面板（Reflex Software Metrics）**：
  以 NVIDIA Reflex 的桩位为坐标，逐帧实测并展示 PC 端到端延迟、Sleep 时长、对齐误差（带符号）与各标记区间的真实耗时。
* **多级结构化诊断日志**：开启诊断日志后，每秒输出互补的诊断行（`Summary` 与 `Internals` 无条件输出；`Timestamps` 要等 GPU 时间戳首次就绪后才出现）：
  * `[Reflex Summary]` —— 平滑后的总览（FPS / PC Latency / CPU / Oversleep / GPU / Sleep / AlignErr / Starve / Mode）；
  * `[Reflex Timestamps]` —— 抽样帧的全部标记时刻（InputSample、Simulation、RenderSubmit、Present、GPU 各自的起止）、三段标记间空档、对齐误差、真实 PC 延迟与 GPU↔系统时钟偏移；
  * `[Reflex Internals]` —— **休眠决策全过程**与全部估计量：`cfg{...}` 精简开关摘要、`res` 实际分辨率、`est={gpu,cpu,sim,sub}`、`deque` 深度、`gpuEndAge`、`align{err,lead,gpuFree}`、`sleep{estFinishNow -> sleep [原因]}`、Oversleep 死区、投影误差均值±离散度。
    `原因` 只有六种取值，直接说明本帧为什么睡/没睡：`sleep`（拿到正目标并等待）、`too-short`（目标 ≤0.05ms，不值得睡）、`CPU-bound`（`estSim+estSub ≥ estGpu`，对齐不可能，全速）、`no-anchor`（一次 GPU 完成时刻都没测到，不猜）、`no-estimate`（估计量尚未建立）、`disabled`（总开关关闭）。后四种下 `AlignErr` 均为 `n/a`。
  最后一行用于回答"这一帧为什么睡了 0ms 而不是 4ms"，是排查调度行为的首要依据。
* **配置状态入日志**：启动时与**每次修改配置落盘后**，都会输出一行 `[Reflex Config]`，完整记录当时全部选项。这样任何一份日志都能自证"当时 mod 是什么设置"，不必再靠外部记忆对照。

---

### F3 调试面板指标说明

开启「时序流程图展示」后，F3 左侧以**多行甘特图**逐帧展示各标记的实测位置。所有横轴时刻都相对本帧**唤醒瞬间**（`T+0`）：

```text
[Reflex Pipeline] PC: 29.0ms (Sleep: 4.1ms | AlignErr: +0.1ms | Enabled)
├─ Last    │ ░░░░░░░░░░░░░░░░░░░░░░░░|░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░
├─ Input   │ ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ Input: T+0.08ms | →Sim: 0.00ms
├─ Sim     │ ░░░█▒░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ Simulation: 0.00ms | →RenderSubmit: 0.56ms
├─ Submit  │ ░░░░█████████████████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ RenderSubmit: 2.36ms | →Present: 0.17ms
├─ Present │ ░░░░░░░░░░░░░░░░░░░░░░░░░░█░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ Present: 0.15ms
├─ GPU     │ ░░░░░░░░░░░░░░░░░░░░░░░░░░███████████████████████████████ GPU: 18.29ms | Oversleep: 0.23ms
```

**每行只画自己那一段标记区间**，行与行之间的暗轨即两段标记之间的空档。`→X` 就是这种空档的时长——NVIDIA 只给标记本身命名，空档不作专名，这里也沿用同一约定，不另造术语。

示例数字互相自洽，可拿来核对读法：`Submit` 条右端在 `T+12.50ms`，`Last` 的 `|` 在 `T+12.40ms`，差的 `0.10ms` 正是抬头那个 `AlignErr`；`PC 29.0ms` = `GPU` 条右端 `T+29.0ms` − `T+0`。

两点注意：

* **行标签的字符数故意不相等**（7~9 个）。`│` 的对齐是按 MC 默认字体实测像素宽补白算出来的，不是按字符数——MC 用的是比例字体。
* **亚毫秒的段会画不出来**（如 `Input` 的 0.12ms 在整帧尺度下不足一格），数值仍照常显示。要看清它们需要给时间轴加缩放。

*(若在配置中关闭「时序流程图展示」，则退化为单行紧凑模式：`[Reflex] PC | CPU | Oversleep | GPU | Sleep | AlignErr`)*

#### 打点位置

八个 CPU 侧桩位与 NVIDIA Reflex 一一对应；最后两行的 GPU 起止**不是** NVIDIA 标记——驱动内部本就知道显卡何时开工收工，而 mod 拿不到那个可见性，只能自己插时间戳。

| 桩位 | 注入位置 | 含义 |
| :--- | :--- | :--- |
| Sleep | `Minecraft#run` 内、`RenderSystem.pollEvents()` **之前**（pre-26 为 `runTick` 调用点） | 唯一的控制性桩：睡到临界点再醒，醒来立刻采输入 |
| INPUT_SAMPLE | `pollEvents()` **之后**（pre-26 无独立注入点，恒等于唤醒时刻） | 输入真正被采到的瞬间 |
| SIMULATION_START / END | `Minecraft#tick()` 的 HEAD / RETURN | 游戏逻辑区间；其后的收尾工作归入 `→RenderSubmit` 空档 |
| RENDER_SUBMIT_START / END | `GameRenderer#render` 调用前后 | CPU 录制并提交绘制命令的区间；**END 就是被控量** |
| PRESENT_START / END | 交换链呈现调用前后（各版本符号不同） | 把帧交给驱动; 它不等待画面显示 |
| GPU 起止（非 NVIDIA 标记） | OpenGL `GL_TIMESTAMP` / `GpuDevice` 时间戳查询池 | 硬件真实执行区间，经时钟偏移换算到系统时钟 |

#### 指标含义

| 指标 | 物理含义 |
| :--- | :--- |
| **PC** | **端到端计算延迟** = 本帧 GPU 完成时刻 − **唤醒时刻**。这是本 mod 优化的总量。注意起点与 NVIDIA 的 PC Latency 定义不同：NVIDIA 从输入采样起算，本 mod 从 `sleep()` 返回起算，两者相差的正是 `Input` 行那个量（`pollEvents()` 自身开销，通常亚毫秒） |
| **Sleep** | **本帧实际休眠时长**（`sleep()` 的实测返回）。为把 RENDER_SUBMIT_END 对齐到 GPU 变闲瞬间而主动等待的时间 |
| **AlignErr** | **对齐误差** = RENDER_SUBMIT_END − 上一帧 GPU 完成时刻，符号原样保留：`0` 完美对齐；`>0` 提交晚了（GPU 在等指令）；`<0` 提交早了（指令先入队，有积压）。本帧没做过对齐就显示 `n/a`——那说明调度器没参与摆放这一帧，差值只是游戏自然节拍的结果 |
| **Input** | INPUT_SAMPLE 相对唤醒时刻的偏移，即 `pollEvents()` 自身的开销 |
| **Simulation** | `Minecraft#tick()` 的实际执行时长 |
| **RenderSubmit** | `GameRenderer#render` 的实际执行时长 |
| **Present** | 呈现调用的实际时长 |
| **GPU** | 显卡硬件执行本帧的时长 |
| **Oversleep** | **睡过头的时间** = `min(Sleep, 本帧 GPU 开工时刻 − 上一帧 GPU 完成时刻)`。后者是显卡真正空转的那段; 再与 Sleep 取小, 确保只计入【因本次休眠造成】的空转, CPU 受限或逻辑卡顿导致的空闲不会被误纳入 |

#### Last 行

单根亮白 `|` = **上一帧的 GPU 完成时刻**。控制律把本帧 RENDER_SUBMIT_END 摆到这一时刻上，所以对齐正确时它应紧贴在 **Submit 条的右端**，两者间隔即 `AlignErr`。

---

### 配置选项说明

游戏内通过 Mod Menu / Cloth Config 打开配置界面：

1. **启用 Reflex**（默认开启）：功能总开关。
2. **时序流程图展示 (Timeline Diagram)**（默认开启）：在 F3 以多行甘特图展示八个标记的实测位置与对齐质量。
3. **显示实时延迟指标 (Reflex Metrics)**（默认开启）：是否把面板注入 F3 左侧。
4. **启用诊断日志 (Diagnostic Logging)**（开发环境默认开启，生产环境默认关闭）：每秒聚合向控制台输出一次三类诊断行。
5. **手动休眠偏置 (毫秒)**（默认 0.0ms，范围 −5.0 ~ +5.0）：在算出的休眠时长上叠加固定偏置。正数推迟唤醒，负数提前唤醒；提前到不需要休眠时就不再休眠。

> **配置键迁移**：`config/reflex.json` 里的该项历史上叫 `manualWaitOffsetNs`，后为 `manualWaitOffsetMs`，现为 `manualSleepOffsetMs`。读取时会依次回退到旧键，老配置不会被清零；下次保存即写入新键。

---

## English

**Minecraft-Reflex** is a performance optimization mod that implements the **NVIDIA Reflex dynamic low-latency architecture** in Minecraft. Compatible with any GPU vendor (NVIDIA, AMD, Intel), it eliminates GPU render queue backlog without reducing frame rate or GPU utilization.

### Key Highlights

1. **Render Queue Elimination**:
   Aligns frame start (input sampling + simulation) just-in-time for GPU completion, draining the driver command queue and drastically reducing system input lag.
2. **Input Alignment Before Poll Events**:
   Frame pacing sleep executes strictly *before* RenderSystem.pollEvents(), ensuring inputs are freshly polled right after waking up.
3. **Single Signed Alignment Target**:
   The control law chases exactly one quantity — align this frame's RENDER_SUBMIT_END to the previous frame's measured GPU completion. It re-anchors from fresh measurements every frame, so phase error never accumulates. Wake time is derived entirely from measured estimators (estGpu / estSim / estSub) with no artificial clamps; the only user knob is a fixed manual sleep offset.
4. **Reflex Software Metrics on F3**:
   Displays real-time PC latency, Sleep duration, signed alignment error, and the measured span of every Reflex marker (InputSample, Simulation, RenderSubmit, Present, GPU) on the F3 debug screen.

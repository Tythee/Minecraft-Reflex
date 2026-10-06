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
3. **准确的软硬件解耦与重叠测量**：
   彻底分离 CPU 模拟提交耗时与 GPU 队列同步耗时。通过 GPU 硬件时间戳与系统时钟统一对齐，精确打点获取 CPU/GPU 真实重叠区间（T_overlap），防止调度器虚假误判。

---

### 功能特性

* **全显卡通用**：基于纯物理时序闭环调度，不仅支持 NVIDIA GeForce，同样原生支持 AMD Radeon 与 Intel Arc 显卡。
* **跨渲染后端支持（26.2+ 原生 API 与兼容分支）**：
  * **26.2+**：通过 Mojang GpuDevice 原生时间戳查询池，同时无缝兼容 **OpenGL 与 Vulkan**。
  * **< 26.2**：通过 LWJGL OpenGL 异步查询池（GL_TIMESTAMP）提供高精度纳秒级硬件测量。
* **自适应动态闭环（Adaptive Closed-Loop Margin）**：
  根据当前帧率与硬件波动自动监测 GPU 饥饿（Starvation）与排队积压，自动动态调节安全裕量（0.1ms ~ 1.5ms），无需用户手动猜测纳秒参数。
* **F3 实时延迟指标面板（Reflex Software Metrics）**：
  在 F3 界面实时展示端到端计算延迟、CPU 耗时、GPU 耗时、重叠耗时与休眠时间。
* **多级结构化诊断日志**：
  每秒周期性输出统计摘要至控制台，并对突发 CPU 毛刺与自适应调优事件进行智能追踪。

---

### F3 调试面板指标说明

开启选项后，F3 调试面板将以直观的**多行时序流程图（Timeline Diagram）**展示 CPU 与 GPU 的对齐与排队状态：
```text
[Reflex Pipeline] PC: 21.4ms (Adaptive | Margin: 0.00ms)
├─ CPU: [Wait 13.0ms] ──► [Input] ──► [Game 3.5ms]
└─ GPU:                 [Zero Queue] ──► [Render 17.6ms] (Overlap: 1.5ms)
```
*(若在配置中关闭“时序流程图展示”，则紧凑显示单行文本模式)*

| 指标 | 物理含义 |
| :--- | :--- |
| **PC** | **PC 端到端计算延迟**（输入采样到显卡渲染呈现完成：T_pc = GPU完成时刻 - 输入采样时刻） |
| **Game** | **CPU 耗时**（从输入采样、世界状态更新到渲染指令构建完毕） |
| **Queue** | **渲染队列滞留时长**（指令在显卡驱动队列中等待执行的积压时间） |
| **Render** | **GPU 硬件渲染耗时**（硬件真实执行本帧绘制命令消耗的纳秒时间） |
| **Overlap** | **管线并行重叠时长**（CPU 绘制提交与显卡硬件执行重叠的时间段） |
| **Wait** | **Reflex 调度休眠时长**（为对齐输入所主动等待的时间） |
| **Margin** | **安全裕量**（为防止 GPU 饥饿空转预留的缓冲时长，自适应模式下会自动微调） |

---

### 配置选项说明

游戏内通过 Mod Menu / Cloth Config 打开配置界面：

1. **启用 Reflex**（默认开启）：功能总开关。
2. **时序流程图展示 (Timeline Diagram)**（默认开启）：在 F3 调试面板中以多行流水线流程图直观展示 CPU 与 GPU 的对齐与排队状态。
3. **自适应动态闭环 (Adaptive Margin)**（默认开启）：全自动动态调节安全裕量，平衡无空转与最低延迟。
4. **显示实时延迟指标 (Reflex Metrics)**（默认开启）：在 F3 调试面板注入实时延迟信息。
5. **启用诊断日志 (Diagnostic Logging)**（默认开启）：每秒聚合向控制台输出一次统计摘要。
6. **手动等待偏置 (纳秒)**（默认 0）：可选的微调参数，正数减少等待时间，负数增加等待时间。

---

## English

**Minecraft-Reflex** is a performance optimization mod that implements the **NVIDIA Reflex dynamic low-latency architecture** in Minecraft. Compatible with any GPU vendor (NVIDIA, AMD, Intel), it eliminates GPU render queue backlog without reducing frame rate or GPU utilization.

### Key Highlights

1. **Render Queue Elimination**:
   Aligns frame start (input sampling + simulation) just-in-time for GPU completion, draining the driver command queue and drastically reducing system input lag.
2. **Input Alignment Before Poll Events**:
   Frame pacing sleep executes strictly *before* RenderSystem.pollEvents(), ensuring inputs are freshly polled right after waking up.
3. **Hardware Decoupled Timing & Overlap Measurement**:
   Measures pure CPU frame time without stalling on GPU fence syncs. Tracks GPU hardware execution and clock offset to compute the true CPU/GPU parallel execution overlap (T_overlap).
4. **Adaptive Closed-Loop Control**:
   Auto-tunes safety margins (0.1ms ~ 1.5ms) in response to GPU starvation gaps and CPU workload spikes.
5. **Reflex Software Metrics on F3**:
   Displays real-time PC Latency, Game (CPU), Queue, Render (GPU), Overlap, Wait duration, and Safety Margin on the F3 debug screen.

---

### License

LGPL-3.0 License

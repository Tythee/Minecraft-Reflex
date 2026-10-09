import io.tythee.GpuTimeCollector;
import io.tythee.ReflexScheduler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 回归测试(重构后控制律): presentStart 对齐上一帧 gpuRenderEnd。
 *
 * 新控制律没有积分状态(无 offset/bias), 控制质量完全取决于三个估计量的收敛:
 *   estGpu(GPU 单帧工作时长) / estSim / estSub(唤醒到提交结束的 CPU 时长)
 * 以及由它们构成的投影:
 *   gpuFree = 已测 GPU 结束 + estGpu   (上一帧 GPU 结束的投影 = GPU 变闲时刻)
 *   wake    = gpuFree − lead
 * 所以回归断言全部落在估计器上:
 *   E1/E2  estGpu 抗离群(单帧 8.5x 毛刺不得带偏超过 50%, 3 帧内恢复 15%)
 *   E3     estSim/estSub 收敛到真值
 *   E4     投影 gpuFree 与实际 GPU 结束的偏差 = estGpu − P, 稳态下应收敛到 EMA 底噪内
 *   E5     负载阶跃(P: 17.2 → 14)后 estGpu 在有限帧内重新收敛(瞬态有界)
 *   E6     gpuFree 锚点: 空队列/未开工帧/已开工帧三种 deque 状态下的取值, 以及无锚点时放弃对齐
 *          (E6 直接驱动真实的 calculateSleepTime(), 是唯一覆盖该决策路径的用例)
 *
 * 被控对象(显式声明): GPU 背靠背执行, gpuStart = max(prevGpuEnd, 首批到达),
 * gpuEnd = gpuStart + P。对齐正确时 GPU 无缝衔接(帧间空转 0), 帧率 = 1/P。
 * 对齐是否真的落在 0 由真机 AlignErr 遥测负责, 本测试只保证估计与投影不撒谎。
 */
public class ControllerRecoverySim {

    static final long GPU = 17_200_000L;      // 稳态 GPU 单帧工作时长
    static final long SIM = 1_030_000L;       // pollEvents → render 开始
    static final long SUB = 2_860_000L;       // 提交时长
    static final long FENCE = 100_000L;
    static final long SPIKE_GPU = 146_000_000L; // 实测毛刺帧(8.5x)

    static Method processCompleted;
    static Method calcSleep;
    static Field fSubEst, fSimEst, fGpuEnd;
    static Field fDeque, fGpuFreeDbg, fRecFlag;
    static int failures = 0;

    public static void main(String[] argv) throws Exception {
        Class<?> C = ReflexScheduler.class;
        processCompleted = C.getDeclaredMethod("processCompletedFrame", GpuTimeCollector.class);
        processCompleted.setAccessible(true);
        calcSleep = C.getDeclaredMethod("calculateSleepTime");
        calcSleep.setAccessible(true);
        fSubEst = acc(C, "estimateSubmissionTime");
        fSimEst = acc(C, "estimateSimTime");
        fGpuEnd = acc(C, "lastFrameGpuEndTimeSystem");
        fDeque = acc(C, "gpuTimeCollectorDeque");
        fGpuFreeDbg = acc(C, "dbgGpuFreeNs");
        fRecFlag = acc(C, "dbgRecordedThisFrame");

        estimatorOutlierSim();
        steadyAndStepSim();
        gpuFreeAnchorSim();

        System.out.println();
        System.out.println(failures == 0
                ? "== 回归通过: 估计器与投影在新控制律下可信 =="
                : ("== 回归失败: " + failures + " 项 =="));
        if (failures != 0) {
            System.exit(1);
        }
    }

    /** 构造一帧的合成测量(背靠背饱和态: gpuStart = prevGpuEnd, gpuEnd = gpuStart + p) */
    private static GpuTimeCollector frame(int k, long prevGpuEnd, long p, long sim, long sub) {
        GpuTimeCollector c = new GpuTimeCollector();
        c.frameId = k + 1L;
        c.sleepReturnTime = prevGpuEnd;
        c.renderSubmitStartTime = c.sleepReturnTime + sim;
        c.renderSubmitEndTime = c.renderSubmitStartTime + sub;
        c.presentStartTime = c.renderSubmitEndTime + FENCE;
        c.presentEndTime = c.presentStartTime + FENCE;
        c.startTimeSystem = c.renderSubmitStartTime;   // 首批到达即开工(对齐正确时 GPU 恰好空闲)
        c.endTimeSystem = c.startTimeSystem + p;
        c.sleepDurationNs = 1_000_000L;
        c.startQueryInserted = true;
        return c;
    }

    private static double estGpuMs(ReflexScheduler s) {
        Long v = s.getEstimateGpuTime();
        return v == null ? 0.0 : v / 1e6;
    }

    private static double estLeadMs(ReflexScheduler s) throws Exception {
        long a = (Long) fSimEst.get(s);
        long b = (Long) fSubEst.get(s);
        return (a + b) / 1e6;
    }

    // ============ 用例一: estGpu 抗离群(单个 8.5x 毛刺帧) ============
    private static void estimatorOutlierSim() throws Exception {
        System.out.println("== E1/E2: estGpu 抗离群(单个 8.5x 毛刺帧) ==");
        ReflexScheduler s = newScheduler();
        long prevGpuEnd = 1_000_000_000L;
        int k = 0;
        for (int i = 0; i < 30; i++) {
            GpuTimeCollector c = frame(k++, prevGpuEnd, GPU, SIM, SUB);
            processCompleted.invoke(s, c);
            prevGpuEnd = c.endTimeSystem;
        }

        GpuTimeCollector hitch = frame(k++, prevGpuEnd, SPIKE_GPU, SIM, SUB);
        processCompleted.invoke(s, hitch);
        prevGpuEnd = hitch.endTimeSystem;
        double estAfter = estGpuMs(s);

        double worst = Math.abs(estAfter - GPU / 1e6);
        for (int i = 0; i < 3; i++) {
            GpuTimeCollector c = frame(k++, prevGpuEnd, GPU, SIM, SUB);
            processCompleted.invoke(s, c);
            prevGpuEnd = c.endTimeSystem;
            worst = Math.min(worst, Math.abs(estGpuMs(s) - GPU / 1e6));
        }

        System.out.printf(Locale.ROOT,
                "  estGpu: %.2f ->(毛刺帧) %.2fms ->(3帧) %.2fms  | 真值 %.2fms%n",
                GPU / 1e6, estAfter, estGpuMs(s), GPU / 1e6);
        check("E1 单帧 8.5x 毛刺后 estGpu 偏离 <= 50% (修复前 +250%)",
                Math.abs(estAfter - GPU / 1e6) <= 0.5 * GPU / 1e6);
        check("E2 3 帧内恢复到 15% 以内", worst <= 0.15 * GPU / 1e6);
    }

    // ============ 用例二: 稳态收敛 + 投影精度 + 负载阶跃 ============
    private static void steadyAndStepSim() throws Exception {
        System.out.println();
        System.out.println("== E3/E4/E5: 稳态收敛, 投影精度, 负载阶跃恢复 ==");
        ReflexScheduler s = newScheduler();
        long prevGpuEnd = 1_000_000_000L;
        int k = 0;

        // 稳态 60 帧
        double worstProj = 0;
        for (int i = 0; i < 60; i++) {
            double projBefore = prevGpuEnd / 1e6 + estGpuMs(s);   // 控制器视角: 上一帧结束 + estGpu
            GpuTimeCollector c = frame(k++, prevGpuEnd, GPU, SIM, SUB);
            processCompleted.invoke(s, c);
            prevGpuEnd = c.endTimeSystem;
            if (i >= 20) {                                        // 预热后才开始断言
                worstProj = Math.max(worstProj, Math.abs(projBefore - prevGpuEnd / 1e6));
            }
        }
        double gpuErr = Math.abs(estGpuMs(s) - GPU / 1e6);
        double leadErr = Math.abs(estLeadMs(s) - (SIM + SUB) / 1e6);
        System.out.printf(Locale.ROOT,
                "  稳态: estGpu %.2fms(真值 %.2f) | estLead %.2fms(真值 %.2f) | 投影最大偏差 %.2fms%n",
                estGpuMs(s), GPU / 1e6, estLeadMs(s), (SIM + SUB) / 1e6, worstProj);
        check("E3 估计量收敛(estGpu/estLead 偏差 <= 5%)",
                gpuErr <= 0.05 * GPU / 1e6 && leadErr <= 0.05 * (SIM + SUB) / 1e6);
        check("E4 投影 gpuFree 与实际 GPU 结束的偏差 <= 10% (决定对齐精度)",
                worstProj <= 0.10 * GPU / 1e6);

        // 负载阶跃: P 17.2 → 14.0(场景变轻), 20 帧后再评估
        double before = estGpuMs(s);
        for (int i = 0; i < 20; i++) {
            GpuTimeCollector c = frame(k++, prevGpuEnd, 14_000_000L, SIM, SUB);
            processCompleted.invoke(s, c);
            prevGpuEnd = c.endTimeSystem;
        }
        System.out.printf(Locale.ROOT,
                "  阶跃: estGpu %.2f -> %.2fms(真值 14.00)%n", before, estGpuMs(s));
        check("E5 负载阶跃后 20 帧内重新收敛(偏差 <= 10%)",
                Math.abs(estGpuMs(s) - 14.0) <= 0.10 * 14.0);
    }

    // ============ 用例三: gpuFree 锚点在三种 deque 状态下的取值 ============
    /**
     * gpuFree 的语义是"最后一帧的预期 GPU 完成时刻", 因此锚点(上一帧实测完成时刻)之上
     * 只应叠加【真实在途】的帧数。旧实现在锚点上无条件先加一个 estGpu, 于是:
     *   - 队列为空(上一帧已完工, 显卡此刻真闲着)时凭空多算一帧 => 对着空闲显卡白睡;
     *   - 队列非空但都是未开工帧时多算一帧。
     * 而"有已开工帧"这一支会用实测开工时刻【覆盖】锚点, 所以满载(GPU-Bound)行为不受影响。
     * E6c 就是钉住这一点: 该分支的期望值在修复前后必须逐位相同。
     */
    private static void gpuFreeAnchorSim() throws Exception {
        System.out.println();
        System.out.println("== E6: gpuFree 锚点取值 (deque 三种状态 + 无锚点) ==");

        ReflexScheduler s = newScheduler();
        for (int i = 0; i < 30; i++) {
            s.updateGpuTime(GPU);                       // 让 estGpu 收敛并稳定
        }
        long estGpu = s.getEstimateGpuTime();
        long lastEnd = 1_000_000_000L;
        fGpuEnd.set(s, lastEnd);
        System.out.printf(Locale.ROOT, "  estGpu=%,.3fms  锚点=上一帧实测完成时刻%n", estGpu / 1e6);

        // A) 空队列: 显卡已闲, 不该再投影一帧
        clearDeque(s);
        report("E6a 空队列 => gpuFree = 锚点 (修复前会多算 1×estGpu)", s, lastEnd, estGpu);

        // B) 一个已提交但未开工的帧: 它排在锚点之后, 占一帧
        clearDeque(s);
        deque(s).add(inFlight(0L));
        report("E6b 1 个未开工帧 => 锚点 + 1×estGpu", s, lastEnd + estGpu, estGpu);

        // C) 满载护栏: 已开工帧的实测开工时刻覆盖锚点, 再叠加排队的 1 帧
        long startedAt = lastEnd + 500_000L;
        clearDeque(s);
        deque(s).add(inFlight(startedAt));
        deque(s).add(inFlight(0L));
        report("E6c 1 已开工 + 1 未开工 => 开工时刻 + 2×estGpu (修复前后必须一致)",
                s, startedAt + 2 * estGpu, estGpu);

        // D) 完全没有锚点: 不猜, 放弃对齐
        fGpuEnd.set(s, null);
        clearDeque(s);
        Long r = calcCall(s);
        boolean ok = (r == null) && (gpuFreeOf(s) == Long.MIN_VALUE);
        System.out.printf(Locale.ROOT, "  %-62s 期望=不猜/无效  实得=%s%n",
                "E6d 无锚点", (r == null) ? "null(未对齐)" : String.format(Locale.ROOT, "%.2fms", r / 1e6));
        check("E6d 无锚点时不猜测, 返回 null 且 gpuFree 记为无效", ok);
    }

    /** 跑一次真实 calculateSleepTime(), 比对它算出的 gpuFree 与期望值。 */
    private static void report(String what, ReflexScheduler s, long expect, long estGpu) throws Exception {
        calcCall(s);
        long got = gpuFreeOf(s);
        System.out.printf(Locale.ROOT, "  %-62s 期望=%,.3fms  实得=%,.3fms%n",
                what, (expect - 1_000_000_000L) / 1e6, (got - 1_000_000_000L) / 1e6);
        check(what, got == expect);
    }

    private static Long calcCall(ReflexScheduler s) throws Exception {
        fRecFlag.set(s, false);          // recordSleepDebug 每帧只记一次, 逐例复位
        return (Long) calcSleep.invoke(s);
    }

    private static long gpuFreeOf(ReflexScheduler s) throws Exception {
        return (Long) fGpuFreeDbg.get(s);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Deque<GpuTimeCollector> deque(ReflexScheduler s) throws Exception {
        return (java.util.Deque<GpuTimeCollector>) fDeque.get(s);
    }

    private static void clearDeque(ReflexScheduler s) throws Exception {
        deque(s).clear();
    }

    /** 在途帧: startedAt==0 表示已提交但尚未开工(startTimeSystem 还没测到)。 */
    private static GpuTimeCollector inFlight(long startedAt) {
        GpuTimeCollector c = new GpuTimeCollector();
        c.startQueryInserted = true;
        c.startTimeSystem = (startedAt == 0L) ? null : startedAt;
        return c;
    }

    private static ReflexScheduler newScheduler() throws Exception {
        ReflexScheduler s = new ReflexScheduler();
        fSubEst.set(s, SUB);
        fSimEst.set(s, SIM);
        fGpuEnd.set(s, 1_000_000_000L);
        return s;
    }

    private static void check(String what, boolean ok) {
        System.out.println("   [" + (ok ? "PASS" : "FAIL") + "] " + what);
        if (!ok) {
            failures++;
        }
    }

    private static Field acc(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}

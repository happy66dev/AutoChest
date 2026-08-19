package io.github.autochest.service;

/**
 * 记录单个提交 tick 的操作数量与时间预算，供整理和补货阶段共享喵~
 */
public final class SubmissionBudget {
    // 当前 tick 允许执行的最大完整操作数量喵~
    private final int maxOperations;
    // 当前 tick 允许消耗的最大纳秒数喵~
    private final long maxNanos;
    // 当前 tick 的起始单调时钟值喵~
    private final long startNanos;
    // 当前 tick 已完成的操作数量喵~
    private int processedOperations;

    /**
     * 创建一个提交预算。
     *
     * @param maxOperations 最大操作数量
     * @param maxNanos 最大纳秒数
     * @param startNanos 单调时钟起点
     */
    public SubmissionBudget(int maxOperations, long maxNanos, long startNanos) {
        // 喵~防御：非正预算会导致任务无法推进，统一回退到一次操作喵~
        this.maxOperations = Math.max(1, maxOperations);
        // 喵~防御：非正时间预算会导致任务每 tick 立即让出，统一回退到一纳秒喵~
        this.maxNanos = Math.max(1L, maxNanos);
        // 保存调用方提供的单调时钟起点，禁止使用墙上时间喵~
        this.startNanos = startNanos;
    }

    // 判断当前 tick 是否已经达到任一预算上限喵~
    public boolean exhausted(long nowNanos) {
        // 以饱和减法防御系统时钟异常回退造成的溢出喵~
        long elapsedNanos = nowNanos >= startNanos ? nowNanos - startNanos : Long.MAX_VALUE;
        // 任一上限达到后都必须等待下一 tick 喵~
        return processedOperations >= maxOperations || elapsedNanos >= maxNanos;
    }

    // 记录一次已经完整结束的 validate 或 mutation 操作喵~
    public void markOperation() {
        // 喵~防御：计数接近上限时饱和，避免整数溢出绕过预算喵~
        if (processedOperations < Integer.MAX_VALUE) {
            processedOperations++;
        }
    }

    // 返回当前 tick 已处理操作数，供测试和指标使用喵~
    public int processedOperations() {
        // 暴露只读计数，不允许调用方直接修改预算状态喵~
        return processedOperations;
    }

    // 返回归一化后的最大操作数喵~
    public int maxOperations() {
        // 提供配置诊断值喵~
        return maxOperations;
    }

    // 返回归一化后的最大纳秒数喵~
    public long maxNanos() {
        // 提供配置诊断值喵~
        return maxNanos;
    }
}

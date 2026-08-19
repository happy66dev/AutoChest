package io.github.autochest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// 验证单 tick 提交预算的边界、时钟异常和计数饱和语义喵~
class SubmissionBudgetTest {
    // 验证正常操作计数达到上限后预算耗尽喵~
    @Test
    void operationLimit_isEnforced() {
        // 创建允许两次完整操作的预算喵~
        SubmissionBudget budget = new SubmissionBudget(2, 1_000L, 100L);
        // 初始状态未达到任何预算上限喵~
        assertFalse(budget.exhausted(100L));
        // 记录第一次完整操作喵~
        budget.markOperation();
        // 一次操作后仍可继续喵~
        assertFalse(budget.exhausted(100L));
        // 记录第二次完整操作喵~
        budget.markOperation();
        // 达到操作上限后必须让出 tick 喵~
        assertTrue(budget.exhausted(100L));
        // 暴露计数必须与完整操作次数一致喵~
        assertEquals(2, budget.processedOperations());
    }

    // 验证纳秒预算达到边界时停止继续处理喵~
    @Test
    void nanosLimit_isEnforcedAtBoundary() {
        // 创建时间预算为十纳秒的对象喵~
        SubmissionBudget budget = new SubmissionBudget(10, 10L, 100L);
        // 边界前仍可执行喵~
        assertFalse(budget.exhausted(109L));
        // 到达边界时必须停止喵~
        assertTrue(budget.exhausted(110L));
    }

    // 验证时钟回退不会被错误解释为负耗时喵~
    @Test
    void clockRollback_isConservative() {
        // 创建单调时钟起点喵~
        SubmissionBudget budget = new SubmissionBudget(10, 10L, 100L);
        // 时钟回退直接按异常耗时处理喵~
        assertTrue(budget.exhausted(99L));
    }

    // 验证非法配置归一化并防御非正预算喵~
    @Test
    void invalidLimits_areNormalized() {
        // 使用零和负数构造预算喵~
        SubmissionBudget budget = new SubmissionBudget(0, -1L, 100L);
        // 最小操作数回退为一次喵~
        assertEquals(1, budget.maxOperations());
        // 最小时间回退为一纳秒喵~
        assertEquals(1L, budget.maxNanos());
    }

    // 验证操作计数接近整数上限时不会溢出绕过限制喵~
    @Test
    void operationCounter_saturatesAtIntegerMaximum() throws Exception {
        // 创建普通预算对象喵~
        SubmissionBudget budget = new SubmissionBudget(Integer.MAX_VALUE, Long.MAX_VALUE, 0L);
        // 使用反射构造边界状态，避免执行超过二十亿次循环喵~
        java.lang.reflect.Field field = SubmissionBudget.class.getDeclaredField("processedOperations");
        // 允许测试访问私有计数器喵~
        field.setAccessible(true);
        // 设置为整数最大值喵~
        field.setInt(budget, Integer.MAX_VALUE);
        // 再次计数必须保持饱和喵~
        budget.markOperation();
        // 断言没有溢出为负数喵~
        assertEquals(Integer.MAX_VALUE, budget.processedOperations());
    }
}

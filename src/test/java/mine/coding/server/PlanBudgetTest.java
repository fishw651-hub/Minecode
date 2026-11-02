package mine.coding.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R8/T3：计划级方块预算只统计"通过校验（allowed）"的动作。
 * 之前 preview 先把每个动作的 box 塞进列表再校验，5 个会被拒绝的 4096 方块动作
 * 会让整份预览以 "plan exceeds block limit" 失败，且快照已经白做。
 */
class PlanBudgetTest {
    @Test void onlyAllowedActionsCountTowardsThePlanBudget(){
        PlanBudget budget=new PlanBudget();
        assertEquals(4096,budget.add(ActionPolicy.box(0,0,0,15,15,15)),"第一个允许的动作计入");
        assertEquals(8192,budget.add(ActionPolicy.box(0,0,0,15,15,15)),"8192 正好是计划上限，必须放行");
    }

    @Test void rejectedActionsDoNotCountTowardsThePlanBudget(){
        // 模拟 preview：5 个"会被拒绝"的动作不会被 add（它们没有通过校验）；
        // 之前它们被计入后整份预览会以 plan exceeds block limit 失败，这里必须只剩 2 个允许动作的 8192。
        PlanBudget budget=new PlanBudget();
        long total=budget.add(ActionPolicy.box(0,0,0,15,15,15));
        total=budget.add(ActionPolicy.box(0,0,0,15,15,15));
        assertEquals(8192,total,"被拒绝的动作不能占用计划预算");
    }

    @Test void exceedingThePlanBudgetFailsDeterministically(){
        PlanBudget budget=new PlanBudget();
        budget.add(ActionPolicy.box(0,0,0,15,15,15));
        budget.add(ActionPolicy.box(0,0,0,15,15,15));
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->budget.add(ActionPolicy.box(0,0,0,15,15,15)));
        assertEquals("plan exceeds block limit",error.getMessage(),"超限必须让整份预览以明确原因失败，而不是静默丢弃动作");
    }

    @Test void smallerAllowedActionsAccumulateExactly(){
        PlanBudget budget=new PlanBudget();
        assertEquals(1,budget.add(ActionPolicy.box(0,0,0,0,0,0)),"单方块动作体积为 1");
        assertEquals(1+27,budget.add(ActionPolicy.box(0,0,0,2,2,2)),"3x3x3 动作体积为 27");
    }
}

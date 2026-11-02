package mine.coding.server;

import java.util.ArrayList;
import java.util.List;

/**
 * 计划级方块预算（R8 / T3）。
 *
 * <p>{@code preview} 必须只对<b>通过校验（allowed）</b>的动作调用 {@link #add}：
 * 之前把每个动作的 box 先塞进列表再校验，导致 5 个"会被拒绝的 4096 方块动作"也会让整份预览
 * 以 {@code plan exceeds block limit} 失败，而且快照已经白做。
 *
 * <p>超限行为保持确定：抛 {@link IllegalArgumentException}（文案与原来一致），
 * 让整份预览以明确原因失败，绝不静默丢弃动作。
 *
 * <p>纯类：零 {@code net.minecraft.*} 依赖。
 */
public final class PlanBudget {
    private final List<ActionPolicy.Box> counted=new ArrayList<>();

    /** 计入一个已通过校验的动作，返回当前累计体积；超出计划上限时抛出。 */
    public long add(ActionPolicy.Box allowedBox){
        counted.add(allowedBox);
        return ActionPolicy.totalVolume(counted);
    }
}

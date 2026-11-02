package mine.coding.server;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.UUID;

/**
 * 动作确认/拒绝的纯记账流程：消费提案 key、确认墓碑、审计日志与结果表。
 * 不碰 Minecraft API；MineCodeService 只在记账成功之后才写世界，因此世界改动永远不会先于记账。
 */
public final class ActionLedger {
    private ActionLedger(){}

    public static String actionKey(String actionId){return "action/"+actionId;}
    public static String resultKey(String planId,String actionId){return planId+"/"+actionId;}

    /** 确认前记账：墓碑与消费都必须先成功，失败时世界还没被碰过。 */
    public static void confirm(ProposalRegistry<?> plans,UUID owner,String planId,String actionId,String dimension,JsonObject archive,String actionType){
        ArchiveStore.confirmOnce(archive,planId,actionId);
        plans.consume(owner,planId,actionKey(actionId),dimension);
        ArchiveStore.appendLog(archive,"confirmed action "+actionType);
    }

    /** 世界写入成功后的记账：新版本、成功日志与结果表。 */
    public static void succeed(JsonObject archive,Map<String,Boolean> results,String planId,String actionId,String actionType){
        ArchiveStore.commitRevision(archive);ArchiveStore.appendLog(archive,"action succeeded "+actionType);
        results.put(resultKey(planId,actionId),true);
    }

    /** 拒绝：只消费一次提案 key（第二次消费会把审计和结果一起丢掉），并记审计与失败结果。 */
    public static void reject(ProposalRegistry<?> plans,UUID owner,String planId,String actionId,String dimension,JsonObject archive,Map<String,Boolean> results){
        plans.consume(owner,planId,actionKey(actionId),dimension);
        ArchiveStore.appendLog(archive,"action rejected");
        results.put(resultKey(planId,actionId),false);
    }

    /** 失败记账：世界已回滚，档案不动，结果表置 false。 */
    public static void fail(Map<String,Boolean> results,String planId,String actionId){results.put(resultKey(planId,actionId),false);}
}

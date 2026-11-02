package mine.coding.server;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 每玩家、每操作一个令牌桶，用于挡住"每 tick 一发"的重请求（R7 / T1）。
 *
 * <p>容量与速率的取值依据（单次请求在主线程上的实际工作量）：
 * <ul>
 *   <li>{@code preview}：最多 16 个动作 × 4096 方块（每个动作要校验位置、检查方块实体，再做一次世界快照），
 *       外加项目授权读取；单次就足以吃掉一个 tick 量级的时间。容量 1、每 2 秒补 1 个令牌，
 *       即"每 2 秒最多 1 次 preview"。</li>
 *   <li>{@code focus}：最多 4096 次 getBlockState + hasChunk + 方块状态字符串化。容量 3、每秒补 2 个令牌：
 *       一次突发正好放行编排器一轮最多 3 次 attention 读取（协议上限），之后必须以 2 次/秒的速率排队，
 *       既不影响正常读取，又把"每 tick 一发"压成持续负载上限。</li>
 *   <li>{@code context}：读取档案 + 项目档案并回传整份状态。容量 4、每秒补 2 个令牌。</li>
 *   <li>{@code rememberChat}：读档、追加上限校验、写盘，并紧接着再触发一次 context。容量 2、每秒补 1 个令牌。</li>
 * </ul>
 *
 * <p>状态迁移类请求（confirm/reject/undo/approveProject 等）<b>不</b>限流：玩家必须随时能收拾自己的局面，
 * 否则限流会变成新的可用性缺陷。令牌桶状态只存在于内存，玩家断线即丢弃。
 *
 * <p>纯类：零 {@code net.minecraft.*} 依赖，时钟由 {@link LongSupplier} 注入，可纯 JVM 单测。
 */
public final class RateLimiter {
    private record Rule(double capacity,double tokensPerSecond,String description){}
    private static final Map<String,Rule> RULES=Map.of(
            "preview",new Rule(1,0.5,"preview 每 2 秒最多 1 次"),
            "focus",new Rule(3,2.0,"focus 每秒最多 2 次"),
            "context",new Rule(4,2.0,"context 每秒最多 2 次"),
            "rememberChat",new Rule(2,1.0,"rememberChat 每秒最多 1 次"));
    private final LongSupplier clockMillis;
    private final Map<UUID,Map<String,Bucket>> buckets=new HashMap<>();

    public RateLimiter(){this(System::currentTimeMillis);}
    public RateLimiter(LongSupplier clockMillis){this.clockMillis=Objects.requireNonNull(clockMillis,"clockMillis");}

    public static boolean isHeavyOperation(String op){return RULES.containsKey(op);}

    /** 命中限流时展示给玩家的明确中文提示；非重操作返回 null。 */
    public static String limitDescription(String op){
        Rule rule=RULES.get(op);
        return rule==null?null:rule.description();
    }

    /** 供测试与限流文案使用：该操作的突发容量（未知操作为 0）。 */
    public static int burstCapacity(String op){
        Rule rule=RULES.get(op);
        return rule==null?0:(int)rule.capacity();
    }

    /**
     * 尝试为玩家取一个令牌。未知/状态迁移类操作一律放行（返回 true）。
     * 时钟回拨不会补令牌，空闲再久也不会突破突发容量。
     */
    public synchronized boolean tryAcquire(UUID player,String op){
        Rule rule=RULES.get(op);
        if(rule==null)return true;
        Objects.requireNonNull(player,"player");
        long now=clockMillis.getAsLong();
        Map<String,Bucket> perPlayer=buckets.computeIfAbsent(player,ignored->new HashMap<>());
        Bucket bucket=perPlayer.computeIfAbsent(op,ignored->new Bucket(rule.capacity(),now));
        long elapsed=now-bucket.updated;
        if(elapsed>0){
            bucket.tokens=Math.min(rule.capacity(),bucket.tokens+elapsed/1000.0*rule.tokensPerSecond());
            bucket.updated=now;
        }
        // 1e-9 容差：毫秒/秒换算的浮点误差不能把"刚好补满一个令牌"判成不够。
        if(bucket.tokens<1.0-1e-9)return false;
        bucket.tokens=Math.max(0.0,bucket.tokens-1.0);
        return true;
    }

    /** 玩家断线时丢弃他的桶，重连不继承旧冷却。 */
    public synchronized void forget(UUID player){buckets.remove(player);}

    /** 服务器停止时丢弃全部桶。 */
    public synchronized void clear(){buckets.clear();}

    private static final class Bucket{
        private double tokens;
        private long updated;
        private Bucket(double tokens,long updated){this.tokens=tokens;this.updated=updated;}
    }
}

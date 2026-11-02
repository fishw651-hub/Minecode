package mine.coding.server;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R7/T1：重请求（preview/focus/context/rememberChat）必须按玩家令牌桶限流，
 * 状态迁移类请求（confirm/reject/undo/approveProject 等）永远不受限。
 */
class RateLimiterTest {
    private final AtomicLong clock=new AtomicLong(1_000_000L);
    private RateLimiter limiter(){return new RateLimiter(clock::get);}

    @Test void burstUpToCapacityThenThrottles(){
        RateLimiter limiter=limiter();UUID player=UUID.randomUUID();
        for(int i=0;i<RateLimiter.burstCapacity("focus");i++)assertTrue(limiter.tryAcquire(player,"focus"),"第 "+(i+1)+" 次 focus 应落在突发窗口内");
        assertFalse(limiter.tryAcquire(player,"focus"),"突发窗口用尽后必须限流，否则每 tick 一发仍能打满主线程");
    }

    @Test void tokensRefillWithElapsedTimeInsteadOfCallCount(){
        RateLimiter limiter=limiter();UUID player=UUID.randomUUID();
        exhaust(limiter,player,"focus");
        clock.addAndGet(499);
        assertFalse(limiter.tryAcquire(player,"focus"),"focus 每秒补 2 个令牌，499ms 不足一个");
        clock.addAndGet(1);
        assertTrue(limiter.tryAcquire(player,"focus"),"满 500ms 必须补回 1 个令牌");
        assertFalse(limiter.tryAcquire(player,"focus"),"补回的令牌只能用一次");
    }

    @Test void refillIsCappedAtBurstCapacity(){
        RateLimiter limiter=limiter();UUID player=UUID.randomUUID();
        exhaust(limiter,player,"focus");
        clock.addAndGet(60_000);
        for(int i=0;i<RateLimiter.burstCapacity("focus");i++)assertTrue(limiter.tryAcquire(player,"focus"),"长时间空闲最多攒满一个突发窗口");
        assertFalse(limiter.tryAcquire(player,"focus"),"空闲再久也不能突破突发容量");
    }

    @Test void playersHaveIndependentBuckets(){
        RateLimiter limiter=limiter();UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        exhaust(limiter,a,"focus");
        assertFalse(limiter.tryAcquire(a,"focus"));
        assertTrue(limiter.tryAcquire(b,"focus"),"一个玩家被限流不能影响其他玩家");
    }

    @Test void operationsHaveIndependentBuckets(){
        RateLimiter limiter=limiter();UUID player=UUID.randomUUID();
        exhaust(limiter,player,"focus");
        assertFalse(limiter.tryAcquire(player,"focus"));
        assertTrue(limiter.tryAcquire(player,"preview"),"不同操作各自独立计数，focus 读爆不能连坐 preview");
    }

    @Test void throttledRequestsRecoverAfterCooldown(){
        RateLimiter limiter=limiter();UUID player=UUID.randomUUID();
        assertTrue(limiter.tryAcquire(player,"preview"),"第一次 preview 放行");
        assertFalse(limiter.tryAcquire(player,"preview"),"preview 每 2 秒最多 1 次");
        clock.addAndGet(2_000);
        assertTrue(limiter.tryAcquire(player,"preview"),"冷却结束后必须恢复可用");
    }

    @Test void stateTransitionsAreNeverRateLimited(){
        RateLimiter limiter=limiter();UUID player=UUID.randomUUID();
        for(String op:List.of("confirm","reject","undo","approveProject","confirmStage","confirmMemory","confirmSkill","rollback","manualMemory","manualSkill","proposeProject","logs","clearMemory")){
            assertFalse(RateLimiter.isHeavyOperation(op),op+" 不是重请求");
            for(int i=0;i<50;i++)assertTrue(limiter.tryAcquire(player,op),op+" 必须永远可执行：限流状态迁移会让玩家无法收拾自己的局面");
        }
    }

    @Test void heavyOperationSurfaceIsExplicit(){
        for(String op:List.of("preview","focus","context","rememberChat")){
            assertTrue(RateLimiter.isHeavyOperation(op),op+" 必须被限流");
            String description=RateLimiter.limitDescription(op);
            assertNotNull(description,op);
            assertTrue(description.contains("次"),"限流提示必须是明确中文文案："+description);
        }
        assertNull(RateLimiter.limitDescription("confirm"),"状态迁移没有限流文案");
    }

    @Test void forgottenPlayerStartsWithAFullBucket(){
        RateLimiter limiter=limiter();UUID player=UUID.randomUUID();
        exhaust(limiter,player,"focus");
        limiter.forget(player);
        assertTrue(limiter.tryAcquire(player,"focus"),"断线后桶状态必须丢弃，重连不能带着旧冷却");
    }

    @Test void clockGoingBackwardsDoesNotGrantTokens(){
        RateLimiter limiter=limiter();UUID player=UUID.randomUUID();
        exhaust(limiter,player,"focus");
        clock.addAndGet(-5_000);
        assertFalse(limiter.tryAcquire(player,"focus"),"时钟回拨不能被当成补充令牌");
    }

    private static void exhaust(RateLimiter limiter,UUID player,String op){
        for(int i=0;i<RateLimiter.burstCapacity(op);i++)assertTrue(limiter.tryAcquire(player,op),op+" 第 "+(i+1)+" 次应放行");
    }
}

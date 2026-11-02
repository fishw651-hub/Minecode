package mine.coding.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

class ActionPolicyTest {
    @Test void inclusiveVolumeAndReversedCoordinates() { assertEquals(4096,ActionPolicy.volume(ActionPolicy.box(15,15,15,0,0,0))); }
    @Test void overflowAndOversizedVolumeAreRejected() { assertThrows(IllegalArgumentException.class,()->ActionPolicy.box(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE)); assertThrows(IllegalArgumentException.class,()->ActionPolicy.box(0,0,0,16,16,16)); }
    @Test void radiusAndPlanBudgetAreEnforced() { assertThrows(IllegalArgumentException.class,()->ActionPolicy.checkRange(ActionPolicy.box(33,0,0,33,0,0),0,0,0)); var box=ActionPolicy.box(0,0,0,15,15,15); assertThrows(IllegalArgumentException.class,()->ActionPolicy.totalVolume(List.of(box,box,box))); }
    @Test void commandsUseExactWhitelistAndRejectIndirectBypass() { for(String command:List.of("execute run say hello","minecraft:fill 0 0 0 1 1 1 stone","function nested","schedule function a 1t","mod:execute say hi","unknown foo","time set day","say hi\nstop","say hi;stop")) assertThrows(IllegalArgumentException.class,()->ActionPolicy.safeCommand(command),command); assertEquals("say hello",ActionPolicy.safeCommand("/say hello")); assertEquals("time query daytime",ActionPolicy.safeCommand("time query daytime")); }
    @Test void radiusUsesDistanceRatherThanAllowingCubeCorners(){assertThrows(IllegalArgumentException.class,()->ActionPolicy.checkRange(ActionPolicy.box(32,0,32,32,0,32),0,0,0));}
    @Test void fullBoxMustFitRadiusInsteadOfOnlyNearestPoint(){assertThrows(IllegalArgumentException.class,()->ActionPolicy.checkRange(ActionPolicy.box(0,0,0,33,0,0),0,0,0));}
    @Test void radiusBoundaryIsInclusive(){assertDoesNotThrow(()->ActionPolicy.checkRange(ActionPolicy.box(0,0,0,32,0,0),0,0,0));}
    // S8：dispatcher.execute 返回 0 表示命令没有执行/没有效果，必须按失败处理。
    @Test void dispatcherZeroResultIsAFailure(){assertFalse(ActionPolicy.commandSucceeded(0));assertTrue(ActionPolicy.commandSucceeded(1));assertTrue(ActionPolicy.commandSucceeded(3));}
}

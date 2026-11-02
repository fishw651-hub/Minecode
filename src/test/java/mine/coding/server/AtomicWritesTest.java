package mine.coding.server;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** S2/S7：多方块写入的原子性（部分写入失败必须回到原状）。 */
class AtomicWritesTest {
    private static final class FakeWorld {
        final Map<String,String> blocks=new LinkedHashMap<>();
        final Set<String> rejectWrites=new HashSet<>();   // 直接写入失败的键
        final Set<String> rejectRestores=new HashSet<>(); // 回滚写入失败的键
        int writes;
        void write(String key,String value){
            writes++;
            boolean restore="air".equals(value);
            if(restore?rejectRestores.contains(key):rejectWrites.contains(key))throw new IllegalArgumentException("写入失败："+key);
            blocks.put(key,value);
        }
    }
    private static Map<String,String> air(String... keys){
        Map<String,String> map=new LinkedHashMap<>();for(String key:keys)map.put(key,"air");return map;
    }
    private static Map<String,String> stone(String... keys){
        Map<String,String> map=new LinkedHashMap<>();for(String key:keys)map.put(key,"stone");return map;
    }
    @Test void successfulWriteLeavesEveryTargetBlockInPlace(){
        var world=new FakeWorld();
        assertEquals(3,AtomicWrites.writeAll(stone("a","b","c"),air("a","b","c"),world::write));
        assertEquals(Map.of("a","stone","b","stone","c","stone"),world.blocks);
    }
    @Test void failureRestoresTheBlocksAlreadyWrittenAndSkipsTheRest(){
        var world=new FakeWorld();world.rejectWrites.add("b");
        var failure=assertThrows(IllegalArgumentException.class,()->AtomicWrites.writeAll(stone("a","b","c"),air("a","b","c"),world::write));
        assertEquals("写入失败：b",failure.getMessage());
        assertEquals("air",world.blocks.get("a"),"已写入的方块必须回到写入前状态");
        assertFalse(world.blocks.containsKey("b"),"失败的方块本身没有被写入");
        assertFalse(world.blocks.containsKey("c"),"失败之后的方块不能继续写");
    }
    @Test void rollbackFailureIsSuppressedInsteadOfHidingTheOriginalCause(){
        var world=new FakeWorld();world.rejectWrites.add("b");world.rejectRestores.add("a");
        var failure=assertThrows(IllegalArgumentException.class,()->AtomicWrites.writeAll(stone("a","b"),air("a","b"),world::write));
        assertEquals("写入失败：b",failure.getMessage(),"原始失败原因不能被回滚失败覆盖");
        assertEquals(1,failure.getSuppressed().length,"回滚失败要作为 suppressed 保留");
    }
    @Test void emptyTargetIsANoOp(){
        var world=new FakeWorld();assertEquals(0,AtomicWrites.writeAll(Map.of(),Map.of(),world::write));assertEquals(0,world.writes);
    }
}

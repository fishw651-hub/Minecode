package mine.coding.server;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ProposalRegistryTest {
    @Test void proposalsAreOwnedExpiringAndSingleUse(){var time=new AtomicLong(1);var registry=new ProposalRegistry<String>(time::get);var owner=UUID.randomUUID();var id=registry.put(owner,"overworld","plan");assertEquals("plan",registry.get(owner,id,"overworld"));assertThrows(IllegalArgumentException.class,()->registry.get(UUID.randomUUID(),id,"overworld"));assertThrows(IllegalArgumentException.class,()->registry.get(owner,id,"nether"));registry.consume(owner,id,"a","overworld");assertThrows(IllegalArgumentException.class,()->registry.consume(owner,id,"a","overworld"));time.set(600001);assertThrows(IllegalArgumentException.class,()->registry.get(owner,id,"overworld"));}
    @Test void replacingPlanAndDisconnectInvalidateOldId(){var registry=new ProposalRegistry<String>();var owner=UUID.randomUUID();String first=registry.put(owner,"dim","old");String next=registry.put(owner,"dim","new");assertThrows(IllegalArgumentException.class,()->registry.get(owner,first,"dim"));registry.remove(owner);assertThrows(IllegalArgumentException.class,()->registry.get(owner,next,"dim"));}
    // S6：tick 必须同时清理计划与项目草稿（草稿含最长 16384 字符的蓝图）。
    @Test void expiringOnlyPlansLeavesProjectDraftsBehind(){
        var time=new AtomicLong(1);var plans=new ProposalRegistry<String>(time::get);var drafts=new ProposalRegistry<String>(time::get);var owner=UUID.randomUUID();
        plans.put(owner,"dim","plan");drafts.put(owner,"dim","draft");time.set(600001);
        plans.expire();
        assertEquals(0,plans.size());assertEquals(1,drafts.size(),"只 expire 计划会把项目草稿留在内存里");
        ProposalRegistry.expireAll(plans,drafts);
        assertEquals(0,drafts.size());
    }
    @Test void expireAllLeavesLiveEntriesAlone(){
        var time=new AtomicLong(1);var plans=new ProposalRegistry<String>(time::get);var drafts=new ProposalRegistry<String>(time::get);var owner=UUID.randomUUID();
        String planId=plans.put(owner,"dim","plan");String draftId=drafts.put(owner,"dim","draft");
        ProposalRegistry.expireAll(plans,drafts);
        assertEquals(1,plans.size());assertEquals(1,drafts.size());
        assertEquals("plan",plans.get(owner,planId,"dim"));assertEquals("draft",drafts.get(owner,draftId,"dim"));
    }
}

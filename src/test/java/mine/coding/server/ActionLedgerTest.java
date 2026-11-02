package mine.coding.server;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** S1/S2：确认与拒绝必须只消费一次提案，并把记账放在世界写入之前。 */
class ActionLedgerTest {
    @Test void rejectConsumesTheActionExactlyOnce(){
        var plans=new ProposalRegistry<String>();UUID owner=UUID.randomUUID();String planId=plans.put(owner,"overworld","plan");
        var archive=ArchiveStore.empty();var results=new HashMap<String,Boolean>();
        ActionLedger.reject(plans,owner,planId,"a","overworld",archive,results);
        assertTrue(plans.isConsumed(owner,planId,"action/a","overworld"));
        assertEquals(Boolean.FALSE,results.get(planId+"/a"));
        assertEquals("action rejected",logMessage(archive,0));
        assertThrows(IllegalArgumentException.class,()->ActionLedger.reject(plans,owner,planId,"a","overworld",archive,results),"重复拒绝必须失败，不能二次消费");
        assertEquals(1,archive.getAsJsonArray("auditLog").size(),"失败的拒绝不能再写一条审计");
    }
    @Test void confirmTombstonesAndConsumesBeforeAnyWorldWrite(){
        var plans=new ProposalRegistry<String>();UUID owner=UUID.randomUUID();String planId=plans.put(owner,"overworld","plan");
        var archive=ArchiveStore.empty();
        ActionLedger.confirm(plans,owner,planId,"a","overworld",archive,"PLACE_BLOCK");
        assertTrue(plans.isConsumed(owner,planId,"action/a","overworld"));
        assertEquals("confirmed action PLACE_BLOCK",logMessage(archive,0));
        assertThrows(IllegalArgumentException.class,()->ActionLedger.confirm(plans,owner,planId,"a","overworld",archive,"PLACE_BLOCK"),"重复确认必须在写世界之前就失败");
        assertEquals(1,archive.getAsJsonArray("auditLog").size());
    }
    @Test void succeedBumpsRevisionAndMarksTheResultSuccessful(){
        var archive=ArchiveStore.empty();var results=new HashMap<String,Boolean>();
        ActionLedger.succeed(archive,results,"plan","a","FILL");
        assertEquals(1,ArchiveStore.revision(archive));
        assertEquals("action succeeded FILL",logMessage(archive,0));
        assertEquals(Boolean.TRUE,results.get("plan/a"));
        assertDoesNotThrow(()->ArchiveStore.validate(archive));
    }
    @Test void failOnlyMarksTheResultAndLeavesTheArchiveAlone(){
        var archive=ArchiveStore.empty();var results=new HashMap<String,Boolean>();
        ActionLedger.fail(results,"plan","a");
        assertEquals(Boolean.FALSE,results.get("plan/a"));
        assertEquals(0,archive.getAsJsonArray("auditLog").size());
    }
    private static String logMessage(JsonObject archive,int index){return archive.getAsJsonArray("auditLog").get(index).getAsJsonObject().get("message").getAsString();}
}

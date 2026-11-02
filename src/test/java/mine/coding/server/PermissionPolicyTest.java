package mine.coding.server;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** S4：权限闸门必须覆盖所有改变世界或扩大授权范围的入口。 */
class PermissionPolicyTest {
    @Test void approvingAProjectIsAGuardedEntryPoint(){
        assertTrue(PermissionPolicy.requiresWorldPermission("approveProject"),"批准项目会把半径 32 检查换成项目边界检查，是授权闸门");
    }
    @Test void worldMutationsAndAuditReadsRequirePermission(){
        for(String op:List.of("confirm","undo","approveProject","clearMemory","logs"))assertTrue(PermissionPolicy.requiresWorldPermission(op),op);
    }
    @Test void readOnlyAndDraftRequestsStayUnguarded(){
        for(String op:List.of("context","preview","reject","focus","proposeProject","confirmStage","confirmMemory","confirmSkill","manualMemory","manualSkill","rollback","rememberChat"))assertFalse(PermissionPolicy.requiresWorldPermission(op),op);
    }
}

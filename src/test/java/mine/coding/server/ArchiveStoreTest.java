package mine.coding.server;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveStoreTest {
    @Test void playerArchivesRemainIsolatedAndReload() throws Exception { Path dir=Files.createTempDirectory("minecode-archive-"); var store=new ArchiveStore(dir); UUID a=UUID.randomUUID(),b=UUID.randomUUID(); var archive=store.load(a); archive.getAsJsonArray("memory").add("learned"); ArchiveStore.commitRevision(archive); store.save(a,archive); assertEquals("learned",store.load(a).getAsJsonArray("memory").get(0).getAsString()); assertEquals(0,store.load(b).getAsJsonArray("memory").size()); }
    @Test void corruptArchiveCannotBeSilentlyOverwritten() throws Exception { Path dir=Files.createTempDirectory("minecode-corrupt-"); var store=new ArchiveStore(dir); UUID id=UUID.randomUUID(); Files.createDirectories(store.path(id).getParent()); Files.writeString(store.path(id),"{\"version\":\"broken\"}"); assertThrows(java.io.IOException.class,()->store.load(id)); assertThrows(java.io.IOException.class,()->store.save(id,ArchiveStore.empty())); assertEquals("{\"version\":\"broken\"}",Files.readString(store.path(id))); }
    @Test void rollbackCreatesNewRevisionAndKeepsConfirmationTombstones() { var archive=ArchiveStore.empty(); archive.getAsJsonArray("memory").add("first"); ArchiveStore.commitRevision(archive); archive.getAsJsonArray("memory").add("second"); ArchiveStore.commitRevision(archive); ArchiveStore.confirmOnce(archive,"p","memory/0"); ArchiveStore.rollback(archive,1); assertEquals(3,ArchiveStore.revision(archive)); assertEquals(1,archive.getAsJsonArray("memory").size()); assertThrows(IllegalArgumentException.class,()->ArchiveStore.confirmOnce(archive,"p","memory/0")); }
    @Test void revisionAndLogsRetentionAreBounded() { var archive=ArchiveStore.empty(); for(int i=0;i<40;i++) ArchiveStore.commitRevision(archive); for(int i=0;i<300;i++) ArchiveStore.appendLog(archive,"event "+i); assertEquals(32,archive.getAsJsonArray("history").size()); assertEquals(256,archive.getAsJsonArray("auditLog").size()); assertThrows(IllegalArgumentException.class,()->ArchiveStore.rollback(archive,1)); }
    @Test void chatIsSeparateFromConfirmedMemory() { var archive=ArchiveStore.empty(); for(int i=0;i<25;i++) ArchiveStore.appendChat(archive,"user","assistant","thought"); assertEquals(20,archive.getAsJsonArray("chatHistory").size()); assertEquals(0,archive.getAsJsonArray("memory").size()); }
    @Test void rollbackSnapshotCannotBeMutatedThroughCurrentState() { var archive=ArchiveStore.empty(); archive.getAsJsonArray("memory").add("first"); ArchiveStore.commitRevision(archive); archive.getAsJsonArray("memory").add("second"); ArchiveStore.commitRevision(archive); ArchiveStore.rollback(archive,1); archive.getAsJsonArray("memory").add("new"); assertEquals(1,archive.getAsJsonArray("history").get(1).getAsJsonObject().getAsJsonArray("memory").size()); }
    @Test void logsAndConfirmationsRemainByteBounded() { var archive=ArchiveStore.empty(); for(int i=0;i<400;i++){ArchiveStore.appendLog(archive,"中".repeat(96));ArchiveStore.confirmOnce(archive,"p"+i,"a");} assertTrue(archive.getAsJsonArray("auditLog").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=32768);assertEquals(256,archive.getAsJsonArray("confirmedActions").size()); }
    @Test void chatEntryThatCannotFitIsRejected() { var archive=ArchiveStore.empty(); assertThrows(IllegalArgumentException.class,()->ArchiveStore.appendChat(archive,"中".repeat(2048),"中".repeat(4096),"中".repeat(1024)));assertEquals(0,archive.getAsJsonArray("chatHistory").size()); }

    // S5：审计日志必须保留完整动作文案（validate 允许 96 字符），且截断不能切断代理对。
    @Test void auditLogKeepsFullActionText() { var archive=ArchiveStore.empty(); ArchiveStore.appendLog(archive,"confirmed action PLACE_BLOCK"); assertEquals("confirmed action PLACE_BLOCK",auditLog(archive,0)); }

    @Test void auditLogIsBoundedAtValidationLimit() {
        var archive=ArchiveStore.empty(); ArchiveStore.appendLog(archive,"x".repeat(200));
        assertEquals(96,auditLog(archive,0).length()); assertDoesNotThrow(()->ArchiveStore.validate(archive));
    }

    @Test void auditLogTruncationNeverSplitsSurrogatePairs() {
        var archive=ArchiveStore.empty(); String emoji="😀";
        ArchiveStore.appendLog(archive,"a".repeat(23)+emoji.repeat(5));
        assertEquals("a".repeat(23)+emoji.repeat(5),auditLog(archive,0),"33 个 UTF-16 单元远小于 96，不该被截断");
        ArchiveStore.appendLog(archive,"a".repeat(95)+emoji.repeat(3));
        String truncated=auditLog(archive,1);
        assertTrue(truncated.length()<=96,truncated.length()+" 超出 96"); assertFalse(Character.isHighSurrogate(truncated.charAt(truncated.length()-1)),"截断切断了代理对");
        assertDoesNotThrow(()->ArchiveStore.validate(archive));
    }

    // S3：追加必须按容量淘汰最旧（保留最新），否则第 33 条起 save 永久失败。
    @Test void memoryQuotaEvictsOldestAndStaysSaveable() throws Exception {
        Path dir=Files.createTempDirectory("minecode-memory-quota-"); var store=new ArchiveStore(dir); UUID id=UUID.randomUUID(); JsonObject archive=store.load(id);
        for(int i=0;i<40;i++) ArchiveStore.addMemory(archive,"memory "+i);
        assertEquals(ArchiveStore.MAX_MEMORY,archive.getAsJsonArray("memory").size());
        assertEquals("memory 8",archive.getAsJsonArray("memory").get(0).getAsString(),"淘汰最旧、保留最新");
        assertEquals("memory 39",archive.getAsJsonArray("memory").get(ArchiveStore.MAX_MEMORY-1).getAsString());
        store.save(id,archive);
        assertEquals(ArchiveStore.MAX_MEMORY,store.load(id).getAsJsonArray("memory").size());
    }
    @Test void skillQuotaEvictsOldestAndStaysSaveable() throws Exception {
        Path dir=Files.createTempDirectory("minecode-skill-quota-"); var store=new ArchiveStore(dir); UUID id=UUID.randomUUID(); JsonObject archive=store.load(id);
        for(int i=0;i<20;i++) ArchiveStore.addSkill(archive,skill("skill "+i));
        assertEquals(ArchiveStore.MAX_SKILLS,archive.getAsJsonArray("skills").size());
        assertEquals("skill 4",archive.getAsJsonArray("skills").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("skill 19",archive.getAsJsonArray("skills").get(ArchiveStore.MAX_SKILLS-1).getAsJsonObject().get("name").getAsString());
        store.save(id,archive);
        assertEquals(ArchiveStore.MAX_SKILLS,store.load(id).getAsJsonArray("skills").size());
    }
    @Test void profileByteBudgetEvictsOldestInsteadOfBreakingSave() throws Exception {
        Path dir=Files.createTempDirectory("minecode-byte-quota-"); var store=new ArchiveStore(dir); UUID id=UUID.randomUUID(); JsonObject archive=store.load(id);
        for(int i=0;i<10;i++) ArchiveStore.addMemory(archive,"中".repeat(1024));
        assertTrue(archive.getAsJsonArray("memory").size()<ArchiveStore.MAX_MEMORY,"8192 字节预算比 32 条更早生效");
        assertTrue(archive.getAsJsonArray("memory").size()>0,"最新的一条必须保留");
        store.save(id,archive);
        assertEquals(archive.getAsJsonArray("memory").size(),store.load(id).getAsJsonArray("memory").size());
    }
    @Test void overQuotaProfileIsStillRejectedByValidation(){
        var archive=ArchiveStore.empty(); for(int i=0;i<33;i++) archive.getAsJsonArray("memory").add("memory "+i);
        assertThrows(java.io.IOException.class,()->ArchiveStore.validate(archive),"validate 仍是最后一道防线");
    }
    private static JsonObject skill(String name){JsonObject o=new JsonObject();o.addProperty("id",UUID.randomUUID().toString());o.addProperty("name",name);o.addProperty("description","手动技能");o.addProperty("instructions","保持紧凑");return o;}
    private static String auditLog(JsonObject archive,int index){return archive.getAsJsonArray("auditLog").get(index).getAsJsonObject().get("message").getAsString();}
}

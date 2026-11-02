package mine.coding.server;

import com.google.gson.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;
import java.util.UUID;

/** Atomic world/player JSON archive. Corrupt files are never treated as an empty profile. */
public final class ArchiveStore {
    public static final int MAX_REVISIONS=32, MAX_LOGS=256, MAX_CHAT_HISTORY=20, MAX_MEMORY=32, MAX_SKILLS=16;
    public static final int MAX_PROFILE_BYTES=8192, MAX_CHAT_BYTES=16384, MAX_FILE_BYTES=1048576;
    /** 审计文案上限必须与 validate 的 text(message,MAX_LOG_MESSAGE) 一致，日志总量沿用 32 KiB 预算。 */
    public static final int MAX_LOG_MESSAGE=96, MAX_LOG_BYTES=32768;
    private static final Gson GSON=new GsonBuilder().disableHtmlEscaping().setStrictness(Strictness.STRICT).create();
    private static final Set<String> KEYS=Set.of("version","memory","skills","chatHistory","auditLog","history","confirmedActions");
    private final Path directory;

    public ArchiveStore(Path worldRoot){ directory=worldRoot.resolve("data").resolve("minecode"); }
    public Path path(UUID player){ return directory.resolve(player+".json"); }

    public synchronized JsonObject load(UUID player)throws IOException{
        Path file=path(player);
        if(!Files.exists(file))return empty();
        try{
            if(Files.size(file)>MAX_FILE_BYTES)throw new IOException("archive exceeds size limit");
            JsonObject archive=GSON.fromJson(Files.readString(file,StandardCharsets.UTF_8),JsonObject.class);
            validate(archive);
            return archive;
        }catch(RuntimeException e){throw new IOException("corrupt archive; refusing to overwrite",e);}
    }

    public synchronized void save(UUID player,JsonObject archive)throws IOException{
        if(Files.exists(path(player)))load(player);
        validate(archive);
        byte[] bytes=GSON.toJson(archive).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_FILE_BYTES)throw new IOException("archive exceeds size limit");
        Files.createDirectories(directory);
        Path temp=directory.resolve(player+".json.tmp-"+UUID.randomUUID());
        try{
            try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
                ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            Files.move(temp,path(player),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temp);}
    }

    public static JsonObject empty(){
        JsonObject o=new JsonObject();o.addProperty("version",0);
        for(String k:new String[]{"memory","skills","chatHistory","auditLog","history","confirmedActions"})o.add(k,new JsonArray());
        JsonObject initial=snapshot(o);initial.addProperty("version",0);o.getAsJsonArray("history").add(initial);
        return o;
    }

    public static JsonObject snapshot(JsonObject archive){
        JsonObject s=new JsonObject();for(String key:new String[]{"memory","skills"})s.add(key,archive.getAsJsonArray(key).deepCopy());return s;
    }

    public static int revision(JsonObject a){return integer(a.get("version"));}
    public static int commitRevision(JsonObject a){
        int n=Math.addExact(revision(a),1);a.addProperty("version",n);
        JsonObject s=snapshot(a);s.addProperty("version",n);JsonArray h=a.getAsJsonArray("history");h.add(s);
        while(h.size()>MAX_REVISIONS)h.remove(0);
        return n;
    }

    public static void appendLog(JsonObject a,String message){
        JsonObject entry=new JsonObject();entry.addProperty("message",truncateLog(message));entry.addProperty("at",System.currentTimeMillis());
        JsonArray logs=a.getAsJsonArray("auditLog");logs.add(entry);
        while(logs.size()>MAX_LOGS||(logs.size()>1&&utf8(logs)>MAX_LOG_BYTES))logs.remove(0);
    }

    /** 截断到 96 个 UTF-16 单元（validate 的上限），且绝不把代理对切成两半。 */
    public static String truncateLog(String message){
        if(message==null)return "";
        if(message.length()<=MAX_LOG_MESSAGE)return message;
        int end=MAX_LOG_MESSAGE;
        if(Character.isHighSurrogate(message.charAt(end-1)))end--;
        return message.substring(0,end);
    }

    /** 追加记忆：按容量淘汰最旧，保证第 33 条之后 save 不会因 validate 失败而永久报废。 */
    public static void addMemory(JsonObject a,String memory){
        text(memory,1024);
        JsonArray memories=a.getAsJsonArray("memory");memories.add(memory);
        evictOldest(memories,MAX_MEMORY);
    }

    /** 追加技能：与记忆同一套容量淘汰逻辑。 */
    public static void addSkill(JsonObject a,JsonElement skill){
        validateSkill(skill);
        JsonArray skills=a.getAsJsonArray("skills");skills.add(skill);
        evictOldest(skills,MAX_SKILLS);
    }

    /** 条数超限或整个 profile 超出 8192 字节预算时淘汰最旧的一条；始终至少保留最新一条。 */
    private static void evictOldest(JsonArray array,int maxCount){
        while(array.size()>maxCount||(array.size()>1&&utf8(array)>MAX_PROFILE_BYTES))array.remove(0);
    }

    public static void appendChat(JsonObject a,String user,String assistant,String thought){
        JsonObject e=new JsonObject();e.addProperty("user",text(user,2048));e.addProperty("assistant",text(assistant,4096));e.addProperty("thought",text(thought,1024));
        if(utf8(e)>MAX_CHAT_BYTES)throw new IllegalArgumentException("chat entry exceeds byte limit");
        JsonArray h=a.getAsJsonArray("chatHistory");h.add(e);
        while(h.size()>MAX_CHAT_HISTORY || utf8(h)>MAX_CHAT_BYTES)h.remove(0);
    }

    public static void confirmOnce(JsonObject a,String planId,String actionId){
        text(planId,128);text(actionId,128);String key=planId+"/"+actionId;
        JsonArray c=a.getAsJsonArray("confirmedActions");for(JsonElement e:c)if(key.equals(e.getAsString()))throw new IllegalArgumentException("proposal already confirmed");
        c.add(key);while(c.size()>256)c.remove(0);
    }

    public static void rollback(JsonObject a,int version){
        JsonObject found=null;for(JsonElement e:a.getAsJsonArray("history"))if(integer(e.getAsJsonObject().get("version"))==version)found=e.getAsJsonObject();
        if(found==null)throw new IllegalArgumentException("version not retained");
        for(String k:new String[]{"memory","skills"})a.add(k,found.get(k).deepCopy());
        commitRevision(a);appendLog(a,"rolled back profile to revision "+version);
    }

    public static void validate(JsonObject archive)throws IOException{
        try{
            if(archive==null || !archive.keySet().equals(KEYS))throw new IllegalArgumentException("archive schema mismatch");
            int current=revision(archive);if(current<0)throw new IllegalArgumentException("negative revision");
            validateProfile(archive);
            JsonArray h=array(archive,"history",MAX_REVISIONS);if(h.isEmpty())throw new IllegalArgumentException("history missing");
            int previous=-1;for(JsonElement e:h){JsonObject s=e.getAsJsonObject();if(!s.keySet().equals(Set.of("version","memory","skills")))throw new IllegalArgumentException("history schema mismatch");int v=integer(s.get("version"));if(v<=previous||v>current)throw new IllegalArgumentException("history order mismatch");previous=v;validateProfile(s);}
            if(previous!=current)throw new IllegalArgumentException("history does not contain current revision");
            JsonArray logs=array(archive,"auditLog",MAX_LOGS);for(JsonElement e:logs){JsonObject l=e.getAsJsonObject();if(!l.keySet().equals(Set.of("message","at")))throw new IllegalArgumentException("log schema mismatch");text(l.get("message"),MAX_LOG_MESSAGE);if(!l.get("at").getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("invalid log time");}
            JsonArray chat=array(archive,"chatHistory",MAX_CHAT_HISTORY);if(utf8(chat)>MAX_CHAT_BYTES)throw new IllegalArgumentException("chat exceeds budget");for(JsonElement e:chat){JsonObject c=e.getAsJsonObject();if(!c.keySet().equals(Set.of("user","assistant","thought")))throw new IllegalArgumentException("chat schema mismatch");text(c.get("user"),2048);text(c.get("assistant"),4096);text(c.get("thought"),1024);}
            for(JsonElement e:array(archive,"confirmedActions",256))text(e,257);
        }catch(RuntimeException e){throw new IOException("invalid archive schema",e);}
    }

    private static void validateProfile(JsonObject o){
        JsonArray memories=array(o,"memory",MAX_MEMORY),skills=array(o,"skills",MAX_SKILLS);
        if(utf8(memories)>MAX_PROFILE_BYTES || utf8(skills)>MAX_PROFILE_BYTES)throw new IllegalArgumentException("profile exceeds budget");
        for(JsonElement e:memories)text(e,1024);
        for(JsonElement e:skills)validateSkill(e);
    }

    private static void validateSkill(JsonElement e){
        JsonObject s=e.getAsJsonObject();if(!s.keySet().equals(Set.of("id","name","description","instructions")))throw new IllegalArgumentException("skill schema mismatch");
        text(s.get("id"),128);text(s.get("name"),80);text(s.get("description"),256);text(s.get("instructions"),2048);
    }

    private static JsonArray array(JsonObject o,String k,int max){JsonElement e=o.get(k);if(e==null||!e.isJsonArray()||e.getAsJsonArray().size()>max)throw new IllegalArgumentException("invalid "+k);return e.getAsJsonArray();}
    private static String text(JsonElement e,int max){if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString())throw new IllegalArgumentException("expected text");return text(e.getAsString(),max);}
    private static String text(String s,int max){if(s==null||s.length()>max)throw new IllegalArgumentException("text too long or missing");return s;}
    private static int integer(JsonElement e){if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber()||!e.getAsString().matches("[0-9]+"))throw new IllegalArgumentException("expected integer");return Integer.parseInt(e.getAsString());}
    private static int utf8(JsonElement e){return e.toString().getBytes(StandardCharsets.UTF_8).length;}
}

package mine.coding.server;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/** Reject malformed types and unknown fields before any server state is touched. */
public final class ServerRequest {
    private static final Gson GSON=new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final Map<String,Set<String>> FIELDS=Map.ofEntries(
            Map.entry("context",Set.of("op")), Map.entry("logs",Set.of("op")), Map.entry("clearMemory",Set.of("op")),
            Map.entry("preview",Set.of("op","plan")), Map.entry("confirm",Set.of("op","planId","actionId")), Map.entry("reject",Set.of("op","planId","actionId")),
            Map.entry("undo",Set.of("op","operationId")), Map.entry("confirmMemory",Set.of("op","planId","index")), Map.entry("confirmSkill",Set.of("op","planId","index")),
            Map.entry("manualMemory",Set.of("op","text")), Map.entry("manualSkill",Set.of("op","name","instructions")), Map.entry("rollback",Set.of("op","version")),
            Map.entry("rememberChat",Set.of("op","user","assistant","thought")), Map.entry("proposeProject",Set.of("op","definition","checkpoint")),
            Map.entry("approveProject",Set.of("op","draftId")), Map.entry("confirmStage",Set.of("op","planId")), Map.entry("focus",Set.of("op","requestId","bounds","purpose")));
    private ServerRequest(){}
    public static JsonObject parse(String json){
        if(json==null||json.getBytes(StandardCharsets.UTF_8).length>65536)throw new IllegalArgumentException("request exceeds 64 KiB");
        JsonObject o=GSON.fromJson(json,JsonObject.class); String op=text(o,"op",32); Set<String> fields=FIELDS.get(op);
        boolean legacyProject = "proposeProject".equals(op) && o.keySet().equals(Set.of("op", "definition"));
        if(fields==null||(!legacyProject && !o.keySet().equals(fields)))throw new IllegalArgumentException("unknown operation or missing/unknown fields");
        for(String key:fields)if(!key.equals("op")){if(legacyProject && key.equals("checkpoint") && !o.has(key)) continue; if(key.equals("index")||key.equals("version")){if(integer(o,key)<0)throw new IllegalArgumentException("negative "+key);}else text(o,key,key.equals("plan")||key.equals("definition")?65536:8192);}
        return o;
    }
    public static String text(JsonObject o,String key,int max){JsonElement e=o==null?null:o.get(key);if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString())throw new IllegalArgumentException("missing or invalid "+key);String s=e.getAsString();if(s.length()>max)throw new IllegalArgumentException(key+" too long");return s;}
    public static int integer(JsonObject o,String key){JsonElement e=o.get(key);if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber()||!e.getAsString().matches("-?[0-9]+"))throw new IllegalArgumentException("invalid integer "+key);try{return Integer.parseInt(e.getAsString());}catch(NumberFormatException x){throw new IllegalArgumentException("integer outside range "+key,x);}}
}

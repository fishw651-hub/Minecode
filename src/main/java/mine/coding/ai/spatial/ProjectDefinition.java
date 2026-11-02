package mine.coding.ai.spatial;

import com.google.gson.JsonObject;
import java.util.Objects;

public record ProjectDefinition(String id, String name, String goal, String blueprint, String dimension, BlockBox bounds) {
    public ProjectDefinition { id = FocusRequest.text(id,"id",128); name = FocusRequest.text(name,"name",256); goal = FocusRequest.text(goal,"goal",2048); blueprint = FocusRequest.text(blueprint,"blueprint",16384); dimension = FocusRequest.text(dimension,"dimension",128); bounds = Objects.requireNonNull(bounds,"bounds"); if (bounds.x2()- (long)bounds.x() + 1 > 512 || bounds.y2()-(long)bounds.y()+1 > 512 || bounds.z2()-(long)bounds.z()+1 > 512) throw new IllegalArgumentException("Project bounds exceed 512 blocks per axis"); }
    public JsonObject toJson() { JsonObject j = new JsonObject(); j.addProperty("id",id); j.addProperty("name",name); j.addProperty("goal",goal); j.addProperty("blueprint",blueprint); j.addProperty("dimension",dimension); j.add("bounds",bounds.toJson()); return j; }
    public static ProjectDefinition fromJson(JsonObject j) { Objects.requireNonNull(j,"json"); BlockBox.requireFields(j,"id","name","goal","blueprint","dimension","bounds"); return new ProjectDefinition(FocusRequest.string(j,"id"),FocusRequest.string(j,"name"),FocusRequest.string(j,"goal"),FocusRequest.string(j,"blueprint"),FocusRequest.string(j,"dimension"),BlockBox.fromJson(FocusRequest.object(j,"bounds"))); }
}

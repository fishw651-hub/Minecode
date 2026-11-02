package mine.coding.server;

import com.google.gson.*;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import mine.coding.MineCode;
import mine.coding.ai.*;
import mine.coding.ai.spatial.BlockBox;
import mine.coding.ai.spatial.FocusRequest;
import mine.coding.ai.spatial.ProjectDefinition;
import mine.coding.ai.spatial.StageCheckpoint;
import mine.coding.network.MineCodeNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.util.*;

/** All request handling and world mutation runs on the Minecraft server thread. */
public final class MineCodeService {
    private static final Map<MinecraftServer,Session> SESSIONS=new IdentityHashMap<>();
    private static final int MAX_OPERATIONS=8,MAX_SNAPSHOTS=32000;
    private record PreparedAction(AiAction action,ActionPolicy.Box box,BlockState state,
                                  Map<BlockPos,BlockState> baseline,String reason){boolean allowed(){return reason.isEmpty();}}
    private record PreparedPlan(AiPlan plan,Map<String,PreparedAction> actions,int projectRevision){}
    private record ProjectProposal(ProjectDefinition definition,StageCheckpoint checkpoint){}
    private record Change(BlockState before,BlockState after){}
    private record Operation(String id,String dimension,Map<BlockPos,Change> changes){}
    private static final class Session {
        final ArchiveStore archives;
        final ProjectStore projects;
        final ProposalRegistry<ProjectProposal> projectDrafts=new ProposalRegistry<>();
        final ProposalRegistry<PreparedPlan> plans=new ProposalRegistry<>();
        final Map<UUID,Deque<Operation>> operations=new HashMap<>();
        final Map<UUID,Map<String,Boolean>> actionResults=new HashMap<>();
        final RateLimiter rateLimits=new RateLimiter();
        Session(MinecraftServer server){archives=new ArchiveStore(server.getWorldPath(LevelResource.ROOT));projects=new ProjectStore(server.getWorldPath(LevelResource.ROOT));}
    }
    private MineCodeService(){}
    private static MinecraftServer server(ServerPlayer player){return Objects.requireNonNull(player.level().getServer(),"server unavailable");}
    private static Session session(ServerPlayer player){return SESSIONS.computeIfAbsent(server(player),Session::new);}
    private static String dimension(ServerPlayer player){return player.level().dimension().identifier().toString();}
    public static boolean hasWorldPermission(CommandSourceStack source){return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);}
    private static void requirePermission(ServerPlayer player){if(!hasWorldPermission(player.createCommandSourceStack()))throw new IllegalArgumentException("需要命令权限等级 2 才能改变或撤销世界");}

    public static void handle(ServerPlayer player,String json){
        MinecraftServer server=server(player);
        if(!server.isSameThread()){server.execute(()->handle(player,json));return;}
        JsonObject request=null;
        try{
            request=ServerRequest.parse(json);
            String op=request.get("op").getAsString();
            if(PermissionPolicy.requiresWorldPermission(op))requirePermission(player);
            // R7/T1：preview/focus/context/rememberChat 单次就足以吃掉一个 tick，必须按玩家限流；
            // 状态迁移类不限流（否则玩家无法收拾自己的局面）。命中时走既有 error() 通道明确回报，不静默丢包。
            if(RateLimiter.isHeavyOperation(op)&&!session(player).rateLimits.tryAcquire(player.getUUID(),op)){
                error(player,"操作过于频繁："+RateLimiter.limitDescription(op)+"，请稍后再试",request);return;
            }
            switch(op){
                case "context"->context(player,"已读取本世界档案");
                case "preview"->preview(player,ServerRequest.text(request,"plan",65536));
                case "confirm"->confirm(player,ServerRequest.text(request,"planId",80),ServerRequest.text(request,"actionId",80));
                case "reject"->reject(player,ServerRequest.text(request,"planId",80),ServerRequest.text(request,"actionId",80));
                case "undo"->undo(player,ServerRequest.text(request,"operationId",80));
                case "confirmMemory"->confirmDraft(player,ServerRequest.text(request,"planId",80),ServerRequest.integer(request,"index"),false);
                case "confirmSkill"->confirmDraft(player,ServerRequest.text(request,"planId",80),ServerRequest.integer(request,"index"),true);
                case "manualMemory"->manualMemory(player,ServerRequest.text(request,"text",1024));
                case "manualSkill"->manualSkill(player,ServerRequest.text(request,"name",80),ServerRequest.text(request,"instructions",2048));
                case "rollback"->rollback(player,ServerRequest.integer(request,"version"));
                case "clearMemory"->clearMemory(player);
                case "logs"->logs(player);
                case "rememberChat"->rememberChat(player,request);
                case "proposeProject"->proposeProject(player,ServerRequest.text(request,"definition",65536),request.has("checkpoint") ? ServerRequest.text(request,"checkpoint",8192) : "{\"stage\":\"planning\",\"summary\":\"等待批准项目范围\",\"nextTask\":\"开始读取第一个 focus\",\"complete\":false}");
                case "approveProject"->approveProject(player,ServerRequest.text(request,"draftId",80));
                case "confirmStage"->confirmStage(player,ServerRequest.text(request,"planId",80));
                case "focus"->focus(player,ServerRequest.text(request,"requestId",128),ServerRequest.text(request,"bounds",8192),ServerRequest.text(request,"purpose",1024));
                default->throw new IllegalArgumentException("未知请求");
            }
        }catch(IOException e){error(player,"世界档案读取或保存失败，未宣称已保存："+safeError(e),request);}
        catch(RuntimeException e){error(player,"请求被拒绝："+safeError(e),request);}
    }

    public static void open(ServerPlayer player){MineCodeNetworking.send(player,event("open","打开 MineCode 控制台"));}
    public static void context(ServerPlayer player,String message)throws IOException{
        JsonObject archive=session(player).archives.load(player.getUUID());
        JsonObject response=event("context",message);
        response.addProperty("dimension",dimension(player));response.addProperty("x",player.getX());response.addProperty("y",player.getY());response.addProperty("z",player.getZ());
        response.add("memory",archive.get("memory").deepCopy());response.add("skills",archive.get("skills").deepCopy());response.add("chatHistory",archive.get("chatHistory").deepCopy());
        response.addProperty("version",ArchiveStore.revision(archive));JsonArray versions=new JsonArray();for(JsonElement e:archive.getAsJsonArray("history"))versions.add(e.getAsJsonObject().get("version").getAsInt());response.add("history",versions);
        JsonObject project=session(player).projects.load(player.getUUID()); response.add("project",project.deepCopy());
        JsonObject limits=new JsonObject();limits.addProperty("radius",ActionPolicy.MAX_RADIUS);limits.addProperty("maxActionBlocks",ActionPolicy.MAX_ACTION_BLOCKS);limits.addProperty("maxPlanBlocks",ActionPolicy.MAX_PLAN_BLOCKS);limits.addProperty("maxActions",ActionPolicy.MAX_ACTIONS);response.add("limits",limits);
        MineCodeNetworking.send(player,response);
    }

    private static void proposeProject(ServerPlayer player,String json,String checkpointJson){
        ProjectDefinition definition=ProjectDefinition.fromJson(new Gson().fromJson(json,JsonObject.class));
        if(!dimension(player).equals(definition.dimension())) throw new IllegalArgumentException("项目维度必须与玩家当前维度一致");
        StageCheckpoint checkpoint=StageCheckpoint.fromJson(new Gson().fromJson(checkpointJson,JsonObject.class));
        String draftId=session(player).projectDrafts.put(player.getUUID(),dimension(player),new ProjectProposal(definition,checkpoint));
        JsonObject response=event("projectDraft","项目草稿已生成，请玩家批准范围");response.addProperty("draftId",draftId);response.add("definition",definition.toJson());MineCodeNetworking.send(player,response);
    }

    private static void approveProject(ServerPlayer player,String draftId)throws IOException{
        ProjectProposal proposal=session(player).projectDrafts.get(player.getUUID(),draftId,dimension(player));
        ProjectDefinition definition=proposal.definition();
        validateProjectBounds(player, definition);
        JsonObject previous=session(player).projects.load(player.getUUID());
        int revision=ProjectStore.revision(previous)+1;
        session(player).projects.saveApproved(player.getUUID(),definition,revision,proposal.checkpoint());
        session(player).projectDrafts.remove(player.getUUID());
        JsonObject response=event("project","项目已批准");response.add("definition",definition.toJson());response.addProperty("requestId",draftId);MineCodeNetworking.send(player,response);
    }

    private static void confirmStage(ServerPlayer player,String planId)throws IOException {
        Session s=session(player);
        PreparedPlan plan=s.plans.get(player.getUUID(),planId,dimension(player));
        if (plan.plan().checkpoint() == null) throw new IllegalArgumentException("当前计划没有阶段 checkpoint");
        // R6/T2：一次加载同时用于 revision 与授权检查，不再在同一请求里重复读盘和全量 validate。
        JsonObject approved=s.projects.load(player.getUUID());
        if (ProjectStore.revision(approved) != plan.projectRevision()) throw new IllegalArgumentException("项目授权已变化，请重新生成预览");
        if (approved.get("project").isJsonNull()) throw new IllegalArgumentException("没有已批准项目，不能确认阶段");
        for (String actionId : plan.actions().keySet()) {
            if (!s.plans.isConsumed(player.getUUID(), planId, "action/" + actionId, dimension(player))) {
                throw new IllegalArgumentException("仍有动作未确认或拒绝，不能完成阶段");
            }
            if (!Boolean.TRUE.equals(s.actionResults.getOrDefault(player.getUUID(), Map.of()).get(planId + "/" + actionId))) {
                throw new IllegalArgumentException("存在未成功执行的动作，不能完成阶段");
            }
        }
        int revision=s.projects.updateCheckpoint(player.getUUID(), plan.plan().checkpoint());
        s.plans.consume(player.getUUID(), planId, "stage", dimension(player));
        JsonObject response=event("stage","阶段 checkpoint 已确认");response.addProperty("planId",planId);response.addProperty("revision",revision);response.add("checkpoint",plan.plan().checkpoint().toJson());MineCodeNetworking.send(player,response);
    }

    private static void focus(ServerPlayer player,String requestId,String boundsJson,String purpose)throws IOException{
        BlockBox bounds=BlockBox.fromJson(new Gson().fromJson(boundsJson,JsonObject.class));
        FocusRequest request=new FocusRequest(requestId,purpose,bounds);
        JsonObject project=session(player).projects.load(player.getUUID());
        if(!project.get("project").isJsonNull()){
            ProjectDefinition approved=ProjectDefinition.fromJson(project.getAsJsonObject("project"));
            if(!approved.bounds().contains(bounds)) throw new IllegalArgumentException("focus 超出批准项目范围");
            if(!dimension(player).equals(approved.dimension())) throw new IllegalArgumentException("项目维度已变化");
        } else ActionPolicy.checkRange(ActionPolicy.box(bounds.x(),bounds.y(),bounds.z(),bounds.x2(),bounds.y2(),bounds.z2()),player.getX(),player.getY(),player.getZ());
        List<String> states=new ArrayList<>((int)bounds.volume()); Set<Long> chunks=new HashSet<>();
        for(BlockPos pos:BlockPos.betweenClosed(bounds.x(),bounds.y(),bounds.z(),bounds.x2(),bounds.y2(),bounds.z2())){
            if(!player.level().hasChunk(pos.getX()>>4,pos.getZ()>>4)) throw new IllegalArgumentException("focus 包含未加载区块");
            chunks.add(((long)(pos.getX() >> 4) << 32) ^ (pos.getZ() & 0xffffffffL)); String state=player.level().getBlockState(pos).toString(); states.add(state);
        }
        JsonObject snapshot=RegionObservation.encode(bounds,dimension(player),states,chunks.size(),System.currentTimeMillis());
        JsonObject response=event("focus","已读取只读方块观察；不包含库存或 NBT");response.addProperty("requestId",request.id());response.add("snapshot",snapshot);MineCodeNetworking.send(player,response);
    }

    private static void preview(ServerPlayer player,String json)throws IOException{
        AiPlan plan=AiPlan.fromJson(json);validatePlan(plan);
        Session s=session(player);
        // R6/T2：项目授权在本请求内只读一次，并把已加载的 JsonObject 作为参数下传给每个动作。
        // 不能用内存缓存：load() 返回可变 JsonObject，跨请求共享会造成状态污染。
        JsonObject storedProject=s.projects.load(player.getUUID());
        int projectRevision=ProjectStore.revision(storedProject);
        Map<String,PreparedAction> prepared=new LinkedHashMap<>();
        PlanBudget budget=new PlanBudget();
        for(AiAction action:plan.actions()){
            ActionPolicy.Box box=null;BlockState state=null;Map<BlockPos,BlockState> baseline=Map.of();String reason="";
            try{
                requirePermission(player);
                if(action.type()==AiAction.Type.RUN_COMMAND){command(action);}
                else{box=box(action);state=parseState(player,ServerRequest.text(action.payload(),"block",256));validateTargets(player,box,state,storedProject);baseline=snapshot(player,box);}
            }catch(RuntimeException|CommandSyntaxException e){reason=safeError(e);}
            // R8/T3：只有通过校验（allowed）的动作才计入计划预算；超限立即抛出，让整份预览
            // 以明确原因失败，而不是静默丢弃动作。被拒绝的动作不再占用预算。
            if(reason.isEmpty()&&box!=null)budget.add(box);
            prepared.put(action.id(),new PreparedAction(action,box,state,baseline,reason));
        }
        String planId=s.plans.put(player.getUUID(),dimension(player),new PreparedPlan(plan,Map.copyOf(prepared),projectRevision));
        JsonObject response=event("preview","服务器已校验计划；只有标为可执行的动作才能确认");response.addProperty("planId",planId);JsonArray actions=new JsonArray();
        for(PreparedAction action:prepared.values()){
            JsonObject item=new JsonObject();item.addProperty("id",action.action().id());item.addProperty("description",action.action().description());item.addProperty("command",describe(action.action()));item.addProperty("allowed",action.allowed());item.addProperty("reason",action.reason());item.addProperty("blockCount",action.box()==null?0:ActionPolicy.volume(action.box()));item.addProperty("reversible",action.action().type()!=AiAction.Type.RUN_COMMAND&&action.allowed());item.addProperty("risk",action.action().risk());actions.add(item);
        }
        response.add("actions",actions);
        JsonArray memories=new JsonArray();plan.memoryDrafts().forEach(memories::add);response.add("memoryDrafts",memories);
        JsonArray skills=new JsonArray();for(SkillDraft draft:plan.skillDrafts())skills.add(skill(draft));response.add("skillDrafts",skills);
        MineCodeNetworking.send(player,response);
    }

    private static void validatePlan(AiPlan plan){
        if(plan.actions().size()>ActionPolicy.MAX_ACTIONS||plan.memoryDrafts().size()>16||plan.skillDrafts().size()>8)throw new IllegalArgumentException("计划动作或草稿数量超限");
        Set<String> ids=new HashSet<>();
        for(AiAction a:plan.actions()){bounded(a.id(),80);bounded(a.description(),512);bounded(a.risk(),80);if(!ids.add(a.id()))throw new IllegalArgumentException("重复 action id");}
        for(String memory:plan.memoryDrafts())bounded(memory,1024);
        ids.clear();for(SkillDraft skill:plan.skillDrafts()){bounded(skill.id(),128);bounded(skill.name(),80);bounded(skill.description(),256);bounded(skill.instructions(),2048);if(!ids.add(skill.id()))throw new IllegalArgumentException("重复 skill id");}
    }

    private static void confirm(ServerPlayer player,String planId,String actionId)throws IOException{
        Session s=session(player);PreparedPlan plan=s.plans.get(player.getUUID(),planId,dimension(player));PreparedAction prepared=plan.actions().get(actionId);
        if(prepared==null)throw new IllegalArgumentException("未知 action id");
        // R6/T2：项目授权在本请求内只读一次；revision 检查与目标校验复用同一份已加载状态。
        JsonObject storedProject=s.projects.load(player.getUUID());
        if(ProjectStore.revision(storedProject)!=plan.projectRevision())throw new IllegalArgumentException("项目授权已变化，请重新生成预览");
        if(s.plans.isConsumed(player.getUUID(),planId,ActionLedger.actionKey(actionId),dimension(player)))throw new IllegalArgumentException("动作已经处理");
        String type=prepared.action().type().name();
        Operation operation=null;
        try{
            if(!prepared.allowed())throw new IllegalArgumentException("服务器预览已拒绝该动作："+prepared.reason());
            requirePermission(player);
            if(prepared.action().type()!=AiAction.Type.RUN_COMMAND){
                validateTargets(player,prepared.box(),prepared.state(),storedProject);
                for(var e:prepared.baseline().entrySet())if(!player.level().getBlockState(e.getKey()).equals(e.getValue()))throw new IllegalArgumentException("世界已变化，请重新生成预览");
            }
            JsonObject archive=s.archives.load(player.getUUID());
            ActionLedger.confirm(s.plans,player.getUUID(),planId,actionId,dimension(player),archive,type);
            String operationId="";boolean reversible=false;
            if(prepared.action().type()==AiAction.Type.RUN_COMMAND)runCommand(player,prepared.action());
            else{
                operation=apply(player,prepared);
                if(!operation.changes().isEmpty()){operationId=operation.id();reversible=true;}
            }
            String message=reversible?"方块操作已执行，可使用 operationId 撤销":"动作已执行；命令无法撤销";
            ActionLedger.succeed(archive,s.actionResults.computeIfAbsent(player.getUUID(), ignored -> new HashMap<>()),planId,actionId,type);
            if(reversible)retain(s,player.getUUID(),operation);
            try{s.archives.save(player.getUUID(),archive);}catch(IOException e){message="世界操作已执行，但档案保存失败："+safeError(e);}
            result(player,actionId,operationId,true,reversible,ArchiveStore.revision(archive),message);
        }catch(CommandSyntaxException|RuntimeException e){
            if(operation!=null)restore(player,operation);
            ActionLedger.fail(s.actionResults.computeIfAbsent(player.getUUID(), ignored -> new HashMap<>()),planId,actionId);
            result(player,actionId,"",false,false,currentVersion(player),"动作未执行："+safeError(e));
        }
    }

    /** 命令动作：dispatcher 返回 0 说明命令没有执行/没有效果，按失败处理（命令不可回滚）。 */
    private static void runCommand(ServerPlayer player,AiAction action)throws CommandSyntaxException{
        int performed=server(player).getCommands().getDispatcher().execute(command(action),player.createCommandSourceStack());
        if(!ActionPolicy.commandSucceeded(performed))throw new IllegalArgumentException("命令执行失败：服务器没有执行该命令");
    }

    private static Operation apply(ServerPlayer player,PreparedAction action){
        ServerLevel level=player.level();Map<BlockPos,BlockState> before=new LinkedHashMap<>(action.baseline());
        Map<BlockPos,BlockState> target=new LinkedHashMap<>();
        for(var entry:before.entrySet())if(!entry.getValue().equals(action.state()))target.put(entry.getKey(),action.state());
        AtomicWrites.writeAll(target,before,(pos,state)->writeBlock(level,pos,state));   // 中途失败时已写入的方块自动回滚
        Map<BlockPos,Change> changes=new LinkedHashMap<>();
        for(var entry:before.entrySet()){BlockState after=level.getBlockState(entry.getKey());if(!after.equals(entry.getValue()))changes.put(entry.getKey(),new Change(entry.getValue(),after));}
        return new Operation(UUID.randomUUID().toString(),dimension(player),Map.copyOf(changes));
    }

    /** 世界已经改过但后续记账失败时，把方块恢复为写入前状态；回滚失败只作为 suppressed 保留，不掩盖原始错误。 */
    private static void restore(ServerPlayer player,Operation operation){
        Map<BlockPos,BlockState> target=new LinkedHashMap<>(),previous=new LinkedHashMap<>();
        for(var e:operation.changes().entrySet()){target.put(e.getKey(),e.getValue().before());previous.put(e.getKey(),e.getValue().after());}
        try{AtomicWrites.writeAll(target,previous,(pos,state)->writeBlock(player.level(),pos,state));}
        catch(RuntimeException ignored){/* 尽力回滚 */}
    }

    private static void writeBlock(ServerLevel level,BlockPos pos,BlockState state){if(!level.setBlock(pos,state,3))throw new IllegalArgumentException("方块写入失败："+pos);}

    private static void retain(Session s,UUID owner,Operation op){
        Deque<Operation> operations=s.operations.computeIfAbsent(owner,id->new ArrayDeque<>());operations.addLast(op);
        while(operations.size()>MAX_OPERATIONS||operations.stream().mapToInt(o->o.changes().size()).sum()>MAX_SNAPSHOTS)operations.removeFirst();
    }

    private static void reject(ServerPlayer player,String planId,String actionId)throws IOException{
        Session s=session(player);PreparedPlan plan=s.plans.get(player.getUUID(),planId,dimension(player));
        if(!plan.actions().containsKey(actionId))throw new IllegalArgumentException("未知 action id");
        JsonObject archive=s.archives.load(player.getUUID());
        // 只用 ActionLedger 消费一次：重复消费会让审计日志与失败结果一起丢失。
        ActionLedger.reject(s.plans,player.getUUID(),planId,actionId,dimension(player),archive,s.actionResults.computeIfAbsent(player.getUUID(), ignored -> new HashMap<>()));
        s.archives.save(player.getUUID(),archive);
        result(player,actionId,"",true,false,ArchiveStore.revision(archive),"已拒绝动作");
    }

    private static void undo(ServerPlayer player,String operationId)throws IOException{
        Session s=session(player);Deque<Operation> operations=s.operations.getOrDefault(player.getUUID(),new ArrayDeque<>());
        Operation found=operations.stream().filter(o->o.id().equals(operationId)).findFirst().orElseThrow(()->new IllegalArgumentException("未知、过期或已撤销 operation id"));
        if(!found.dimension().equals(dimension(player)))throw new IllegalArgumentException("操作所属维度不一致");
        for(var e:found.changes().entrySet()){validatePosition(player.level(),e.getKey());if(player.level().getBlockEntity(e.getKey())!=null)throw new IllegalArgumentException("含方块实体，拒绝破坏容器数据");if(!player.level().getBlockState(e.getKey()).equals(e.getValue().after()))throw new IllegalArgumentException("方块已被其他操作改动，拒绝过期撤销");}
        JsonObject archive=s.archives.load(player.getUUID());ArchiveStore.appendLog(archive,"undo requested "+operationId);s.archives.save(player.getUUID(),archive);
        Map<BlockPos,BlockState> target=new LinkedHashMap<>(),previous=new LinkedHashMap<>();
        for(var e:found.changes().entrySet()){target.put(e.getKey(),e.getValue().before());previous.put(e.getKey(),e.getValue().after());}
        // 中途写回失败时，已经写回的方块自动回滚为撤销前状态，operation 也不会出队。
        AtomicWrites.writeAll(target,previous,(pos,state)->writeBlock(player.level(),pos,state));
        operations.remove(found);
        ArchiveStore.commitRevision(archive);ArchiveStore.appendLog(archive,"operation undone "+operationId);
        String message="已撤销方块操作";try{s.archives.save(player.getUUID(),archive);}catch(IOException e){message="方块已撤销，但档案保存失败："+safeError(e);}
        result(player,"",operationId,true,false,ArchiveStore.revision(archive),message);
    }

    private static void confirmDraft(ServerPlayer player,String planId,int index,boolean skill)throws IOException{
        Session s=session(player);PreparedPlan plan=s.plans.get(player.getUUID(),planId,dimension(player));int count=skill?plan.plan().skillDrafts().size():plan.plan().memoryDrafts().size();if(index<0||index>=count)throw new IllegalArgumentException("未知草稿 index");
        String key=(skill?"skill/":"memory/")+index;
        JsonObject archive=s.archives.load(player.getUUID());
        // 先写墓碑再消费：顺序反了会在墓碑冲突时把草稿永久消费掉。
        ArchiveStore.confirmOnce(archive,planId,key);s.plans.consume(player.getUUID(),planId,key,dimension(player));
        // 配额满时淘汰最旧一条，避免第 33 条记忆/第 17 个技能让之后每次 save 都因 validate 失败而报废。
        if(skill)ArchiveStore.addSkill(archive,skill(plan.plan().skillDrafts().get(index)));else ArchiveStore.addMemory(archive,plan.plan().memoryDrafts().get(index));
        saveProfile(player,archive,skill?"skill confirmed":"memory confirmed");
    }

    private static void manualMemory(ServerPlayer player,String text)throws IOException{bounded(text,1024);JsonObject a=session(player).archives.load(player.getUUID());ArchiveStore.addMemory(a,text);saveProfile(player,a,"manual memory created");}
    private static void manualSkill(ServerPlayer player,String name,String instructions)throws IOException{bounded(name,80);bounded(instructions,2048);JsonObject a=session(player).archives.load(player.getUUID());ArchiveStore.addSkill(a,skill(new SkillDraft(UUID.randomUUID().toString(),name,"手动技能",instructions)));saveProfile(player,a,"manual skill created");}
    private static JsonObject skill(SkillDraft draft){JsonObject o=new JsonObject();o.addProperty("id",draft.id());o.addProperty("name",draft.name());o.addProperty("description",draft.description());o.addProperty("instructions",draft.instructions());return o;}
    private static void saveProfile(ServerPlayer player,JsonObject archive,String audit)throws IOException{ArchiveStore.commitRevision(archive);ArchiveStore.appendLog(archive,audit);session(player).archives.save(player.getUUID(),archive);context(player,"已保存本世界档案");}
    private static void rollback(ServerPlayer player,int version)throws IOException{JsonObject a=session(player).archives.load(player.getUUID());ArchiveStore.rollback(a,version);session(player).archives.save(player.getUUID(),a);context(player,"档案已回滚为所选版本的内容，并创建新版本");}
    public static void clearMemory(ServerPlayer player)throws IOException{requirePermission(player);JsonObject a=session(player).archives.load(player.getUUID());a.add("memory",new JsonArray());saveProfile(player,a,"memory cleared");}
    public static void logs(ServerPlayer player)throws IOException{requirePermission(player);JsonObject a=session(player).archives.load(player.getUUID());JsonObject response=event("logs","本玩家世界审计日志");response.add("logs",a.get("auditLog").deepCopy());MineCodeNetworking.send(player,response);}
    private static void rememberChat(ServerPlayer player,JsonObject request)throws IOException{JsonObject a=session(player).archives.load(player.getUUID());ArchiveStore.appendChat(a,ServerRequest.text(request,"user",2048),ServerRequest.text(request,"assistant",4096),ServerRequest.text(request,"thought",1024));session(player).archives.save(player.getUUID(),a);context(player,"已保存本世界对话记录");}

    private static ActionPolicy.Box box(AiAction action){
        JsonObject p=action.payload();Set<String> keys=action.type()==AiAction.Type.FILL?Set.of("x","y","z","x2","y2","z2","block"):Set.of("x","y","z","block");if(!p.keySet().equals(keys))throw new IllegalArgumentException("坐标动作字段不符合契约");
        int x=ServerRequest.integer(p,"x"),y=ServerRequest.integer(p,"y"),z=ServerRequest.integer(p,"z");return action.type()==AiAction.Type.FILL?ActionPolicy.box(x,y,z,ServerRequest.integer(p,"x2"),ServerRequest.integer(p,"y2"),ServerRequest.integer(p,"z2")):ActionPolicy.box(x,y,z,x,y,z);
    }
    private static String command(AiAction action){JsonObject p=action.payload();if(!p.keySet().equals(Set.of("command")))throw new IllegalArgumentException("命令字段不符合契约");return ActionPolicy.safeCommand(ServerRequest.text(p,"command",256));}
    private static BlockState parseState(ServerPlayer player,String block)throws CommandSyntaxException{
        StringReader reader=new StringReader(block);BlockState state=BlockStateParser.parseForBlock(player.level().registryAccess().lookupOrThrow(Registries.BLOCK),reader,false).blockState();
        if(reader.canRead())throw new IllegalArgumentException("方块状态含多余内容或 NBT");if(state.hasBlockEntity())throw new IllegalArgumentException("暂不允许放置方块实体，保证状态快照可可靠撤销");return state;
    }
    private static void validateTargets(ServerPlayer player,ActionPolicy.Box box,BlockState state,JsonObject storedProject){
        requirePermission(player);
        // R6/T2：storedProject 由入口一次性加载后下传，本方法不再重复 projects.load()（每次都会全量 validate）。
        if (storedProject.get("project").isJsonNull()) {
            ActionPolicy.checkRange(box,player.getX(),player.getY(),player.getZ());
        } else {
            ProjectDefinition project = ProjectDefinition.fromJson(storedProject.getAsJsonObject("project"));
            if (!dimension(player).equals(project.dimension())) throw new IllegalArgumentException("项目维度已变化");
            BlockBox target = BlockBox.ofCorners(box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ());
            if (!project.bounds().contains(target)) throw new IllegalArgumentException("动作超出已批准项目范围");
        }
        for(BlockPos p:BlockPos.betweenClosed(box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ())){validatePosition(player.level(),p);if(player.level().getBlockEntity(p)!=null)throw new IllegalArgumentException("已有方块实体，拒绝覆盖容器数据");}
    }
    private static void validateProjectBounds(ServerPlayer player, ProjectDefinition project) {
        if (!dimension(player).equals(project.dimension())) throw new IllegalArgumentException("项目维度必须与玩家当前维度一致");
        BlockBox bounds=project.bounds();
        for (int x : new int[]{bounds.x(), bounds.x2()}) for (int y : new int[]{bounds.y(), bounds.y2()}) for (int z : new int[]{bounds.z(), bounds.z2()}) {
            BlockPos pos=new BlockPos(x,y,z);
            if (player.level().isOutsideBuildHeight(pos)) throw new IllegalArgumentException("项目边界超出世界高度");
            if (!player.level().getWorldBorder().isWithinBounds(pos)) throw new IllegalArgumentException("项目边界超出世界边界");
        }
    }
    private static void validatePosition(ServerLevel level,BlockPos p){if(level.isOutsideBuildHeight(p))throw new IllegalArgumentException("超出世界高度");if(!level.getWorldBorder().isWithinBounds(p))throw new IllegalArgumentException("超出世界边界");if(!level.hasChunk(p.getX()>>4,p.getZ()>>4))throw new IllegalArgumentException("目标区块未加载");}
    private static Map<BlockPos,BlockState> snapshot(ServerPlayer player,ActionPolicy.Box box){Map<BlockPos,BlockState> states=new LinkedHashMap<>();for(BlockPos pos:BlockPos.betweenClosed(box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ()))states.put(pos.immutable(),player.level().getBlockState(pos));return Map.copyOf(states);}
    private static String describe(AiAction a){return switch(a.type()){case RUN_COMMAND->a.payload().get("command").getAsString()+"（不可撤销）";case PLACE_BLOCK->"placeBlock "+a.payload();case FILL->"fill "+a.payload();};}
    private static void bounded(String value,int max){if(value==null||value.isBlank()||value.length()>max)throw new IllegalArgumentException("缺少字段或文本超长");}
    private static int currentVersion(ServerPlayer player){try{return ArchiveStore.revision(session(player).archives.load(player.getUUID()));}catch(IOException e){return -1;}}
    private static String safeError(Exception e){String message=e.getMessage();return message==null?e.getClass().getSimpleName():message.substring(0,Math.min(message.length(),180));}
    private static JsonObject event(String name,String message){JsonObject o=new JsonObject();o.addProperty("event",name);o.addProperty("message",message);return o;}
    private static void error(ServerPlayer player,String message){error(player,message,null);}
    private static void error(ServerPlayer player,String message,JsonObject request){
        JsonObject response=event("error",message);
        if(request!=null&&"focus".equals(request.get("op").getAsString())&&request.has("requestId"))response.addProperty("requestId",request.get("requestId").getAsString());
        MineCodeNetworking.send(player,response);
    }
    private static void result(ServerPlayer player,String actionId,String operationId,boolean success,boolean reversible,int version,String message){JsonObject r=event("result",message);r.addProperty("actionId",actionId);r.addProperty("operationId",operationId);r.addProperty("success",success);r.addProperty("reversible",reversible);r.addProperty("version",version);MineCodeNetworking.send(player,r);}
    public static void disconnect(ServerPlayer player){Session s=SESSIONS.get(server(player));if(s!=null){s.plans.remove(player.getUUID());s.projectDrafts.remove(player.getUUID());s.operations.remove(player.getUUID());s.actionResults.remove(player.getUUID());s.rateLimits.forget(player.getUUID());}}
    public static void stop(MinecraftServer server){Session s=SESSIONS.remove(server);if(s!=null){s.plans.clear();s.projectDrafts.clear();s.operations.clear();s.actionResults.clear();s.rateLimits.clear();}}
    public static void tick(MinecraftServer server){Session s=SESSIONS.get(server);if(s!=null)ProposalRegistry.expireAll(s.plans,s.projectDrafts);}
}


package mine.coding.client.screen;

import com.google.gson.JsonObject;
import mine.coding.ai.AiPlan;
import mine.coding.ai.transport.*;
import mine.coding.client.ClientNetworking;
import mine.coding.client.ConsoleController;
import mine.coding.client.ContextGate;
import mine.coding.client.FocusGateway;
import mine.coding.client.ProjectContext;
import mine.coding.client.ProposalState;
import mine.coding.client.ServerResponse;
import mine.coding.client.render.AuditLogView;
import mine.coding.client.render.ChatTimeline;
import mine.coding.client.screen.components.ActionCard;
import mine.coding.client.screen.components.ProfilePanel;
import mine.coding.config.MineCodeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.*;

/** Client cloud requests and server-approved world operations meet in this screen. */
public final class AiConsoleScreen extends Screen {
    private final MineCodeConfig config;
    private final ProfilePanel profiles;
    private final ConsoleController controller = new ConsoleController();
    private final ChatTimeline timeline = new ChatTimeline();
    private final List<ChatMessage> history = new ArrayList<>();
    private final List<PanelEntry> panel = new ArrayList<>();
    private final AiHttpClient http = new AiHttpClient();
    private final AiOrchestrator orchestrator = new AiOrchestrator(http);
    private final FocusGateway focusGateway = new FocusGateway();
    private final ContextGate contextGate = new ContextGate();
    private final ProposalState proposal = new ProposalState();
    private AiOrchestrator.TaskHandle task;
    private MultiLineEditBox input;
    private Button sendButton, cancelButton, retryButton;
    private String worldContext = "", currentUser = "", memoryText = "暂无世界档案";
    private ProjectContext projectContext;
    private long version;
    private List<Long> versions = List.of();
    private boolean includeSkills = true, summaries = true, opened, closed, switchingSettings;
    private int generation, panelScroll, chatScroll;
    private int chatX, chatWidth, chatTop, chatBottom, panelX, panelWidth, panelTop, panelBottom;
    private final List<PanelDraw> panelDraws = new ArrayList<>();

    public AiConsoleScreen() { this(MineCodeConfig.load()); }
    public AiConsoleScreen(MineCodeConfig config) { super(Component.literal("MineCode AI 控制台")); this.config = config; profiles = new ProfilePanel(config); }

    @Override protected void init() {
        String retained = input == null ? "" : input.getValue();
        ConsoleLayout layout = ConsoleLayout.of(width, height);
        chatX = layout.chat().x(); chatWidth = layout.chat().width();
        chatTop = layout.chat().y(); chatBottom = layout.chat().bottom();
        panelX = layout.panel().x(); panelWidth = layout.panel().width();
        panelTop = layout.panel().y(); panelBottom = layout.panel().bottom();
        var tools = layout.tools();
        button("配置/档案", tools.get(0).x(), tools.get(0).y(), tools.get(0).width(), () -> { stopTask(); switchingSettings = true; minecraft.setScreenAndShow(new ProfileSettingsScreen(this, config, profiles)); });
        button("刷新世界", tools.get(1).x(), tools.get(1).y(), tools.get(1).width(), this::requestContext);
        button("技能: " + (includeSkills ? "已勾选" : "未勾选"), tools.get(2).x(), tools.get(2).y(), tools.get(2).width(), () -> { includeSkills = !includeSkills; rebuildWidgets(); });
        button("思考摘要: " + (summaries ? "展开" : "折叠"), tools.get(3).x(), tools.get(3).y(), tools.get(3).width(), () -> { summaries = !summaries; rebuildWidgets(); });
        button("关闭", layout.close().x(), layout.close().y(), layout.close().width(), this::onClose);
        input = MultiLineEditBox.builder().setX(8).setY(height - 64).setPlaceholder(Component.literal("输入问题，Enter 发送 / Shift+Enter 换行")).build(font, width - 16, 35, Component.literal("聊天输入"));
        input.setValue(retained); addRenderableWidget(input);
        sendButton = button("发送", 8, height - 24, 55, () -> send(input.getValue(), false));
        cancelButton = button("取消任务", 67, height - 24, 75, () -> { cancelTask(); rebuildWidgets(); });
        retryButton = button("重试本轮", 146, height - 24, 75, () -> send(controller.retryText(), true));
        buildPanelWidgets();
        switchingSettings = false;
        ClientNetworking.setClientReceiver(this::receiveResponse);
        ClientNetworking.setFocusReceiver(focusGateway::accept);
        if (!opened) { opened = true; requestContext(); }
    }
    private Button button(String text, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), ignored -> action.run()).bounds(x, y, w, 20).build());
    }
    /**
     * Asks for a world context refresh. Requests are serialised: while one is in flight this only
     * records that another is wanted, so the answer that resumes a deferred action is always the
     * freshest one.
     */
    private void requestContext() {
        focusGateway.cancelAll();
        if (contextGate.request()) sendContextRequest();
    }
    private void sendContextRequest() { sendRequest("context", request -> {}); }
    private void sendRequest(String op, java.util.function.Consumer<JsonObject> fields) {
        JsonObject request = new JsonObject(); request.addProperty("op", op); fields.accept(request);
        try { ClientNetworking.send(request); }
        catch (RuntimeException failure) { timeline.append(new ChatEvent("server", "服务端", ChatEvent.Type.FAILED, "网络请求失败: " + failure.getMessage(), null)); }
    }
    private void send(String text, boolean retry) {
        if (!controller.beginSend(text)) return;
        if (worldContext.isBlank()) { controller.finish(); timeline.append(new ChatEvent("server", "服务端", ChatEvent.Type.FAILED, "等待世界上下文，请刷新世界后重试", null)); return; }
        if (retry && !history.isEmpty() && history.getLast().role().equals("user") && history.getLast().content().equals(text)) history.removeLast();
        currentUser = text; history.add(new ChatMessage("user", text));
        timeline.append(new ChatEvent("user", "你", ChatEvent.Type.TEXT, text, null)); input.setValue(""); chatScroll = 0;
        int token = ++generation; panel.clear(); panelScroll = 0; rebuildWidgets();
        try {
            task = orchestrator.start(config.endpoint(), config.apiKey(), profiles.requestProfile(includeSkills), List.copyOf(history), worldContext, focusGateway,
                    event -> Minecraft.getInstance().execute(() -> { if (!closed && token == generation) acceptEvent(event); }));
            task.future().whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
                if (closed || token != generation) return;
                controller.finish();
                if (failure != null && !(failure instanceof java.util.concurrent.CancellationException)) timeline.append(new ChatEvent("main", "主Agent", ChatEvent.Type.FAILED, "本轮请求失败，可重试", null));
            }));
        } catch (RuntimeException failure) { controller.finish(); timeline.append(new ChatEvent("main", "主Agent", ChatEvent.Type.FAILED, "启动请求失败: " + failure.getMessage(), null)); }
    }
    private void acceptEvent(ChatEvent event) {
        controller.accept(event);
        for (ChatEvent fresh : controller.drainEvents()) timeline.append(fresh);
        controller.takePreview().ifPresent(plan -> {
            history.add(new ChatMessage("assistant", plan.message()));
            while (history.size() > 40) history.removeFirst();
            proposal.offer(plan);
            if (plan.projectDraft() != null) {
                sendRequest("proposeProject", request -> {
                    request.addProperty("definition", plan.projectDraft().toJson().toString());
                    request.addProperty("checkpoint", (plan.checkpoint() == null ? new mine.coding.ai.spatial.StageCheckpoint("planning", "等待批准项目范围", "开始读取第一个 focus", false) : plan.checkpoint()).toJson().toString());
                });
                if (plan.checkpoint() != null) {
                    var checkpoint = plan.checkpoint();
                    panel.add(new PanelEntry("阶段记录草稿: " + checkpoint.stage() + "\n" + checkpoint.summary() + "\n下一步: " + checkpoint.nextTask(), List.of()));
                }
                rebuildWidgets();
            } else {
                submitPreview(plan);
            }
            sendRequest("rememberChat", request -> { request.addProperty("user", currentUser); request.addProperty("assistant", plan.message()); request.addProperty("thought", plan.thoughtSummary()); });
        });
    }
    private void submitPreview(AiPlan plan) {
        sendRequest("preview", request -> request.addProperty("plan", plan.toJson()));
    }
    /** Stops streaming work without discarding what the player can still confirm. */
    private void stopTask() {
        if (task != null && !task.future().isDone()) task.cancel();
        if (controller.busy()) { controller.cancel(); for (ChatEvent event : controller.drainEvents()) timeline.append(event); }
        generation++; controller.finish(); timeline.cancelRunning();
        focusGateway.cancelAll(); contextGate.clear();
    }
    /** Player-initiated cancel: also forgets the proposal, so no stale card can act on a dead planId. */
    private void cancelTask() {
        stopTask();
        proposal.clearProposal(); panel.clear(); panelScroll = 0;
    }
    private void receiveResponse(JsonObject json) {
        if (closed) return;
        ServerResponse response = ServerResponse.parse(json.toString());
        switch (response.event()) {
            case "context" -> restoreContext(response);
            case "projectDraft" -> { if (proposal.awaitingApproval()) showProjectDraft(response); }
            case "project" -> onProjectApproved(response);
            case "preview" -> showPreview(response);
            case "result" -> showResult(response);
            case "logs" -> showLogs(response);
            case "error" -> showError(response);
            case "focus" -> { } // 焦点读取由 FocusGateway 自己消费，这里保持静默
            case "open" -> { } // /minecode 由客户端网络层直接打开面板
            default -> { if (!response.displayText().isBlank()) timeline.append(new ChatEvent("server", "服务端", ChatEvent.Type.TEXT, response.displayText(), null)); }
        }
    }
    /**
     * The approval only becomes usable once the refreshed world context arrives: the plan is resent
     * afterwards so the model sees the newly approved bounds instead of the stale ones.
     */
    private void onProjectApproved(ServerResponse response) {
        if (!proposal.confirmsDraft(response.text("requestId"))) return;
        AiPlan approvedPlan = proposal.takeDraftPlan();
        timeline.append(new ChatEvent("server", "服务端", ChatEvent.Type.TEXT, response.displayText(), null));
        if (approvedPlan == null) return;
        contextGate.defer(() -> resumeApprovedProject(approvedPlan));
        requestContext();
    }
    private void resumeApprovedProject(AiPlan approvedPlan) {
        if (closed) return;
        if (controller.busy()) { timeline.append(new ChatEvent("server", "服务端", ChatEvent.Type.FAILED, "上一轮任务仍在进行，已批准项目尚未继续；请稍后重新发送", null)); return; }
        timeline.append(new ChatEvent("server", "服务端", ChatEvent.Type.TEXT, "世界档案已刷新，按已批准范围继续规划", null));
        send("项目范围已批准。请按已批准项目和上一份 attentionRequests 读取焦点，生成当前阶段计划。上一份项目草稿：" + approvedPlan.toJson(), false);
    }
    private void showLogs(ServerResponse response) {
        panel.add(new PanelEntry(AuditLogView.render(response), List.of()));
        rebuildWidgets();
    }
    private void showError(ServerResponse response) {
        timeline.append(new ChatEvent("server", "服务端", ChatEvent.Type.FAILED, response.displayText(), null));
        if (!proposal.invalidatedBy(response.displayText())) return;
        panel.clear(); panelScroll = 0; rebuildWidgets();
        timeline.append(new ChatEvent("server", "服务端", ChatEvent.Type.TEXT, "当前预览已失效，请重新发送上一轮请求以生成新预览", null));
    }
    private void restoreContext(ServerResponse response) {
        if (projectContext != null && !projectContext.dimension().equals(response.text("dimension"))) { cancelTask(); }
        projectContext = ProjectContext.fromJson(response.json());
        worldContext = projectContext.worldContext(); version = ServerResponse.number(response.json(), "version", 0);
        memoryText = "世界: " + response.text("dimension") + " · 档案版本 " + version + "\n记忆: " + String.join("\n", response.strings("memory"));
        var revisions = new ArrayList<Long>(); for (String number : response.strings("history")) try { revisions.add(Long.parseLong(number)); } catch (NumberFormatException ignored) {} versions = List.copyOf(revisions);
        if (history.isEmpty() && timeline.events().isEmpty()) for (JsonObject item : response.objects("chatHistory")) {
            String user = ServerResponse.text(item, "user"), assistant = ServerResponse.text(item, "assistant"), thought = ServerResponse.text(item, "thought");
            if (!user.isBlank()) { history.add(new ChatMessage("user", user)); timeline.append(new ChatEvent("user", "你", ChatEvent.Type.TEXT, user, null)); }
            if (!assistant.isBlank()) { history.add(new ChatMessage("assistant", assistant)); timeline.append(new ChatEvent("summary", "汇总Agent", ChatEvent.Type.TEXT, assistant, null)); }
            if (!thought.isBlank()) timeline.append(new ChatEvent("summary", "公开思考摘要", ChatEvent.Type.REASONING, thought, null));
        }
        rebuildWidgets();
        ContextGate.Step step = contextGate.settle();
        if (step.sendAgain()) { sendContextRequest(); return; }
        if (step.action() != null) step.action().run();
    }
    private void showProjectDraft(ServerResponse response) {
        proposal.draft(response.text("draftId"));
        JsonObject definition = response.json().has("definition") && response.json().get("definition").isJsonObject()
                ? response.json().getAsJsonObject("definition") : null;
        String name = definition == null ? "项目草稿" : ServerResponse.text(definition, "name");
        String goal = definition == null ? response.displayText() : ServerResponse.text(definition, "goal");
        String dimension = definition == null ? "" : ServerResponse.text(definition, "dimension");
        String bounds = definition == null || !definition.has("bounds") ? "" : definition.get("bounds").toString();
        panel.add(new PanelEntry("项目草稿: " + name + "\n目标: " + goal + "\n维度: " + dimension + "\n边界: " + bounds, List.of(new Control("批准项目范围", () -> sendRequest("approveProject", request -> request.addProperty("draftId", proposal.draftId()))))));
        rebuildWidgets();
    }
    private void showPreview(ServerResponse response) {
        proposal.preview(response.text("planId")); panel.clear(); panelScroll = 0;
        for (var focus : proposal.focusRequests()) panel.add(new PanelEntry("注意力请求: " + focus.purpose() + "\n边界: " + focus.bounds().toJson(), List.of()));
        for (JsonObject item : response.objects("actions")) {
            var action = new ActionCard.Action(ServerResponse.text(item,"id"), ServerResponse.text(item,"description"), ServerResponse.text(item,"command"), ServerResponse.flag(item,"allowed"), ServerResponse.text(item,"reason"), (int)ServerResponse.number(item,"blockCount",0), ServerResponse.flag(item,"reversible"));
            ActionCard card = new ActionCard(action, a -> actionRequest("confirm", a.id()), a -> actionRequest("reject", a.id()), ignored -> {});
            String risk = ServerResponse.text(item, "risk");
            var controls = new ArrayList<Control>(); if (action.allowed()) controls.add(new Control("确认", card::confirm)); controls.add(new Control("拒绝", card::reject));
            panel.add(new PanelEntry(action.description() + "\n" + action.command() + "\n风险: " + (risk.isBlank() ? action.reason() : risk) + " · 方块: " + action.blockCount() + "\n" + (action.reversible() ? "可撤销" : "不可撤销") + (action.allowed() ? "" : " · 禁止: " + action.reason()), controls));
        }
        List<String> memories = response.strings("memoryDrafts"); for (int i = 0; i < memories.size(); i++) { int index = i; panel.add(new PanelEntry("记忆草稿: " + memories.get(i), List.of(new Control("确认写入记忆", () -> draftRequest("confirmMemory", index))))); }
        List<JsonObject> skills = response.objects("skillDrafts"); for (int i = 0; i < skills.size(); i++) { int index = i; JsonObject item = skills.get(i); panel.add(new PanelEntry("技能草稿: " + ServerResponse.text(item,"name") + "\n" + ServerResponse.text(item,"instructions"), List.of(new Control("确认写入技能", () -> draftRequest("confirmSkill", index))))); }
        if (proposal.checkpoint() != null) {
            var checkpoint = proposal.checkpoint();
            panel.add(new PanelEntry("阶段 checkpoint: " + checkpoint.stage() + "\n" + checkpoint.summary() + "\n下一步: " + checkpoint.nextTask(),
                    List.of(new Control("确认阶段记录", this::confirmStage))));
        }
        if (panel.isEmpty()) panel.add(new PanelEntry(response.displayText(), List.of()));
        rebuildWidgets();
    }
    private void confirmStage() { sendRequest("confirmStage", request -> request.addProperty("planId", proposal.planId())); }
    private void actionRequest(String op, String actionId) { sendRequest(op, request -> { request.addProperty("planId", proposal.planId()); request.addProperty("actionId", actionId); }); }
    private void draftRequest(String op, int index) { sendRequest(op, request -> { request.addProperty("planId", proposal.planId()); request.addProperty("index", index); }); }
    private void showResult(ServerResponse response) {
        String operation = response.text("operationId"); boolean reversible = ServerResponse.flag(response.json(), "reversible");
        panel.add(new PanelEntry(response.displayText() + "\n" + (reversible ? "可撤销" : "不可撤销"), reversible && !operation.isBlank() ? List.of(new Control("撤销此操作", () -> sendRequest("undo", request -> request.addProperty("operationId", operation)))) : List.of()));
        version = ServerResponse.number(response.json(), "version", version); rebuildWidgets(); requestContext();
    }
    private void buildPanelWidgets() {
        panelDraws.clear(); int y = panelTop - panelScroll;
        String project = projectContext == null ? "项目: 未读取" : "项目: " + projectContext.projectDisplay();
        String checkpoint = projectContext == null ? "" : projectContext.checkpoint().map(c -> "\n阶段: " + c.stage() + " · 下一步: " + c.nextTask()).orElse("");
        String summary = profiles.active().name() + " · " + profiles.active().model() + " · 思考强度 " + profiles.active().reasoningEffort() + "\n" + project + checkpoint + "\n" + memoryText;
        panelDraws.add(new PanelDraw(summary, y)); y += lineHeight(summary, panelWidth) + 4;
        var entries = new ArrayList<>(panel);
        if (entries.isEmpty()) entries.add(new PanelEntry("最终计划就绪后，服务端预览将在这里显示。每条世界修改都需要独立确认。", List.of()));
        entries.add(new PanelEntry("世界档案管理", List.of(new Control("写入聊天为记忆", () -> sendRequest("manualMemory", request -> request.addProperty("text", controller.retryText()))), new Control("查看日志", () -> sendRequest("logs", request -> {})))));
        for (long revision : versions) entries.add(new PanelEntry("历史版本 " + revision, List.of(new Control("回滚至此版本", () -> sendRequest("rollback", request -> request.addProperty("version", revision))))));
        for (PanelEntry entry : entries) {
            panelDraws.add(new PanelDraw(entry.text(), y)); y += lineHeight(entry.text(), panelWidth) + 3;
            int x = panelX;
            for (Control control : entry.controls()) { int w = Math.max(50, Math.min(105, panelWidth / Math.max(1, entry.controls().size()) - 3)); Button button = button(control.text(), x, y, w, control.action()); button.visible = y >= panelTop && y + 20 <= panelBottom; x += w + 4; }
            if (!entry.controls().isEmpty()) y += 24; y += 8;
        }
    }
    private int lineHeight(String text, int width) { return Math.max(1, font.split(Component.literal(text), Math.max(20, width)).size()) * 11; }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == 257 && input.isFocused() && (event.modifiers() & 1) == 0) { send(input.getValue(), false); return true; }
        return super.keyPressed(event);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (y >= panelTop && y <= panelBottom && x >= panelX) { panelScroll = Math.max(0, panelScroll - (int)(vertical * 24)); rebuildWidgets(); return true; }
        if (y >= chatTop && y <= chatBottom) { chatScroll = Math.max(0, chatScroll + (int)(vertical * 24)); return true; }
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xFF101318); graphics.text(font, title, 8, 7, 0xFFFFFFFF);
        sendButton.active = !controller.busy(); retryButton.active = !controller.busy() && !controller.retryText().isBlank(); cancelButton.active = controller.busy();
        renderTimeline(graphics); graphics.enableScissor(panelX, panelTop, panelX+panelWidth, panelBottom);
        for (PanelDraw draw : panelDraws) { int y = draw.y(); for (FormattedCharSequence line : font.split(Component.literal(draw.text()), panelWidth)) { graphics.text(font,line,panelX,y,0xFFD8E0E8); y += 11; } }
        graphics.disableScissor(); super.extractRenderState(graphics,mouseX,mouseY,delta);
    }
    private void renderTimeline(GuiGraphicsExtractor graphics) {
        var lines = new ArrayList<ColoredLine>();
        for (ChatTimeline.Entry entry : timeline.entries()) {
            String label = "[" + entry.role() + " · " + entry.status() + "]";
            lines.add(new ColoredLine(FormattedCharSequence.forward(label, net.minecraft.network.chat.Style.EMPTY), 0xFF99BEE5));
            for (FormattedCharSequence line : font.split(Component.literal(entry.output()), chatWidth)) lines.add(new ColoredLine(line, 0xFFD8E0E8));
            if (summaries && !entry.reasoning().isBlank()) for (FormattedCharSequence line : font.split(Component.literal("公开思考摘要: " + entry.reasoning()), chatWidth)) lines.add(new ColoredLine(line, 0xFFE2D48A));
            lines.add(new ColoredLine(FormattedCharSequence.forward("", net.minecraft.network.chat.Style.EMPTY), 0xFFFFFFFF));
        }
        int total = lines.size() * 11; chatScroll = Math.min(chatScroll, Math.max(0,total-(chatBottom-chatTop))); int y = chatBottom-total+chatScroll;
        graphics.enableScissor(chatX,chatTop,chatX+chatWidth,chatBottom);
        for (ColoredLine line : lines) { if (y >= chatTop-11 && y < chatBottom) graphics.text(font,line.text(),chatX,y,line.color()); y += 11; }
        graphics.disableScissor();
    }
    @Override public void removed() { if (!switchingSettings) { cancelTask(); ClientNetworking.setClientReceiver(null); ClientNetworking.setFocusReceiver(null); focusGateway.close(); closed = true; http.close(); } }
    @Override public void onClose() { cancelTask(); ClientNetworking.setClientReceiver(null); ClientNetworking.setFocusReceiver(null); focusGateway.close(); closed = true; http.close(); super.onClose(); }
    @Override public boolean isPauseScreen() { return false; }
    private record Control(String text, Runnable action) {}
    private record PanelEntry(String text, List<Control> controls) {}
    private record PanelDraw(String text, int y) {}
    private record ColoredLine(FormattedCharSequence text, int color) {}
}

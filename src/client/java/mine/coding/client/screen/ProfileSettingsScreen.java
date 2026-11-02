package mine.coding.client.screen;

import mine.coding.client.screen.components.ProfilePanel;
import mine.coding.config.MineCodeConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** Dedicated form keeps long skill instructions usable at small game resolutions. */
final class ProfileSettingsScreen extends Screen {
    private final AiConsoleScreen parent;
    private final MineCodeConfig config;
    private final ProfilePanel profiles;
    private EditBox endpoint, key, name, model;
    private MultiLineEditBox prompt, skills;
    private String effort;
    private String error = "";
    ProfileSettingsScreen(AiConsoleScreen parent, MineCodeConfig config, ProfilePanel profiles) {
        super(Component.literal("MineCode · AI 配置与技能")); this.parent = parent; this.config = config; this.profiles = profiles;
    }
    @Override protected void init() {
        int x = 12, w = width - 24;
        endpoint = field(x, 32, w, config.endpoint(), "Endpoint");
        key = field(x, 65, w, config.apiKey(), "API Key（仅客户端保存）");
        key.addFormatter((value, offset) -> FormattedCharSequence.forward("•".repeat(value.length()), Style.EMPTY));
        name = field(x, 98, Math.max(80, w / 2 - 3), profiles.active().name(), "档案名称");
        model = field(x + w / 2 + 3, 98, Math.max(80, w / 2 - 3), profiles.active().model(), "模型");
        effort = profiles.active().reasoningEffort();
        addRenderableWidget(Button.builder(Component.literal("思考强度: " + effort), button -> {
            effort = ProfilePanel.EFFORTS.get((ProfilePanel.EFFORTS.indexOf(effort) + 1) % ProfilePanel.EFFORTS.size()); button.setMessage(Component.literal("思考强度: " + effort));
        }).bounds(x, 123, Math.min(160, w / 2 - 3), 20).build());
        addRenderableWidget(Button.builder(Component.literal("切换档案"), button -> { if (save()) { profiles.next(); rebuildWidgets(); } }).bounds(x + w / 2, 123, Math.max(60, w / 4 - 3), 20).build());
        addRenderableWidget(Button.builder(Component.literal("新增档案"), button -> { if (save()) { profiles.create(); rebuildWidgets(); } }).bounds(x + 3 * w / 4, 123, Math.max(60, w / 4), 20).build());
        int areaHeight = Math.max(24, (height - 205) / 2);
        prompt = MultiLineEditBox.builder().setX(x).setY(159).setPlaceholder(Component.literal("档案系统提示")).build(font, w, areaHeight, Component.literal("档案系统提示"));
        prompt.setValue(profiles.active().systemPrompt()); addRenderableWidget(prompt);
        skills = MultiLineEditBox.builder().setX(x).setY(176 + areaHeight).setPlaceholder(Component.literal("技能指令（支持多行）")).build(font, w, areaHeight, Component.literal("技能指令"));
        skills.setValue(ProfilePanel.skillsText(profiles.active().skills())); addRenderableWidget(skills);
        // One exit saves, the other two discard: no hidden third meaning for Esc.
        addRenderableWidget(Button.builder(Component.literal("保存并返回"), button -> { if (save()) minecraft.setScreenAndShow(parent); }).bounds(x, height - 25, 110, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回不保存"), button -> minecraft.setScreenAndShow(parent)).bounds(width - 82, height - 25, 70, 20).build());
    }
    private EditBox field(int x, int y, int w, String value, String label) {
        var field = new EditBox(font, x, y, w, 20, Component.literal(label)); field.setMaxLength(8192); field.setValue(value); addRenderableWidget(field); return field;
    }
    private boolean save() {
        try { config.setEndpoint(endpoint.getValue()); config.setApiKey(key.getValue()); profiles.update(name.getValue(), prompt.getValue(), model.getValue(), effort, skills.getValue()); config.save(); error = ""; return true; }
        catch (RuntimeException failure) { error = "配置未保存: " + failure.getMessage(); return false; }
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xFF101318); graphics.text(font, title, 12, 8, 0xFFFFFFFF);
        graphics.text(font, "Endpoint", 12, 21, 0xFFCCCCCC); graphics.text(font, "API Key（密码隐藏，仅本地）", 12, 54, 0xFFCCCCCC);
        graphics.text(font, "档案名称 / 模型（可直接填写）", 12, 87, 0xFFCCCCCC); graphics.text(font, "系统提示", 12, 148, 0xFFCCCCCC);
        graphics.text(font, "技能草稿 · 多行", 12, skills.getY() - 11, 0xFFE2D48A);
        if (!error.isBlank()) graphics.text(font, error, 130, height - 19, 0xFFFF8888);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }
    @Override public void onClose() { minecraft.setScreenAndShow(parent); }
    @Override public boolean isPauseScreen() { return false; }
}

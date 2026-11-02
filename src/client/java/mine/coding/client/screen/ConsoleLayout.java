package mine.coding.client.screen;

import java.util.List;

/**
 * Pure geometry for the console: the left chat pane, the right action panel, the top tool bar and
 * the close button. Extracted from the screen so layout can be unit tested without a client, and so
 * the narrow-window tool bar can be verified to never overlap.
 *
 * <p>Wide windows put the panel beside the chat (3/5 + 2/5). Below {@link #NARROW_WIDTH} the two
 * panes stack vertically and the tool bar buttons shrink proportionally instead of running past the
 * close button.
 */
public record ConsoleLayout(Rect chat, Rect panel, List<Rect> tools, Rect close) {

    /** Minecraft GUI button height; also the height the layout reserves for every tool button. */
    public static final int BUTTON_HEIGHT = 20;
    /** Below this width the panes stack and the tool bar compresses. */
    public static final int NARROW_WIDTH = 650;
    /** Below this width the four tool buttons no longer fit at their preferred sizes. */
    private static final int WIDE_TOOLBAR_WIDTH = 420;
    private static final int[] TOOL_WIDTHS = {78, 70, 98, 108};
    private static final int GAP = 4;
    private static final int MARGIN = 8;
    private static final int CLOSE_WIDTH = 40;
    private static final int MIN_TOOL_WIDTH = 34;

    /** Inclusive-left, exclusive-right integer rectangle. */
    public record Rect(int x, int y, int width, int height) {
        public int right() { return x + width; }
        public int bottom() { return y + height; }
        public boolean overlaps(Rect other) {
            return x < other.right() && other.x < right() && y < other.bottom() && other.y < bottom();
        }
    }

    public static ConsoleLayout of(int width, int height) {
        int w = Math.max(width, 1), h = Math.max(height, 1);
        int chatTop = 48;
        int panelBottom = Math.max(chatTop + BUTTON_HEIGHT, h - 72);
        Rect chat, panel;
        if (w < NARROW_WIDTH) {
            int paneWidth = Math.max(1, w - 16);
            int chatBottom = Math.max(chatTop + 20, h / 2 - 4);
            chat = new Rect(8, chatTop, paneWidth, Math.max(1, chatBottom - chatTop));
            int panelTop = chatBottom + 10;
            panel = new Rect(8, panelTop, paneWidth, Math.max(1, panelBottom - panelTop));
        } else {
            int chatWidth = Math.max(1, w * 3 / 5 - 14);
            int panelX = w * 3 / 5 + 4;
            int panelWidth = Math.max(1, w - panelX - 8);
            chat = new Rect(8, chatTop, chatWidth, Math.max(1, panelBottom - chatTop));
            panel = new Rect(panelX, chatTop, panelWidth, Math.max(1, panelBottom - chatTop));
        }
        Rect close = new Rect(Math.max(0, w - MARGIN - CLOSE_WIDTH), 3, CLOSE_WIDTH, BUTTON_HEIGHT);
        return new ConsoleLayout(chat, panel, toolBar(w, close), close);
    }

    /** Lays the four tool buttons out left to right, stopped before the close button. */
    private static List<Rect> toolBar(int width, Rect close) {
        int limit = close.x() - GAP;
        int available = limit - MARGIN - GAP * (TOOL_WIDTHS.length - 1);
        int total = 0;
        for (int preferred : TOOL_WIDTHS) total += preferred;
        Rect[] tools = new Rect[TOOL_WIDTHS.length];
        int x = MARGIN;
        for (int i = 0; i < TOOL_WIDTHS.length; i++) {
            int toolWidth = width >= WIDE_TOOLBAR_WIDTH
                    ? TOOL_WIDTHS[i]
                    : Math.max(MIN_TOOL_WIDTH, (int) ((long) TOOL_WIDTHS[i] * available / total));
            // Never let a compressed tool bar run into the close button.
            toolWidth = Math.max(1, Math.min(toolWidth, limit - x));
            tools[i] = new Rect(x, 23, toolWidth, BUTTON_HEIGHT);
            x += toolWidth + GAP;
        }
        return List.of(tools);
    }
}

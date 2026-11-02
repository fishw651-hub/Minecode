package mine.coding.client.screen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Layout must stay inside the window and never overlap, at every supported GUI size. */
class ConsoleLayoutTest {

    /** GUI scales Minecraft actually produces, from tiny to 4K-wide. */
    private static final int[] WIDTHS = {320, 400, 420, 480, 640, 649, 650, 854, 1280, 1920, 2560, 3840};
    private static final int[] HEIGHTS = {240, 320, 480, 720, 1080, 1440};

    @Test void wideWindowsPutThePanelBesideTheChat() {
        ConsoleLayout layout = ConsoleLayout.of(1280, 720);
        assertEquals(8, layout.chat().x());
        assertTrue(layout.panel().x() >= layout.chat().right(), "panel must not overlap the chat pane");
        assertFalse(layout.chat().overlaps(layout.panel()));
        assertTrue(layout.panel().right() <= 1280);
    }

    @Test void narrowWindowsStackThePanesInsteadOfSplittingThem() {
        ConsoleLayout layout = ConsoleLayout.of(480, 320);
        assertEquals(layout.chat().x(), layout.panel().x(), "narrow layout stacks the panes");
        assertTrue(layout.chat().bottom() <= layout.panel().y(), "the panel must start below the chat");
        assertFalse(layout.chat().overlaps(layout.panel()));
    }

    @Test void everyToolButtonStaysInsideTheWindowAndClearOfTheCloseButton() {
        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                ConsoleLayout layout = ConsoleLayout.of(width, height);
                List<ConsoleLayout.Rect> tools = layout.tools();
                assertEquals(4, tools.size());
                for (ConsoleLayout.Rect tool : tools) {
                    assertTrue(tool.x() >= 0 && tool.right() <= width,
                            "tool " + tool + " leaves the " + width + "px window");
                    assertTrue(tool.width() > 0, "tool must keep a clickable width");
                    assertFalse(tool.overlaps(layout.close()),
                            "tool " + tool + " overlaps close " + layout.close() + " at " + width + "px");
                }
                for (int i = 0; i < tools.size(); i++) {
                    for (int j = i + 1; j < tools.size(); j++) {
                        assertFalse(tools.get(i).overlaps(tools.get(j)),
                                "tools overlap at " + width + "px: " + tools.get(i) + " / " + tools.get(j));
                    }
                }
                assertTrue(layout.close().right() <= width);
            }
        }
    }

    @Test void degenerateWindowsStillProduceUsableRectangles() {
        for (int[] size : new int[][]{{1, 1}, {64, 40}, {100, 60}}) {
            ConsoleLayout layout = ConsoleLayout.of(size[0], size[1]);
            assertTrue(layout.chat().width() >= 1 && layout.chat().height() >= 1);
            assertTrue(layout.panel().height() >= 1);
            assertTrue(layout.tools().stream().allMatch(rect -> rect.width() >= 1));
        }
    }

    @Test void toolBarKeepsItsPreferredWidthWhenThereIsRoom() {
        List<ConsoleLayout.Rect> tools = ConsoleLayout.of(1280, 720).tools();
        assertEquals(List.of(78, 70, 98, 108), tools.stream().map(ConsoleLayout.Rect::width).toList());
    }
}

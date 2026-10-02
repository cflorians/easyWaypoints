package com.easywp.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Confirmation step between picking a file in {@link ImportWorldScreen} and actually importing it:
 * asks "import the waypoints of WORLD?" and lists what that file holds, so the player sees what
 * is about to be added before anything is. At most {@link #MAX_LISTED} waypoints are listed; a
 * longer file ends the list with an ellipsis line.
 *
 * <p>The waypoints are read once, here, and the same list is what gets merged on confirm, so the
 * preview and the import can never disagree. Either button returns to the picker.
 */
public class ImportConfirmScreen extends Screen {
    private static final int MAX_LISTED = 7;
    private static final int LINE_HEIGHT = 12;
    private static final int TEXT_WIDTH = 300;
    private static final int BUTTON_WIDTH = 150;

    private final Screen parentScreen;
    private final List<Waypoint> incoming;
    private final Component question;
    private List<FormattedCharSequence> questionLines = new ArrayList<>();
    private final List<Component> listLines = new ArrayList<>();

    public ImportConfirmScreen(Screen parentScreen, Path file) {
        super(I18nHelper.getComponent("import.title"));
        this.parentScreen = parentScreen;
        this.incoming = WaypointStore.loadWaypointsFrom(file);
        this.question = I18nHelper.getComponent("import.confirm_message", WaypointStore.prettyWorldName(file));
    }

    @Override
    protected void init() {
        this.questionLines = this.font.split(this.question, Math.min(TEXT_WIDTH, this.width - 20));

        this.listLines.clear();
        int listed = Math.min(MAX_LISTED, incoming.size());
        for (int i = 0; i < listed; i++) {
            Waypoint wp = incoming.get(i);
            String name = wp.getName() != null ? wp.getName() : "Waypoint";
            String coords = "  " + wp.getPos().getX() + ", " + wp.getPos().getY() + ", " + wp.getPos().getZ();
            int nameWidth = Math.max(20, Math.min(TEXT_WIDTH, this.width - 20) - this.font.width(coords));
            this.listLines.add(Component.literal(this.font.plainSubstrByWidth(name, nameWidth))
                    .append(Component.literal(coords).withStyle(ChatFormatting.GRAY)));
        }
        if (incoming.size() > MAX_LISTED) {
            this.listLines.add(Component.literal("...").withStyle(ChatFormatting.GRAY));
        }

        int buttonY = contentTop() + contentHeight() + 12;
        int centerX = this.width / 2;
        this.addRenderableWidget(Button.builder(I18nHelper.getComponent("import.confirm_button"), btn -> {
            WaypointStore.MergeResult result = WaypointStore.mergeFrom(incoming);
            ClientCompat.setOverlayMessage(this.minecraft,
                    I18nHelper.getComponent("import.result", result.added(), result.skipped()), false);
            this.onClose();
        }).bounds(centerX - BUTTON_WIDTH - 4, buttonY, BUTTON_WIDTH, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, btn -> this.onClose())
                .bounds(centerX + 4, buttonY, BUTTON_WIDTH, 20).build());
    }

    /** Question, a blank line, then the waypoint list. */
    private int contentHeight() {
        return (questionLines.size() + 1 + listLines.size()) * LINE_HEIGHT;
    }

    /** Question, list and buttons are centred vertically as one block. */
    private int contentTop() {
        return Math.max(10, (this.height - (contentHeight() + 12 + 20)) / 2);
    }

    @Override
    public void onClose() {
        ClientCompat.setScreen(this.minecraft, this.parentScreen);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);

        int centerX = this.width / 2;
        int y = contentTop();
        for (FormattedCharSequence line : questionLines) {
            graphics.centeredText(this.font, line, centerX, y, 0xFFFFFFFF);
            y += LINE_HEIGHT;
        }
        y += LINE_HEIGHT;
        for (Component line : listLines) {
            graphics.centeredText(this.font, line, centerX, y, 0xFFFFFFFF);
            y += LINE_HEIGHT;
        }
    }
}

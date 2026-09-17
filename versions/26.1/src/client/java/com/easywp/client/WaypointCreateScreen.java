package com.easywp.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;

import java.util.Locale;

/**
 * Centered modal screen for creating or editing waypoints.
 */
public class WaypointCreateScreen extends Screen {
    /** Modal width, sized for the widest row inside it. The color picker below used to be that row
     *  (a dense grid of every color); since its 2026-09 redesign into a 14-color family bar plus a
     *  15-shade bar for whichever one is active, at {@link #PICKER_WIDTH} (210) it is narrower
     *  than this, and the name field + share icon group is the binding constraint instead. */
    private static final int FORM_WIDTH = 270;
    // Color picker geometry: WaypointColors.FAMILY_BAR (14 colors) as one touching row, then
    // WaypointColors's active family (WaypointColors.SHADE_COUNT = 15 shades, dark to light) as a
    // second touching row below it. PICKER_WIDTH divides evenly by both counts (210 / 14 = 15,
    // 210 / 15 = 14), which is why each bar's own swatch width is derived rather than fixed.
    private static final int PICKER_WIDTH = 210;
    // Matches FAMILY_SWATCH_W (15) exactly - square top-bar swatches, and the bottom bar's own
    // 14px-wide swatches one pixel off square, close enough to read as the same shape.
    private static final int BAR_HEIGHT = 15;
    private static final int BAR_GAP = 10;
    private static final int BAR_FRAME_PAD = 2;
    private static final int FAMILY_SWATCH_W = PICKER_WIDTH / WaypointColors.FAMILY_BAR.length;
    private static final int SHADE_SWATCH_W = PICKER_WIDTH / WaypointColors.SHADE_COUNT;
    /**
     * Marker height in the preview, sized to match the real in-game ratio between the marker and its
     * label text rather than an arbitrary GUI size. In WaypointRenderer the label rides the marker's
     * own billboard, so its size is literally markerSize, and the label's world-space text height
     * works out to {@code 9 * (0.035 * markerSize / 0.7) = 0.45 * markerSize}. Our label is
     * drawn at the standard unscaled 9px GUI line height, so the marker that keeps the same ratio is
     * {@code 9 / 0.45 = 20} px tall — this is that fixed point, not a look-good guess.
     */
    private static final float PREVIEW_MARKER_H = 20.0f;

    private EditBox nameField;
    private EditBox xField;
    private EditBox yField;
    private EditBox zField;
    private int selectedColor = 0xFF00FF00;
    /** Which entry of {@code WaypointColors.FAMILY_BAR} the shade bar below it is currently
     *  browsing - not necessarily the family whose own value equals {@link #selectedColor}, since
     *  the player may have picked one of its shades instead. Seeded from {@code selectedColor} in
     *  {@link #init()} via {@code WaypointColors.nearestMatch}, then only ever changed by clicking
     *  a family-bar swatch. */
    private int activeBaseIndex = 0;
    private final BlockPos originalPos;
    private final Waypoint editingWaypoint;
    private boolean dimShared = false;

    private static class ColorSampleButton extends Button {
        public ColorSampleButton(int x, int y, int width, int height, OnPress onPress) {
            super(x, y, width, height, Component.literal(""), onPress, DEFAULT_NARRATION);
        }

        @Override
        protected void extractContents(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
            // Handled in batch mode by WaypointCreateScreen.extractRenderState for zero frame lag
        }
    }

    public WaypointCreateScreen(BlockPos pos) {
        super(I18nHelper.getComponent("create.title.new"));
        this.originalPos = pos != null ? pos : BlockPos.ZERO;
        this.editingWaypoint = null;

        this.selectedColor = WaypointColors.random();
    }

    public WaypointCreateScreen(Waypoint waypointToEdit) {
        super(I18nHelper.getComponent("create.title.edit"));
        this.originalPos = waypointToEdit.getPos();
        this.editingWaypoint = waypointToEdit;
        this.selectedColor = waypointToEdit.getColor();
        this.dimShared = waypointToEdit.isShared();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        int nameFieldW = 180;
        int nameGroupW = nameFieldW + 6 + 18;
        int nameFieldX = centerX - nameGroupW / 2;
        int shareIconX = nameFieldX + nameFieldW + 6;

        this.nameField = new EditBox(this.font, nameFieldX, centerY - 82, nameFieldW, 18, I18nHelper.getComponent("create.name_label"));
        if (this.editingWaypoint != null) {
            this.nameField.setValue(this.editingWaypoint.getName());
        } else {
            this.nameField.setValue(I18nHelper.getComponent("create.default_name", String.valueOf(WaypointRenderer.waypoints.size() + 1)).getString());
        }
        this.addRenderableWidget(this.nameField);

        ModernButton shareToggleBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
            this.dimShared = !this.dimShared;
        }).pos(shareIconX, centerY - 82).size(18, 18).build();

        String currentDimension = "minecraft:overworld";
        if (this.minecraft.level != null) {
            currentDimension = this.minecraft.level.dimension().identifier().toString();
        }
        if (this.editingWaypoint != null && this.editingWaypoint.getDimension() != null) {
            currentDimension = this.editingWaypoint.getDimension();
        }
        boolean isConvertibleDim = currentDimension.equals("minecraft:overworld") || currentDimension.equals("minecraft:the_nether");
        shareToggleBtn.active = isConvertibleDim;
        this.addRenderableWidget(shareToggleBtn);

        this.xField = new EditBox(this.font, centerX - 118, centerY - 5, 68, 18, Component.literal("X"));
        this.xField.setValue(String.valueOf(originalPos.getX()));
        this.addRenderableWidget(this.xField);

        this.yField = new EditBox(this.font, centerX - 34, centerY - 5, 68, 18, Component.literal("Y"));
        this.yField.setValue(String.valueOf(originalPos.getY()));
        this.addRenderableWidget(this.yField);

        this.zField = new EditBox(this.font, centerX + 50, centerY - 5, 68, 18, Component.literal("Z"));
        this.zField.setValue(String.valueOf(originalPos.getZ()));
        this.addRenderableWidget(this.zField);

        // Top bar: the 14 family colors, touching (no gap between them). Clicking one selects it
        // outright and switches the bottom bar to that color's own shades - see the field
        // javadoc on activeBaseIndex.
        this.activeBaseIndex = WaypointColors.nearestMatch(this.selectedColor)[0];

        int familyBarX = centerX - PICKER_WIDTH / 2;
        int familyBarY = centerY + 29;
        int shadeBarX = familyBarX;
        int shadeBarY = familyBarY + BAR_HEIGHT + BAR_GAP;

        for (int i = 0; i < WaypointColors.FAMILY_BAR.length; i++) {
            final int baseIndex = i;
            final int col = WaypointColors.FAMILY_BAR[i];
            int btnX = familyBarX + i * FAMILY_SWATCH_W;
            this.addRenderableWidget(
                new ColorSampleButton(btnX, familyBarY, FAMILY_SWATCH_W, BAR_HEIGHT, btn -> {
                    this.activeBaseIndex = baseIndex;
                    this.selectedColor = col;
                })
            );
        }

        // Bottom bar: the active family's own shades, dark (left) to light (right), also
        // touching. These WaypointColors.SHADE_COUNT buttons are created once and never rebuilt
        // when activeBaseIndex changes above - each looks up its own current color from
        // WaypointColors.familyOf(activeBaseIndex) at click time (the render loop does the same
        // every frame), so switching families is just mutating a field, not touching the widget
        // list.
        for (int slot = 0; slot < WaypointColors.SHADE_COUNT; slot++) {
            final int shadeIndex = slot;
            int btnX = shadeBarX + slot * SHADE_SWATCH_W;
            this.addRenderableWidget(
                new ColorSampleButton(btnX, shadeBarY, SHADE_SWATCH_W, BAR_HEIGHT, btn -> {
                    this.selectedColor = WaypointColors.familyOf(this.activeBaseIndex)[shadeIndex];
                })
            );
        }

        this.addRenderableWidget(
            ModernButton.modernBuilder(Component.literal(""), btn -> {
                this.onClose();
            }).pos(centerX - 118, centerY + 85).size(22, 20).build()
        );

        this.addRenderableWidget(
            ModernButton.modernBuilder(I18nHelper.getComponent("create.save"), btn -> {
                saveWaypoint();
                this.onClose();
            }).pos(centerX - 90, centerY + 85).size(180, 20).build()
        );

        this.addRenderableWidget(
            ModernButton.modernBuilder(Component.literal(""), btn -> {
                ClientCompat.setScreen(this.minecraft, new WaypointListScreen(this));
            }).pos(centerX + 96, centerY + 85).size(22, 20).build()
        );
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        int bgW = FORM_WIDTH;
        int bgH = 224;
        int minX = centerX - bgW / 2;
        int minY = centerY - bgH / 2;

        int nameFieldW = 180;
        int nameGroupW = nameFieldW + 6 + 18;
        int nameFieldX = centerX - nameGroupW / 2;
        int shareIconX = nameFieldX + nameFieldW + 6;

        graphics.fill(minX, minY, minX + bgW, minY + bgH, UiPalette.MODAL_BG);

        graphics.fill(minX, minY, minX + bgW, minY + 1, UiPalette.MODAL_BORDER);
        graphics.fill(minX, minY + bgH - 1, minX + bgW, minY + bgH, UiPalette.MODAL_BORDER);
        graphics.fill(minX, minY, minX + 1, minY + bgH, UiPalette.MODAL_BORDER);
        graphics.fill(minX + bgW - 1, minY, minX + bgW, minY + bgH, UiPalette.MODAL_BORDER);

        Component titleComp = this.editingWaypoint != null ? I18nHelper.getComponent("create.title.edit") : I18nHelper.getComponent("create.title.new");
        graphics.centeredText(this.font, titleComp, centerX, minY + 6, UiPalette.TEXT_PRIMARY);

        graphics.centeredText(this.font, I18nHelper.getComponent("create.name_label"), centerX, centerY - 92, UiPalette.TEXT_SECONDARY);
        graphics.centeredText(this.font, I18nHelper.getComponent("create.coordinates_label"), centerX, centerY - 15, UiPalette.TEXT_SECONDARY);

        super.extractRenderState(graphics, mouseX, mouseY, a);

        // Batch draw both color bars directly - see the matching loop in init() above for the
        // geometry and why the bottom bar needs no rebuilding when activeBaseIndex changes.
        int familyBarX = centerX - PICKER_WIDTH / 2;
        int familyBarY = centerY + 29;
        int shadeBarX = familyBarX;
        int shadeBarY = familyBarY + BAR_HEIGHT + BAR_GAP;

        // A frame around each bar, not just the modal's own border, is what makes them read as
        // two distinct panels instead of one blob of touching color once neither bar has a gap
        // of its own left to draw a separator into.
        graphics.fill(familyBarX - BAR_FRAME_PAD, familyBarY - BAR_FRAME_PAD,
                familyBarX + PICKER_WIDTH + BAR_FRAME_PAD, familyBarY + BAR_HEIGHT + BAR_FRAME_PAD, UiPalette.MODAL_BORDER);
        graphics.fill(shadeBarX - BAR_FRAME_PAD, shadeBarY - BAR_FRAME_PAD,
                shadeBarX + PICKER_WIDTH + BAR_FRAME_PAD, shadeBarY + BAR_HEIGHT + BAR_FRAME_PAD, UiPalette.MODAL_BORDER);

        for (int i = 0; i < WaypointColors.FAMILY_BAR.length; i++) {
            int col = WaypointColors.FAMILY_BAR[i];
            int btnX = familyBarX + i * FAMILY_SWATCH_W;
            drawColorSwatch(graphics, mouseX, mouseY, btnX, familyBarY, FAMILY_SWATCH_W, BAR_HEIGHT, col);

            // Marks which family the bottom bar is currently browsing. Skipped when that family's
            // own value is already getting the full selection outline drawn inside drawColorSwatch
            // (the player picked the family color itself, not one of its shades) - the two would
            // otherwise sit right on top of each other. An inset frame rather than an outer ring,
            // like the selection outline itself, so it never breaks the bar's touching edges - and
            // the same fixed UiPalette.MODAL_BORDER color as the selection outline and the bars'
            // own frame, rather than a third color of its own, so nothing about the picker's
            // highlight color ever changes depending on which of the two states is showing.
            if (i == this.activeBaseIndex && col != this.selectedColor) {
                graphics.fill(btnX, familyBarY, btnX + FAMILY_SWATCH_W, familyBarY + 2, UiPalette.MODAL_BORDER);
                graphics.fill(btnX, familyBarY + BAR_HEIGHT - 2, btnX + FAMILY_SWATCH_W, familyBarY + BAR_HEIGHT, UiPalette.MODAL_BORDER);
                graphics.fill(btnX, familyBarY, btnX + 2, familyBarY + BAR_HEIGHT, UiPalette.MODAL_BORDER);
                graphics.fill(btnX + FAMILY_SWATCH_W - 2, familyBarY, btnX + FAMILY_SWATCH_W, familyBarY + BAR_HEIGHT, UiPalette.MODAL_BORDER);
            }
        }

        int[] activeFamily = WaypointColors.familyOf(this.activeBaseIndex);
        for (int slot = 0; slot < WaypointColors.SHADE_COUNT; slot++) {
            int btnX = shadeBarX + slot * SHADE_SWATCH_W;
            drawColorSwatch(graphics, mouseX, mouseY, btnX, shadeBarY, SHADE_SWATCH_W, BAR_HEIGHT, activeFamily[slot]);
        }

        Icons.drawPortalIcon(graphics, shareIconX, centerY - 82, this.dimShared, true);

        ClientCompat.blitTinted(graphics, Icons.CANCEL, centerX - 116, centerY + 86, 18, 18, UiPalette.ICON_DESTRUCTIVE);
        ClientCompat.blitTinted(graphics, Icons.LIST, centerX + 98, centerY + 86, 18, 18, UiPalette.ICON_IDLE);

        // Live preview: how this waypoint will look in the 3D world (real marker sprite, real label rules).
        String previewName = resolveWaypointName();
        if (ModConfig.get().labelDisplay.uppercase) {
            previewName = previewName.toUpperCase(Locale.ROOT);
        }

        double opacity = Math.max(0.0, Math.min(1.0, ModConfig.get().waypointSize.opacityPercent / 100.0));
        int markerAlpha = (int) Math.round(255 * opacity);
        int markerTint = (markerAlpha << 24) | (this.selectedColor & 0xFFFFFF);

        // In the world the label floats above the marker's tip (the marker's world anchor is its
        // base, growing upward), so the preview stacks label-then-marker top to bottom, matching
        // WaypointRenderer's actual draw order instead of putting the name underneath like a caption.
        int labelWidth = this.font.width(previewName);
        int labelX = centerX - labelWidth / 2;
        int labelY = centerY - 58;
        graphics.fill(labelX - 2, labelY - 1, labelX + labelWidth + 2, labelY + 9, UiPalette.LABEL_BACKDROP);
        graphics.text(this.font, Component.literal(previewName), labelX, labelY, UiPalette.TEXT_PRIMARY);

        float markerScale = PREVIEW_MARKER_H / 7.0f;
        float markerW = 5.0f * markerScale;
        int markerX = Math.round(centerX - markerW / 2.0f);
        int markerY = labelY + 9 + 6;
        // waypoint_marker.png is only 5x7 texels; blit it at native size inside a scaled pose so it
        // magnifies cleanly instead of tiling (the "sample a WxH texel region" blit overloads treat
        // a bigger request size as wrapping past the 5x7 bounds, not as a stretch). A single uniform
        // scale keeps the 5:7 aspect exact.
        graphics.pose().pushMatrix();
        graphics.pose().translate(markerX, markerY);
        graphics.pose().scale(markerScale, markerScale);
        ClientCompat.blitTinted(graphics, WaypointRenderTypes.MARKER_TEXTURE, 0, 0, 5, 7, markerTint);
        graphics.pose().popMatrix();
    }

    /**
     * Fills the whole {@code w}x{@code h} cell with {@code col} - not inset, unlike the previous
     * design's swatches, because these ones have to touch their neighbors on every edge with no
     * gap. A hover tint overlays the whole cell too. If {@code col} is the current selection, a
     * 2px frame is drawn *inside* the cell's own bounds instead of an outer ring around it, for
     * the same reason: an outer ring would draw over the neighboring swatch, and there is no gap
     * on either side to draw it into. That frame is {@code UiPalette.MODAL_BORDER} - the same
     * fixed color the bars' own outer frame uses, and the same one the "active family, not the
     * exact selection" mark on the family bar uses - so a selection highlight is always this one
     * color, on either bar, never white on one and something else on the other.
     */
    private void drawColorSwatch(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int btnX, int btnY, int w, int h, int col) {
        graphics.fill(btnX, btnY, btnX + w, btnY + h, col);

        boolean isHovered = mouseX >= btnX && mouseY >= btnY && mouseX < btnX + w && mouseY < btnY + h;
        if (isHovered) {
            graphics.fill(btnX, btnY, btnX + w, btnY + h, UiPalette.SWATCH_HOVER);
        }

        if (this.selectedColor == col) {
            graphics.fill(btnX, btnY, btnX + w, btnY + 2, UiPalette.MODAL_BORDER);
            graphics.fill(btnX, btnY + h - 2, btnX + w, btnY + h, UiPalette.MODAL_BORDER);
            graphics.fill(btnX, btnY, btnX + 2, btnY + h, UiPalette.MODAL_BORDER);
            graphics.fill(btnX + w - 2, btnY, btnX + w, btnY + h, UiPalette.MODAL_BORDER);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == ClientCompat.KEY_ENTER || event.key() == ClientCompat.KEY_KP_ENTER) {
            saveWaypoint();
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Resolves the name that would actually be saved right now: the typed value, or the same fallback saveWaypoint() uses when it's empty. */
    private String resolveWaypointName() {
        String name = this.nameField.getValue();
        if (name.isEmpty()) {
            name = this.editingWaypoint != null ? this.editingWaypoint.getName() : I18nHelper.getComponent("create.default_name", String.valueOf(WaypointRenderer.waypoints.size() + 1)).getString();
        }
        return name;
    }

    private void saveWaypoint() {
        String name = resolveWaypointName();

        double x = originalPos.getX();
        double y = originalPos.getY();
        double z = originalPos.getZ();

        try {
            x = Double.parseDouble(this.xField.getValue());
        } catch (NumberFormatException ignored) {}
        try {
            y = Double.parseDouble(this.yField.getValue());
        } catch (NumberFormatException ignored) {}
        try {
            z = Double.parseDouble(this.zField.getValue());
        } catch (NumberFormatException ignored) {}

        BlockPos newPos = BlockPos.containing(x, y, z);
        String dimension = this.editingWaypoint != null ? this.editingWaypoint.getDimension() : "minecraft:overworld";
        if (this.editingWaypoint == null && this.minecraft.level != null) {
            dimension = this.minecraft.level.dimension().identifier().toString();
        }

        if (this.editingWaypoint != null) {
            this.editingWaypoint.setName(name);
            this.editingWaypoint.setPos(newPos);
            this.editingWaypoint.setColor(this.selectedColor);
            this.editingWaypoint.setDimension(dimension);
            this.editingWaypoint.setShared(this.dimShared);
            // A manual edit turns a temporary death waypoint into a regular, permanent one
            // so it stops being auto-deleted on arrival (avoids the classic "renamed
            // deathpoint still gets deleted" bug seen in other waypoint mods). Fast Ping's flag is
            // cleared the same way: once edited, it's a waypoint the player shaped on purpose, not
            // a throwaway ping, so it stops counting toward Ping # numbering.
            this.editingWaypoint.setDeath(false);
            this.editingWaypoint.setFastPing(false);

            if (this.minecraft.player != null) {
                // Gray is set as the component's own style instead of being left to the §7 in the
                // translation. A translated string is split into one part per placeholder and every
                // part is rendered starting from the component's style, so a § code stops applying
                // at the next %s rather than running to the end of the line.
                ClientCompat.setOverlayMessage(this.minecraft, I18nHelper.getComponent(
                    "create.feedback_edited", name, newPos.getX(), newPos.getY(), newPos.getZ()
                ).withStyle(ChatFormatting.GRAY), true);
            }
        } else {
            WaypointRenderer.waypoints.add(new Waypoint(name, newPos, this.selectedColor, dimension, this.dimShared));

            if (this.minecraft.player != null) {
                // Same reasoning as the edited-feedback message above.
                ClientCompat.setOverlayMessage(this.minecraft, I18nHelper.getComponent(
                    "create.feedback_created", name, newPos.getX(), newPos.getY(), newPos.getZ()
                ).withStyle(ChatFormatting.GRAY), true);
            }
        }

        WaypointRenderer.saveToFile();

        if (ModKeyBindings.displayMode == WaypointDisplayMode.DISABLED) {
            ModKeyBindings.displayMode = WaypointDisplayMode.WORLD_MARKERS;
            if (ModConfig.get().visibility.rememberOnExit) {
                ModConfig.get().visibility.lastVisible = true;
                ModConfig.save();
            }
        }
    }
}

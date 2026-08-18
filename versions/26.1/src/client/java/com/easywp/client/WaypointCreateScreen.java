package com.easywp.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.Random;

/**
 * Centered modal screen for creating or editing waypoints.
 */
public class WaypointCreateScreen extends Screen {
    /** Modal width. Sized to fit the 15-wide color grid, the widest row in the single centered column. */
    private static final int FORM_WIDTH = 270;
    /**
     * Marker height in the preview, sized to match the real in-game ratio between the marker and its
     * label text rather than an arbitrary GUI size. In WaypointRenderer, labelSize always equals
     * markerSize (MARKER_PROJECTION_DIST == LABEL_PROJECTION_DIST), and the label's world-space text
     * height works out to {@code 9 * (0.035 * labelSize / 0.7) = 0.45 * markerSize}. Our label is
     * drawn at the standard unscaled 9px GUI line height, so the marker that keeps the same ratio is
     * {@code 9 / 0.45 = 20} px tall — this is that fixed point, not a look-good guess.
     */
    private static final float PREVIEW_MARKER_H = 20.0f;

    private EditBox nameField;
    private EditBox xField;
    private EditBox yField;
    private EditBox zField;
    private int selectedColor = 0xFF00FF00;
    private final BlockPos originalPos;
    private final Waypoint editingWaypoint;
    private boolean dimShared = false;

    /**
     * 15 columns (grayscale, then 14 hues in spectrum order) x 4 rows (vivid / light / deep / dark
     * shade of each hue), so scanning either direction lands on similar colors: down a column for
     * shades of the same hue, across a row for neighboring hues at the same shade.
     */
    private static final int[][] COLOR_GRID = {
        // Row 1: Vivid — bright, saturated tones across the spectrum
        { 0xFFFFFFFF, 0xFFFF3B30, 0xFFFF9500, 0xFFFFC107, 0xFFFFCC00, 0xFFAEEA00, 0xFF28CD41, 0xFF00C7BE, 0xFF00E5FF, 0xFF29B6F6, 0xFF007AFF, 0xFF5856D6, 0xFF7C4DFF, 0xFFAF52DE, 0xFFFF2D92 },
        // Row 2: Light — pastel tints of the same hues
        { 0xFFC0C0C0, 0xFFFF8A80, 0xFFFFB74D, 0xFFFFE082, 0xFFFFF176, 0xFFDCE775, 0xFF81C784, 0xFF4DB6AC, 0xFF4DD0E1, 0xFF81D4FA, 0xFF64B5F6, 0xFF7986CB, 0xFFB39DDB, 0xFFBA68C8, 0xFFF48FB1 },
        // Row 3: Deep — saturated dark shades of the same hues
        { 0xFF4A4A4A, 0xFFB71C1C, 0xFFE65100, 0xFFFF8F00, 0xFFC7A600, 0xFF7CB342, 0xFF1B5E20, 0xFF004D40, 0xFF006064, 0xFF0277BD, 0xFF0D47A1, 0xFF1A237E, 0xFF4A148C, 0xFF6A1B9A, 0xFFAD1457 },
        // Row 4: Dark — near-black shades of the same hues
        { 0xFF000000, 0xFF4A0000, 0xFF7A3C00, 0xFF7A4F00, 0xFF5C4B00, 0xFF33691E, 0xFF0D3010, 0xFF00251A, 0xFF00363A, 0xFF01315C, 0xFF052863, 0xFF0B1247, 0xFF260A4A, 0xFF380E52, 0xFF5C0B2C }
    };

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

        Random rand = new Random();
        int r = rand.nextInt(COLOR_GRID.length);
        int c = rand.nextInt(COLOR_GRID[r].length);
        this.selectedColor = COLOR_GRID[r][c];
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
            this.nameField.setValue("WAYPOINT #" + (WaypointRenderer.waypoints.size() + 1));
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

        int cols = COLOR_GRID[0].length;
        int stepX = 14;
        int gridW = cols * stepX;
        int startX = centerX - gridW / 2;
        int startY = centerY + 19;

        for (int r = 0; r < COLOR_GRID.length; r++) {
            for (int c = 0; c < cols; c++) {
                final int col = COLOR_GRID[r][c];
                int btnX = startX + c * stepX;
                int btnY = startY + r * 14;
                this.addRenderableWidget(
                    new ColorSampleButton(btnX, btnY, 13, 13, btn -> {
                        this.selectedColor = col;
                    })
                );
            }
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
                this.minecraft.setScreen(new WaypointListScreen(this));
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

        RenderPipeline pipeline = RenderPipelines.GUI_TEXTURED;

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

        // Batch draw color palette grid samples directly
        int cols = COLOR_GRID[0].length;
        int stepX = 14;
        int gridW = cols * stepX;
        int startX = centerX - gridW / 2;
        int startY = centerY + 19;

        for (int r = 0; r < COLOR_GRID.length; r++) {
            for (int c = 0; c < cols; c++) {
                int col = COLOR_GRID[r][c];
                int btnX = startX + c * stepX;
                int btnY = startY + r * 14;

                boolean isHovered = mouseX >= btnX && mouseY >= btnY && mouseX < btnX + 13 && mouseY < btnY + 13;
                if (isHovered) {
                    graphics.fill(btnX, btnY, btnX + 13, btnY + 13, UiPalette.SWATCH_HOVER);
                }

                // Centered color sample dot
                graphics.fill(btnX + 2, btnY + 2, btnX + 11, btnY + 11, col);

                // Selection outline
                if (this.selectedColor == col) {
                    graphics.fill(btnX, btnY, btnX + 13, btnY + 13, 0xFFFFFFFF);
                    graphics.fill(btnX + 1, btnY + 1, btnX + 12, btnY + 12, UiPalette.MODAL_BG);
                    graphics.fill(btnX + 2, btnY + 2, btnX + 11, btnY + 11, col);
                }
            }
        }

        Icons.drawPortalIcon(graphics, pipeline, shareIconX, centerY - 82, this.dimShared, true);

        graphics.blit(pipeline, Icons.CANCEL, centerX - 116, centerY + 86, 0.0f, 0.0f, 18, 18, 18, 18, UiPalette.ICON_DESTRUCTIVE);
        graphics.blit(pipeline, Icons.LIST, centerX + 98, centerY + 86, 0.0f, 0.0f, 18, 18, 18, 18, UiPalette.ICON_IDLE);

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
        graphics.blit(pipeline, WaypointRenderTypes.MARKER_TEXTURE, 0, 0, 0.0f, 0.0f, 5, 7, 5, 7, markerTint);
        graphics.pose().popMatrix();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
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
            name = this.editingWaypoint != null ? this.editingWaypoint.getName() : "WAYPOINT #" + (WaypointRenderer.waypoints.size() + 1);
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
            // deathpoint still gets deleted" bug seen in other waypoint mods).
            this.editingWaypoint.setDeath(false);

            if (this.minecraft.player != null) {
                this.minecraft.gui.setOverlayMessage(I18nHelper.getComponent(
                    "create.feedback_edited", name, newPos.getX(), newPos.getY(), newPos.getZ()
                ), true);
            }
        } else {
            WaypointRenderer.waypoints.add(new Waypoint(name, newPos, this.selectedColor, dimension, this.dimShared));

            if (this.minecraft.player != null) {
                this.minecraft.gui.setOverlayMessage(I18nHelper.getComponent(
                    "create.feedback_created", name, newPos.getX(), newPos.getY(), newPos.getZ()
                ), true);
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

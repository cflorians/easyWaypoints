package com.easywp.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

/**
 * Centered modal screen for creating or editing waypoints in 1.21.x (Yarn mappings).
 */
public class WaypointCreateScreen extends Screen {
    private TextFieldWidget nameField;
    private TextFieldWidget xField;
    private TextFieldWidget yField;
    private TextFieldWidget zField;
    private int selectedColor = 0xFF00FF00;
    private final BlockPos originalPos;
    private final Waypoint editingWaypoint;
    private boolean dimShared = false;

    private static final Identifier ICON_CANCEL = Identifier.of("easywp", "textures/gui/cancelbutton.png");
    private static final Identifier ICON_LIST = Identifier.of("easywp", "textures/gui/listbutton.png");
    private static final Identifier ICON_DIM_ON = Identifier.of("easywp", "textures/gui/sharedimentionon.png");
    private static final Identifier ICON_DIM_OFF = Identifier.of("easywp", "textures/gui/sharedimentionoff.png");

    private static final int[][] COLOR_GRID = {
        { 0xFFFFFFFF, 0xFFE0E0E0, 0xFFFF2D55, 0xFFFF9500, 0xFFFFCC00, 0xFF73E600, 0xFF28CD41, 0xFF00C7BE, 0xFF59ADC4, 0xFF30B0C7, 0xFF007AFF, 0xFF5856D6, 0xFFAF52DE, 0xFFFF2D92 },
        { 0xFF8E8E93, 0xFFB8C0D0, 0xFFE57373, 0xFFFFB74D, 0xFFFFF176, 0xFFAED581, 0xFF81C784, 0xFF4DB6AC, 0xFF4DD0E1, 0xFF64B5F6, 0xFF7986CB, 0xFF9575CD, 0xFFBA68C8, 0xFFF06292 },
        { 0xFF555555, 0xFF333333, 0xFFB71C1C, 0xFFE65100, 0xFFFF8F00, 0xFF1B5E20, 0xFF004D40, 0xFF006064, 0xFF0D47A1, 0xFF1A237E, 0xFF4A148C, 0xFF880E4F, 0xFFAD1457, 0xFF4A0000 },
        { 0xFF000000, 0xFF795548, 0xFFA1887F, 0xFF4E342E, 0xFF607D8B, 0xFF37474F, 0xFF455A64, 0xFFB0BEC5, 0xFFD4AF37, 0xFFB87333, 0xFFC0C0C0, 0xFFE6FF00, 0xFF00FF66, 0xFFFF0055 }
    };

    private static class ColorSampleButton extends ButtonWidget {
        public ColorSampleButton(int x, int y, int width, int height, PressAction onPress) {
            super(x, y, width, height, Text.literal(""), onPress, DEFAULT_NARRATION_SUPPLIER);
        }

        @Override
        protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        }
    }

    public WaypointCreateScreen(BlockPos pos) {
        super(I18nHelper.getComponent("create.title.new"));
        this.originalPos = pos != null ? pos : BlockPos.ORIGIN;
        this.editingWaypoint = null;
        
        try {
            Random rand = new Random();
            int r = rand.nextInt(COLOR_GRID.length);
            int c = rand.nextInt(COLOR_GRID[r].length);
            this.selectedColor = COLOR_GRID[r][c];
        } catch (Throwable t) {
            this.selectedColor = 0xFF00FF00;
        }
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

        this.nameField = new TextFieldWidget(this.textRenderer, centerX - 118, centerY - 60, 212, 18, I18nHelper.getComponent("create.name_label"));
        if (this.editingWaypoint != null) {
            this.nameField.setText(this.editingWaypoint.getName());
        } else {
            this.nameField.setText("Waypoint #" + (WaypointRenderer.waypoints.size() + 1));
        }
        this.addDrawableChild(this.nameField);

        ModernButton shareToggleBtn = ModernButton.modernBuilder(Text.literal(""), btn -> {
            this.dimShared = !this.dimShared;
        }).pos(centerX + 100, centerY - 60).size(18, 18).build();

        String currentDimension = "minecraft:overworld";
        if (this.client.world != null) {
            currentDimension = this.client.world.getRegistryKey().getValue().toString();
        }
        if (this.editingWaypoint != null && this.editingWaypoint.getDimension() != null) {
            currentDimension = this.editingWaypoint.getDimension();
        }
        boolean isConvertibleDim = currentDimension.equals("minecraft:overworld") || currentDimension.equals("minecraft:the_nether");
        shareToggleBtn.active = isConvertibleDim;
        this.addDrawableChild(shareToggleBtn);

        this.xField = new TextFieldWidget(this.textRenderer, centerX - 118, centerY - 24, 68, 18, Text.literal("X"));
        this.xField.setText(String.valueOf(originalPos.getX()));
        this.addDrawableChild(this.xField);

        this.yField = new TextFieldWidget(this.textRenderer, centerX - 34, centerY - 24, 68, 18, Text.literal("Y"));
        this.yField.setText(String.valueOf(originalPos.getY()));
        this.addDrawableChild(this.yField);

        this.zField = new TextFieldWidget(this.textRenderer, centerX + 50, centerY - 24, 68, 18, Text.literal("Z"));
        this.zField.setText(String.valueOf(originalPos.getZ()));
        this.addDrawableChild(this.zField);

        int cols = COLOR_GRID[0].length;
        int stepX = 14;
        int gridW = cols * stepX;
        int startX = centerX - gridW / 2;
        int startY = centerY + 2;

        for (int r = 0; r < COLOR_GRID.length; r++) {
            for (int c = 0; c < cols; c++) {
                final int col = COLOR_GRID[r][c];
                int btnX = startX + c * stepX;
                int btnY = startY + r * 14;
                this.addDrawableChild(
                    new ColorSampleButton(btnX, btnY, 13, 13, btn -> {
                        this.selectedColor = col;
                    })
                );
            }
        }

        this.addDrawableChild(
            ModernButton.modernBuilder(Text.literal(""), btn -> {
                this.close();
            }).pos(centerX - 118, centerY + 66).size(22, 20).build()
        );

        this.addDrawableChild(
            ModernButton.modernBuilder(I18nHelper.getComponent("create.save"), btn -> {
                saveWaypoint();
                this.close();
            }).pos(centerX - 90, centerY + 66).size(180, 20).build()
        );

        this.addDrawableChild(
            ModernButton.modernBuilder(Text.literal(""), btn -> {
                this.client.setScreen(new WaypointListScreen(this));
            }).pos(centerX + 96, centerY + 66).size(22, 20).build()
        );
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        int bgW = 270;
        int bgH = 198;
        int minX = centerX - bgW / 2;
        int minY = centerY - bgH / 2;

        // Modal background card (ARGB 0xD010141E)
        context.fill(minX, minY, minX + bgW, minY + bgH, 0xD010141E);
        // Header title background bar (ARGB 0xE0181E30)
        context.fill(minX, minY, minX + bgW, minY + 20, 0xE0181E30);
        
        // Modal border lines
        context.fill(minX, minY, minX + bgW, minY + 1, 0xFF3D4966);
        context.fill(minX, minY + bgH - 1, minX + bgW, minY + bgH, 0xFF3D4966);
        context.fill(minX, minY, minX + 1, minY + bgH, 0xFF3D4966);
        context.fill(minX + bgW - 1, minY, minX + bgW, minY + bgH, 0xFF3D4966);
        context.fill(minX, minY + 20, minX + bgW, minY + 21, 0xFF3D4966);

        Text titleComp = this.editingWaypoint != null ? I18nHelper.getComponent("create.title.edit") : I18nHelper.getComponent("create.title.new");
        context.drawCenteredTextWithShadow(this.textRenderer, titleComp, centerX, centerY - 93, 0xFFFFFFFF);

        context.drawText(this.textRenderer, I18nHelper.getComponent("create.name_label"), centerX - 118, centerY - 72, 0xFFA0A0A0, false);
        context.drawText(this.textRenderer, I18nHelper.getComponent("create.coordinates_label"), centerX - 118, centerY - 36, 0xFFA0A0A0, false);

        super.render(context, mouseX, mouseY, delta);

        // Batch draw color palette grid samples directly
        int cols = COLOR_GRID[0].length;
        int stepX = 14;
        int gridW = cols * stepX;
        int startX = centerX - gridW / 2;
        int startY = centerY + 2;

        for (int r = 0; r < COLOR_GRID.length; r++) {
            for (int c = 0; c < cols; c++) {
                int col = COLOR_GRID[r][c];
                int btnX = startX + c * stepX;
                int btnY = startY + r * 14;

                boolean isHovered = mouseX >= btnX && mouseY >= btnY && mouseX < btnX + 13 && mouseY < btnY + 13;
                if (isHovered) {
                    context.fill(btnX, btnY, btnX + 13, btnY + 13, 0x603D4966);
                }

                // Centered color sample dot
                context.fill(btnX + 2, btnY + 2, btnX + 11, btnY + 11, col);

                // Selection outline
                if (this.selectedColor == col) {
                    context.fill(btnX, btnY, btnX + 13, btnY + 13, 0xFFFFFFFF);
                    context.fill(btnX + 1, btnY + 1, btnX + 12, btnY + 12, 0xD010141E);
                    context.fill(btnX + 2, btnY + 2, btnX + 11, btnY + 11, col);
                }
            }
        }

        Identifier shareDimIcon = this.dimShared ? ICON_DIM_ON : ICON_DIM_OFF;
        context.drawTexture(RenderLayer::getGuiTextured, shareDimIcon, centerX + 100, centerY - 60, 0.0f, 0.0f, 18, 18, 18, 18);

        context.drawTexture(RenderLayer::getGuiTextured, ICON_CANCEL, centerX - 116, centerY + 67, 0.0f, 0.0f, 18, 18, 18, 18);
        context.drawTexture(RenderLayer::getGuiTextured, ICON_LIST, centerX + 98, centerY + 67, 0.0f, 0.0f, 18, 18, 18, 18);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            saveWaypoint();
            this.close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private void saveWaypoint() {
        String name = this.nameField.getText();
        if (name.isEmpty()) {
            name = this.editingWaypoint != null ? this.editingWaypoint.getName() : "Waypoint #" + (WaypointRenderer.waypoints.size() + 1);
        }

        double x = originalPos.getX();
        double y = originalPos.getY();
        double z = originalPos.getZ();

        try {
            x = Double.parseDouble(this.xField.getText());
        } catch (NumberFormatException ignored) {}
        try {
            y = Double.parseDouble(this.yField.getText());
        } catch (NumberFormatException ignored) {}
        try {
            z = Double.parseDouble(this.zField.getText());
        } catch (NumberFormatException ignored) {}

        BlockPos newPos = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        String dimension = this.editingWaypoint != null ? this.editingWaypoint.getDimension() : "minecraft:overworld";
        if (this.editingWaypoint == null && this.client.world != null) {
            dimension = this.client.world.getRegistryKey().getValue().toString();
        }

        if (this.editingWaypoint != null) {
            this.editingWaypoint.setName(name);
            this.editingWaypoint.setPos(newPos);
            this.editingWaypoint.setColor(this.selectedColor);
            this.editingWaypoint.setDimension(dimension);
            this.editingWaypoint.setShared(this.dimShared);

            if (this.client.player != null) {
                this.client.inGameHud.setOverlayMessage(I18nHelper.getComponent(
                    "create.feedback_edited", name, newPos.getX(), newPos.getY(), newPos.getZ()
                ), true);
            }
        } else {
            WaypointRenderer.waypoints.add(new Waypoint(name, newPos, this.selectedColor, dimension, this.dimShared));

            if (this.client.player != null) {
                this.client.inGameHud.setOverlayMessage(I18nHelper.getComponent(
                    "create.feedback_created", name, newPos.getX(), newPos.getY(), newPos.getZ()
                ), true);
            }
        }

        WaypointRenderer.saveToFile();

        if (ModKeyBindings.displayMode == WaypointDisplayMode.DISABLED) {
            ModKeyBindings.displayMode = WaypointDisplayMode.WORLD_MARKERS;
        }
    }
}

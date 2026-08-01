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
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

public class WaypointCreateScreen extends Screen {
    private EditBox nameField;
    private EditBox xField;
    private EditBox yField;
    private EditBox zField;
    private int selectedColor = 0xFF00FF00;
    private final BlockPos originalPos;
    private final Waypoint editingWaypoint;
    private boolean dimShared = false;

    private static final Identifier LOCATOR_BAR_DOT_0 = Identifier.fromNamespaceAndPath("minecraft", "hud/locator_bar_dot/default_0");
    private static final Identifier ICON_CANCEL = Identifier.fromNamespaceAndPath("easywp", "textures/gui/cancelButton.png");
    private static final Identifier ICON_LIST = Identifier.fromNamespaceAndPath("easywp", "textures/gui/listButton.png");
    private static final Identifier ICON_DIM_ON = Identifier.fromNamespaceAndPath("easywp", "textures/gui/shareDimentionOn.png");
    private static final Identifier ICON_DIM_OFF = Identifier.fromNamespaceAndPath("easywp", "textures/gui/shareDimentionOff.png");
    private static final Identifier BG_CREATE_SCREEN = Identifier.fromNamespaceAndPath("easywp", "textures/gui/createScreen.png");

    private static final int[][] COLOR_GRID = {
        { 0xFFFFFFFF, 0xFF00FF00, 0xFF00FFFF, 0xFF0088FF, 0xFF0000FF, 0xFF8800FF, 0xFFFF00FF, 0xFFFF007F, 0xFFFF0000, 0xFFFF6A00, 0xFFFFD800, 0xFF88FF00 },
        { 0xFF555555, 0xFF00B800, 0xFF00B8B8, 0xFF0062B8, 0xFF0000B8, 0xFF6200B8, 0xFFB800B8, 0xFFB8005F, 0xFFB80000, 0xFFB84C00, 0xFFB89C00, 0xFF62B800 },
        { 0xFF000000, 0xFF007A00, 0xFF007A7A, 0xFF00417A, 0xFF00007A, 0xFF41007A, 0xFF7A007A, 0xFF7A003F, 0xFF7A0000, 0xFF7A3300, 0xFF7A6800, 0xFF417A00 }
    };

    public WaypointCreateScreen(BlockPos pos) {
        super(I18nHelper.getComponent("create.title.new"));
        this.originalPos = pos != null ? pos : BlockPos.ZERO;
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

        this.nameField = new EditBox(this.font, centerX - 100, centerY - 55, 178, 20, I18nHelper.getComponent("create.name_label"));
        if (this.editingWaypoint != null) {
            this.nameField.setValue(this.editingWaypoint.getName());
        } else {
            this.nameField.setValue("Waypoint #" + (WaypointRenderer.waypoints.size() + 1));
        }
        this.addRenderableWidget(this.nameField);

        Button shareToggleBtn = Button.builder(Component.literal(""), btn -> {
            this.dimShared = !this.dimShared;
        }).pos(centerX + 82, centerY - 54).size(18, 18).build();

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

        this.xField = new EditBox(this.font, centerX - 100, centerY - 15, 60, 20, Component.literal("X"));
        this.xField.setValue(String.valueOf(originalPos.getX()));
        this.addRenderableWidget(this.xField);

        this.yField = new EditBox(this.font, centerX - 30, centerY - 15, 60, 20, Component.literal("Y"));
        this.yField.setValue(String.valueOf(originalPos.getY()));
        this.addRenderableWidget(this.yField);

        this.zField = new EditBox(this.font, centerX + 40, centerY - 15, 60, 20, Component.literal("Z"));
        this.zField.setValue(String.valueOf(originalPos.getZ()));
        this.addRenderableWidget(this.zField);

        int startX = centerX - 88;
        int startY = centerY + 10;

        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 12; c++) {
                final int col = COLOR_GRID[r][c];
                int btnX = startX + c * 15;
                int btnY = startY + r * 13;
                this.addRenderableWidget(
                    Button.builder(Component.literal(""), btn -> {
                        this.selectedColor = col;
                    }).pos(btnX, btnY).size(12, 12).build()
                );
            }
        }

        this.addRenderableWidget(
            Button.builder(Component.literal(""), btn -> {
                this.onClose();
            }).pos(centerX - 100, centerY + 53).size(18, 18).build()
        );

        this.addRenderableWidget(
            Button.builder(I18nHelper.getComponent("create.save"), btn -> {
                saveWaypoint();
                this.onClose();
            }).pos(centerX - 75, centerY + 53).size(150, 20).build()
        );

        this.addRenderableWidget(
            Button.builder(Component.literal(""), btn -> {
                this.minecraft.setScreen(new WaypointListScreen(this));
            }).pos(centerX + 82, centerY + 53).size(18, 18).build()
        );
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        int bgW = 224;
        int bgH = 160;
        int minX = centerX - bgW / 2;
        int minY = centerY - bgH / 2;

        RenderPipeline pipeline = RenderPipelines.GUI_TEXTURED;
        graphics.blit(pipeline, BG_CREATE_SCREEN, minX, minY, 0.0f, 0.0f, bgW, bgH, bgW, bgH);

        Component titleComp = this.editingWaypoint != null ? I18nHelper.getComponent("create.title.edit") : I18nHelper.getComponent("create.title.new");
        graphics.centeredText(this.font, titleComp, centerX, centerY - 73, 0xFFFFFFFF);

        graphics.text(this.font, I18nHelper.getComponent("create.name_label"), centerX - 100, centerY - 65, 0xFFA0A0A0);
        graphics.text(this.font, I18nHelper.getComponent("create.coordinates_label"), centerX - 100, centerY - 27, 0xFFA0A0A0);

        super.extractRenderState(graphics, mouseX, mouseY, a);

        Identifier shareDimIcon = this.dimShared ? ICON_DIM_ON : ICON_DIM_OFF;
        graphics.blit(pipeline, shareDimIcon, centerX + 82, centerY - 54, 0.0f, 0.0f, 18, 18, 18, 18);

        int startX = centerX - 88;
        int startY = centerY + 10;

        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 12; c++) {
                int col = COLOR_GRID[r][c];
                int btnX = startX + c * 15;
                int btnY = startY + r * 13;

                graphics.blitSprite(pipeline, LOCATOR_BAR_DOT_0, btnX + 1, btnY + 1, 10, 10, col);

                if (col == this.selectedColor) {
                    graphics.fill(btnX, btnY, btnX + 12, btnY + 1, 0xFFFFFFFF);
                    graphics.fill(btnX, btnY + 11, btnX + 12, btnY + 12, 0xFFFFFFFF);
                    graphics.fill(btnX, btnY, btnX + 1, btnY + 12, 0xFFFFFFFF);
                    graphics.fill(btnX + 11, btnY, btnX + 12, btnY + 12, 0xFFFFFFFF);
                }
            }
        }

        graphics.blit(pipeline, ICON_CANCEL, centerX - 100, centerY + 53, 0.0f, 0.0f, 18, 18, 18, 18);
        graphics.blit(pipeline, ICON_LIST, centerX + 82, centerY + 53, 0.0f, 0.0f, 18, 18, 18, 18);
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

    private void saveWaypoint() {
        String name = this.nameField.getValue();
        if (name.isEmpty()) {
            name = this.editingWaypoint != null ? this.editingWaypoint.getName() : "Waypoint #" + (WaypointRenderer.waypoints.size() + 1);
        }

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
        }
    }
}

package com.easywp.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;

public class WaypointCreateScreen extends Screen {
    private EditBox nameField;
    private EditBox xField;
    private EditBox yField;
    private EditBox zField;
    private int selectedColor = 0xFF00FF00; // Default to green
    private final BlockPos originalPos;
    private final Waypoint editingWaypoint;

    // 12x3 color palette grid
    private static final int[][] COLOR_GRID = {
        { 0xFFFFFFFF, 0xFF00FF00, 0xFF00FFFF, 0xFF0088FF, 0xFF0000FF, 0xFF8800FF, 0xFFFF00FF, 0xFFFF007F, 0xFFFF0000, 0xFFFF6A00, 0xFFFFD800, 0xFF88FF00 },
        { 0xFFAAAAAA, 0xFF00B800, 0xFF00B8B8, 0xFF0062B8, 0xFF0000B8, 0xFF6200B8, 0xFFB800B8, 0xFFB8005F, 0xFFB80000, 0xFFB84C00, 0xFFB89C00, 0xFF62B800 },
        { 0xFF555555, 0xFF007A00, 0xFF007A7A, 0xFF00417A, 0xFF00007A, 0xFF41007A, 0xFF7A007A, 0xFF7A003F, 0xFF7A0000, 0xFF7A3300, 0xFF7A6800, 0xFF417A00 }
    };

    public WaypointCreateScreen(BlockPos pos) {
        super(Component.literal("Crear Waypoint"));
        this.originalPos = pos != null ? pos : BlockPos.ZERO;
        this.editingWaypoint = null;
    }

    public WaypointCreateScreen(Waypoint waypointToEdit) {
        super(Component.literal("Editar Waypoint"));
        this.originalPos = waypointToEdit.getPos();
        this.editingWaypoint = waypointToEdit;
        this.selectedColor = waypointToEdit.getColor();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.nameField = new EditBox(this.font, centerX - 100, centerY - 60, 200, 20, Component.literal("Nombre"));
        if (this.editingWaypoint != null) {
            this.nameField.setValue(this.editingWaypoint.getName());
        } else {
            this.nameField.setValue("Waypoint #" + (WaypointRenderer.waypoints.size() + 1));
        }
        this.addRenderableWidget(this.nameField);

        this.xField = new EditBox(this.font, centerX - 100, centerY - 20, 60, 20, Component.literal("X"));
        this.xField.setValue(String.valueOf(originalPos.getX()));
        this.addRenderableWidget(this.xField);

        this.yField = new EditBox(this.font, centerX - 30, centerY - 20, 60, 20, Component.literal("Y"));
        this.yField.setValue(String.valueOf(originalPos.getY()));
        this.addRenderableWidget(this.yField);

        this.zField = new EditBox(this.font, centerX + 40, centerY - 20, 60, 20, Component.literal("Z"));
        this.zField.setValue(String.valueOf(originalPos.getZ()));
        this.addRenderableWidget(this.zField);

        int startX = centerX - 88;
        int startY = centerY + 12;

        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 12; c++) {
                final int col = COLOR_GRID[r][c];
                int btnX = startX + c * 15;
                int btnY = startY + r * 15;
                this.addRenderableWidget(
                    Button.builder(Component.literal(""), btn -> {
                        this.selectedColor = col;
                    }).pos(btnX, btnY).size(12, 12).build()
                );
            }
        }

        this.addRenderableWidget(
            Button.builder(Component.literal("Guardar"), btn -> {
                saveWaypoint();
                this.onClose();
            }).pos(centerX - 100, centerY + 60).size(62, 20).build()
        );

        this.addRenderableWidget(
            Button.builder(Component.literal("Lista"), btn -> {
                this.minecraft.setScreen(new WaypointListScreen(this));
            }).pos(centerX - 31, centerY + 60).size(62, 20).build()
        );

        this.addRenderableWidget(
            Button.builder(Component.literal("Cancelar"), btn -> {
                this.onClose();
            }).pos(centerX + 38, centerY + 60).size(62, 20).build()
        );
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // Menu background box
        graphics.fill(centerX - 110, centerY - 80, centerX + 110, centerY + 90, 0x90000000);

        // Menu title
        String titleText = this.editingWaypoint != null ? "Editar Waypoint" : "Crear Waypoint";
        graphics.centeredText(this.font, Component.literal(titleText), centerX, centerY - 75, 0xFFFFFFFF);

        // Labels
        graphics.text(this.font, Component.literal("Nombre:"), centerX - 100, centerY - 70, 0xFFA0A0A0);
        graphics.text(this.font, Component.literal("Coordenadas (X / Y / Z):"), centerX - 100, centerY - 32, 0xFFA0A0A0);

        super.extractRenderState(graphics, mouseX, mouseY, a);

        int startX = centerX - 88;
        int startY = centerY + 12;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 12; c++) {
                int col = COLOR_GRID[r][c];
                int btnX = startX + c * 15;
                int btnY = startY + r * 15;
                
                // Color filling
                graphics.fill(btnX + 1, btnY + 1, btnX + 11, btnY + 11, col);
                
                // White selection border
                if (col == this.selectedColor) {
                    graphics.fill(btnX, btnY, btnX + 12, btnY + 1, 0xFFFFFFFF);
                    graphics.fill(btnX, btnY + 11, btnX + 12, btnY + 12, 0xFFFFFFFF);
                    graphics.fill(btnX, btnY, btnX + 1, btnY + 12, 0xFFFFFFFF);
                    graphics.fill(btnX + 11, btnY, btnX + 12, btnY + 12, 0xFFFFFFFF);
                }
            }
        }
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
        } catch (NumberFormatException e) {}
        try {
            y = Double.parseDouble(this.yField.getValue());
        } catch (NumberFormatException e) {}
        try {
            z = Double.parseDouble(this.zField.getValue());
        } catch (NumberFormatException e) {}

        BlockPos newPos = BlockPos.containing(x, y, z);
        String dimension = "minecraft:overworld";
        if (this.minecraft.level != null) {
            dimension = this.minecraft.level.dimension().identifier().toString();
        }

        if (this.editingWaypoint != null) {
            this.editingWaypoint.setName(name);
            this.editingWaypoint.setPos(newPos);
            this.editingWaypoint.setColor(this.selectedColor);
            this.editingWaypoint.setDimension(dimension);

            if (this.minecraft.player != null) {
                this.minecraft.gui.setOverlayMessage(Component.literal(
                    "§a¡Waypoint editado! §7(" + name + ") en X: " + newPos.getX() + " Y: " + newPos.getY() + " Z: " + newPos.getZ()
                ), true);
            }
        } else {
            WaypointRenderer.waypoints.add(new Waypoint(name, newPos, this.selectedColor, dimension));

            if (this.minecraft.player != null) {
                this.minecraft.gui.setOverlayMessage(Component.literal(
                    "§a¡Waypoint creado! §7(" + name + ") en X: " + newPos.getX() + " Y: " + newPos.getY() + " Z: " + newPos.getZ()
                ), true);
            }
        }

        // Enable waypoints visibility on save
        ModKeyBindings.showWaypoints = true;
    }
}

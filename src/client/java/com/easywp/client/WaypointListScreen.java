package com.easywp.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;

import java.util.List;

public class WaypointListScreen extends Screen {
    private final Screen parentScreen;
    private int currentPage = 0;
    private static final int ITEMS_PER_PAGE = 5;

    public WaypointListScreen(Screen parentScreen) {
        super(Component.literal("Lista de Waypoints"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        String currentDimension = this.minecraft.level != null ? this.minecraft.level.dimension().identifier().toString() : "minecraft:overworld";
        List<Waypoint> waypoints = WaypointRenderer.waypoints.stream()
            .filter(wp -> wp.getDimension() == null || wp.getDimension().equals(currentDimension))
            .collect(java.util.stream.Collectors.toList());
        int totalWaypoints = waypoints.size();
        int maxPages = Math.max(1, (int) Math.ceil((double) totalWaypoints / ITEMS_PER_PAGE));

        // Clamp current page index if size changed
        if (currentPage >= maxPages) {
            currentPage = maxPages - 1;
        }
        if (currentPage < 0) {
            currentPage = 0;
        }

        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, totalWaypoints);

        for (int i = startIndex; i < endIndex; i++) {
            final int index = i;
            Waypoint wp = waypoints.get(index);
            BlockPos pos = wp.getPos();
            int offsetIndex = i - startIndex;
            int itemY = centerY - 65 + offsetIndex * 24;

            boolean isCreative = this.minecraft.player != null && this.minecraft.player.isCreative();
            Button tpButton = Button.builder(Component.literal("TP"), btn -> {
                if (this.minecraft.player != null && this.minecraft.player.connection != null) {
                    this.minecraft.player.connection.sendCommand(
                        "tp @s " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                    );
                    this.onClose();
                }
            }).pos(centerX + 20, itemY).size(25, 18).build();
            tpButton.active = isCreative;
            this.addRenderableWidget(tpButton);

            this.addRenderableWidget(
                Button.builder(Component.literal("Edit"), btn -> {
                    this.minecraft.setScreen(new WaypointCreateScreen(wp));
                }).pos(centerX + 48, itemY).size(36, 18).build()
            );

            this.addRenderableWidget(
                Button.builder(Component.literal("X"), btn -> {
                    WaypointRenderer.waypoints.remove(wp);
                    // Refresh screen widgets
                    this.rebuildWidgets();
                }).pos(centerX + 87, itemY).size(18, 18).build()
            );
        }

        this.addRenderableWidget(
            Button.builder(Component.literal("<"), btn -> {
                if (currentPage > 0) {
                    currentPage--;
                    this.rebuildWidgets();
                }
            }).pos(centerX - 100, centerY + 62).size(20, 20).build()
        );

        this.addRenderableWidget(
            Button.builder(Component.literal(">"), btn -> {
                if ((currentPage + 1) * ITEMS_PER_PAGE < totalWaypoints) {
                    currentPage++;
                    this.rebuildWidgets();
                }
            }).pos(centerX + 80, centerY + 62).size(20, 20).build()
        );

        this.addRenderableWidget(
            Button.builder(Component.literal("Volver"), btn -> {
                if (parentScreen != null) {
                    this.minecraft.setScreen(parentScreen);
                } else {
                    this.onClose();
                }
            }).pos(centerX - 40, centerY + 62).size(80, 20).build()
        );
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // Dark background box
        graphics.fill(centerX - 110, centerY - 80, centerX + 110, centerY + 90, 0x90000000);

        // Title
        graphics.centeredText(this.font, Component.literal("Lista de Waypoints"), centerX, centerY - 75, 0xFFFFFFFF);

        String currentDimension = this.minecraft.level != null ? this.minecraft.level.dimension().identifier().toString() : "minecraft:overworld";
        List<Waypoint> waypoints = WaypointRenderer.waypoints.stream()
            .filter(wp -> wp.getDimension() == null || wp.getDimension().equals(currentDimension))
            .collect(java.util.stream.Collectors.toList());
        int totalWaypoints = waypoints.size();
        int maxPages = Math.max(1, (int) Math.ceil((double) totalWaypoints / ITEMS_PER_PAGE));
        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, totalWaypoints);

        if (totalWaypoints == 0) {
            graphics.centeredText(this.font, Component.literal("No hay waypoints creados"), centerX, centerY - 10, 0xFF888888);
        } else {
            // Draw list items for current page
            for (int i = startIndex; i < endIndex; i++) {
                Waypoint wp = waypoints.get(i);
                BlockPos pos = wp.getPos();
                int offsetIndex = i - startIndex;
                int itemY = centerY - 65 + offsetIndex * 24;

                // Color indicator
                graphics.fill(centerX - 100, itemY + 5, centerX - 92, itemY + 13, wp.getColor());

                // Name in uppercase
                String nameUpper = wp.getName().toUpperCase();
                // Truncate name if too long
                if (nameUpper.length() > 14) {
                    nameUpper = nameUpper.substring(0, 11) + "...";
                }
                graphics.text(this.font, Component.literal(nameUpper), centerX - 86, itemY + 1, 0xFFFFFFFF);

                // Coordinates text
                String coords = String.format("%d, %d, %d", pos.getX(), pos.getY(), pos.getZ());
                graphics.text(this.font, Component.literal(coords), centerX - 86, itemY + 10, 0xFF888888);
            }
        }

        // Page number text
        String pageText = String.format("%d / %d", currentPage + 1, maxPages);
        graphics.centeredText(this.font, Component.literal(pageText), centerX, centerY + 68, 0xFFA0A0A0);

        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (ModKeyBindings.listWaypointsKey.matches(event)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

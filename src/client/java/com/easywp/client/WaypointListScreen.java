package com.easywp.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.ArrayList;

public class WaypointListScreen extends Screen {
    private final Screen parentScreen;
    private int scrollIndex = 0;
    private static final int ITEMS_PER_PAGE = 5;
    private String selectedDimension = "minecraft:overworld";

    private static final List<String> DIMENSIONS = List.of(
        "minecraft:overworld",
        "minecraft:the_nether",
        "minecraft:the_end"
    );

    private static class WaypointEntry {
        final Waypoint waypoint;
        final BlockPos displayPos;
        final boolean converted;

        WaypointEntry(Waypoint waypoint, BlockPos displayPos, boolean converted) {
            this.waypoint = waypoint;
            this.displayPos = displayPos;
            this.converted = converted;
        }
    }

    public WaypointListScreen(Screen parentScreen) {
        super(I18nHelper.getComponent("menu.title"));
        this.parentScreen = parentScreen;
        
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            this.selectedDimension = mc.level.dimension().identifier().toString();
        } else {
            this.selectedDimension = "minecraft:overworld";
        }
    }

    private List<WaypointEntry> getWaypointsForDimension(String dimension) {
        List<WaypointEntry> list = new ArrayList<>();
        for (Waypoint wp : WaypointRenderer.waypoints) {
            if (wp == null) continue;
            String wpDim = wp.getDimension() != null ? wp.getDimension() : "minecraft:overworld";
            if (wpDim.equals(dimension)) {
                list.add(new WaypointEntry(wp, wp.getPos(), false));
            } else if (wp.isShared()) {
                if (wpDim.equals("minecraft:overworld") && dimension.equals("minecraft:the_nether")) {
                    BlockPos convertedPos = new BlockPos(
                        (int) Math.round(wp.getPos().getX() / 8.0),
                        wp.getPos().getY(),
                        (int) Math.round(wp.getPos().getZ() / 8.0)
                    );
                    list.add(new WaypointEntry(wp, convertedPos, true));
                } else if (wpDim.equals("minecraft:the_nether") && dimension.equals("minecraft:overworld")) {
                    BlockPos convertedPos = new BlockPos(
                        (int) Math.round(wp.getPos().getX() * 8.0),
                        wp.getPos().getY(),
                        (int) Math.round(wp.getPos().getZ() * 8.0)
                    );
                    list.add(new WaypointEntry(wp, convertedPos, true));
                }
            }
        }
        return list;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        List<WaypointEntry> waypoints = getWaypointsForDimension(selectedDimension);
        int totalWaypoints = waypoints.size();

        if (scrollIndex > totalWaypoints - ITEMS_PER_PAGE) {
            scrollIndex = Math.max(0, totalWaypoints - ITEMS_PER_PAGE);
        }
        if (scrollIndex < 0) {
            scrollIndex = 0;
        }

        // Check if there is any active focused waypoint in this dimension to determine dimming/Solo state
        boolean hasAnyFocused = false;
        for (WaypointEntry entry : waypoints) {
            if (entry != null && entry.waypoint != null && entry.waypoint.isFocused()) {
                hasAnyFocused = true;
                break;
            }
        }
        final boolean finalHasAnyFocused = hasAnyFocused;

        int startIndex = scrollIndex;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, totalWaypoints);

        for (int i = startIndex; i < endIndex; i++) {
            final int index = i;
            WaypointEntry entry = waypoints.get(index);
            if (entry == null || entry.waypoint == null) continue;
            Waypoint wp = entry.waypoint;
            BlockPos pos = entry.displayPos;
            int offsetIndex = i - startIndex;
            int itemY = centerY - 65 + offsetIndex * 24;

            // TP button exists only if the player has active cheat/OP permissions to execute "/tp"
            boolean showTP = false;
            if (this.minecraft.player != null && this.minecraft.player.connection != null) {
                try {
                    showTP = this.minecraft.player.connection.getCommands().getRoot().getChild("tp") != null;
                } catch (Throwable t) {
                    showTP = this.minecraft.player.isCreative();
                }
            }
            int buttonX = centerX - 4;
            
            // 1. Focus Button (★) - Solo Mode (Mutual exclusivity)
            String focusText = wp.isFocused() ? "§6★" : "★";
            this.addRenderableWidget(
                Button.builder(Component.literal(focusText), btn -> {
                    boolean targetState = !wp.isFocused();
                    // Clear focus and forceVisible on all other waypoints in the list
                    for (WaypointEntry other : waypoints) {
                        if (other != null && other.waypoint != null) {
                            other.waypoint.setFocused(false);
                            other.waypoint.setForceVisible(false);
                        }
                    }
                    // Apply target focus state
                    wp.setFocused(targetState);
                    WaypointRenderer.saveToFile();
                    Minecraft.getInstance().execute(this::rebuildWidgets);
                }).pos(buttonX, itemY).size(14, 18).build()
            );
            buttonX += 16; // 14 width + 2 spacing

            // 2. Hide Button - Toggle Visibility or Join/Leave Focus Group
            // Dims (slashes the eye) if manually hidden or if another waypoint holds Focus and this is not forceVisible.
            boolean currentlyDimmed = !wp.isVisible() || (finalHasAnyFocused && !wp.isFocused() && !wp.isForceVisible());
            String hideText = currentlyDimmed ? "§c§m👁" : "👁";
            this.addRenderableWidget(
                Button.builder(Component.literal(hideText), btn -> {
                    if (finalHasAnyFocused) {
                        // If in Solo/Focus Mode, toggle forceVisible to allow joining/leaving the active group
                        // without altering the primary focused waypoint's focus state
                        if (!wp.isFocused()) {
                            wp.setForceVisible(!wp.isForceVisible());
                            wp.setVisible(true); // Ensure visible is true if desilencing
                        } else {
                            // Toggling visibility of the primary focused waypoint directly
                            wp.setVisible(!wp.isVisible());
                        }
                    } else {
                        // Regular Mute/Visibility toggle
                        wp.setVisible(!wp.isVisible());
                    }
                    WaypointRenderer.saveToFile();
                    Minecraft.getInstance().execute(this::rebuildWidgets);
                }).pos(buttonX, itemY).size(14, 18).build()
            );
            buttonX += 16; // 14 width + 2 spacing

            // 3. TP Button - Only created and rendered if cheats are enabled
            if (showTP) {
                this.addRenderableWidget(
                    Button.builder(Component.literal("TP"), btn -> {
                        if (this.minecraft.player != null && this.minecraft.player.connection != null) {
                            this.minecraft.player.connection.sendCommand(
                                "tp @s " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                            );
                            this.onClose();
                        }
                    }).pos(buttonX, itemY).size(16, 18).build()
                );
                buttonX += 18; // 16 width + 2 spacing
            }

            // 4. Edit Button (✎)
            this.addRenderableWidget(
                Button.builder(Component.literal("✎"), btn -> {
                    this.minecraft.setScreen(new WaypointCreateScreen(wp));
                }).pos(buttonX, itemY).size(14, 18).build()
            );
            buttonX += 16; // 14 width + 2 spacing

            // 5. Share Button (✉)
            this.addRenderableWidget(
                Button.builder(Component.literal("✉"), btn -> {
                    if (this.minecraft.player != null && this.minecraft.player.connection != null) {
                        String dimName = selectedDimension.equals("minecraft:overworld") ? "Overworld" : 
                                         (selectedDimension.equals("minecraft:the_nether") ? "Nether" : "The End");
                        String msg = String.format("%s: %d %d %d - %s", 
                            wp.getName(), pos.getX(), pos.getY(), pos.getZ(), dimName);
                        this.minecraft.player.connection.sendChat(msg);
                        this.onClose();
                    }
                }).pos(buttonX, itemY).size(14, 18).build()
            );
            buttonX += 16; // 14 width + 2 spacing

            // 6. Dimension Share Toggle Button (⇄)
            Button dimButton = Button.builder(Component.literal("⇄"), btn -> {
                wp.setShared(!wp.isShared());
                WaypointRenderer.saveToFile();
                Minecraft.getInstance().execute(this::rebuildWidgets);
            }).pos(buttonX, itemY).size(14, 18).build();
            dimButton.active = !selectedDimension.equals("minecraft:the_end");
            this.addRenderableWidget(dimButton);
            buttonX += 16; // 14 width + 2 spacing

            // 7. Delete Button (🗑) - Red trash bin icon with safety confirmation prompt
            this.addRenderableWidget(
                Button.builder(Component.literal("§c🗑"), btn -> {
                    this.minecraft.setScreen(new ConfirmScreen(
                        confirmed -> {
                            if (confirmed) {
                                WaypointRenderer.waypoints.remove(wp);
                                WaypointRenderer.saveToFile();
                            }
                            this.minecraft.setScreen(this);
                        },
                        I18nHelper.getComponent("menu.confirm_delete_title"),
                        I18nHelper.getComponent("menu.confirm_delete_text", wp.getName())
                    ));
                }).pos(buttonX, itemY).size(14, 18).build()
            );
        }

        // Bottom navigation bar (positioned at centerY + 66)
        // Prev Dim Button
        this.addRenderableWidget(
            Button.builder(Component.literal("<"), btn -> {
                int currentIndex = DIMENSIONS.indexOf(selectedDimension);
                int prevIndex = (currentIndex - 1 + DIMENSIONS.size()) % DIMENSIONS.size();
                selectedDimension = DIMENSIONS.get(prevIndex);
                scrollIndex = 0;
                Minecraft.getInstance().execute(this::rebuildWidgets);
            }).pos(centerX - 104, centerY + 66).size(20, 20).build()
        );

        // New Waypoint Button
        this.addRenderableWidget(
            Button.builder(I18nHelper.getComponent("menu.new"), btn -> {
                if (this.minecraft.player != null) {
                    this.minecraft.setScreen(new WaypointCreateScreen(this.minecraft.player.blockPosition()));
                }
            }).pos(centerX - 80, centerY + 66).size(78, 20).build()
        );

        // Back Button
        this.addRenderableWidget(
            Button.builder(I18nHelper.getComponent("menu.back"), btn -> {
                if (parentScreen != null) {
                    this.minecraft.setScreen(parentScreen);
                } else {
                    this.onClose();
                }
            }).pos(centerX + 2, centerY + 66).size(78, 20).build()
        );

        // Next Dim Button
        this.addRenderableWidget(
            Button.builder(Component.literal(">"), btn -> {
                int currentIndex = DIMENSIONS.indexOf(selectedDimension);
                int nextIndex = (currentIndex + 1) % DIMENSIONS.size();
                selectedDimension = DIMENSIONS.get(nextIndex);
                scrollIndex = 0;
                Minecraft.getInstance().execute(this::rebuildWidgets);
            }).pos(centerX + 84, centerY + 66).size(20, 20).build()
        );
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        List<WaypointEntry> waypoints = getWaypointsForDimension(selectedDimension);
        int total = waypoints.size();
        if (total > ITEMS_PER_PAGE) {
            if (verticalAmount < 0) {
                if (scrollIndex < total - ITEMS_PER_PAGE) {
                    scrollIndex++;
                    Minecraft.getInstance().execute(this::rebuildWidgets);
                    return true;
                }
            } else if (verticalAmount > 0) {
                if (scrollIndex > 0) {
                    scrollIndex--;
                    Minecraft.getInstance().execute(this::rebuildWidgets);
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // Expanded dark background box to 236px width to clear space for the scrollbar
        graphics.fill(centerX - 118, centerY - 95, centerX + 118, centerY + 90, 0x90000000);

        // Title
        graphics.centeredText(this.font, I18nHelper.getComponent("menu.title"), centerX, centerY - 91, 0xFFFFFFFF);

        // Display current dimension name
        String dimensionName = selectedDimension.equals("minecraft:overworld") ? "OVERWORLD" : 
                              (selectedDimension.equals("minecraft:the_nether") ? "NETHER" : "THE END");
        int dimensionColor = 0xFFFFFFFF;
        if (selectedDimension.equals("minecraft:overworld")) {
            dimensionColor = 0xFF4EAE32;
        } else if (selectedDimension.equals("minecraft:the_nether")) {
            dimensionColor = 0xFFE05252;
        } else {
            dimensionColor = 0xFFB85CFF;
        }
        graphics.centeredText(this.font, Component.literal(dimensionName), centerX, centerY - 76, dimensionColor);

        List<WaypointEntry> waypoints = getWaypointsForDimension(selectedDimension);
        int totalWaypoints = waypoints.size();
        int startIndex = scrollIndex;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, totalWaypoints);

        // Check if there is any active focused waypoint in this dimension to determine dimming
        boolean hasAnyFocused = false;
        for (WaypointEntry entry : waypoints) {
            if (entry != null && entry.waypoint != null && entry.waypoint.isFocused()) {
                hasAnyFocused = true;
                break;
            }
        }

        if (totalWaypoints == 0) {
            graphics.centeredText(this.font, I18nHelper.getComponent("menu.no_waypoints"), centerX, centerY - 10, 0xFF888888);
        } else {
            // Draw list items
            for (int i = startIndex; i < endIndex; i++) {
                WaypointEntry entry = waypoints.get(i);
                if (entry == null || entry.waypoint == null) continue;
                Waypoint wp = entry.waypoint;
                BlockPos pos = entry.displayPos;
                int offsetIndex = i - startIndex;
                int itemY = centerY - 65 + offsetIndex * 24;

                // Determine if this entry should be dimmed (muted)
                boolean isDimmed = !wp.isVisible() || (hasAnyFocused && !wp.isFocused() && !wp.isForceVisible());

                // Color indicator (dimmed if muted)
                int markerColor = wp.getColor();
                if (isDimmed) {
                    int r = (markerColor >> 16) & 0xFF;
                    int g = (markerColor >> 8) & 0xFF;
                    int b = markerColor & 0xFF;
                    markerColor = (0x60 << 24) | ((r / 2) << 16) | ((g / 2) << 8) | (b / 2);
                }
                graphics.fill(centerX - 104, itemY + 5, centerX - 96, itemY + 13, markerColor);

                // Name formatting
                String nameDisplay = wp.getName().toUpperCase();
                if (entry.converted) {
                    String prefix = wp.getDimension().equals("minecraft:overworld") ? "[OW] " : "[N] ";
                    nameDisplay = prefix + nameDisplay;
                }
                
                // Truncate name if too long to avoid overlapping the compact buttons starting at centerX - 4
                if (nameDisplay.length() > 14) {
                    nameDisplay = nameDisplay.substring(0, 11) + "...";
                }
                
                // Draw name with dimmed/normal color codes
                int nameColor = isDimmed ? 0xFF555555 : (entry.converted ? 0xFFFFFFAA : 0xFFFFFFFF);
                graphics.text(this.font, Component.literal(nameDisplay), centerX - 92, itemY + 1, nameColor);

                // Coordinates text
                String coords = String.format("%d, %d, %d", pos.getX(), pos.getY(), pos.getZ());
                int coordsColor = isDimmed ? 0xFF353535 : 0xFF888888;
                graphics.text(this.font, Component.literal(coords), centerX - 92, itemY + 10, coordsColor);

                // Render green dot indicator next to coordinates if it's currently dimension-shared
                if (wp.isShared() && !entry.converted) {
                    int dotColor = isDimmed ? 0xFF225522 : 0xFF55FF55;
                    graphics.text(this.font, Component.literal("•"), centerX - 95, itemY - 1, dotColor);
                }
            }

            // Draw scrollbar (starting at centerY - 65, height 114, repositioned to centerX + 113)
            if (totalWaypoints > ITEMS_PER_PAGE) {
                int scrollbarX = centerX + 113;
                int scrollbarY = centerY - 65;
                int scrollbarHeight = 114;
                
                graphics.fill(scrollbarX, scrollbarY, scrollbarX + 2, scrollbarY + scrollbarHeight, 0xFF444444);
                
                float visibleRatio = (float) ITEMS_PER_PAGE / totalWaypoints;
                int thumbHeight = Math.max(12, (int) (scrollbarHeight * visibleRatio));
                
                float scrollRatio = (float) scrollIndex / (totalWaypoints - ITEMS_PER_PAGE);
                int thumbY = scrollbarY + (int) ((scrollbarHeight - thumbHeight) * scrollRatio);
                
                graphics.fill(scrollbarX, thumbY, scrollbarX + 2, thumbY + thumbHeight, 0xFFFFFFFF);
            }
        }

        // Summary count text positioned safely at centerY + 53
        graphics.centeredText(this.font, I18nHelper.getComponent("menu.total", totalWaypoints), centerX, centerY + 53, 0xFFA0A0A0);

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

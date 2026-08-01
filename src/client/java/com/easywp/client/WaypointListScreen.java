package com.easywp.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permissions;

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

    private static final Identifier ICON_FOCUS_ON = Identifier.fromNamespaceAndPath("easywp", "textures/gui/focusOn.png");
    private static final Identifier ICON_FOCUS_OFF = Identifier.fromNamespaceAndPath("easywp", "textures/gui/focusOff.png");
    private static final Identifier ICON_SHOW = Identifier.fromNamespaceAndPath("easywp", "textures/gui/show.png");
    private static final Identifier ICON_HIDE = Identifier.fromNamespaceAndPath("easywp", "textures/gui/hide.png");
    private static final Identifier ICON_TP = Identifier.fromNamespaceAndPath("easywp", "textures/gui/tp.png");
    private static final Identifier ICON_EDIT = Identifier.fromNamespaceAndPath("easywp", "textures/gui/edit.png");
    private static final Identifier ICON_SHARE = Identifier.fromNamespaceAndPath("easywp", "textures/gui/shareButton.png");
    private static final Identifier ICON_DIM_ON = Identifier.fromNamespaceAndPath("easywp", "textures/gui/shareDimentionOn.png");
    private static final Identifier ICON_DIM_OFF = Identifier.fromNamespaceAndPath("easywp", "textures/gui/shareDimentionOff.png");
    private static final Identifier ICON_DELETE = Identifier.fromNamespaceAndPath("easywp", "textures/gui/delete.png");
    private static final Identifier ICON_TO_LEFT = Identifier.fromNamespaceAndPath("easywp", "textures/gui/toLeftButton.png");
    private static final Identifier ICON_TO_RIGHT = Identifier.fromNamespaceAndPath("easywp", "textures/gui/toRightButton.png");
    private static final Identifier BG_LIST_SCREEN = Identifier.fromNamespaceAndPath("easywp", "textures/gui/listScreen.png");

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
        List<WaypointEntry> result = new ArrayList<>();
        for (Waypoint wp : WaypointRenderer.waypoints) {
            if (wp == null) continue;

            String wpDim = wp.getDimension() != null ? wp.getDimension() : "minecraft:overworld";
            
            if (wpDim.equals(dimension)) {
                result.add(new WaypointEntry(wp, wp.getPos(), false));
            } else if (wp.isShared()) {
                if (wpDim.equals("minecraft:overworld") && dimension.equals("minecraft:the_nether")) {
                    BlockPos netherPos = new BlockPos(
                        (int) Math.round(wp.getPos().getX() / 8.0),
                        wp.getPos().getY(),
                        (int) Math.round(wp.getPos().getZ() / 8.0)
                    );
                    result.add(new WaypointEntry(wp, netherPos, true));
                } else if (wpDim.equals("minecraft:the_nether") && dimension.equals("minecraft:overworld")) {
                    BlockPos overworldPos = new BlockPos(
                        (int) Math.round(wp.getPos().getX() * 8.0),
                        wp.getPos().getY(),
                        (int) Math.round(wp.getPos().getZ() * 8.0)
                    );
                    result.add(new WaypointEntry(wp, overworldPos, true));
                }
            }
        }
        return result;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        List<WaypointEntry> waypoints = getWaypointsForDimension(selectedDimension);
        int totalWaypoints = waypoints.size();
        int startIndex = scrollIndex;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, totalWaypoints);

        boolean hasAnyFocused = false;
        for (WaypointEntry entry : waypoints) {
            if (entry != null && entry.waypoint != null && entry.waypoint.isFocused()) {
                hasAnyFocused = true;
                break;
            }
        }

        boolean showTP = this.minecraft.player != null && this.minecraft.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);

        for (int i = startIndex; i < endIndex; i++) {
            WaypointEntry entry = waypoints.get(i);
            if (entry == null || entry.waypoint == null) continue;
            Waypoint wp = entry.waypoint;
            BlockPos pos = entry.displayPos;
            int offsetIndex = i - startIndex;
            int itemY = centerY - 65 + offsetIndex * 24;

            int buttonX = centerX - 16;

            this.addRenderableWidget(
                Button.builder(Component.literal(""), btn -> {
                    if (wp.isFocused()) {
                        wp.setFocused(false);
                        wp.setForceVisible(false);
                    } else {
                        for (Waypoint w : WaypointRenderer.waypoints) {
                            if (w != null) {
                                w.setFocused(false);
                                w.setForceVisible(false);
                            }
                        }
                        wp.setFocused(true);
                        wp.setVisible(true);
                    }
                    WaypointRenderer.saveToFile();
                    Minecraft.getInstance().execute(this::rebuildWidgets);
                }).pos(buttonX, itemY).size(18, 18).build()
            );
            buttonX += 20;

            boolean finalHasAnyFocused = hasAnyFocused;

            this.addRenderableWidget(
                Button.builder(Component.literal(""), btn -> {
                    if (finalHasAnyFocused) {
                        if (!wp.isFocused()) {
                            wp.setForceVisible(!wp.isForceVisible());
                            wp.setVisible(true);
                        } else {
                            wp.setVisible(!wp.isVisible());
                        }
                    } else {
                        wp.setVisible(!wp.isVisible());
                    }
                    WaypointRenderer.saveToFile();
                    Minecraft.getInstance().execute(this::rebuildWidgets);
                }).pos(buttonX, itemY).size(18, 18).build()
            );
            buttonX += 20;

            if (showTP) {
                this.addRenderableWidget(
                    Button.builder(Component.literal(""), btn -> {
                        if (this.minecraft.player != null && this.minecraft.player.connection != null) {
                            String targetDim = wp.isShared() ? selectedDimension : (wp.getDimension() != null ? wp.getDimension() : selectedDimension);
                            String tpCommand = String.format("execute in %s run tp @s %d %d %d", targetDim, pos.getX(), pos.getY(), pos.getZ());
                            this.minecraft.player.connection.sendCommand(tpCommand);
                            this.onClose();
                        }
                    }).pos(buttonX, itemY).size(18, 18).build()
                );
                buttonX += 20;
            }

            this.addRenderableWidget(
                Button.builder(Component.literal(""), btn -> {
                    this.minecraft.setScreen(new WaypointCreateScreen(wp));
                }).pos(buttonX, itemY).size(18, 18).build()
            );
            buttonX += 20;

            this.addRenderableWidget(
                Button.builder(Component.literal(""), btn -> {
                    if (this.minecraft.player != null && this.minecraft.player.connection != null) {
                        String dimName = selectedDimension.equals("minecraft:overworld") ? "Overworld" : 
                                         (selectedDimension.equals("minecraft:the_nether") ? "Nether" : "The End");
                        String msg = String.format("%s -> [%d, %d, %d] at %s", 
                            wp.getName().toUpperCase(), pos.getX(), pos.getY(), pos.getZ(), dimName);
                        this.minecraft.player.connection.sendChat(msg);
                        this.onClose();
                    }
                }).pos(buttonX, itemY).size(18, 18).build()
            );
            buttonX += 20;

            Button dimButton = Button.builder(Component.literal(""), btn -> {
                wp.setShared(!wp.isShared());
                WaypointRenderer.saveToFile();
                Minecraft.getInstance().execute(this::rebuildWidgets);
            }).pos(buttonX, itemY).size(18, 18).build();
            dimButton.active = !selectedDimension.equals("minecraft:the_end");
            this.addRenderableWidget(dimButton);
            buttonX += 20;

            this.addRenderableWidget(
                Button.builder(Component.literal(""), btn -> {
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
                }).pos(buttonX, itemY).size(18, 18).build()
            );
        }

        this.addRenderableWidget(
            Button.builder(Component.literal(""), btn -> {
                int currentIndex = DIMENSIONS.indexOf(selectedDimension);
                int prevIndex = (currentIndex - 1 + DIMENSIONS.size()) % DIMENSIONS.size();
                selectedDimension = DIMENSIONS.get(prevIndex);
                scrollIndex = 0;
                Minecraft.getInstance().execute(this::rebuildWidgets);
            }).pos(centerX - 116, centerY + 66).size(18, 18).build()
        );

        this.addRenderableWidget(
            Button.builder(I18nHelper.getComponent("menu.new"), btn -> {
                if (this.minecraft.player != null) {
                    this.minecraft.setScreen(new WaypointCreateScreen(this.minecraft.player.blockPosition()));
                }
            }).pos(centerX - 92, centerY + 66).size(90, 20).build()
        );

        this.addRenderableWidget(
            Button.builder(I18nHelper.getComponent("menu.back"), btn -> {
                if (parentScreen != null) {
                    this.minecraft.setScreen(parentScreen);
                } else {
                    this.onClose();
                }
            }).pos(centerX + 3, centerY + 66).size(90, 20).build()
        );

        this.addRenderableWidget(
            Button.builder(Component.literal(""), btn -> {
                int currentIndex = DIMENSIONS.indexOf(selectedDimension);
                int nextIndex = (currentIndex + 1) % DIMENSIONS.size();
                selectedDimension = DIMENSIONS.get(nextIndex);
                scrollIndex = 0;
                Minecraft.getInstance().execute(this::rebuildWidgets);
            }).pos(centerX + 98, centerY + 66).size(18, 18).build()
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
        int headerCenterX = centerX;

        int bgW = 272;
        int bgH = 192;
        int minX = centerX - bgW / 2;
        int minY = centerY - bgH / 2;

        RenderPipeline pipeline = RenderPipelines.GUI_TEXTURED;
        graphics.blit(pipeline, BG_LIST_SCREEN, minX, minY, 0.0f, 0.0f, bgW, bgH, bgW, bgH);

        graphics.centeredText(this.font, I18nHelper.getComponent("menu.title"), headerCenterX, centerY - 91, 0xFFFFFFFF);

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
        graphics.centeredText(this.font, Component.literal(dimensionName), headerCenterX, centerY - 76, dimensionColor);

        super.extractRenderState(graphics, mouseX, mouseY, a);

        List<WaypointEntry> waypoints = getWaypointsForDimension(selectedDimension);
        int totalWaypoints = waypoints.size();
        int startIndex = scrollIndex;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, totalWaypoints);

        boolean hasAnyFocused = false;
        for (WaypointEntry entry : waypoints) {
            if (entry != null && entry.waypoint != null && entry.waypoint.isFocused()) {
                hasAnyFocused = true;
                break;
            }
        }

        boolean showTP = this.minecraft.player != null && this.minecraft.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);

        if (totalWaypoints == 0) {
            graphics.centeredText(this.font, I18nHelper.getComponent("menu.no_waypoints"), headerCenterX, centerY - 10, 0xFF888888);
        } else {
            for (int i = startIndex; i < endIndex; i++) {
                WaypointEntry entry = waypoints.get(i);
                if (entry == null || entry.waypoint == null) continue;
                Waypoint wp = entry.waypoint;
                BlockPos pos = entry.displayPos;
                int offsetIndex = i - startIndex;
                int itemY = centerY - 65 + offsetIndex * 24;

                boolean isDimmed = !wp.isVisible() || (hasAnyFocused && !wp.isFocused() && !wp.isForceVisible());

                int markerColor = wp.getColor();
                if (isDimmed) {
                    int r = (markerColor >> 16) & 0xFF;
                    int g = (markerColor >> 8) & 0xFF;
                    int b = markerColor & 0xFF;
                    markerColor = (0x60 << 24) | ((r / 2) << 16) | ((g / 2) << 8) | (b / 2);
                }
                graphics.fill(centerX - 116, itemY + 5, centerX - 108, itemY + 13, markerColor);

                String nameDisplay = wp.getName().toUpperCase();
                if (entry.converted) {
                    String prefix = wp.getDimension().equals("minecraft:overworld") ? "[OW] " : "[N] ";
                    nameDisplay = prefix + nameDisplay;
                }
                
                if (nameDisplay.length() > 14) {
                    nameDisplay = nameDisplay.substring(0, 11) + "...";
                }
                
                int nameColor = isDimmed ? 0xFF555555 : (entry.converted ? 0xFFFFFFAA : 0xFFFFFFFF);
                graphics.text(this.font, Component.literal(nameDisplay), centerX - 104, itemY + 1, nameColor);

                String coords = String.format("%d, %d, %d", pos.getX(), pos.getY(), pos.getZ());
                int coordsColor = isDimmed ? 0xFF353535 : 0xFF888888;
                graphics.text(this.font, Component.literal(coords), centerX - 104, itemY + 10, coordsColor);

                if (wp.isShared() && !entry.converted) {
                    int dotColor = isDimmed ? 0xFF225522 : 0xFF55FF55;
                    graphics.text(this.font, Component.literal("•"), centerX - 107, itemY - 1, dotColor);
                }

                int buttonX = centerX - 16;

                Identifier focusIcon = wp.isFocused() ? ICON_FOCUS_ON : ICON_FOCUS_OFF;
                graphics.blit(pipeline, focusIcon, buttonX, itemY, 0.0f, 0.0f, 18, 18, 18, 18);
                buttonX += 20;

                boolean isVisible = wp.isVisible();
                if (hasAnyFocused) {
                    isVisible = wp.isFocused() || wp.isForceVisible();
                }
                Identifier visIcon = isVisible ? ICON_SHOW : ICON_HIDE;
                graphics.blit(pipeline, visIcon, buttonX, itemY, 0.0f, 0.0f, 18, 18, 18, 18);
                buttonX += 20;

                if (showTP) {
                    graphics.blit(pipeline, ICON_TP, buttonX, itemY, 0.0f, 0.0f, 18, 18, 18, 18);
                    buttonX += 20;
                }

                graphics.blit(pipeline, ICON_EDIT, buttonX, itemY, 0.0f, 0.0f, 18, 18, 18, 18);
                buttonX += 20;

                graphics.blit(pipeline, ICON_SHARE, buttonX, itemY, 0.0f, 0.0f, 18, 18, 18, 18);
                buttonX += 20;

                Identifier dimIcon = wp.isShared() ? ICON_DIM_ON : ICON_DIM_OFF;
                graphics.blit(pipeline, dimIcon, buttonX, itemY, 0.0f, 0.0f, 18, 18, 18, 18);
                buttonX += 20;

                graphics.blit(pipeline, ICON_DELETE, buttonX, itemY, 0.0f, 0.0f, 18, 18, 18, 18);
            }

            if (totalWaypoints > ITEMS_PER_PAGE) {
                int scrollbarX = centerX + 126;
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

        graphics.blit(pipeline, ICON_TO_LEFT, centerX - 116, centerY + 66, 0.0f, 0.0f, 18, 18, 18, 18);
        graphics.blit(pipeline, ICON_TO_RIGHT, centerX + 98, centerY + 66, 0.0f, 0.0f, 18, 18, 18, 18);

        graphics.centeredText(this.font, I18nHelper.getComponent("menu.total", totalWaypoints), headerCenterX, centerY + 53, 0xFFA0A0A0);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
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

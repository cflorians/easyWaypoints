package com.easywp.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permissions;

import java.util.List;
import java.util.ArrayList;

/**
 * Centered modal screen for browsing, filtering, and managing waypoints.
 */
public class WaypointListScreen extends Screen {
    private final Screen parentScreen;
    private int scrollIndex = 0;
    private static final int ITEMS_PER_PAGE = 5;
    private String selectedDimension = "minecraft:overworld";
    private EditBox searchField;
    private final List<GuiEventListener> dynamicWidgets = new ArrayList<>();

    // Cached state fields for zero-allocation render loop
    private List<WaypointEntry> cachedFilteredWaypoints = new ArrayList<>();
    private boolean cachedHasAnyFocused = false;
    private boolean cachedShowTP = false;

    private static final List<String> DIMENSIONS = List.of(
        "minecraft:overworld",
        "minecraft:the_nether",
        "minecraft:the_end"
    );

    private static final Identifier ICON_FOCUS_ON = Identifier.fromNamespaceAndPath("easywp", "textures/gui/focuson.png");
    private static final Identifier ICON_FOCUS_OFF = Identifier.fromNamespaceAndPath("easywp", "textures/gui/focusoff.png");
    private static final Identifier ICON_SHOW = Identifier.fromNamespaceAndPath("easywp", "textures/gui/show.png");
    private static final Identifier ICON_HIDE = Identifier.fromNamespaceAndPath("easywp", "textures/gui/hide.png");
    private static final Identifier ICON_TP = Identifier.fromNamespaceAndPath("easywp", "textures/gui/tp.png");
    private static final Identifier ICON_EDIT = Identifier.fromNamespaceAndPath("easywp", "textures/gui/edit.png");
    private static final Identifier ICON_SHARE = Identifier.fromNamespaceAndPath("easywp", "textures/gui/sharebutton.png");
    private static final Identifier ICON_DIM_ON = Identifier.fromNamespaceAndPath("easywp", "textures/gui/sharedimentionon.png");
    private static final Identifier ICON_DIM_OFF = Identifier.fromNamespaceAndPath("easywp", "textures/gui/sharedimentionoff.png");
    private static final Identifier ICON_DELETE = Identifier.fromNamespaceAndPath("easywp", "textures/gui/delete.png");

    /**
     * Name column runs from the marker swatch (centerX - 118) up to just short of the TP icon
     * (centerX - 12), regardless of whether TP is actually shown for this player, so the layout
     * doesn't shift with permissions. Leaves a small gap so the text never touches the icon.
     */
    private static final int NAME_MAX_WIDTH = 102;

    private static class WaypointEntry {
        final Waypoint waypoint;
        final BlockPos displayPos;
        final boolean converted;
        final Component nameComponent;
        final Component coordsComponent;

        WaypointEntry(net.minecraft.client.gui.Font font, Waypoint waypoint, BlockPos displayPos, boolean converted) {
            this.waypoint = waypoint;
            this.displayPos = displayPos;
            this.converted = converted;

            String name = waypoint.getName();
            if (converted) {
                String prefix = waypoint.getDimension().equals("minecraft:overworld") ? "[OW] " : "[N] ";
                name = prefix + name;
            }
            if (waypoint.isDeath()) {
                name = "☠ " + name;
            }
            name = truncateToWidth(font, name, NAME_MAX_WIDTH);
            this.nameComponent = Component.literal(name);
            this.coordsComponent = Component.literal(String.format("%d, %d, %d", displayPos.getX(), displayPos.getY(), displayPos.getZ()));
        }
    }

    private static String truncateToWidth(net.minecraft.client.gui.Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;

        String ellipsis = "...";
        int ellipsisWidth = font.width(ellipsis);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String candidate = sb.toString() + text.charAt(i);
            if (font.width(candidate) + ellipsisWidth > maxWidth) break;
            sb.append(text.charAt(i));
        }
        return sb + ellipsis;
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
                result.add(new WaypointEntry(this.font, wp, wp.getPos(), false));
            } else if (wp.isShared()) {
                if (wpDim.equals("minecraft:overworld") && dimension.equals("minecraft:the_nether")) {
                    BlockPos netherPos = new BlockPos(
                        (int) Math.round(wp.getPos().getX() / 8.0),
                        wp.getPos().getY(),
                        (int) Math.round(wp.getPos().getZ() / 8.0)
                    );
                    result.add(new WaypointEntry(this.font, wp, netherPos, true));
                } else if (wpDim.equals("minecraft:the_nether") && dimension.equals("minecraft:overworld")) {
                    BlockPos overworldPos = new BlockPos(
                        (int) Math.round(wp.getPos().getX() * 8.0),
                        wp.getPos().getY(),
                        (int) Math.round(wp.getPos().getZ() * 8.0)
                    );
                    result.add(new WaypointEntry(this.font, wp, overworldPos, true));
                }
            }
        }
        return result;
    }

    private List<WaypointEntry> getFilteredWaypoints() {
        List<WaypointEntry> base = getWaypointsForDimension(selectedDimension);
        if (searchField == null) return base;
        String query = searchField.getValue().trim().toLowerCase();
        if (query.isEmpty()) return base;

        List<WaypointEntry> filtered = new ArrayList<>();
        for (WaypointEntry entry : base) {
            if (entry != null && entry.waypoint != null && entry.waypoint.getName() != null) {
                if (entry.waypoint.getName().toLowerCase().contains(query)) {
                    filtered.add(entry);
                }
            }
        }
        return filtered;
    }

    private void updateWaypointsCache() {
        this.cachedFilteredWaypoints = getFilteredWaypoints();
        this.cachedHasAnyFocused = false;
        for (WaypointEntry entry : cachedFilteredWaypoints) {
            if (entry != null && entry.waypoint != null && entry.waypoint.isFocused()) {
                this.cachedHasAnyFocused = true;
                break;
            }
        }
        this.cachedShowTP = this.minecraft != null && this.minecraft.player != null &&
                            this.minecraft.player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        if (this.searchField == null) {
            this.searchField = new EditBox(this.font, centerX - 130, centerY - 82, 260, 16, I18nHelper.getComponent("menu.search"));
            this.searchField.setHint(I18nHelper.getComponent("menu.search_hint"));
            this.searchField.setResponder(text -> {
                this.scrollIndex = 0;
                refreshItemWidgets();
            });
        } else {
            this.searchField.setPosition(centerX - 130, centerY - 82);
            this.searchField.setWidth(260);
        }

        this.addRenderableWidget(this.searchField);

        this.addRenderableWidget(
            ModernButton.modernBuilder(I18nHelper.getComponent("config.open_button"), btn -> {
                this.minecraft.setScreenAndShow(new ModConfigScreen(this));
            }).pos(8, this.height - 28).size(70, 20).build()
        );

        refreshItemWidgets();
    }

    private void refreshItemWidgets() {
        updateWaypointsCache();
        for (var widget : dynamicWidgets) {
            this.removeWidget(widget);
        }
        dynamicWidgets.clear();

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        List<WaypointEntry> waypoints = this.cachedFilteredWaypoints;
        int totalWaypoints = waypoints.size();
        int startIndex = scrollIndex;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, totalWaypoints);

        boolean showTP = this.cachedShowTP;
        boolean finalHasAnyFocused = this.cachedHasAnyFocused;

        for (int i = startIndex; i < endIndex; i++) {
            WaypointEntry entry = waypoints.get(i);
            if (entry == null || entry.waypoint == null) continue;
            Waypoint wp = entry.waypoint;
            BlockPos pos = entry.displayPos;
            int offsetIndex = i - startIndex;
            int itemY = centerY - 52 + offsetIndex * 22;

            int buttonX = centerX + 108;

            var delBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                this.minecraft.setScreenAndShow(new ConfirmScreen(
                    confirmed -> {
                        if (confirmed) {
                            WaypointRenderer.waypoints.remove(wp);
                            WaypointRenderer.saveToFile();
                        }
                        this.minecraft.setScreenAndShow(this);
                    },
                    I18nHelper.getComponent("menu.confirm_delete_title"),
                    I18nHelper.getComponent("menu.confirm_delete_text", wp.getName())
                ));
            }).pos(buttonX, itemY).size(18, 18).build();
            this.addRenderableWidget(delBtn);
            dynamicWidgets.add(delBtn);
            buttonX -= 20;

            var dimBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                wp.setShared(!wp.isShared());
                WaypointRenderer.saveToFile();
                refreshItemWidgets();
            }).pos(buttonX, itemY).size(18, 18).build();
            dimBtn.active = !selectedDimension.equals("minecraft:the_end");
            this.addRenderableWidget(dimBtn);
            dynamicWidgets.add(dimBtn);
            buttonX -= 20;

            var shareBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                if (this.minecraft.player != null && this.minecraft.player.connection != null) {
                    String dimName = selectedDimension.equals("minecraft:overworld") ? "Overworld" :
                                     (selectedDimension.equals("minecraft:the_nether") ? "Nether" : "The End");
                    String msg = String.format("%s -> [%d, %d, %d] at %s",
                        wp.getName().toUpperCase(), pos.getX(), pos.getY(), pos.getZ(), dimName);
                    this.minecraft.player.connection.sendChat(msg);
                    this.onClose();
                }
            }).pos(buttonX, itemY).size(18, 18).build();
            this.addRenderableWidget(shareBtn);
            dynamicWidgets.add(shareBtn);
            buttonX -= 20;

            var editBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                this.minecraft.setScreenAndShow(new WaypointCreateScreen(wp));
            }).pos(buttonX, itemY).size(18, 18).build();
            this.addRenderableWidget(editBtn);
            dynamicWidgets.add(editBtn);
            buttonX -= 20;

            var visBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
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
                refreshItemWidgets();
            }).pos(buttonX, itemY).size(18, 18).build();
            this.addRenderableWidget(visBtn);
            dynamicWidgets.add(visBtn);
            buttonX -= 20;

            var focusBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
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
                refreshItemWidgets();
            }).pos(buttonX, itemY).size(18, 18).build();
            this.addRenderableWidget(focusBtn);
            dynamicWidgets.add(focusBtn);

            if (showTP) {
                buttonX -= 20;
                var tpBtn = ModernButton.modernBuilder(Component.literal(""), btn -> {
                    if (this.minecraft.player != null && this.minecraft.player.connection != null) {
                        String targetDim = wp.isShared() ? selectedDimension : (wp.getDimension() != null ? wp.getDimension() : selectedDimension);
                        String tpCommand = String.format("execute in %s run tp @s %d %d %d", targetDim, pos.getX(), pos.getY(), pos.getZ());
                        this.minecraft.player.connection.sendCommand(tpCommand);
                        this.onClose();
                    }
                }).pos(buttonX, itemY).size(18, 18).build();
                this.addRenderableWidget(tpBtn);
                dynamicWidgets.add(tpBtn);
            }
        }

        var prevDimBtn = ModernButton.modernBuilder(Component.literal("<"), btn -> {
            int currentIndex = DIMENSIONS.indexOf(selectedDimension);
            int prevIndex = (currentIndex - 1 + DIMENSIONS.size()) % DIMENSIONS.size();
            selectedDimension = DIMENSIONS.get(prevIndex);
            scrollIndex = 0;
            refreshItemWidgets();
        }).pos(centerX - 130, centerY + 74).size(20, 20).build();
        this.addRenderableWidget(prevDimBtn);
        dynamicWidgets.add(prevDimBtn);

        var newBtn = ModernButton.modernBuilder(I18nHelper.getComponent("menu.new"), btn -> {
            if (this.minecraft.player != null) {
                this.minecraft.setScreenAndShow(new WaypointCreateScreen(this.minecraft.player.blockPosition()));
            }
        }).pos(centerX - 105, centerY + 74).size(102, 20).build();
        this.addRenderableWidget(newBtn);
        dynamicWidgets.add(newBtn);

        var backBtn = ModernButton.modernBuilder(I18nHelper.getComponent("menu.back"), btn -> {
            if (parentScreen != null) {
                this.minecraft.setScreenAndShow(parentScreen);
            } else {
                this.onClose();
            }
        }).pos(centerX + 2, centerY + 74).size(103, 20).build();
        this.addRenderableWidget(backBtn);
        dynamicWidgets.add(backBtn);

        var nextDimBtn = ModernButton.modernBuilder(Component.literal(">"), btn -> {
            int currentIndex = DIMENSIONS.indexOf(selectedDimension);
            int nextIndex = (currentIndex + 1) % DIMENSIONS.size();
            selectedDimension = DIMENSIONS.get(nextIndex);
            scrollIndex = 0;
            refreshItemWidgets();
        }).pos(centerX + 110, centerY + 74).size(20, 20).build();
        this.addRenderableWidget(nextDimBtn);
        dynamicWidgets.add(nextDimBtn);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        List<WaypointEntry> waypoints = this.cachedFilteredWaypoints;
        int total = waypoints.size();
        if (total > ITEMS_PER_PAGE) {
            if (verticalAmount < 0) {
                if (scrollIndex < total - ITEMS_PER_PAGE) {
                    scrollIndex++;
                    refreshItemWidgets();
                    return true;
                }
            } else if (verticalAmount > 0) {
                if (scrollIndex > 0) {
                    scrollIndex--;
                    refreshItemWidgets();
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

        int bgW = 290;
        int bgH = 220;
        int minX = centerX - bgW / 2;
        int minY = centerY - bgH / 2;

        RenderPipeline pipeline = RenderPipelines.GUI_TEXTURED;

        // Modal background card (ARGB 0xD010141E)
        graphics.fill(minX, minY, minX + bgW, minY + bgH, 0xD010141E);
        // Header title background bar (ARGB 0xE0181E30)
        graphics.fill(minX, minY, minX + bgW, minY + 22, 0xE0181E30);
        // Card border lines (ARGB 0xFF3D4966)
        graphics.fill(minX, minY, minX + bgW, minY + 1, 0xFF3D4966);
        graphics.fill(minX, minY + bgH - 1, minX + bgW, minY + bgH, 0xFF3D4966);
        graphics.fill(minX, minY, minX + 1, minY + bgH, 0xFF3D4966);
        graphics.fill(minX + bgW - 1, minY, minX + bgW, minY + bgH, 0xFF3D4966);
        graphics.fill(minX, minY + 22, minX + bgW, minY + 23, 0xFF3D4966);

        List<WaypointEntry> waypoints = this.cachedFilteredWaypoints;
        int totalWaypoints = waypoints.size();

        String dimensionName = selectedDimension.equals("minecraft:overworld") ? "OVERWORLD" :
                              (selectedDimension.equals("minecraft:the_nether") ? "NETHER" : "THE END");
        String headerDimText = dimensionName + " (" + totalWaypoints + ")";
        int dimensionColor = 0xFFFFFFFF;
        if (selectedDimension.equals("minecraft:overworld")) {
            dimensionColor = 0xFF4EAE32;
        } else if (selectedDimension.equals("minecraft:the_nether")) {
            dimensionColor = 0xFFE05252;
        } else {
            dimensionColor = 0xFFB85CFF;
        }
        graphics.centeredText(this.font, Component.literal(headerDimText), headerCenterX, centerY - 103, dimensionColor);

        super.extractRenderState(graphics, mouseX, mouseY, a);

        int startIndex = scrollIndex;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, totalWaypoints);

        boolean hasAnyFocused = this.cachedHasAnyFocused;
        boolean showTP = this.cachedShowTP;

        if (totalWaypoints == 0) {
            graphics.centeredText(this.font, I18nHelper.getComponent("menu.no_waypoints"), headerCenterX, centerY - 10, 0xFF888888);
        } else {
            int arrowColor = 0xFF3B4866;
            if (scrollIndex > 0) {
                drawUpTriangle(graphics, headerCenterX, centerY - 60, arrowColor);
            }

            if (startIndex + ITEMS_PER_PAGE < totalWaypoints) {
                drawDownTriangle(graphics, headerCenterX, centerY + 58, arrowColor);
            }

            for (int i = startIndex; i < endIndex; i++) {
                WaypointEntry entry = waypoints.get(i);
                if (entry == null || entry.waypoint == null) continue;
                Waypoint wp = entry.waypoint;
                int offsetIndex = i - startIndex;
                int itemY = centerY - 52 + offsetIndex * 22;

                boolean isDimmed = !wp.isVisible() || (hasAnyFocused && !wp.isFocused() && !wp.isForceVisible());

                int markerColor = wp.getColor();
                if (isDimmed) {
                    int r = (markerColor >> 16) & 0xFF;
                    int g = (markerColor >> 8) & 0xFF;
                    int b = markerColor & 0xFF;
                    markerColor = (0x60 << 24) | ((r / 2) << 16) | ((g / 2) << 8) | (b / 2);
                }
                graphics.fill(centerX - 130, itemY + 5, centerX - 122, itemY + 13, markerColor);

                int nameColor = isDimmed ? 0xFF555555 : (entry.converted ? 0xFFFFFFAA : 0xFFFFFFFF);
                graphics.text(this.font, entry.nameComponent, centerX - 118, itemY + 1, nameColor);

                int coordsColor = isDimmed ? 0xFF353535 : 0xFF888888;
                graphics.text(this.font, entry.coordsComponent, centerX - 118, itemY + 10, coordsColor);

                if (wp.isShared() && !entry.converted) {
                    int dotColor = isDimmed ? 0xFF225522 : 0xFF55FF55;
                    graphics.text(this.font, Component.literal("•"), centerX - 121, itemY - 1, dotColor);
                }

                if (showTP) {
                    graphics.blit(pipeline, ICON_TP, centerX - 12, itemY, 0.0f, 0.0f, 18, 18, 18, 18);
                }

                Identifier focusIcon = wp.isFocused() ? ICON_FOCUS_ON : ICON_FOCUS_OFF;
                graphics.blit(pipeline, focusIcon, centerX + 8, itemY, 0.0f, 0.0f, 18, 18, 18, 18);

                boolean isVisible = wp.isVisible();
                if (hasAnyFocused) {
                    isVisible = wp.isFocused() || wp.isForceVisible();
                }
                Identifier visIcon = isVisible ? ICON_SHOW : ICON_HIDE;
                graphics.blit(pipeline, visIcon, centerX + 28, itemY, 0.0f, 0.0f, 18, 18, 18, 18);

                graphics.blit(pipeline, ICON_EDIT, centerX + 48, itemY, 0.0f, 0.0f, 18, 18, 18, 18);
                graphics.blit(pipeline, ICON_SHARE, centerX + 68, itemY, 0.0f, 0.0f, 18, 18, 18, 18);

                Identifier dimIcon = wp.isShared() ? ICON_DIM_ON : ICON_DIM_OFF;
                graphics.blit(pipeline, dimIcon, centerX + 88, itemY, 0.0f, 0.0f, 18, 18, 18, 18);

                graphics.blit(pipeline, ICON_DELETE, centerX + 108, itemY, 0.0f, 0.0f, 18, 18, 18, 18);
            }

            if (totalWaypoints > ITEMS_PER_PAGE) {
                int scrollbarX = centerX + 134;
                int scrollbarY = centerY - 52;
                int scrollbarHeight = 108;

                graphics.fill(scrollbarX, scrollbarY, scrollbarX + 2, scrollbarY + scrollbarHeight, 0xFF444444);

                float visibleRatio = (float) ITEMS_PER_PAGE / totalWaypoints;
                int thumbHeight = Math.max(12, (int) (scrollbarHeight * visibleRatio));

                float scrollRatio = (float) scrollIndex / (totalWaypoints - ITEMS_PER_PAGE);
                int thumbY = scrollbarY + (int) ((scrollbarHeight - thumbHeight) * scrollRatio);

                graphics.fill(scrollbarX, thumbY, scrollbarX + 2, thumbY + thumbHeight, 0xFFFFFFFF);
            }
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (ModKeyBindings.listWaypointsKey.matches(event)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private void drawUpTriangle(GuiGraphicsExtractor graphics, int centerX, int startY, int color) {
        graphics.fill(centerX, startY, centerX + 1, startY + 1, color);
        graphics.fill(centerX - 1, startY + 1, centerX + 2, startY + 2, color);
        graphics.fill(centerX - 2, startY + 2, centerX + 3, startY + 3, color);
        graphics.fill(centerX - 3, startY + 3, centerX + 4, startY + 4, color);
    }

    private void drawDownTriangle(GuiGraphicsExtractor graphics, int centerX, int startY, int color) {
        graphics.fill(centerX - 3, startY, centerX + 4, startY + 1, color);
        graphics.fill(centerX - 2, startY + 1, centerX + 3, startY + 2, color);
        graphics.fill(centerX - 1, startY + 2, centerX + 2, startY + 3, color);
        graphics.fill(centerX, startY + 3, centerX + 1, startY + 4, color);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}



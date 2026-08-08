package com.easywp.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Centered modal screen for browsing, filtering, and managing waypoints in 1.21.x (Yarn mappings).
 */
public class WaypointListScreen extends Screen {
    private final Screen parentScreen;
    private int scrollIndex = 0;
    private static final int ITEMS_PER_PAGE = 5;
    private String selectedDimension = "minecraft:overworld";
    private TextFieldWidget searchField;
    private final List<Element> dynamicWidgets = new ArrayList<>();

    // Cached state fields for zero-allocation render loop
    private List<WaypointEntry> cachedFilteredWaypoints = new ArrayList<>();
    private boolean cachedHasAnyFocused = false;
    private boolean cachedShowTP = false;

    private static final List<String> DIMENSIONS = List.of(
        "minecraft:overworld",
        "minecraft:the_nether",
        "minecraft:the_end"
    );

    private static final Identifier ICON_FOCUS_ON = Identifier.of("easywp", "textures/gui/focuson.png");
    private static final Identifier ICON_FOCUS_OFF = Identifier.of("easywp", "textures/gui/focusoff.png");
    private static final Identifier ICON_SHOW = Identifier.of("easywp", "textures/gui/show.png");
    private static final Identifier ICON_HIDE = Identifier.of("easywp", "textures/gui/hide.png");
    private static final Identifier ICON_TP = Identifier.of("easywp", "textures/gui/tp.png");
    private static final Identifier ICON_EDIT = Identifier.of("easywp", "textures/gui/edit.png");
    private static final Identifier ICON_SHARE = Identifier.of("easywp", "textures/gui/sharebutton.png");
    private static final Identifier ICON_DIM_ON = Identifier.of("easywp", "textures/gui/sharedimentionon.png");
    private static final Identifier ICON_DIM_OFF = Identifier.of("easywp", "textures/gui/sharedimentionoff.png");
    private static final Identifier ICON_DELETE = Identifier.of("easywp", "textures/gui/delete.png");

    private static class WaypointEntry {
        final Waypoint waypoint;
        final BlockPos displayPos;
        final boolean converted;
        final Text nameComponent;
        final Text coordsComponent;

        WaypointEntry(Waypoint waypoint, BlockPos displayPos, boolean converted) {
            this.waypoint = waypoint;
            this.displayPos = displayPos;
            this.converted = converted;
            this.nameComponent = Text.literal(waypoint.getName());
            this.coordsComponent = Text.literal(String.format("X: %d  Y: %d  Z: %d", displayPos.getX(), displayPos.getY(), displayPos.getZ()));
        }
    }

    public WaypointListScreen(Screen parentScreen) {
        super(I18nHelper.getComponent("list.title"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        if (this.client != null && this.client.world != null) {
            this.selectedDimension = this.client.world.getRegistryKey().getValue().toString();
        }

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.searchField = new TextFieldWidget(this.textRenderer, centerX - 118, centerY - 67, 236, 16, I18nHelper.getComponent("list.search_placeholder"));
        this.searchField.setPlaceholder(I18nHelper.getComponent("list.search_placeholder"));
        this.searchField.setChangedListener(text -> {
            this.scrollIndex = 0;
            rebuildWaypointList();
        });
        this.addDrawableChild(this.searchField);

        rebuildWaypointList();
    }

    private void rebuildWaypointList() {
        for (Element widget : this.dynamicWidgets) {
            this.remove(widget);
        }
        this.dynamicWidgets.clear();

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.cachedFilteredWaypoints.clear();
        String filter = this.searchField != null ? this.searchField.getText().toLowerCase().trim() : "";
        this.cachedHasAnyFocused = false;

        BlockPos playerPos = this.client.player != null ? this.client.player.getBlockPos() : BlockPos.ORIGIN;

        for (Waypoint wp : WaypointRenderer.waypoints) {
            if (wp.isFocused()) {
                this.cachedHasAnyFocused = true;
            }

            if (filter.isEmpty() || wp.getName().toLowerCase().contains(filter)) {
                if (this.selectedDimension.equals(wp.getDimension())) {
                    this.cachedFilteredWaypoints.add(new WaypointEntry(wp, wp.getPos(), false));
                } else if (wp.isShared()) {
                    BlockPos convertedPos = Waypoint.getConvertedPos(wp.getPos(), wp.getDimension(), this.selectedDimension);
                    if (convertedPos != null) {
                        this.cachedFilteredWaypoints.add(new WaypointEntry(wp, convertedPos, true));
                    }
                }
            }
        }

        // Sort by distance to player
        this.cachedFilteredWaypoints.sort((a, b) -> {
            double distA = a.displayPos.getSquaredDistance(playerPos);
            double distB = b.displayPos.getSquaredDistance(playerPos);
            return Double.compare(distA, distB);
        });

        int totalWaypoints = this.cachedFilteredWaypoints.size();
        int maxScroll = Math.max(0, totalWaypoints - ITEMS_PER_PAGE);
        if (this.scrollIndex > maxScroll) {
            this.scrollIndex = maxScroll;
        }

        boolean isSinglePlayer = this.client.isIntegratedServerRunning();
        boolean isCreativeOrOp = this.client.player != null && (this.client.player.isCreative() || this.client.player.hasPermissionLevel(2));
        this.cachedShowTP = isSinglePlayer || isCreativeOrOp;

        int listTopY = centerY - 46;
        int itemHeight = 22;

        int endIndex = Math.min(this.scrollIndex + ITEMS_PER_PAGE, totalWaypoints);
        for (int i = this.scrollIndex; i < endIndex; i++) {
            final WaypointEntry entry = this.cachedFilteredWaypoints.get(i);
            final Waypoint wp = entry.waypoint;
            int itemY = listTopY + (i - this.scrollIndex) * itemHeight;

            int rightBtnX = centerX + 118;

            // Delete button
            rightBtnX -= 18;
            ButtonWidget delBtn = ModernButton.modernBuilder(Text.literal(""), btn -> {
                this.client.setScreen(new ConfirmScreen(
                    confirmed -> {
                        if (confirmed) {
                            WaypointRenderer.waypoints.remove(wp);
                            WaypointRenderer.saveToFile();
                        }
                        this.client.setScreen(this);
                    },
                    I18nHelper.getComponent("list.delete_confirm_title"),
                    I18nHelper.getComponent("list.delete_confirm_message", wp.getName())
                ));
            }).pos(rightBtnX, itemY + 2).size(18, 18).build();
            this.addDrawableChild(delBtn);
            this.dynamicWidgets.add(delBtn);

            // Edit button
            rightBtnX -= 20;
            ButtonWidget editBtn = ModernButton.modernBuilder(Text.literal(""), btn -> {
                this.client.setScreen(new WaypointCreateScreen(wp));
            }).pos(rightBtnX, itemY + 2).size(18, 18).build();
            this.addDrawableChild(editBtn);
            this.dynamicWidgets.add(editBtn);

            // Share / Dimension Toggle button
            boolean isConvertible = wp.getDimension() != null && (wp.getDimension().equals("minecraft:overworld") || wp.getDimension().equals("minecraft:the_nether"));
            if (isConvertible) {
                rightBtnX -= 20;
                ButtonWidget shareBtn = ModernButton.modernBuilder(Text.literal(""), btn -> {
                    wp.setShared(!wp.isShared());
                    WaypointRenderer.saveToFile();
                    rebuildWaypointList();
                }).pos(rightBtnX, itemY + 2).size(18, 18).build();
                this.addDrawableChild(shareBtn);
                this.dynamicWidgets.add(shareBtn);
            }

            // TP button
            if (this.cachedShowTP) {
                rightBtnX -= 20;
                ButtonWidget tpBtn = ModernButton.modernBuilder(Text.literal(""), btn -> {
                    if (this.client.player != null) {
                        BlockPos tpPos = entry.displayPos;
                        this.client.player.networkHandler.sendChatCommand(
                            String.format("tp @s %d %d %d", tpPos.getX(), tpPos.getY(), tpPos.getZ())
                        );
                        this.close();
                    }
                }).pos(rightBtnX, itemY + 2).size(18, 18).build();
                this.addDrawableChild(tpBtn);
                this.dynamicWidgets.add(tpBtn);
            }

            // Visibility toggle button
            rightBtnX -= 20;
            ButtonWidget visBtn = ModernButton.modernBuilder(Text.literal(""), btn -> {
                wp.setVisible(!wp.isVisible());
                WaypointRenderer.saveToFile();
                rebuildWaypointList();
            }).pos(rightBtnX, itemY + 2).size(18, 18).build();
            this.addDrawableChild(visBtn);
            this.dynamicWidgets.add(visBtn);

            // Focus toggle button
            rightBtnX -= 20;
            ButtonWidget focusBtn = ModernButton.modernBuilder(Text.literal(""), btn -> {
                boolean targetFocus = !wp.isFocused();
                for (Waypoint w : WaypointRenderer.waypoints) {
                    w.setFocused(false);
                }
                wp.setFocused(targetFocus);
                WaypointRenderer.saveToFile();
                rebuildWaypointList();
            }).pos(rightBtnX, itemY + 2).size(18, 18).build();
            this.addDrawableChild(focusBtn);
            this.dynamicWidgets.add(focusBtn);
        }

        // Tab Dimension selection buttons
        int dimBtnY = centerY - 92;
        int dimBtnW = 76;
        int dimBtnH = 18;
        int dimStartX = centerX - (DIMENSIONS.size() * dimBtnW + (DIMENSIONS.size() - 1) * 4) / 2;

        for (int d = 0; d < DIMENSIONS.size(); d++) {
            final String dimKey = DIMENSIONS.get(d);
            int btnX = dimStartX + d * (dimBtnW + 4);
            String labelKey = "list.dim." + dimKey.replace("minecraft:", "");
            
            ButtonWidget dimBtn = ModernButton.modernBuilder(I18nHelper.getComponent(labelKey), btn -> {
                this.selectedDimension = dimKey;
                this.scrollIndex = 0;
                rebuildWaypointList();
            }).pos(btnX, dimBtnY).size(dimBtnW, dimBtnH).build();

            dimBtn.active = !this.selectedDimension.equals(dimKey);
            this.addDrawableChild(dimBtn);
            this.dynamicWidgets.add(dimBtn);
        }

        // Bottom control buttons: Create & Close
        ButtonWidget createBtn = ModernButton.modernBuilder(I18nHelper.getComponent("list.new_btn"), btn -> {
            BlockPos pPos = this.client.player != null ? this.client.player.getBlockPos() : BlockPos.ORIGIN;
            this.client.setScreen(new WaypointCreateScreen(pPos));
        }).pos(centerX - 118, centerY + 67).size(114, 20).build();
        this.addDrawableChild(createBtn);
        this.dynamicWidgets.add(createBtn);

        ButtonWidget closeBtn = ModernButton.modernBuilder(I18nHelper.getComponent("list.close_btn"), btn -> {
            this.close();
        }).pos(centerX + 4, centerY + 67).size(114, 20).build();
        this.addDrawableChild(closeBtn);
        this.dynamicWidgets.add(closeBtn);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount != 0) {
            int total = this.cachedFilteredWaypoints.size();
            int maxScroll = Math.max(0, total - ITEMS_PER_PAGE);
            if (verticalAmount < 0 && this.scrollIndex < maxScroll) {
                this.scrollIndex++;
                rebuildWaypointList();
                return true;
            } else if (verticalAmount > 0 && this.scrollIndex > 0) {
                this.scrollIndex--;
                rebuildWaypointList();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        int bgW = 270;
        int bgH = 200;
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

        context.drawCenteredTextWithShadow(this.textRenderer, I18nHelper.getComponent("list.title"), centerX, centerY - 94, 0xFFFFFFFF);

        int listTopY = centerY - 46;
        int itemHeight = 22;
        int listWidth = 240;
        int listLeft = centerX - listWidth / 2;

        // Render waypoints list items
        int totalWaypoints = this.cachedFilteredWaypoints.size();
        int endIndex = Math.min(this.scrollIndex + ITEMS_PER_PAGE, totalWaypoints);

        for (int i = this.scrollIndex; i < endIndex; i++) {
            WaypointEntry entry = this.cachedFilteredWaypoints.get(i);
            Waypoint wp = entry.waypoint;
            int itemY = listTopY + (i - this.scrollIndex) * itemHeight;

            int rowBg = (i % 2 == 0) ? 0x40161C2A : 0x20161C2A;
            if (!wp.isVisible()) {
                rowBg = 0x20000000;
            }
            context.fill(listLeft, itemY, listLeft + listWidth, itemY + itemHeight - 2, rowBg);

            // Color indicator pill
            context.fill(listLeft + 3, itemY + 3, listLeft + 7, itemY + itemHeight - 5, wp.getColor());

            // Name and coordinates
            int nameColor = wp.isVisible() ? 0xFFFFFFFF : 0xFF777777;
            context.drawText(this.textRenderer, entry.nameComponent, listLeft + 12, itemY + 2, nameColor, false);
            
            int coordsColor = entry.converted ? 0xFFFFB74D : 0xFFA0A0A0;
            context.drawText(this.textRenderer, entry.coordsComponent, listLeft + 12, itemY + 11, coordsColor, false);
        }

        // Empty state message
        if (totalWaypoints == 0) {
            context.drawCenteredTextWithShadow(this.textRenderer, I18nHelper.getComponent("list.empty"), centerX, centerY - 10, 0xFF888888);
        }

        super.render(context, mouseX, mouseY, delta);

        // Draw item button icons
        for (int i = this.scrollIndex; i < endIndex; i++) {
            WaypointEntry entry = this.cachedFilteredWaypoints.get(i);
            Waypoint wp = entry.waypoint;
            int itemY = listTopY + (i - this.scrollIndex) * itemHeight;

            int rightBtnX = centerX + 118;

            // Delete icon
            rightBtnX -= 18;
            context.drawTexture(RenderLayer::getGuiTextured, ICON_DELETE, rightBtnX + 1, itemY + 3, 0.0f, 0.0f, 16, 16, 16, 16);

            // Edit icon
            rightBtnX -= 20;
            context.drawTexture(RenderLayer::getGuiTextured, ICON_EDIT, rightBtnX + 1, itemY + 3, 0.0f, 0.0f, 16, 16, 16, 16);

            // Share / Dimension Toggle icon
            boolean isConvertible = wp.getDimension() != null && (wp.getDimension().equals("minecraft:overworld") || wp.getDimension().equals("minecraft:the_nether"));
            if (isConvertible) {
                rightBtnX -= 20;
                Identifier shareIcon = wp.isShared() ? ICON_DIM_ON : ICON_DIM_OFF;
                context.drawTexture(RenderLayer::getGuiTextured, shareIcon, rightBtnX + 1, itemY + 3, 0.0f, 0.0f, 16, 16, 16, 16);
            }

            // TP icon
            if (this.cachedShowTP) {
                rightBtnX -= 20;
                context.drawTexture(RenderLayer::getGuiTextured, ICON_TP, rightBtnX + 1, itemY + 3, 0.0f, 0.0f, 16, 16, 16, 16);
            }

            // Vis icon
            rightBtnX -= 20;
            Identifier visIcon = wp.isVisible() ? ICON_SHOW : ICON_HIDE;
            context.drawTexture(RenderLayer::getGuiTextured, visIcon, rightBtnX + 1, itemY + 3, 0.0f, 0.0f, 16, 16, 16, 16);

            // Focus icon
            rightBtnX -= 20;
            Identifier focusIcon = wp.isFocused() ? ICON_FOCUS_ON : ICON_FOCUS_OFF;
            context.drawTexture(RenderLayer::getGuiTextured, focusIcon, rightBtnX + 1, itemY + 3, 0.0f, 0.0f, 16, 16, 16, 16);
        }

        // Scrollbar indicator
        if (totalWaypoints > ITEMS_PER_PAGE) {
            int scrollTrackH = ITEMS_PER_PAGE * itemHeight - 2;
            int thumbH = Math.max(12, scrollTrackH * ITEMS_PER_PAGE / totalWaypoints);
            int maxThumbY = scrollTrackH - thumbH;
            int maxScroll = totalWaypoints - ITEMS_PER_PAGE;
            int thumbY = listTopY + (this.scrollIndex * maxThumbY / maxScroll);

            context.fill(centerX + 121, listTopY, centerX + 123, listTopY + scrollTrackH, 0x40FFFFFF);
            context.fill(centerX + 121, thumbY, centerX + 123, thumbY + thumbH, 0xFFA0A0A0);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        if (this.parentScreen != null) {
            this.client.setScreen(this.parentScreen);
        } else {
            super.close();
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}

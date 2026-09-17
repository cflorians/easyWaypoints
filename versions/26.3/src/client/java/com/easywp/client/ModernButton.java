package com.easywp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Custom dark translucent button matching the v1.2.0 modal UI theme.
 */
public class ModernButton extends Button {

    public ModernButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    public static ModernBuilder modernBuilder(Component message, OnPress onPress) {
        return new ModernBuilder(message, onPress);
    }

    public static class ModernBuilder {
        private final Component message;
        private final OnPress onPress;
        private int x;
        private int y;
        private int width = 150;
        private int height = 20;

        public ModernBuilder(Component message, OnPress onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        public ModernBuilder pos(int x, int y) {
            this.x = x;
            this.y = y;
            return this;
        }

        public ModernBuilder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public ModernButton build() {
            return new ModernButton(this.x, this.y, this.width, this.height, this.message, this.onPress);
        }
    }

    @Override
    protected void extractContents(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        if (!this.visible) return;

        boolean isHovered = mouseX >= this.getX() && mouseY >= this.getY() &&
                            mouseX < this.getX() + this.width && mouseY < this.getY() + this.height;

        int bgColor;
        int borderColor;
        int textColor;

        if (!this.active) {
            bgColor = UiPalette.BTN_BG_OFF;
            borderColor = UiPalette.BTN_BORDER_OFF;
            textColor = UiPalette.BTN_TEXT_OFF;
        } else if (isHovered) {
            bgColor = UiPalette.BTN_BG_HOVER;
            borderColor = UiPalette.BTN_BORDER_HOVER;
            textColor = UiPalette.BTN_TEXT_HOVER;
        } else {
            bgColor = UiPalette.BTN_BG;
            borderColor = UiPalette.BTN_BORDER;
            textColor = UiPalette.BTN_TEXT;
        }

        graphics.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, borderColor);
        graphics.fill(this.getX() + 1, this.getY() + 1, this.getX() + this.width - 1, this.getY() + this.height - 1, bgColor);

        if (this.getMessage() != null && !this.getMessage().getString().isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            int textX = this.getX() + this.width / 2;
            int textY = this.getY() + (this.height - 8) / 2;
            graphics.centeredText(mc.font, this.getMessage(), textX, textY, textColor);
        }
    }
}

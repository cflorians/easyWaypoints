package com.easywp.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Custom dark translucent button matching the v1.2.0 modal UI theme (1.21.x Yarn Mappings).
 */
public class ModernButton extends ButtonWidget {

    public ModernButton(int x, int y, int width, int height, Text message, PressAction onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION_SUPPLIER);
    }

    public static ModernBuilder modernBuilder(Text message, PressAction onPress) {
        return new ModernBuilder(message, onPress);
    }

    public static class ModernBuilder {
        private final Text message;
        private final PressAction onPress;
        private int x;
        private int y;
        private int width = 150;
        private int height = 20;

        public ModernBuilder(Text message, PressAction onPress) {
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
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!this.visible) return;

        boolean isHovered = mouseX >= this.getX() && mouseY >= this.getY() &&
                            mouseX < this.getX() + this.getWidth() && mouseY < this.getY() + this.getHeight();

        int bgColor;
        int borderColor;
        int textColor;

        if (!this.active) {
            bgColor = 0x3010141E;
            borderColor = 0x50252D40;
            textColor = 0xFF666666;
        } else if (isHovered) {
            bgColor = 0xE025324A;
            borderColor = 0xFF5E75A8;
            textColor = 0xFFFFFFFF;
        } else {
            bgColor = 0xB0141A26;
            borderColor = 0xFF3B4866;
            textColor = 0xFFD0D0D0;
        }

        // Draw border frame and interior fill efficiently
        context.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), borderColor);
        context.fill(this.getX() + 1, this.getY() + 1, this.getX() + this.getWidth() - 1, this.getY() + this.getHeight() - 1, bgColor);

        if (this.getMessage() != null && !this.getMessage().getString().isEmpty()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            int textX = this.getX() + this.getWidth() / 2;
            int textY = this.getY() + (this.getHeight() - 8) / 2;
            context.drawCenteredTextWithShadow(mc.textRenderer, this.getMessage(), textX, textY, textColor);
        }
    }
}

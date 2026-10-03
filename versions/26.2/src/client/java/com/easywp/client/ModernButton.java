package com.easywp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Custom dark translucent button matching the v1.2.0 modal UI theme.
 */
public class ModernButton extends Button {

    /**
     * Draws a button's icon, in the button's own render pass and from its own position, so the
     * icon can never drift away from where the button actually is (or be clicked in one place and
     * drawn in another). Called after the background and label.
     */
    @FunctionalInterface
    public interface IconPainter {
        void paint(GuiGraphicsExtractor graphics, ModernButton button, boolean hovered);
    }

    private IconPainter icon;

    /**
     * Whether the message has any text to draw. Kept rather than asked of the message every frame:
     * most of these buttons are icon-only with an empty literal, and resolving the message to a
     * string just to find that out cost a string build per button per frame. Emptiness cannot
     * change without {@link #setMessage} - a translated message with a missing key falls back to
     * the key itself, never to nothing.
     */
    private boolean hasLabel;

    public ModernButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.hasLabel = hasText(message);
    }

    @Override
    public void setMessage(Component message) {
        super.setMessage(message);
        this.hasLabel = hasText(message);
    }

    private static boolean hasText(Component message) {
        return message != null && !message.getString().isEmpty();
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
        private IconPainter icon;

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

        public ModernBuilder icon(IconPainter icon) {
            this.icon = icon;
            return this;
        }

        public ModernButton build() {
            ModernButton button = new ModernButton(this.x, this.y, this.width, this.height, this.message, this.onPress);
            button.icon = this.icon;
            return button;
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

        if (this.hasLabel) {
            Minecraft mc = Minecraft.getInstance();
            int textX = this.getX() + this.width / 2;
            int textY = this.getY() + (this.height - 8) / 2;
            graphics.centeredText(mc.font, this.getMessage(), textX, textY, textColor);
        }

        if (this.icon != null) {
            this.icon.paint(graphics, this, isHovered);
        }
    }
}

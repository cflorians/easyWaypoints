package com.easywp.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** Shared icon identifiers and layered blit helpers for the mod's dark UI. Icons are white masks tinted by UiPalette. */
public final class Icons {
    private Icons() {}

    public static final Identifier MARKER_FILL    = Identifier.fromNamespaceAndPath("easywp", "textures/gui/marker_fill.png");
    public static final Identifier MARKER_OUTLINE = Identifier.fromNamespaceAndPath("easywp", "textures/gui/marker_outline.png");
    public static final Identifier MARKER_SKULL   = Identifier.fromNamespaceAndPath("easywp", "textures/gui/marker_skull.png");
    public static final Identifier PORTAL_FRAME   = Identifier.fromNamespaceAndPath("easywp", "textures/gui/portal_frame.png");
    public static final Identifier PORTAL_FILL    = Identifier.fromNamespaceAndPath("easywp", "textures/gui/portal_fill.png");

    public static final Identifier CANCEL  = Identifier.fromNamespaceAndPath("easywp", "textures/gui/cancelbutton.png");
    public static final Identifier LIST    = Identifier.fromNamespaceAndPath("easywp", "textures/gui/listbutton.png");
    public static final Identifier CONFIG  = Identifier.fromNamespaceAndPath("easywp", "textures/gui/configicon.png");
    public static final Identifier DELETE  = Identifier.fromNamespaceAndPath("easywp", "textures/gui/delete.png");
    public static final Identifier EDIT    = Identifier.fromNamespaceAndPath("easywp", "textures/gui/edit.png");
    public static final Identifier SHOW    = Identifier.fromNamespaceAndPath("easywp", "textures/gui/show.png");
    public static final Identifier HIDE    = Identifier.fromNamespaceAndPath("easywp", "textures/gui/hide.png");
    public static final Identifier TP      = Identifier.fromNamespaceAndPath("easywp", "textures/gui/tp.png");
    public static final Identifier SHARE   = Identifier.fromNamespaceAndPath("easywp", "textures/gui/sharebutton.png");

    /** Marker in layers: fill tinted with the waypoint's own color (skip if 0), then outline, then an optional skull badge. */
    public static void drawMarkerIcon(GuiGraphicsExtractor g, RenderPipeline pipeline, int x, int y,
                                       int fillColor, int outlineColor, boolean skull) {
        if (fillColor != 0) {
            g.blit(pipeline, MARKER_FILL, x, y, 0.0f, 0.0f, 18, 18, 18, 18, fillColor);
        }
        g.blit(pipeline, MARKER_OUTLINE, x, y, 0.0f, 0.0f, 18, 18, 18, 18, outlineColor);
        if (skull) {
            g.blit(pipeline, MARKER_SKULL, x, y, 0.0f, 0.0f, 18, 18, 18, 18, UiPalette.MARKER_SKULL);
        }
    }

    /**
     * Nether portal icon: obsidian frame whenever the control is usable (matching a real portal's
     * always-visible stone frame), purple swirl fill only while shared. Only truly blocked states
     * (e.g. sharing disabled in The End) fall back to a plain gray frame.
     */
    public static void drawPortalIcon(GuiGraphicsExtractor g, RenderPipeline pipeline, int x, int y,
                                       boolean shared, boolean enabled) {
        if (!enabled) {
            g.blit(pipeline, PORTAL_FRAME, x, y, 0.0f, 0.0f, 18, 18, 18, 18, UiPalette.ICON_DISABLED);
            return;
        }
        if (shared) {
            g.blit(pipeline, PORTAL_FILL, x, y, 0.0f, 0.0f, 18, 18, 18, 18, UiPalette.ICON_ACCENT);
        }
        g.blit(pipeline, PORTAL_FRAME, x, y, 0.0f, 0.0f, 18, 18, 18, 18, UiPalette.PORTAL_FRAME);
    }
}

package com.easywp.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * The vanilla calls and constants that were renamed across 26.1, 26.2 and 26.3, behind one name each.
 *
 * <p>This class is the <em>only</em> per-version file in the client layer that exists for the sake
 * of the version difference itself. Before it, four otherwise byte-identical screens and managers
 * each carried the rename inline, which kept them off {@code checkSharedSources}' list and left
 * them unguarded against a real divergence: {@code DeathWaypointManager}, {@code ModConfigScreen},
 * {@code ModKeyBindings} and {@code WaypointListScreen} differed from their 26.2 twins by nothing
 * but these calls. Routing through here makes those four identical and therefore checkable. Since
 * the waypoints moved to the HUD it is also the last per-version file left: the renderer, the
 * client entrypoint and the create screen are shared too, their differences reduced to the
 * {@link #isHudHidden} and {@link #cameraRenderState} accessors below.
 *
 * <p>Deliberately not moved to {@code common/}: it names {@code net.minecraft} types, which that
 * module cannot compile against (see CLAUDE.md). It stays duplicated, and it stays the one file
 * that is <em>supposed</em> to differ.
 *
 * <p>Add to it only for renames of this shape - same semantics, different vanilla name. Anything
 * where the versions genuinely do different work belongs in the caller, where the difference can
 * be explained - or, better, lifted out of the version difference altogether, the way moving the
 * waypoints from three per-version world renderers to one HUD renderer did.
 *
 * <p>26.3 widened what "rename" covers. It moved {@code RenderPipeline} out of
 * {@code com.mojang.blaze3d.pipeline} into {@code com.mojang.renderpearl.api.pipeline}, and it
 * replaced GLFW with SDL, which renamed every key constant and one {@code InputConstants.Type}
 * value. A moved <em>type</em> cannot hide behind a method that merely takes it, so
 * {@link #blitTinted} takes the texture and looks the pipeline up itself - that is what keeps
 * {@code Icons}, {@code WaypointListScreen} and {@code WaypointCreateScreen} from naming the type
 * and dropping out of the guarded set.
 */
public final class ClientCompat {

    private ClientCompat() { }

    /** 26.2 renamed this to {@code setScreenAndShow}, which also forces a frame. */
    public static void setScreen(Minecraft client, Screen screen) {
        client.setScreen(screen);
    }

    /** 26.2 moved the overlay message behind {@code gui.hud}. */
    public static void setOverlayMessage(Minecraft client, Component message, boolean animate) {
        client.gui.setOverlayMessage(message, animate);
    }

    /**
     * The keyboard half of {@code InputConstants.Type}. 26.1/26.2 called it {@code KEYSYM} - GLFW's
     * name for a layout-mapped key; 26.3 moved to SDL and renamed it {@code KEYBOARD}.
     */
    public static final InputConstants.Type KEYBOARD = InputConstants.Type.KEYSYM;

    // Default key codes for the five bindings, plus the two Enter keys the create screen accepts.
    // 26.1/26.2 read them off org.lwjgl.glfw.GLFW; 26.3 dropped LWJGL's GLFW binding for SDL and
    // publishes the same codes as constants on InputConstants itself, under SDL's own names -
    // RETURN and NUMPADENTER for the two Enters.
    public static final int KEY_K = GLFW.GLFW_KEY_K;
    public static final int KEY_N = GLFW.GLFW_KEY_N;
    public static final int KEY_J = GLFW.GLFW_KEY_J;
    public static final int KEY_H = GLFW.GLFW_KEY_H;
    public static final int KEY_V = GLFW.GLFW_KEY_V;
    public static final int KEY_ENTER = GLFW.GLFW_KEY_ENTER;
    public static final int KEY_KP_ENTER = GLFW.GLFW_KEY_KP_ENTER;

    /**
     * A GUI blit through the textured pipeline, tinted multiplicatively (result = texture x color).
     *
     * <p>Callers used to hold a {@code RenderPipeline} of their own and pass it in, which named a
     * type 26.3 moved to another package. Doing the lookup here keeps that type out of the shared
     * files entirely; every call site passed {@code RenderPipelines.GUI_TEXTURED} anyway, so
     * nothing is lost by not letting them choose.
     */
    public static void blitTinted(GuiGraphicsExtractor graphics, Identifier texture, int x, int y,
                                  int width, int height, int color) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0.0f, 0.0f, width, height, width, height, color);
    }

    /** Whether the player hid the HUD (F1). 26.1 keeps it on {@code Options.hideGui}; 26.2 moved it to {@code Hud.isHidden()}. */
    public static boolean isHudHidden(Minecraft client) {
        return client.options.hideGui;
    }

    /**
     * The camera state of the frame being extracted - filled before the GUI is, so the HUD reads
     * the same camera the world is drawn with.
     * 26.1 names the accessor {@code getGameRenderState()}; 26.2 renamed it {@code gameRenderState()}.
     */
    public static CameraRenderState cameraRenderState(Minecraft client) {
        return client.gameRenderer.getGameRenderState().levelRenderState.cameraRenderState;
    }
}

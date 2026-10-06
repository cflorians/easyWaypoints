package com.easywp.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

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

    /** 26.1 had this on {@code Minecraft}. Not {@code setScreenAndShow}: that one renders a frame on
     *  the spot, without advancing game time, which showed as a flicker on every screen change. */
    public static void setScreen(Minecraft client, Screen screen) {
        client.gui.setScreen(screen);
    }

    /** 26.1 had this directly on {@code gui}, without the {@code hud} indirection. */
    public static void setOverlayMessage(Minecraft client, Component message, boolean animate) {
        client.gui.hud.setOverlayMessage(message, animate);
    }

    /** Adds a local line to the chat, seen only by this player. 26.1 had the chat directly on
     *  {@code gui}, without the {@code hud} indirection. */
    public static void addChatMessage(Minecraft client, Component message) {
        client.gui.hud.getChat().addClientSystemMessage(message);
    }

    /**
     * The keyboard half of {@code InputConstants.Type}. 26.1/26.2 called it {@code KEYSYM} - GLFW's
     * name for a layout-mapped key; 26.3 moved to SDL and renamed it {@code KEYBOARD}.
     */
    public static final InputConstants.Type KEYBOARD = InputConstants.Type.KEYBOARD;

    // Default key codes for the five bindings, plus the two Enter keys the create screen accepts.
    // 26.1/26.2 read them off org.lwjgl.glfw.GLFW; 26.3 dropped LWJGL's GLFW binding for SDL and
    // publishes the same codes as constants on InputConstants itself, under SDL's own names -
    // RETURN and NUMPADENTER for the two Enters.
    public static final int KEY_K = InputConstants.KEY_K;
    public static final int KEY_N = InputConstants.KEY_N;
    public static final int KEY_J = InputConstants.KEY_J;
    public static final int KEY_H = InputConstants.KEY_H;
    public static final int KEY_V = InputConstants.KEY_V;
    public static final int KEY_ENTER = InputConstants.KEY_RETURN;
    public static final int KEY_KP_ENTER = InputConstants.KEY_NUMPADENTER;

    /**
     * The left mouse button as {@code MouseButtonEvent.button()} reports it. GLFW numbered it 0;
     * SDL, which 26.3 switched to, numbers it 1 (and right is 3, not 1). Comparing against a literal
     * 0 made every hand-handled click - the menus, the list's double-click copy - dead on 26.3.
     */
    public static final int MOUSE_LEFT = InputConstants.MOUSE_BUTTON_LEFT;

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

    /** Whether the player hid the HUD (F1). 26.1 kept it on {@code Options.hideGui}; 26.2 moved it to {@code Hud.isHidden()}. */
    public static boolean isHudHidden(Minecraft client) {
        return client.gui.hud.isHidden();
    }

    /**
     * The camera state of the frame being extracted - filled before the GUI is, so the HUD reads
     * the same camera the world is drawn with.
     * 26.1 named the accessor {@code getGameRenderState()}; 26.2 renamed it {@code gameRenderState()}.
     */
    public static CameraRenderState cameraRenderState(Minecraft client) {
        return client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
    }
}

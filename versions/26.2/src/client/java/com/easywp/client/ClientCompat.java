package com.easywp.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

import static java.lang.invoke.MethodType.methodType;

/**
 * The vanilla calls and constants that were renamed across 26.1, 26.2 and 26.3, behind one name each.
 *
 * <p>This copy is shared by 26.1 and 26.2, which ship as <b>one jar</b>: it is compiled against
 * 26.1 and must run unchanged on 26.2, and the 26.2 subproject compiles this same source as a check
 * (see {@code checkMergedJar} in the root build.gradle). So nothing here may name a member that
 * only one of the two has. The renames between them are resolved once, by name, when this class
 * loads:
 * <ul>
 *   <li>{@code setScreen} moved from {@code Minecraft} to {@code Gui} in 26.2. Both versions also
 *       have {@code Minecraft.setScreenAndShow}, but that one renders a frame on the spot, without
 *       advancing game time - mid-click, between two screens - which showed as a flicker on every
 *       screen change, so it is not used.</li>
 *   <li>26.2 split the HUD out of {@code Gui} into a public {@code Gui.hud} field. The overlay
 *       message and the chat moved there with the same names, and the F1 flag moved from
 *       {@code Options.hideGui} to {@code Hud.isHidden()}. Whether {@code Gui} has that field is
 *       the one question that tells the two apart.</li>
 *   <li>{@code GameRenderer.getGameRenderState()} became {@code gameRenderState()}.</li>
 * </ul>
 * The handles are {@code static final}, so the JIT folds them into direct calls: the two read every
 * frame ({@link #isHudHidden}, {@link #cameraRenderState}) cost what the plain calls did. A version
 * where none of the names resolves fails here, at startup, rather than on the first use -
 * {@code fabric.mod.json} keeps the jar off any version it was not built for.
 *
 * <p>26.3 keeps its own copy: it moved {@code RenderPipeline} to another package and replaced GLFW
 * with SDL, which a by-name lookup cannot hide (a moved <em>type</em> appears in the signatures of
 * every class that touches it), and it ships as its own jar.
 *
 * <p>Deliberately not moved to {@code common/}: it names {@code net.minecraft} types, which that
 * module cannot compile against (see CLAUDE.md). Add to it only for renames of this shape - same
 * semantics, different vanilla name. Anything where the versions genuinely do different work belongs
 * in the caller, where the difference can be explained.
 */
public final class ClientCompat {

    private ClientCompat() { }

    /** Whether this is 26.2 or later: {@code Gui} has the {@code hud} field. */
    private static final boolean HUD_SPLIT;
    /** 26.2: {@code (Gui) -> Hud} as {@code Object}. */
    private static final MethodHandle GUI_HUD;
    /** {@code (Gui or Hud, Component, boolean) -> void}. */
    private static final MethodHandle SET_OVERLAY_MESSAGE;
    /** {@code (Gui or Hud) -> ChatComponent}. */
    private static final MethodHandle GET_CHAT;
    /** 26.2: {@code (Hud) -> boolean}. */
    private static final MethodHandle HUD_IS_HIDDEN;
    /** 26.1: {@code (Options) -> boolean}, the {@code hideGui} field. */
    private static final MethodHandle OPTIONS_HIDE_GUI;
    /** {@code (GameRenderer) -> GameRenderState}. */
    private static final MethodHandle GAME_RENDER_STATE;
    /** Whether {@code setScreen} is on {@code Gui} (26.2) rather than on {@code Minecraft} (26.1). */
    private static final boolean SCREEN_ON_GUI;
    /** {@code (Minecraft or Gui, Screen) -> void}. */
    private static final MethodHandle SET_SCREEN;

    static {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        try {
            Field hud = null;
            try {
                hud = Gui.class.getField("hud");
            } catch (NoSuchFieldException before262) {
                // 26.1: the overlay message, the chat and F1 are still on Gui and Options.
            }
            HUD_SPLIT = hud != null;
            Class<?> holder = HUD_SPLIT ? hud.getType() : Gui.class;

            GUI_HUD = HUD_SPLIT ? lookup.unreflectGetter(hud).asType(methodType(Object.class, Gui.class)) : null;
            SET_OVERLAY_MESSAGE = lookup.findVirtual(holder, "setOverlayMessage",
                            methodType(void.class, Component.class, boolean.class))
                    .asType(methodType(void.class, Object.class, Component.class, boolean.class));
            GET_CHAT = lookup.findVirtual(holder, "getChat", methodType(ChatComponent.class))
                    .asType(methodType(ChatComponent.class, Object.class));
            HUD_IS_HIDDEN = HUD_SPLIT
                    ? lookup.findVirtual(holder, "isHidden", methodType(boolean.class))
                            .asType(methodType(boolean.class, Object.class))
                    : null;
            OPTIONS_HIDE_GUI = HUD_SPLIT ? null : lookup.findGetter(Options.class, "hideGui", boolean.class);
            GAME_RENDER_STATE = findFirst(lookup, GameRenderer.class, methodType(GameRenderState.class),
                    "gameRenderState", "getGameRenderState");
            MethodType setScreen = methodType(void.class, Screen.class);
            MethodHandle onMinecraft = null;
            try {
                onMinecraft = lookup.findVirtual(Minecraft.class, "setScreen", setScreen);
            } catch (NoSuchMethodException since262) {
                // 26.2: Gui.setScreen.
            }
            SCREEN_ON_GUI = onMinecraft == null;
            SET_SCREEN = (SCREEN_ON_GUI ? lookup.findVirtual(Gui.class, "setScreen", setScreen) : onMinecraft)
                    .asType(methodType(void.class, Object.class, Screen.class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("easyWp does not support this Minecraft version's client API", e);
        }
    }

    /** The first of {@code names} that {@code owner} has with {@code type}. */
    private static MethodHandle findFirst(MethodHandles.Lookup lookup, Class<?> owner,
                                          MethodType type, String... names)
            throws ReflectiveOperationException {
        ReflectiveOperationException last = null;
        for (String name : names) {
            try {
                return lookup.findVirtual(owner, name, type);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                last = e;
            }
        }
        throw last;
    }

    /** What {@code gui.hud} is on 26.2, and the {@code Gui} itself on 26.1 - where the overlay
     *  message and the chat live. */
    private static Object hudHolder(Minecraft client) throws Throwable {
        return HUD_SPLIT ? (Object) GUI_HUD.invokeExact(client.gui) : client.gui;
    }

    /** A {@code Throwable} from a handle, back out as what vanilla threw. */
    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException e) return e;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

    /** {@code Minecraft.setScreen} on 26.1, {@code gui.setScreen} on 26.2. */
    public static void setScreen(Minecraft client, Screen screen) {
        try {
            SET_SCREEN.invokeExact(SCREEN_ON_GUI ? (Object) client.gui : (Object) client, screen);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code gui.setOverlayMessage} on 26.1, {@code gui.hud.setOverlayMessage} on 26.2. */
    public static void setOverlayMessage(Minecraft client, Component message, boolean animate) {
        try {
            SET_OVERLAY_MESSAGE.invokeExact(hudHolder(client), message, animate);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Adds a local line to the chat, seen only by this player: {@code gui.getChat()} on 26.1,
     *  {@code gui.hud.getChat()} on 26.2. */
    public static void addChatMessage(Minecraft client, Component message) {
        ChatComponent chat;
        try {
            chat = (ChatComponent) GET_CHAT.invokeExact(hudHolder(client));
        } catch (Throwable t) {
            throw rethrow(t);
        }
        chat.addClientSystemMessage(message);
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

    /** Whether the player hid the HUD (F1): {@code Options.hideGui} on 26.1, {@code Hud.isHidden()}
     *  on 26.2. */
    public static boolean isHudHidden(Minecraft client) {
        try {
            return HUD_SPLIT
                    ? (boolean) HUD_IS_HIDDEN.invokeExact(hudHolder(client))
                    : (boolean) OPTIONS_HIDE_GUI.invokeExact(client.options);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * The camera state of the frame being extracted - filled before the GUI is, so the HUD reads
     * the same camera the world is drawn with. The accessor is {@code getGameRenderState()} on 26.1
     * and {@code gameRenderState()} on 26.2.
     */
    public static CameraRenderState cameraRenderState(Minecraft client) {
        GameRenderState state;
        try {
            state = (GameRenderState) GAME_RENDER_STATE.invokeExact(client.gameRenderer);
        } catch (Throwable t) {
            throw rethrow(t);
        }
        return state.levelRenderState.cameraRenderState;
    }
}

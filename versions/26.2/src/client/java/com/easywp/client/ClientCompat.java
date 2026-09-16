package com.easywp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The two vanilla calls that were renamed between 26.1 and 26.2, behind one name each.
 *
 * <p>This class is the <em>only</em> per-version file in the client layer that exists for the sake
 * of the version difference itself. Before it, four otherwise byte-identical screens and managers
 * each carried the rename inline, which kept them off {@code checkSharedSources}' list and left
 * them unguarded against a real divergence: {@code DeathWaypointManager}, {@code ModConfigScreen},
 * {@code ModKeyBindings} and {@code WaypointListScreen} differed from their 26.2 twins by nothing
 * but these calls. Routing through here makes those four identical and therefore checkable, and
 * narrows {@code WaypointCreateScreen}'s remaining difference down to a single javadoc paragraph.
 *
 * <p>Deliberately not moved to {@code common/}: it names {@code net.minecraft} types, which that
 * module cannot compile against (see CLAUDE.md). It stays duplicated, and it stays the one file
 * that is <em>supposed</em> to differ.
 *
 * <p>Add to it only for renames of this shape - same semantics, different vanilla name. Anything
 * where the two versions genuinely do different work belongs in the caller, where the difference
 * can be explained: {@code EasyWpClient}'s {@code END_MAIN} vs {@code COLLECT_SUBMITS} hook and the
 * two renderers are that case, not this one.
 */
public final class ClientCompat {

    private ClientCompat() { }

    /** 26.1 called this {@code setScreen}; this variant also forces a frame. */
    public static void setScreen(Minecraft client, Screen screen) {
        client.setScreenAndShow(screen);
    }

    /** 26.1 had this directly on {@code gui}, without the {@code hud} indirection. */
    public static void setOverlayMessage(Minecraft client, Component message, boolean animate) {
        client.gui.hud.setOverlayMessage(message, animate);
    }
}

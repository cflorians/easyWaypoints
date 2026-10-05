package com.easywp.client;

import com.easywp.WaypointShareFormat;
import com.easywp.XaeroWaypointFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * What sharing a waypoint sends to chat: the list's share button and {@code /easywp share} alike,
 * so the two cannot drift apart.
 */
public final class WaypointShare {

    private WaypointShare() {
    }

    /**
     * The line for {@code wp}: {@link WaypointShareFormat}'s, or - with
     * {@code xaeroIntegration.xaeroChatFormat} on - the {@code xaero-waypoint:} line Xaero's own chat
     * listener turns into an {@code [Add]} button for recipients who run it.
     *
     * <p>Plain text, no colour: vanilla strips the section sign from any chat message a player sends
     * before broadcasting it (a long-standing anti-spoofing measure), so no client mod can colour
     * another player's view of it.
     */
    public static String chatLine(Waypoint wp) {
        BlockPos pos = wp.getPos();
        if (ModConfig.get().xaeroIntegration.xaeroChatFormat) {
            // The set name is irrelevant to chatShareLine (the chat format carries no set field at
            // all - see its javadoc) but the convenience constructor requires one.
            XaeroWaypointFormat.XaeroWaypoint xwp = new XaeroWaypointFormat.XaeroWaypoint(
                    wp.getName(), pos.getX(), pos.getY(), pos.getZ(),
                    XaeroWaypointFormat.nearestColorOrdinal(wp.getColor()),
                    XaeroWaypointFormat.DEFAULT_SET_NAME
            );
            return XaeroWaypointFormat.chatShareLine(xwp);
        }
        return WaypointShareFormat.format(wp.getName(), wp.dimensionOrOverworld(), pos.getX(), pos.getY(), pos.getZ());
    }

    /** Sends {@link #chatLine} as a public chat message. @return whether there was a connection to
     *  send it on */
    public static boolean send(Minecraft client, Waypoint wp) {
        if (client.player == null || client.player.connection == null) return false;
        client.player.connection.sendChat(chatLine(wp));
        return true;
    }
}

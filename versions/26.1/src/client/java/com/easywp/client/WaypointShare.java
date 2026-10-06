package com.easywp.client;

import com.easywp.WaypointShareFormat;
import com.easywp.XaeroWaypointFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every way a waypoint leaves or enters as text: the list's share menu (public, private, copy),
 * {@code /easywp share}, the clipboard export and paste, and the lines the chat {@code [+]} reads -
 * one place, so they cannot drift apart.
 */
public final class WaypointShare {

    /** How long a line this player sent counts as their own when it comes back in chat. */
    private static final long OWN_LINE_MS = 30_000;

    /** Lines this player sent recently, with when: the echo of a public line, and vanilla's
     *  "You whisper to Bob: ..." of a private one, must not get a {@code [+]}. */
    private static final Deque<SentLine> recentlySent = new ArrayDeque<>();

    private record SentLine(String line, long at) {
    }

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
     *
     * <p>Always something the server accepts: the name loses any character vanilla refuses in chat
     * (a section sign, a control character - which only an imported name can carry), and is cut
     * short if the line would pass {@code maxLength}. Either would otherwise get the player
     * disconnected instead of the message sent. {@code null} only if not even a one-letter name fits.
     */
    public static String chatLine(Waypoint wp, int maxLength) {
        BlockPos pos = wp.getPos();
        String name = WaypointShareFormat.chatSafe(wp.getName()).strip();
        if (name.isEmpty()) {
            name = "Waypoint";
        }
        if (ModConfig.get().xaeroIntegration.xaeroChatFormat) {
            // The set name is irrelevant to chatShareLine (the chat format carries no set field at
            // all - see its javadoc) but the convenience constructor requires one.
            int colorOrdinal = XaeroWaypointFormat.nearestColorOrdinal(wp.getColor());
            return WaypointShareFormat.fitLine(name, maxLength, n -> XaeroWaypointFormat.chatShareLine(
                    new XaeroWaypointFormat.XaeroWaypoint(n, pos.getX(), pos.getY(), pos.getZ(),
                            colorOrdinal, XaeroWaypointFormat.DEFAULT_SET_NAME)));
        }
        String dimension = wp.dimensionOrOverworld();
        return WaypointShareFormat.fitLine(name, maxLength,
                n -> WaypointShareFormat.format(n, dimension, pos.getX(), pos.getY(), pos.getZ()));
    }

    /** Always {@link WaypointShareFormat}'s line, whatever the chat setting - what is copied. */
    public static String plainLine(Waypoint wp) {
        BlockPos pos = wp.getPos();
        return WaypointShareFormat.format(wp.getName(), wp.dimensionOrOverworld(), pos.getX(), pos.getY(), pos.getZ());
    }

    /** Sends {@link #chatLine} as a public chat message. @return whether there was a connection to
     *  send it on */
    public static boolean send(Minecraft client, Waypoint wp) {
        if (client.player == null || client.player.connection == null) return false;
        String line = chatLine(wp, WaypointShareFormat.CHAT_MAX_LENGTH);
        if (line == null) return false;
        remember(line);
        client.player.connection.sendChat(line);
        return true;
    }

    /** Sends {@link #chatLine} to one player with vanilla's {@code /msg}, which every server has. */
    public static boolean sendPrivate(Minecraft client, Waypoint wp, String playerName) {
        if (client.player == null || client.player.connection == null) return false;
        String command = "msg " + playerName + " ";
        String line = chatLine(wp, WaypointShareFormat.CHAT_MAX_LENGTH - command.length());
        if (line == null) return false;
        remember(line);
        client.player.connection.sendCommand(command + line);
        return true;
    }

    /** Copies {@code wp}'s {@link #plainLine} to the clipboard. */
    public static void copy(Minecraft client, Waypoint wp) {
        client.keyboardHandler.setClipboard(plainLine(wp));
    }

    /** One {@link #plainLine} per waypoint, a line each - the list's clipboard export. */
    public static String exportLines(List<Waypoint> waypoints) {
        StringBuilder sb = new StringBuilder();
        for (Waypoint wp : waypoints) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(plainLine(wp));
        }
        return sb.toString();
    }

    /** The other players online, by name, sorted - who a private share can go to. Empty in
     *  singleplayer, and on a server with nobody else on it. */
    public static List<String> otherPlayers(Minecraft client) {
        List<String> names = new ArrayList<>();
        ClientPacketListener connection = client.getConnection();
        if (connection == null || client.player == null) return names;
        Collection<PlayerInfo> online = connection.getOnlinePlayers();
        for (PlayerInfo info : online) {
            if (info.getProfile().id().equals(client.player.getUUID())) continue;
            String name = info.getProfile().name();
            // Tab-list decorations some server plugins add as fake entries often have blank or
            // spaced names; no /msg can reach those.
            if (name == null || name.isBlank() || name.contains(" ")) continue;
            names.add(name);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /**
     * The waypoints in pasted text, one per line: share lines ({@link WaypointShareFormat#findIn},
     * so a line copied out of chat with its sender still reads) and {@code xaero-waypoint:} lines.
     * Anything else is skipped. A share line has no colour, so it gets a random one, like Fast Ping;
     * a Xaero line's comes from its palette index. A dimension the line cannot name, or this server
     * does not have, becomes the player's own - as does the Y of a Xaero waypoint saved without one.
     */
    public static List<Waypoint> parseLines(Minecraft client, String text) {
        List<Waypoint> out = new ArrayList<>();
        if (text == null || client.player == null) return out;
        String here = currentDimension(client);
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            WaypointShareFormat.Shared shared = WaypointShareFormat.findIn(line);
            if (shared != null) {
                out.add(new Waypoint(shared.name(), new BlockPos(shared.x(), shared.y(), shared.z()),
                        WaypointColors.random(), resolveDimension(client, shared.dimension())));
                continue;
            }
            XaeroWaypointFormat.XaeroWaypoint xaero = XaeroWaypointFormat.parseChatShareLine(line);
            if (xaero != null) {
                int y = xaero.y() != null ? xaero.y() : client.player.getBlockY();
                out.add(new Waypoint(xaero.name(), new BlockPos(xaero.x(), y, xaero.z()),
                        XaeroWaypointFormat.rgbForColorOrdinal(xaero.colorOrdinal()), here));
            }
        }
        return out;
    }

    /** {@code dimension} if this server has that level, else the player's own. */
    public static String resolveDimension(Minecraft client, String dimension) {
        if (dimension != null && knownDimensions(client).contains(dimension)) {
            return dimension;
        }
        return currentDimension(client);
    }

    public static String currentDimension(Minecraft client) {
        return client.level != null ? client.level.dimension().identifier().toString() : DimensionNames.OVERWORLD;
    }

    private static Set<String> knownDimensions(Minecraft client) {
        Set<String> ids = new LinkedHashSet<>();
        ClientPacketListener connection = client.getConnection();
        if (connection != null) {
            for (ResourceKey<Level> key : connection.levels()) {
                ids.add(key.identifier().toString());
            }
        }
        return ids;
    }

    /** Whether {@code message} carries a line this player sent in the last {@link #OWN_LINE_MS}. */
    public static boolean isOwnLine(String message) {
        long now = System.currentTimeMillis();
        while (!recentlySent.isEmpty() && now - recentlySent.peekFirst().at() > OWN_LINE_MS) {
            recentlySent.removeFirst();
        }
        for (SentLine sent : recentlySent) {
            if (message.contains(sent.line())) return true;
        }
        return false;
    }

    private static void remember(String line) {
        recentlySent.addLast(new SentLine(line, System.currentTimeMillis()));
        while (recentlySent.size() > 16) {
            recentlySent.removeFirst();
        }
    }
}

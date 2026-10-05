package com.easywp.client;

import com.easywp.WaypointShareFormat;
import com.easywp.XaeroWaypointFormat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts a {@code [+]} under every received chat message carrying a shared waypoint: a share line
 * ({@link WaypointShareFormat}) anywhere in the message, or an {@code xaero-waypoint:} line when
 * Xaero's Minimap is <em>not</em> installed (when it is, Xaero already offers its own
 * {@code [Add]}). Clicking it runs {@code /easywp open ...}, which opens the create screen filled in,
 * for the player to adjust name and colour before saving.
 *
 * <p>Both events are listened to: a player's chat ({@code CHAT}), and system messages
 * ({@code GAME}) - which is how {@code /msg} and most chat plugins deliver theirs, with a prefix in
 * front. Messages this player sent are skipped: the sender's own id on {@code CHAT}, and on
 * {@code GAME} (the "You whisper to Bob" echo) any line {@link WaypointShare} sent in the last
 * seconds.
 *
 * <p>The {@code [+]} is added on the next tick rather than from inside the event: the event runs
 * before vanilla adds the message itself, so it would land above the message it belongs to.
 */
public final class ChatAddButtons {

    private static final List<Component> pending = new ArrayList<>();

    private ChatAddButtons() {
    }

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            Minecraft client = Minecraft.getInstance();
            if (sender != null && client.player != null && sender.id().equals(client.player.getUUID())) return;
            // The player's own text, when the message carries it, reads a name containing ": " or
            // "> " whole, which the decorated text cannot (see WaypointShareFormat.findIn).
            String text = message.getString();
            if (signedMessage != null) {
                WaypointShareFormat.Shared shared = WaypointShareFormat.parse(signedMessage.signedContent());
                if (shared != null) {
                    queueIfNotOwn(client, text, shared);
                    return;
                }
            }
            handle(client, text);
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                handle(Minecraft.getInstance(), message.getString());
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pending.isEmpty()) return;
            for (Component line : pending) {
                ClientCompat.addChatMessage(client, line);
            }
            pending.clear();
        });
    }

    private static void handle(Minecraft client, String text) {
        WaypointShareFormat.Shared shared = WaypointShareFormat.findIn(text);
        if (shared != null) {
            queueIfNotOwn(client, text, shared);
            return;
        }
        if (XaeroBridge.isModInstalled()) return;
        XaeroWaypointFormat.XaeroWaypoint xaero = XaeroWaypointFormat.parseChatShareLine(text);
        if (xaero != null && !WaypointShare.isOwnLine(text)) {
            // No dimension in Xaero's line: the receiver's own, as Xaero itself does. No Y: "~".
            queue(client, xaero.name(), null, String.valueOf(xaero.x()),
                    xaero.y() != null ? String.valueOf(xaero.y()) : "~", String.valueOf(xaero.z()));
        }
    }

    private static void queueIfNotOwn(Minecraft client, String text, WaypointShareFormat.Shared shared) {
        if (WaypointShare.isOwnLine(text)) return;
        queue(client, shared.name(), shared.dimension(),
                String.valueOf(shared.x()), String.valueOf(shared.y()), String.valueOf(shared.z()));
    }

    private static void queue(Minecraft client, String name, String dimension, String x, String y, String z) {
        if (client.player == null) return;
        String dim = WaypointShare.resolveDimension(client, dimension);
        String command = "/easywp open " + dim + " " + x + " " + y + " " + z + " " + name;
        Component hover = I18nHelper.getComponent("chat.add_hover", name,
                DimensionNames.chatName(dim), x, y, z);
        MutableComponent line = I18nHelper.getComponent("chat.add_button")
                .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                .append(I18nHelper.getComponent("chat.add_text", name).withStyle(style -> style
                        .withColor(ChatFormatting.GRAY).withBold(false)));
        line.withStyle(style -> style
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(hover)));
        pending.add(line);
    }
}

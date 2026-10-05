package com.easywp.client;

import com.easywp.DistanceFormat;
import com.easywp.RelativeCoordinate;
import com.easywp.WaypointShareFormat;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.permissions.Permissions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/**
 * The client-side {@code /easywp} command: {@code list}, {@code add}, {@code remove},
 * {@code focus}, {@code share}, {@code tp} and {@code open}. Client commands never reach the
 * server, so they work on any server, mod or not.
 *
 * <p>Fabric runs a client command for both a typed command and a chat click
 * ({@code ClientPacketListener.sendCommand} and {@code sendUnattendedCommand}), before vanilla can
 * ask for confirmation - which is why {@code list}'s TP click runs {@code /easywp tp} rather than
 * the vanilla {@code /execute ... tp} directly: vanilla asks before sending a clicked command that
 * needs permissions.
 *
 * <p>A name at the end of a command ({@code remove}, {@code focus}, {@code share}, {@code tp},
 * {@code open}) is the rest of the line, so spaces need no quotes. {@code add}'s name has
 * coordinates after it, so a name with spaces is quoted there. Brigadier's own unquoted strings
 * accept only {@code [0-9A-Za-z_.+-]}, which would refuse an accent or {@code ~}, so names,
 * coordinates and colours are read as plain space-separated tokens by the argument types below.
 */
public final class EasyWpCommands {

    /** Vanilla {@code EditBox}'s default limit, which the create screen's name field keeps: a longer
     *  name from {@code add} would be cut short the first time it was edited. A name must also fit
     *  the list's width ({@code WaypointListScreen.NAME_FIT_WIDTH}), which is usually the tighter. */
    static final int MAX_NAME_LENGTH = 32;

    /** A screen a command asked for, opened on the next tick: the chat closes itself right after
     *  running the command, and would close a screen opened from inside it. */
    private static Screen pendingScreen;

    private static final SimpleCommandExceptionType EMPTY_NAME =
            new SimpleCommandExceptionType(I18nHelper.getComponent("command.empty_name"));
    private static final DynamicCommandExceptionType BAD_COORDINATE =
            new DynamicCommandExceptionType(text -> I18nHelper.getComponent("command.bad_coordinate", text));
    private static final DynamicCommandExceptionType BAD_COLOR =
            new DynamicCommandExceptionType(text -> I18nHelper.getComponent("command.bad_color", text));

    private EasyWpCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> build(dispatcher));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pendingScreen != null) {
                Screen screen = pendingScreen;
                pendingScreen = null;
                if (client.level != null) {
                    ClientCompat.setScreen(client, screen);
                }
            }
        });
    }

    private static void build(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("easywp")
                .executes(ctx -> help(ctx.getSource()))
                .then(literal("list").executes(ctx -> list(ctx.getSource())))
                .then(literal("add")
                        .then(argument("name", NameArgument.INSTANCE)
                                .executes(ctx -> add(ctx, false, false))
                                // Before the coordinates on purpose: Brigadier keeps the first of two
                                // branches that both read the whole input, so a lone six-digit token
                                // ("add Base 123456") is a colour, not an X waiting for Y and Z.
                                .then(argument("color", ColorArgument.INSTANCE)
                                        .executes(ctx -> add(ctx, false, true)))
                                .then(argument("x", CoordinateArgument.INSTANCE)
                                        .then(argument("y", CoordinateArgument.INSTANCE)
                                                .then(argument("z", CoordinateArgument.INSTANCE)
                                                        .executes(ctx -> add(ctx, true, false))
                                                        .then(argument("color", ColorArgument.INSTANCE)
                                                                .executes(ctx -> add(ctx, true, true))))))))
                .then(literal("remove").then(existingName().executes(EasyWpCommands::remove)))
                .then(literal("focus").then(existingName().executes(EasyWpCommands::focus)))
                .then(literal("share").then(existingName().executes(EasyWpCommands::share)))
                .then(literal("tp").then(existingName().executes(EasyWpCommands::teleport)))
                .then(literal("open")
                        .then(argument("dimension", NameArgument.INSTANCE)
                                .then(argument("x", CoordinateArgument.INSTANCE)
                                        .then(argument("y", CoordinateArgument.INSTANCE)
                                                .then(argument("z", CoordinateArgument.INSTANCE)
                                                        .then(argument("name", StringArgumentType.greedyString())
                                                                .executes(EasyWpCommands::open))))))));
    }

    // ---- subcommands ----

    private static int help(FabricClientCommandSource source) {
        source.sendFeedback(I18nHelper.getComponent("command.help"));
        return 1;
    }

    private static int list(FabricClientCommandSource source) {
        Minecraft client = source.getClient();
        LocalPlayer player = source.getPlayer();
        WaypointStore.checkAndLoadWorldWaypoints();
        String dimension = currentDimension(client);

        List<Waypoint> here = new ArrayList<>();
        for (Waypoint wp : WaypointStore.waypoints) {
            if (wp != null && !wp.isPendingRemoval() && wp.positionIn(dimension) != null) {
                here.add(wp);
            }
        }
        here.sort(Comparator.comparingDouble(wp -> distanceTo(player, wp)));

        String dimName = DimensionNames.chatName(dimension);
        if (here.isEmpty()) {
            source.sendFeedback(I18nHelper.getComponent("command.list_empty", dimName));
            return 0;
        }
        source.sendFeedback(I18nHelper.getComponent("command.list_header", dimName, String.valueOf(here.size()))
                .withStyle(ChatFormatting.GOLD));

        boolean useKm = !ModConfig.get().labelDisplay.metersOnly;
        boolean showTp = canTeleport(player);
        for (Waypoint wp : here) {
            BlockPos pos = wp.getPos();
            MutableComponent line = Component.literal(" ■ ").withColor(wp.getColor() & 0xFFFFFF);
            line.append(Component.literal(wp.getName()).withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(
                    Component.literal(pos.getX() + " " + pos.getY() + " " + pos.getZ())))));
            line.append(Component.literal(" (" + DistanceFormat.format((int) distanceTo(player, wp), useKm) + ")")
                    .withStyle(ChatFormatting.GRAY));
            line.append(" ");
            line.append(action("command.list_focus", "command.list_focus_hover", "/easywp focus " + wp.getName(),
                    ChatFormatting.AQUA));
            if (showTp) {
                line.append(" ");
                line.append(action("command.list_tp", "command.list_tp_hover", "/easywp tp " + wp.getName(),
                        ChatFormatting.GREEN));
            }
            source.sendFeedback(line);
        }
        return here.size();
    }

    private static int add(CommandContext<FabricClientCommandSource> ctx, boolean hasCoordinates, boolean hasColor) {
        FabricClientCommandSource source = ctx.getSource();
        Minecraft client = source.getClient();
        LocalPlayer player = source.getPlayer();
        WaypointStore.checkAndLoadWorldWaypoints();

        String name = ctx.getArgument("name", String.class).trim();
        if (name.length() > MAX_NAME_LENGTH || client.font.width(name) > WaypointListScreen.NAME_FIT_WIDTH) {
            source.sendError(I18nHelper.getComponent("command.name_too_long"));
            return 0;
        }

        BlockPos pos;
        if (hasCoordinates) {
            pos = BlockPos.containing(
                    coordinate(ctx, "x", player.getX()),
                    coordinate(ctx, "y", player.getY()),
                    coordinate(ctx, "z", player.getZ()));
        } else {
            pos = player.blockPosition();
        }
        // Without a colour, a random one - the same pick as Fast Ping and a new waypoint's form.
        int color = hasColor ? ctx.getArgument("color", Integer.class) : WaypointColors.random();

        Waypoint wp = new Waypoint(name, pos, color, currentDimension(client));
        WaypointStore.waypoints.add(wp);
        WaypointStore.saveToFile();
        ModKeyBindings.showWaypointsAfterCreate();

        source.sendFeedback(I18nHelper.getComponent("command.added", name,
                String.valueOf(pos.getX()), String.valueOf(pos.getY()), String.valueOf(pos.getZ())));
        return 1;
    }

    /** Deletes at once: unlike the list, there is no row to offer Undo from. */
    private static int remove(CommandContext<FabricClientCommandSource> ctx) {
        Waypoint wp = resolve(ctx);
        if (wp == null) return 0;
        WaypointStore.waypoints.remove(wp);
        WaypointStore.saveToFile();
        ctx.getSource().sendFeedback(I18nHelper.getComponent("command.removed", wp.getName()));
        return 1;
    }

    private static int focus(CommandContext<FabricClientCommandSource> ctx) {
        Waypoint wp = resolve(ctx);
        if (wp == null) return 0;
        WaypointStore.focus(wp);
        ctx.getSource().sendFeedback(I18nHelper.getComponent("command.focused", wp.getName()));
        return 1;
    }

    private static int share(CommandContext<FabricClientCommandSource> ctx) {
        Waypoint wp = resolve(ctx);
        if (wp == null) return 0;
        return WaypointShare.send(ctx.getSource().getClient(), wp) ? 1 : 0;
    }

    private static int teleport(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        if (!canTeleport(source.getPlayer())) {
            source.sendError(I18nHelper.getComponent("command.no_permission"));
            return 0;
        }
        Waypoint wp = resolve(ctx);
        if (wp == null) return 0;
        // Not a client command, so Fabric hands it on to the server.
        source.getPlayer().connection.sendCommand(teleportCommand(wp));
        return 1;
    }

    /**
     * Opens the create screen filled in with a shared waypoint, for the player to adjust before
     * saving - what the chat's {@code [+]} runs. A dimension this server does not have (or that the
     * line could not name) becomes the player's own.
     */
    private static int open(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        Minecraft client = source.getClient();
        LocalPlayer player = source.getPlayer();

        String dimension = WaypointShare.resolveDimension(client,
                WaypointShareFormat.dimensionFromLabel(ctx.getArgument("dimension", String.class)));
        BlockPos pos = BlockPos.containing(
                coordinate(ctx, "x", player.getX()),
                coordinate(ctx, "y", player.getY()),
                coordinate(ctx, "z", player.getZ()));
        String name = unquote(ctx.getArgument("name", String.class));
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH);
        }
        // The form also fits it to what the list shows whole (WaypointListScreen.NAME_FIT_WIDTH).
        pendingScreen = new WaypointCreateScreen(pos, dimension, name);
        return 1;
    }

    // ---- shared with the list screen ----

    /** The command the TP button and {@code /easywp tp} send. Block centre on X/Z (x + 0.5, not
     *  "%d.5", which is wrong for negatives), so the player lands in the middle of the block, and
     *  {@code Locale.ROOT} so a comma-decimal locale cannot break it. */
    static String teleportCommand(Waypoint wp) {
        BlockPos pos = wp.getPos();
        return String.format(Locale.ROOT, "execute in %s run tp @s %.1f %d %.1f",
                wp.dimensionOrOverworld(), pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    /** Whether the player may teleport - the TP button's and {@code /easywp tp}'s shared gate. */
    static boolean canTeleport(LocalPlayer player) {
        return player != null && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    // ---- helpers ----

    private static RequiredArgumentBuilder<FabricClientCommandSource, String> existingName() {
        return argument("name", StringArgumentType.greedyString()).suggests(EasyWpCommands::suggestNames);
    }

    /**
     * The waypoint the {@code name} argument names, or {@code null} after telling the player there
     * is none. An exact match wins over one that differs only in case. With several of the same
     * name, the nearest in the player's dimension (or else the first) - and the player is told.
     */
    private static Waypoint resolve(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        WaypointStore.checkAndLoadWorldWaypoints();
        String name = unquote(ctx.getArgument("name", String.class));

        List<Waypoint> matches = matching(name, false);
        if (matches.isEmpty()) {
            matches = matching(name, true);
        }
        if (matches.isEmpty()) {
            source.sendError(I18nHelper.getComponent("command.not_found", name));
            return null;
        }

        String dimension = currentDimension(source.getClient());
        LocalPlayer player = source.getPlayer();
        Waypoint best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Waypoint wp : matches) {
            if (wp.positionIn(dimension) == null) continue;
            double d = distanceTo(player, wp);
            if (d < bestDistance) {
                bestDistance = d;
                best = wp;
            }
        }
        if (best == null) {
            best = matches.get(0);
        }
        if (matches.size() > 1) {
            source.sendFeedback(I18nHelper.getComponent("command.ambiguous", String.valueOf(matches.size()), name)
                    .withStyle(ChatFormatting.YELLOW));
        }
        return best;
    }

    private static List<Waypoint> matching(String name, boolean ignoreCase) {
        List<Waypoint> out = new ArrayList<>();
        for (Waypoint wp : WaypointStore.waypoints) {
            if (wp == null || wp.isPendingRemoval() || wp.getName() == null) continue;
            if (ignoreCase ? wp.getName().equalsIgnoreCase(name) : wp.getName().equals(name)) {
                out.add(wp);
            }
        }
        return out;
    }

    /** Every waypoint name in this world, the player's own dimension first - though Brigadier sorts
     *  the suggestions alphabetically in the end. */
    private static CompletableFuture<Suggestions> suggestNames(CommandContext<FabricClientCommandSource> ctx,
                                                               SuggestionsBuilder builder) {
        WaypointStore.checkAndLoadWorldWaypoints();
        String typed = builder.getRemainingLowerCase();
        Set<String> names = new LinkedHashSet<>();
        for (Waypoint wp : WaypointStore.waypoints) {
            if (wp != null && !wp.isPendingRemoval() && wp.getName() != null
                    && wp.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                names.add(wp.getName());
            }
        }
        names.forEach(builder::suggest);
        return builder.buildFuture();
    }

    private static MutableComponent action(String labelKey, String hoverKey, String command, ChatFormatting color) {
        return I18nHelper.getComponent(labelKey).withStyle(style -> style
                .withColor(color)
                .withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(I18nHelper.getComponent(hoverKey))));
    }

    /** A coordinate argument against the player's position on that axis. The argument type already
     *  refused anything {@link RelativeCoordinate} cannot read, so this always has a value. */
    private static double coordinate(CommandContext<FabricClientCommandSource> ctx, String name, double base) {
        OptionalDouble value = RelativeCoordinate.parse(ctx.getArgument(name, String.class), base);
        return value.isPresent() ? value.getAsDouble() : base;
    }

    private static double distanceTo(LocalPlayer player, Waypoint wp) {
        BlockPos pos = wp.getPos();
        double dx = pos.getX() + 0.5 - player.getX();
        double dy = pos.getY() + 0.5 - player.getY();
        double dz = pos.getZ() + 0.5 - player.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String currentDimension(Minecraft client) {
        return WaypointShare.currentDimension(client);
    }

    /** A rest-of-line name typed in quotes, as {@code add} requires, works here too. */
    private static String unquote(String text) {
        String trimmed = text.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** Everything up to the next space - Brigadier's argument separator. */
    private static String readToken(StringReader reader) {
        int start = reader.getCursor();
        while (reader.canRead() && reader.peek() != ' ') {
            reader.skip();
        }
        return reader.getString().substring(start, reader.getCursor());
    }

    // ---- argument types (parsed on the client only, so nothing needs registering) ----

    /** A quoted string (with Brigadier's escapes) or a single token of any characters. */
    private static final class NameArgument implements ArgumentType<String> {
        static final NameArgument INSTANCE = new NameArgument();

        @Override
        public String parse(StringReader reader) throws CommandSyntaxException {
            int start = reader.getCursor();
            String value = reader.canRead() && StringReader.isQuotedStringStart(reader.peek())
                    ? reader.readQuotedString()
                    : readToken(reader);
            if (value.isBlank()) {
                reader.setCursor(start);
                throw EMPTY_NAME.createWithContext(reader);
            }
            return value;
        }
    }

    /** A number, or {@code ~}/{@code ~5}/{@code ~-3} relative to the player - {@link RelativeCoordinate}. */
    private static final class CoordinateArgument implements ArgumentType<String> {
        static final CoordinateArgument INSTANCE = new CoordinateArgument();

        @Override
        public String parse(StringReader reader) throws CommandSyntaxException {
            int start = reader.getCursor();
            String token = readToken(reader);
            // Any finite base: this only checks that the text reads as a coordinate.
            if (RelativeCoordinate.parse(token, 0).isEmpty()) {
                reader.setCursor(start);
                throw BAD_COORDINATE.createWithContext(reader, token);
            }
            return token;
        }

        @Override
        public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
            if ("~".startsWith(builder.getRemaining())) {
                builder.suggest("~");
            }
            return builder.buildFuture();
        }
    }

    /** {@code #RRGGBB} or {@code RRGGBB}, as an opaque ARGB colour. */
    private static final class ColorArgument implements ArgumentType<Integer> {
        static final ColorArgument INSTANCE = new ColorArgument();

        @Override
        public Integer parse(StringReader reader) throws CommandSyntaxException {
            int start = reader.getCursor();
            String token = readToken(reader);
            String hex = token.startsWith("#") ? token.substring(1) : token;
            if (hex.length() != 6 || !hex.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                reader.setCursor(start);
                throw BAD_COLOR.createWithContext(reader, token);
            }
            return 0xFF000000 | Integer.parseInt(hex, 16);
        }
    }
}

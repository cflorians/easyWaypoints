package com.easywp.client;

import com.easywp.AsyncJsonWriter;
import com.easywp.EasyWp;
import com.easywp.JsonStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * General, world-independent mod settings, stored once at
 * {@code config/easywp/config.json}. Grouped by feature so future settings have
 * a place to land without reshaping the file.
 */
public class ModConfig {
    /** Reused across loads and saves - Gson instances are safe to share and hold no per-call state. */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public DeathWaypoints deathWaypoints = new DeathWaypoints();
    public WaypointSize waypointSize = new WaypointSize();
    public LabelDisplay labelDisplay = new LabelDisplay();
    public Confirmations confirmations = new Confirmations();
    public Visibility visibility = new Visibility();
    public Ping ping = new Ping();
    public ListOptions list = new ListOptions();
    public XaeroIntegration xaeroIntegration = new XaeroIntegration();
    public LookFade lookFade = new LookFade();

    public static class DeathWaypoints {
        public boolean enabled = true;
        public double radius = 2.0;
        /** Seconds the player must stand within {@code radius} of the death waypoint, without
         *  leaving, before it is deleted. Resets if the player steps back out of range. */
        public double graceSeconds = 1.0;
        /** How many death waypoints can exist at once before the oldest is deleted to make room
         *  for a new one. 1 (default) reproduces the original behavior: only the latest death is kept. */
        public int maxCount = 1;
    }

    public static class WaypointSize {
        /** Percentage multiplier applied to the renderer's default min/max marker size. 100 = default size. */
        public double sizePercent = 100.0;
        /** Marker icon opacity, 0-100. 100 = fully opaque (default). Does not affect label text. */
        public double opacityPercent = 100.0;
    }

    /**
     * The fade applied to waypoints the player is not looking at (see WaypointRenderer). A group of
     * its own rather than fields on WaypointSize: neither default is Gson's zero value, and a field
     * added to an existing group reads as 0 from a config.json that predates it.
     */
    public static class LookFade {
        /** Opacity, 0-100, of a waypoint the player is not looking at. 100 turns the fade off. */
        public double unlookedOpacityPercent = 60.0;
        /** Size of the "looking at it" area around each waypoint, 1-7: 1 smallest, 7 largest. */
        public int lookAreaSize = 3;
    }

    public static class LabelDisplay {
        /** Renders in-world/HUD waypoint marker labels in all caps when true. */
        public boolean uppercase = false;
        /** Appends the "(Xm)" distance suffix to marker labels when true. */
        public boolean showDistance = true;
        /** Keeps the distance in metres at any range when true. False (the default) shows it in
         *  kilometres from 1000 m on ({@code 1.2km}) - see {@code DistanceFormat}. Named this way
         *  round so that Gson's {@code false}, which a config.json from before the setting reads
         *  as, is the default: kilometres on. */
        public boolean metersOnly = false;
    }

    public static class Confirmations {
        /** Shows the "Are you sure?" dialog before deleting a waypoint when true. */
        public boolean confirmBeforeDelete = true;
    }

    public static class Visibility {
        /** Persists and restores the world-marker show/hide state across game restarts when true. */
        public boolean rememberOnExit = false;
        /** Last known visibility state; only meaningful when rememberOnExit is true. */
        public boolean lastVisible = true;
        /** At most this many waypoints drawn at once, the nearest on screen, the focused one always
         *  among them; 0 (the default) is no limit. 0 is also what a config.json from before the
         *  setting reads as, so it needs no group of its own. */
        public int maxVisible = 0;
    }

    public static class Ping {
        /** How far the ping ray reaches, in blocks. Read once per key press, never per frame. */
        public double maxDistance = 128.0;
        /** Lets fluid surfaces stop the ping ray when true; the ray passes through them when false. */
        public boolean hitFluids = false;
        /** Uses the client's render distance setting as the ping range when true, ignoring maxDistance. */
        public boolean followRenderDistance = false;
    }

    public static class ListOptions {
        /** Row ordering of the waypoint list, stored as a {@link WaypointSortMode} name. */
        public String sortMode = WaypointSortMode.CREATED.name();
    }

    public static class XaeroIntegration {
        /** Master switch. Nothing in this integration ever runs on its own, so this only decides
         *  whether the Import/Export buttons appear in {@code WaypointListScreen} at all (alongside
         *  Xaero actually being installed) and whether those two actions do anything when clicked. */
        public boolean enabled = true;
        /** Shares waypoints in chat using Xaero's own {@code xaero-waypoint:} format (offering
         *  recipients who run Xaero an [Add] button) instead of the plain readable message.
         *  <b>Off</b> by default: a recipient without Xaero sees the raw tag-like string instead of a
         *  readable sentence, and most recipients are in that position, so the readable format is the
         *  one that works for everyone and the clickable one is the opt-in. */
        public boolean xaeroChatFormat = false;
    }

    private static ModConfig instance;

    public static ModConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static Path configFile() {
        return new File(Minecraft.getInstance().gameDirectory, "config/easywp/config.json").toPath();
    }

    private static ModConfig load() {
        Path file = configFile();
        if (Files.isRegularFile(file)) {
            ModConfig loaded = parse(JsonStore.read(file));
            if (loaded == null) {
                loaded = parse(JsonStore.read(JsonStore.backupOf(file)));
                if (loaded != null) {
                    EasyWp.LOGGER.warn("Recovered the mod config from the backup file");
                }
            }
            if (loaded != null) {
                fillMissingGroups(loaded);
                return loaded;
            }
            // Neither the file nor its backup could be read. The defaults stand in for this
            // session but are deliberately not written back: saving over an unreadable file
            // would push it into the backup slot and destroy the last good revision, along
            // with whatever hand edit the user may still want to salvage from it.
            return new ModConfig();
        }

        // First run: write the defaults out so the file exists and is discoverable/editable.
        ModConfig defaults = new ModConfig();
        instance = defaults;
        save();
        return defaults;
    }

    /** @return the parsed config, or {@code null} if {@code json} is absent or not readable as one. */
    private static ModConfig parse(String json) {
        if (json == null) return null;
        try {
            return GSON.fromJson(json, ModConfig.class);
        } catch (Exception e) {
            EasyWp.LOGGER.error("Failed to load mod config, using defaults", e);
            return null;
        }
    }

    /** Backfills the groups a config.json written by an older build has never heard of. */
    private static void fillMissingGroups(ModConfig loaded) {
        if (loaded.deathWaypoints == null) {
            loaded.deathWaypoints = new DeathWaypoints();
        }
        if (loaded.waypointSize == null) {
            loaded.waypointSize = new WaypointSize();
        }
        if (loaded.labelDisplay == null) {
            loaded.labelDisplay = new LabelDisplay();
        }
        if (loaded.confirmations == null) {
            loaded.confirmations = new Confirmations();
        }
        if (loaded.visibility == null) {
            loaded.visibility = new Visibility();
        }
        if (loaded.ping == null) {
            loaded.ping = new Ping();
        }
        if (loaded.list == null) {
            loaded.list = new ListOptions();
        }
        if (loaded.xaeroIntegration == null) {
            loaded.xaeroIntegration = new XaeroIntegration();
        }
        if (loaded.lookFade == null) {
            loaded.lookFade = new LookFade();
        }
    }

    public static void save() {
        if (instance == null) return;
        try {
            // Written in the background: sliders save on every step, and each write forces the
            // file to disk. See AsyncJsonWriter.
            AsyncJsonWriter.SHARED.write(configFile(), GSON.toJson(instance));
        } catch (Exception e) {
            EasyWp.LOGGER.error("Failed to save mod config", e);
        }
    }
}

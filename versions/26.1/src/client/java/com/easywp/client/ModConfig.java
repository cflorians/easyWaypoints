package com.easywp.client;

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
    public DeathWaypoints deathWaypoints = new DeathWaypoints();
    public WaypointSize waypointSize = new WaypointSize();
    public LabelDisplay labelDisplay = new LabelDisplay();
    public Confirmations confirmations = new Confirmations();
    public Visibility visibility = new Visibility();
    public Ping ping = new Ping();
    public ListOptions list = new ListOptions();

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

    public static class LabelDisplay {
        /** Renders in-world/HUD waypoint marker labels in all caps when true. */
        public boolean uppercase = false;
        /** Appends the "(Xm)" distance suffix to marker labels when true. */
        public boolean showDistance = true;
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
            return new Gson().fromJson(json, ModConfig.class);
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
    }

    public static void save() {
        if (instance == null) return;
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            JsonStore.write(configFile(), gson.toJson(instance));
        } catch (Exception e) {
            EasyWp.LOGGER.error("Failed to save mod config", e);
        }
    }
}

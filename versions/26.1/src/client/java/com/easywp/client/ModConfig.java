package com.easywp.client;

import com.easywp.EasyWp;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

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

    public static class DeathWaypoints {
        public boolean enabled = true;
        public double radius = 2.0;
        /** Seconds the player must stand within {@code radius} of the death waypoint, without
         *  leaving, before it is deleted. Resets if the player steps back out of range. */
        public double graceSeconds = 1.0;
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

    private static ModConfig instance;

    public static ModConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static File configFile() {
        return new File(Minecraft.getInstance().gameDirectory, "config/easywp/config.json");
    }

    private static ModConfig load() {
        File file = configFile();
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                ModConfig loaded = new Gson().fromJson(reader, ModConfig.class);
                if (loaded != null) {
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
                    return loaded;
                }
            } catch (Exception e) {
                EasyWp.LOGGER.error("Failed to load mod config, using defaults", e);
            }
            return new ModConfig();
        }

        // First run: write the defaults out so the file exists and is discoverable/editable.
        ModConfig defaults = new ModConfig();
        instance = defaults;
        save();
        return defaults;
    }

    public static void save() {
        if (instance == null) return;
        File file = configFile();
        try {
            file.getParentFile().mkdirs();
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (FileWriter writer = new FileWriter(file)) {
                gson.toJson(instance, writer);
            }
        } catch (Exception e) {
            EasyWp.LOGGER.error("Failed to save mod config", e);
        }
    }
}

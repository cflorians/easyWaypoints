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

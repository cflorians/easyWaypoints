package com.easywp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class I18nHelper {
    public static String getLanguageCode() {
        try {
            String code = Minecraft.getInstance().getLanguageManager().getSelected();
            if (code != null) {
                return code;
            }
        } catch (Throwable ignored) {}
        return "en_us";
    }

    public static boolean isSpanish() {
        String code = getLanguageCode();
        return code != null && code.toLowerCase().startsWith("es_");
    }

    public static String translate(String key) {
        boolean es = isSpanish();
        switch (key) {
            case "menu.title":
                return es ? "Lista de Waypoints" : "Waypoint List";
            case "menu.total":
                return es ? "Total: %d" : "Total: %d";
            case "menu.no_waypoints":
                return es ? "No hay waypoints" : "No waypoints";
            case "menu.new":
                return es ? "Nuevo" : "New";
            case "menu.back":
                return es ? "Volver" : "Back";
            case "menu.confirm_delete_title":
                return es ? "Confirmar Borrado" : "Confirm Deletion";
            case "menu.confirm_delete_text":
                return es ? "¿Estás seguro de borrar \"%s\"?" : "Are you sure you want to delete \"%s\"?";
            case "menu.search":
                return es ? "Buscar..." : "Search...";
            case "menu.search_hint":
                return es ? "Buscar waypoint..." : "Search waypoint...";

            case "create.title.new":
                return es ? "Crear Waypoint" : "Create Waypoint";
            case "create.title.edit":
                return es ? "Editar Waypoint" : "Edit Waypoint";
            case "create.name_label":
                return es ? "Nombre:" : "Name:";
            case "create.coordinates_label":
                return es ? "Coordenadas (X / Y / Z):" : "Coordinates (X / Y / Z):";
            case "create.share_label":
                return es ? "Compartir Dim: %s" : "Share Dim: %s";
            case "create.yes":
                return es ? "SI" : "YES";
            case "create.no":
                return es ? "NO" : "NO";
            case "create.save":
                return es ? "Guardar" : "Save";
            case "create.cancel":
                return es ? "Cancelar" : "Cancel";
            case "create.feedback_edited":
                return es ? "§a¡Waypoint editado! §7(%s) en X: %d Y: %d Z: %d" : "§aWaypoint edited! §7(%s) at X: %d Y: %d Z: %d";
            case "create.feedback_created":
                return es ? "§a¡Waypoint creado! §7(%s) en X: %d Y: %d Z: %d" : "§aWaypoint created! §7(%s) at X: %d Y: %d Z: %d";

            case "hud.visible":
                return es ? "§fWaypoints visibles" : "§fWaypoints visible";
            case "hud.hidden":
                return es ? "§7Waypoints ocultos" : "§7Waypoints hidden";
            case "hud.mode.world":
                return es ? "§fWaypoints activados" : "§fWaypoints enabled";
            case "hud.mode.disabled":
                return es ? "§7Waypoints desactivados" : "§7Waypoints disabled";

            case "death.name":
                return es ? "☠ Muerte %s" : "☠ Death %s";
            case "death.created":
                return es ? "§cWaypoint de muerte creado" : "§cDeath waypoint created";
            case "death.reached":
                return es ? "§aWaypoint de muerte alcanzado" : "§aDeath waypoint reached";

            case "config.title":
                return es ? "Configuración" : "Settings";
            case "config.size_row":
                return es ? "Tamaño: %.0f%%" : "Size: %.0f%%";
            case "config.opacity_row":
                return es ? "Opacidad: %.0f%%" : "Opacity: %.0f%%";
            case "config.show_distance":
                return es ? "Mostrar distancia" : "Show Distance";
            case "config.uppercase_labels":
                return es ? "Texto en mayúsculas" : "Uppercase Text";
            case "config.confirm_delete_toggle":
                return es ? "Confirmar antes de borrar" : "Confirm Before Delete";
            case "config.remember_visibility":
                return es ? "Recordar visibilidad" : "Remember Visibility";
            case "config.section.appearance":
                return es ? "§7Apariencia" : "§7Appearance";
            case "config.section.death_waypoint":
                return es ? "§7Waypoint de muerte" : "§7Death Waypoint";
            case "config.section.behavior":
                return es ? "§7Comportamiento" : "§7Behavior";
            case "config.death_enabled_toggle":
                return es ? "Activar" : "Enabled";
            case "config.radius_row":
                return es ? "Radio: %.0f bloques" : "Radius: %.0f blocks";
            case "config.grace_row":
                return es ? "Tiempo: %.1f s" : "Time: %.1f s";

            default:
                return key;
        }
    }

    public static Component getComponent(String key, Object... args) {
        return Component.literal(String.format(translate(key), args));
    }
}

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
        } catch (Throwable t) {
            // Safe fallback if signature differs in specific envs
        }
        return "en_us";
    }

    public static boolean isSpanish() {
        String code = getLanguageCode();
        return code != null && code.toLowerCase().startsWith("es_");
    }

    public static String translate(String key) {
        boolean es = isSpanish();
        switch (key) {
            // List Screen
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
            
            // Create Screen
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
            
            // HUD
            case "hud.visible": 
                return es ? "§fWaypoints visibles" : "§fWaypoints visible";
            case "hud.hidden": 
                return es ? "§7Waypoints ocultos" : "§7Waypoints hidden";
                
            default: 
                return key;
        }
    }
    
    public static Component getComponent(String key, Object... args) {
        return Component.literal(String.format(translate(key), args));
    }
}

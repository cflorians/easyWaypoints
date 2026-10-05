package com.easywp.client;

import com.easywp.AsyncJsonWriter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;

public class EasyWpClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModKeyBindings.register();
		EasyWpCommands.register();
		ChatAddButtons.register();

		// Waypoints are drawn on the HUD, projected from the world - see WaypointRenderer. Two
		// attachment points because vanilla skips every HUD element while the HUD is hidden (F1)
		// except the sleep overlay; each entry draws only in its own half of that state.
		HudElementRegistry.attachElementBefore(VanillaHudElements.MISC_OVERLAYS,
				Identifier.fromNamespaceAndPath("easywp", "waypoints"), WaypointRenderer::renderHud);
		HudElementRegistry.attachElementBefore(VanillaHudElements.SLEEP,
				Identifier.fromNamespaceAndPath("easywp", "waypoints_hidden_hud"), WaypointRenderer::renderHudWhileHidden);

		ClientTickEvents.END_CLIENT_TICK.register(DeathWaypointManager::tick);

		// Out of a world, drop it: see WaypointStore.forgetWorld. A tick rather than a disconnect
		// event because it runs on the client thread, the only one that touches the waypoint list.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.level == null) {
				WaypointStore.forgetWorld();
			} else {
				// Deletions from the list run out on their own, list open or not.
				WaypointStore.tickPendingRemovals();
			}
		});

		// Saves are written on a daemon thread (AsyncJsonWriter); let the queue finish before the
		// game exits so the last change is never cut off.
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> AsyncJsonWriter.SHARED.flush());
	}
}

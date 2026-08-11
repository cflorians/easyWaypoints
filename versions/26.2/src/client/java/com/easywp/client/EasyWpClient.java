package com.easywp.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

public class EasyWpClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModKeyBindings.register();
		WaypointRenderTypes.init();
		WaypointRenderer.init();

		// Waypoints are queued as world submits rather than drawn immediately, so the hook is
		// the submit collection phase instead of the end of the main pass.
		LevelRenderEvents.COLLECT_SUBMITS.register(WaypointRenderer::render);

		ClientTickEvents.END_CLIENT_TICK.register(DeathWaypointManager::tick);
	}
}

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

		LevelRenderEvents.END_MAIN.register(WaypointRenderer::render);

		ClientTickEvents.END_CLIENT_TICK.register(DeathWaypointManager::tick);
	}
}

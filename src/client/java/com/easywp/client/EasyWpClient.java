package com.easywp.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

public class EasyWpClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModKeyBindings.register();
		WaypointRenderer.init();

		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context -> {
			WaypointRenderer.render(context);
		});
	}
}
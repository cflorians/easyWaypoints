package com.easywp.client;

import net.fabricmc.api.ClientModInitializer;

public class EasyWpClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModKeyBindings.register();
		WaypointRenderer.init();
	}
}
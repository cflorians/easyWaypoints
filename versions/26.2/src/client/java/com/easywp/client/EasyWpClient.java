package com.easywp.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.resources.Identifier;

public class EasyWpClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModKeyBindings.register();
		WaypointRenderer.init();

		// Hook 3D: Capture frame matrices at the end of world rendering
		LevelRenderEvents.END_MAIN.register(WaypointRenderer::captureFrameState);

		// Hook 2D: Draw waypoints on the HUD layer (completely immune to Iris shaderpack darkening)
		HudElementRegistry.addLast(
			Identifier.fromNamespaceAndPath("easywp", "waypoints_hud"),
			(graphics, deltaTracker) -> WaypointRenderer.renderHud(graphics, deltaTracker)
		);
	}
}
package com.easywp.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Puts the mod's own settings screen behind Mod Menu's configure button.
 *
 * <p>Wired through the optional {@code modmenu} entrypoint, which Fabric only instantiates when
 * Mod Menu is installed, so nothing here runs - or needs to resolve - without it. Mod Menu is a
 * compile-only dependency and is never bundled into the jar.
 */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ModConfigScreen::new;
    }
}

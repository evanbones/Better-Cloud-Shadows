package com.evandev.better_cloud_shadows.compat;

import com.evandev.better_cloud_shadows.config.ConfigScreens;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        if (!ConfigScreens.isAvailable()) {
            return screen -> null;
        }
        return ConfigScreens::create;
    }
}

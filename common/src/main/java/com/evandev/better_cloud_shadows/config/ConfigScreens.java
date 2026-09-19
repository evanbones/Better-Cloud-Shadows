package com.evandev.better_cloud_shadows.config;

import com.evandev.better_cloud_shadows.Constants;
import com.evandev.better_cloud_shadows.compat.yacl.YaclConfigScreen;
import com.evandev.better_cloud_shadows.platform.Services;
import net.minecraft.client.gui.screens.Screen;

public final class ConfigScreens {

    public static final String YACL_MOD_ID = "yet_another_config_lib_v3";

    private ConfigScreens() {
    }

    public static boolean isAvailable() {
        return Services.PLATFORM.isModLoaded(YACL_MOD_ID);
    }

    public static Screen create(Screen parent) {
        if (!isAvailable()) return parent;
        try {
            return YaclConfigScreen.create(parent);
        } catch (LinkageError | RuntimeException e) {
            Constants.LOG.error("Failed to build the config screen", e);
            return parent;
        }
    }
}

package com.evandev.better_cloud_shadows.client;

import com.evandev.better_cloud_shadows.config.ConfigScreens;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

public class ClientConfigSetup {
    public static void register(ModContainer container) {
        if (!ConfigScreens.isAvailable()) return;
        container.registerExtensionPoint(IConfigScreenFactory.class, (c, parent) -> ConfigScreens.create(parent));
    }
}

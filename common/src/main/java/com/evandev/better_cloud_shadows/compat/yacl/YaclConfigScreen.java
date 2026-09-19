package com.evandev.better_cloud_shadows.compat.yacl;

import com.evandev.better_cloud_shadows.config.ModConfig;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.DoubleSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class YaclConfigScreen {

    private YaclConfigScreen() {
    }

    public static Screen create(Screen parent) {
        ModConfig config = ModConfig.get();

        return YetAnotherConfigLib.createBuilder()
                .title(Component.translatable("config.better_cloud_shadows.title"))
                .save(ModConfig::save)
                .category(ConfigCategory.createBuilder()
                        .name(Component.translatable("config.better_cloud_shadows.category.general"))
                        .option(Option.<Boolean>createBuilder()
                                .name(name("cloudShadows"))
                                .description(tooltip("cloudShadows"))
                                .binding(true, () -> config.cloudShadows, value -> config.cloudShadows = value)
                                .controller(TickBoxControllerBuilder::create)
                                .build())
                        .option(Option.<Double>createBuilder()
                                .name(name("cloudShadowStrength"))
                                .description(tooltip("cloudShadowStrength"))
                                .binding(0.7, () -> config.cloudShadowStrength, value -> config.cloudShadowStrength = value)
                                .controller(opt -> DoubleSliderControllerBuilder.create(opt).range(0.0, 1.0).step(0.05))
                                .build())
                        .option(Option.<Integer>createBuilder()
                                .name(name("cloudShadowSoftness"))
                                .description(tooltip("cloudShadowSoftness"))
                                .binding(16, () -> config.cloudShadowSoftness, value -> config.cloudShadowSoftness = value)
                                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(0, 64).step(4))
                                .build())
                        .build())
                .build()
                .generateScreen(parent);
    }

    private static Component name(String key) {
        return Component.translatable("config.better_cloud_shadows.option." + key);
    }

    private static OptionDescription tooltip(String key) {
        return OptionDescription.of(Component.translatable("config.better_cloud_shadows.option." + key + ".tooltip"));
    }
}

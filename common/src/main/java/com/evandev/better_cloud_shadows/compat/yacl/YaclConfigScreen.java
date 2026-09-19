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
                                .binding(0.75, () -> config.cloudShadowStrength, value -> config.cloudShadowStrength = value)
                                .controller(opt -> DoubleSliderControllerBuilder.create(opt)
                                        .range(0.0, ModConfig.MAX_STRENGTH)
                                        .step(0.05))
                                .build())
                        .option(Option.<Integer>createBuilder()
                                .name(name("cloudShadowSoftness"))
                                .description(tooltip("cloudShadowSoftness"))
                                .binding(ModConfig.DEFAULT_SOFTNESS,
                                        () -> config.cloudShadowSoftness,
                                        value -> config.cloudShadowSoftness = value)
                                .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                        .range(0, ModConfig.MAX_SOFTNESS)
                                        .step(1)
                                        .formatValue(value -> Component.translatable("config.better_cloud_shadows.blocks", value)))
                                .build())
                        .option(Option.<Integer>createBuilder()
                                .name(name("cloudShadowDistance"))
                                .description(tooltip("cloudShadowDistance"))
                                .binding(ModConfig.DEFAULT_DISTANCE,
                                        () -> config.cloudShadowDistance,
                                        value -> config.cloudShadowDistance = value)
                                .controller(opt -> IntegerSliderControllerBuilder.create(opt)
                                        .range(ModConfig.MIN_DISTANCE, ModConfig.MAX_DISTANCE)
                                        .step(64)
                                        .formatValue(value -> Component.translatable("config.better_cloud_shadows.blocks", value)))
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

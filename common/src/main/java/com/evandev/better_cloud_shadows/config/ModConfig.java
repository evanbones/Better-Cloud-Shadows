package com.evandev.better_cloud_shadows.config;

import com.evandev.better_cloud_shadows.Constants;
import com.evandev.better_cloud_shadows.platform.Services;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.minecraft.util.Mth;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class ModConfig {
    public static final double MAX_STRENGTH = 2.0;
    public static final int MAX_SOFTNESS = 32;
    public static final double MAX_SHEAR = 1.0;
    public static final double DEFAULT_SHEAR = 1.0;
    public static final int DEFAULT_SOFTNESS = 4;
    public static final int MIN_DISTANCE = 128;
    public static final int MAX_DISTANCE = 8192;
    public static final int DEFAULT_DISTANCE = 512;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = Services.PLATFORM.getConfigDirectory().resolve(Constants.MOD_ID + ".json").toFile();
    private static ModConfig INSTANCE;

    @SerializedName("cloudShadows")
    public boolean cloudShadows = true;

    @SerializedName("cloudShadowStrength")
    public double cloudShadowStrength = 0.75;

    @SerializedName("cloudShadowSoftness")
    public int cloudShadowSoftness = DEFAULT_SOFTNESS;

    @SerializedName("matchCloudRenderDistance")
    public boolean matchCloudRenderDistance = true;

    @SerializedName("cloudShadowDistance")
    public int cloudShadowDistance = DEFAULT_DISTANCE;

    @SerializedName("cloudShadowShear")
    public double cloudShadowShear = DEFAULT_SHEAR;

    @SerializedName("distantHorizonsClouds")
    public boolean distantHorizonsClouds = true;

    public static ModConfig get() {
        if (INSTANCE == null) {
            load();
        }
        return INSTANCE;
    }

    public static void load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                INSTANCE = GSON.fromJson(reader, ModConfig.class);
            } catch (Exception e) {
                Constants.LOG.error("Failed to load {}.json", Constants.MOD_ID, e);
                INSTANCE = null;
            }
            if (INSTANCE == null) {
                INSTANCE = new ModConfig();
            }
            INSTANCE.clamp();
            save();
        } else {
            INSTANCE = new ModConfig();
            save();
        }
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(INSTANCE, writer);
        } catch (IOException e) {
            Constants.LOG.error("Failed to save {}.json", Constants.MOD_ID, e);
        }
    }

    private void clamp() {
        cloudShadowStrength = Mth.clamp(cloudShadowStrength, 0.0, MAX_STRENGTH);
        cloudShadowSoftness = Mth.clamp(cloudShadowSoftness, 0, MAX_SOFTNESS);
        cloudShadowShear = Mth.clamp(cloudShadowShear, 0.0, MAX_SHEAR);
        cloudShadowDistance = Mth.clamp(cloudShadowDistance, MIN_DISTANCE, MAX_DISTANCE);
    }
}

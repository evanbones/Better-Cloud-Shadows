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
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = Services.PLATFORM.getConfigDirectory().resolve(Constants.MOD_ID + ".json").toFile();
    private static ModConfig INSTANCE;

    @SerializedName("cloudShadows")
    public boolean cloudShadows = true;

    @SerializedName("cloudShadowStrength")
    public double cloudShadowStrength = 0.7;

    @SerializedName("cloudShadowSoftness")
    public int cloudShadowSoftness = 16;

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
        cloudShadowStrength = Mth.clamp(cloudShadowStrength, 0.0, 1.0);
        cloudShadowSoftness = Mth.clamp(cloudShadowSoftness, 0, 64);
    }
}

package com.example.sbd;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.*;

public class Config {
    private static final String CONFIG_FILE = "config/sbd.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config instance;

    private int requiredTimeSeconds;
    private boolean autoKickEnabled;

    public Config() {
        // Default values
        this.requiredTimeSeconds = 420; // 7 minutes
        this.autoKickEnabled = true;
    }

    public static Config getInstance() {
        if (instance == null) {
            instance = loadConfig();
        }
        return instance;
    }

    public int getRequiredTimeSeconds() {
        return requiredTimeSeconds;
    }

    public void setRequiredTimeSeconds(int requiredTimeSeconds) {
        this.requiredTimeSeconds = requiredTimeSeconds;
        saveConfig();
    }

    public boolean isAutoKickEnabled() {
        return autoKickEnabled;
    }

    public void setAutoKickEnabled(boolean autoKickEnabled) {
        this.autoKickEnabled = autoKickEnabled;
        saveConfig();
    }

    private static Config loadConfig() {
        File configFile = new File(Minecraft.getMinecraft().mcDataDir, CONFIG_FILE);
        if (!configFile.exists()) {
            Config defaultConfig = new Config();
            defaultConfig.saveConfig();
            return defaultConfig;
        }

        try {
            FileReader reader = new FileReader(configFile);
            Config config = GSON.fromJson(reader, Config.class);
            reader.close();
            return config != null ? config : new Config();
        } catch (Exception e) {
            e.printStackTrace();
            return new Config();
        }
    }

    private void saveConfig() {
        try {
            File configFile = new File(Minecraft.getMinecraft().mcDataDir, CONFIG_FILE);
            configFile.getParentFile().mkdirs(); // Create config directory if it doesn't exist
            
            FileWriter writer = new FileWriter(configFile);
            GSON.toJson(this, writer);
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
package com.example.sbd;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import org.apache.commons.io.IOUtils;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class PartyMember {
    private final String name;
    private String uuid;
    private Dungeons dungeons;
    private boolean changed;

    public PartyMember(String name) {
        this.name = name;
        this.uuid = null;
        this.dungeons = new Dungeons();
        this.dungeons.setPb(new PersonalBest());
        this.changed = false;
    }

    public String getName() {
        return name;
    }

    public void init() {
        try {
            // Make API call to fetch player data
            String apiUrl = "https://sky.shiiyu.moe/api/v2/dungeons/" + this.name;
            HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");

            if (connection.getResponseCode() == 200) {
                String response = IOUtils.toString(connection.getInputStream(), StandardCharsets.UTF_8);
                JsonObject data = new JsonParser().parse(response).getAsJsonObject();
                
                // Get the selected profile
                JsonObject selectedProfile = null;
                JsonObject profiles = data.getAsJsonObject("profiles");
                
                if (profiles != null) {
                    for (Map.Entry<String, JsonElement> entry : profiles.entrySet()) {
                        if (!entry.getValue().isJsonObject()) continue;
                        JsonObject profile = entry.getValue().getAsJsonObject();
                        if (profile.has("selected") && profile.get("selected").getAsBoolean()) {
                            selectedProfile = profile;
                            break;
                        }
                    }
                }

                if (selectedProfile == null) {
                    // No selected profile found
                    setDefaultValues();
                    return;
                }

                try {
                    JsonObject dungeonsData = selectedProfile.getAsJsonObject("dungeons");
                    
                    // Set catacombs level
                    this.dungeons.setCatalevel(
                        dungeonsData.getAsJsonObject("catacombs")
                                  .getAsJsonObject("level")
                                  .get("uncappedLevel")
                                  .getAsInt()
                    );

                    // Set secrets
                    this.dungeons.setSecrets(
                        dungeonsData.get("secrets_found").getAsInt()
                    );

                    // Set total runs
                    this.dungeons.setRuns(
                        dungeonsData.get("floor_completions").getAsInt()
                    );

                    // Fetch times for all floors in both normal and master mode
                    fetchAllFloorTimes(dungeonsData, "catacombs", 7);
                    fetchAllFloorTimes(dungeonsData, "master_catacombs", 7);

                } catch (Exception e) {
                    // If any part fails, set that specific part to default
                    if (this.dungeons.getCatalevel() == 0) {
                        this.dungeons.setCatalevel(0);
                    }
                    setDefaultTimesForAllFloors();
                }
                
                this.changed = true;
            } else {
                // API call failed, use default values
                setDefaultValues();
            }
        } catch (Exception e) {
            // Any exception, use default values
            setDefaultValues();
        }
    }

    private void fetchAllFloorTimes(JsonObject dungeonsData, String dungeonType, int maxFloor) {
        try {
            JsonObject floorsData = dungeonsData.getAsJsonObject(dungeonType)
                                              .getAsJsonObject("floors");
            
            for (int floor = 1; floor <= maxFloor; floor++) {
                try {
                    String floorKey = String.valueOf(floor);
                    if (!floorsData.has(floorKey)) {
                        this.dungeons.getPb().setTime(dungeonType, floor, "N/A");
                        continue;
                    }
                    
                    JsonObject floorData = floorsData.getAsJsonObject(floorKey);
                    if (!floorData.has("stats")) {
                        this.dungeons.getPb().setTime(dungeonType, floor, "N/A");
                        continue;
                    }
                    
                    JsonObject stats = floorData.getAsJsonObject("stats");
                    if (!stats.has("fastest_time_s_plus")) {
                        this.dungeons.getPb().setTime(dungeonType, floor, "N/A");
                        continue;
                    }
                    
                    String timeSPlus = stats.get("fastest_time_s_plus").getAsString();
                    if (timeSPlus.equals("0")) {
                        this.dungeons.getPb().setTime(dungeonType, floor, "N/A");
                        continue;
                    }
                    
                    long timeMillis = Long.parseLong(timeSPlus);
                    long timeSeconds = timeMillis / 1000;
                    this.dungeons.getPb().setTime(
                        dungeonType, 
                        floor, 
                        String.format("%d:%02d", timeSeconds / 60, timeSeconds % 60)
                    );
                } catch (Exception e) {
                    this.dungeons.getPb().setTime(dungeonType, floor, "N/A");
                }
            }
        } catch (Exception e) {
            setDefaultTimesForAllFloors();
        }
    }

    private void setDefaultTimesForAllFloors() {
        for (int floor = 1; floor <= 7; floor++) {
            this.dungeons.getPb().setTime("catacombs", floor, "N/A");
            this.dungeons.getPb().setTime("master_catacombs", floor, "N/A");
        }
    }

    private void setDefaultValues() {
        this.dungeons.setCatalevel(0);
        this.dungeons.setSecrets(0);
        this.dungeons.setRuns(0);
        this.dungeons.setSecretAverage(0.0);
        setDefaultTimesForAllFloors();
        this.changed = true;
    }

    public boolean hasChanged() {
        boolean hasChanged = this.changed;
        this.changed = false;
        return hasChanged;
    }

    public String getDungeonTime(String dungeonType, int floor) {
        return this.dungeons.getPb().getTime(dungeonType, floor);
    }

    public int getCatacombsLevel() {
        return this.dungeons.getCatalevel();
    }

    public String getMasterCatacombsFloor7PbSPlus() {
        return this.dungeons.getPb().getTime("master_catacombs", 7);
    }

    public static class Dungeons {
        private int catalevel;
        private int secrets;
        private int runs;
        private double secretAverage;
        private PersonalBest pb;

        public int getCatalevel() {
            return catalevel;
        }

        public void setCatalevel(int catalevel) {
            this.catalevel = catalevel;
        }

        public int getSecrets() {
            return secrets;
        }

        public void setSecrets(int secrets) {
            this.secrets = secrets;
        }

        public int getRuns() {
            return runs;
        }

        public void setRuns(int runs) {
            this.runs = runs;
        }

        public double getSecretAverage() {
            return secretAverage;
        }

        public void setSecretAverage(double secretAverage) {
            this.secretAverage = secretAverage;
        }

        public PersonalBest getPb() {
            return pb;
        }

        public void setPb(PersonalBest pb) {
            this.pb = pb;
        }
    }

    public static class PersonalBest {
        private final Map<String, Map<Integer, String>> pbTimes = new HashMap<String, Map<Integer, String>>();

        public String getTime(String dungeonType, int floor) {
            Map<Integer, String> times = pbTimes.get(dungeonType);
            if (times == null) {
                times = new HashMap<Integer, String>();
                pbTimes.put(dungeonType, times);
            }
            return times.getOrDefault(floor, "N/A");
        }

        public void setTime(String dungeonType, int floor, String time) {
            Map<Integer, String> times = pbTimes.get(dungeonType);
            if (times == null) {
                times = new HashMap<Integer, String>();
                pbTimes.put(dungeonType, times);
            }
            times.put(floor, time);
        }
    }
}
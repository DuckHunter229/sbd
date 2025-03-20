package com.example.sbd;

import java.util.HashMap;
import java.util.Map;

public class DataManager {
    private static final Map<String, PartyMember> players = new HashMap<String, PartyMember>();

    public static PartyMember getPlayer(String name) {
        return players.get(name);
    }

    public static void addPlayer(PartyMember player) {
        players.put(player.getName(), player);
    }
}
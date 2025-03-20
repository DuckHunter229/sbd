package com.example.sbd;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

public class SBDCommand extends CommandBase {
    @Override
    public String getCommandName() {
        return "sbd";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/sbd [Ign]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.addChatMessage(new ChatComponentText(
                EnumChatFormatting.RED + "Usage: /sbd [Ign] // /setreq [Time in seconds] // /toggleautokick"
            ));
            return;
        }

        final String playerName = args[0];
        final ICommandSender finalSender = sender;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    PartyMember player = DataManager.getPlayer(playerName);
                    
                    if (player == null) {
                        finalSender.addChatMessage(new ChatComponentText(
                            EnumChatFormatting.AQUA + "Fetching data for " + playerName + "..."
                        ));
                        
                        player = new PartyMember(playerName);
                        player.init();
                        DataManager.addPlayer(player);
                    }

                    displayPlayerData(player, playerName, finalSender);

                } catch (Exception e) {
                    handleCommandError(e, playerName, finalSender);
                }
            }
        }, "SBDCommandThread").start();
    }

    private void displayPlayerData(PartyMember player, String playerName, ICommandSender sender) {
        sender.addChatMessage(new ChatComponentText(
            EnumChatFormatting.GRAY + "----------------------------------------"
        ));
        
        sender.addChatMessage(new ChatComponentText(
            EnumChatFormatting.GREEN + "Dungeons Data for " + playerName + ":"
        ));
        
        sender.addChatMessage(new ChatComponentText(
            EnumChatFormatting.YELLOW + "Catacombs Level: " + player.getCatacombsLevel()
        ));
        
        displayDungeonTimes(player, sender);
        
        sender.addChatMessage(new ChatComponentText(
            EnumChatFormatting.GRAY + "----------------------------------------"
        ));
    }

    private void displayDungeonTimes(PartyMember player, ICommandSender sender) {
        // Display regular catacombs times
        for (int floor = 0; floor <= 7; floor++) {
            String time = player.getDungeonTime("catacombs", floor);
            if (!time.equals("N/A")) {
                String floorName = floor == 0 ? "Entrance" : "F" + floor;
                sender.addChatMessage(new ChatComponentText(
                    EnumChatFormatting.YELLOW + floorName + " S+ PB: " + time
                ));
            }
        }
        
        // Display master catacombs times
        for (int floor = 1; floor <= 7; floor++) {
            String time = player.getDungeonTime("master_catacombs", floor);
            if (!time.equals("N/A")) {
                sender.addChatMessage(new ChatComponentText(
                    EnumChatFormatting.YELLOW + "M" + floor + " S+ PB: " + time
                ));
            }
        }
    }

    private void handleCommandError(Exception e, String playerName, ICommandSender sender) {
        String errorMessage = e.getMessage();
        
        if (errorMessage != null && errorMessage.equals("No valid SkyBlock profile found")) {
            sender.addChatMessage(new ChatComponentText(
                EnumChatFormatting.RED + "Error: Could not find an active SkyBlock profile for " + playerName
            ));
        } else {
            sender.addChatMessage(new ChatComponentText(
                EnumChatFormatting.RED + "Error: Invalid profile data for " + playerName
            ));
        }
    }
}
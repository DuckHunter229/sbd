package com.example.sbd;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PartyManager {
    private static final Pattern PARTY_JOIN_PATTERN = Pattern.compile("Party Finder > (\\w+) joined the dungeon group! \\((\\w+) Level \\d+\\)");
    private static final long KICK_DELAY = 650;
    private Timer kickTimer;
    private Config config;

    public PartyManager() {
        MinecraftForge.EVENT_BUS.register(this);
        registerCommands();
        kickTimer = new Timer(true);
        config = Config.getInstance();
    }

    private void registerCommands() {
        ClientCommandHandler.instance.registerCommand(new SetRequirementCommand());
        ClientCommandHandler.instance.registerCommand(new ToggleAutoKickCommand());
    }

    @SubscribeEvent
    public void onChatMessage(ClientChatReceivedEvent event) {
        if (!config.isAutoKickEnabled()) return;

        String message = event.message.getUnformattedText();
        Matcher matcher = PARTY_JOIN_PATTERN.matcher(message);

        if (matcher.find()) {
            final String username = matcher.group(1);
            checkPlayerAndRespond(username);
        }
    }

    private void checkPlayerAndRespond(final String username) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    PartyMember player = new PartyMember(username);
                    player.init();
                    String pb = player.getMasterCatacombsFloor7PbSPlus();
                    
                    if (pb.equals("N/A")) {
                        sendPartyMessage(String.format("/pc Kicking %s (No M7 completion)", username));
                        scheduleKick(username);
                        return;
                    }

                    int pbSeconds = convertPbToSeconds(pb);
                    if (pbSeconds > config.getRequiredTimeSeconds()) {
                        sendPartyMessage(String.format("/pc Kicking %s PB: %s Req: %s", 
                            username, 
                            formatTime(pbSeconds), 
                            formatTime(config.getRequiredTimeSeconds())));
                        scheduleKick(username);
                    }
                } catch (Exception e) {
                    // Silent fail - don't spam chat with errors
                }
            }
        }).start();
    }

    private void scheduleKick(final String username) {
        kickTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                sendPartyMessage("/p kick " + username);
            }
        }, KICK_DELAY);
    }

    private void sendPartyMessage(final String command) {
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                Minecraft.getMinecraft().thePlayer.sendChatMessage(command);
            }
        });
    }

    private int convertPbToSeconds(String pb) {
        try {
            String[] parts = pb.split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private String formatTime(int totalSeconds) {
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    public class SetRequirementCommand extends CommandBase {
        @Override
        public String getCommandName() {
            return "setreq";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/setreq <seconds>";
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (args.length != 1) {
                sendMessage(EnumChatFormatting.RED + "Usage: /setreq <seconds>");
                return;
            }

            try {
                int seconds = Integer.parseInt(args[0]);
                if (seconds <= 0) {
                    sendMessage(EnumChatFormatting.RED + "Time must be positive!");
                    return;
                }

                config.setRequiredTimeSeconds(seconds);
                sendMessage(EnumChatFormatting.GREEN + "Requirement set to " + formatTime(seconds));
            } catch (NumberFormatException e) {
                sendMessage(EnumChatFormatting.RED + "Invalid number format!");
            }
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }
    }

    public class ToggleAutoKickCommand extends CommandBase {
        @Override
        public String getCommandName() {
            return "toggleautokick";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/toggleautokick";
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            boolean newState = !config.isAutoKickEnabled();
            config.setAutoKickEnabled(newState);
            sendMessage(EnumChatFormatting.GREEN + "Auto-kick has been " + 
                (newState ? "enabled" : "disabled"));
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }
    }

    private void sendMessage(String message) {
        Minecraft.getMinecraft().thePlayer.addChatMessage(
            new ChatComponentText(EnumChatFormatting.DARK_GRAY + "[" + 
                EnumChatFormatting.LIGHT_PURPLE + "Nour Addons" + 
                EnumChatFormatting.DARK_GRAY + "] " + message));
    }
}
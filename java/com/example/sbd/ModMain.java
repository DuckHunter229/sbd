package com.example.sbd;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.inventory.IInventory;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Field;
import java.util.regex.Pattern;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

@Mod(modid = ModMain.MODID, version = ModMain.VERSION)
public class ModMain {
    public static final String MODID = "sbd";
    public static final String VERSION = "1.0";
    private static final Pattern PLAYER_PATTERN = Pattern.compile(".*([^:]+):\\s*(Archer|Mage|Tank|Berserk|Healer).*");
    private static final long UPDATE_COOLDOWN = 15000;
    private static final int MAX_CONCURRENT_UPDATES = 2;
    private static final long REQUEST_DELAY = 100;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY = 2000;
    private static final int MAX_REQUESTS_PER_MINUTE = 120;
    private static final long PARTY_FINDER_CHECK_COOLDOWN = 100;
    private static final long HOVER_TIMEOUT = 2000;
    private static final long LORE_ANALYSIS_COOLDOWN = 1000;
    private long lastLoreAnalysis = 0;
    private String lastAnalyzedLore = "";

    private PartyManager partyManager;
    private ItemStack lastHoveredItem = null;
    private long lastHoverTime = 0;
    private String lastDungeonType = "";
    private int lastFloorNumber = -1;
    
    private long lastCacheClearTime = System.currentTimeMillis();
    private Map<String, Long> lastUpdateTimes = new ConcurrentHashMap<String, Long>();
    private Map<String, String> processedLines = new ConcurrentHashMap<String, String>();
    private Queue<String> playerUpdateQueue = new ConcurrentLinkedQueue<String>();
    private Map<String, Integer> retryCount = new ConcurrentHashMap<String, Integer>();
    private Map<String, PartyMember> playerCache = new ConcurrentHashMap<String, PartyMember>();
    private Set<String> successfullyFetchedPlayers;
    private Set<String> queuedLogged;
    private boolean isUpdating = false;
    private boolean lastPartyFinderState = false;
    private long lastPartyFinderCheck = 0;
    private final Object rateLimitLock = new Object();
    private Queue<Long> requestTimes = new LinkedList<Long>();

    private String currentDungeonType = "master_catacombs";
    private int currentFloorNumber = 7;

    public ModMain() {
        ConcurrentHashMap<String, Boolean> backingMap1 = new ConcurrentHashMap<String, Boolean>();
        ConcurrentHashMap<String, Boolean> backingMap2 = new ConcurrentHashMap<String, Boolean>();
        successfullyFetchedPlayers = Collections.newSetFromMap(backingMap1);
        queuedLogged = Collections.newSetFromMap(backingMap2);
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ClientCommandHandler.instance.registerCommand(new SBDCommand());
        partyManager = new PartyManager();
    }

    @Mod.EventHandler
    public void onServerStopping(FMLServerStoppingEvent event) {
        // Cleanup resources
    }

    private void startUpdateThread() {
        if (isUpdating) {
            return;
        }

        final ItemStack itemBeingProcessed = lastHoveredItem;
        final long startTime = System.currentTimeMillis();
        isUpdating = true;
        
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Set<String> currentlyProcessing = Collections.newSetFromMap(
                        new ConcurrentHashMap<String, Boolean>());
                    List<String> batch = new ArrayList<String>();
                    int failedAttempts = 0;
                    final int MAX_FAILED_ATTEMPTS = 3;
                    int processedCount = 0;

                    while (!playerUpdateQueue.isEmpty() && failedAttempts < MAX_FAILED_ATTEMPTS) {
                        if (isInPartyFinderMenu() && itemBeingProcessed == lastHoveredItem) {
                            lastHoverTime = System.currentTimeMillis();
                        }
                        
                        if (!isInPartyFinderMenu()) {
                            break;
                        }

                        if (itemBeingProcessed != lastHoveredItem) {
                            break;
                        }

                        batch.clear();
                        
                        while (batch.size() < MAX_CONCURRENT_UPDATES && !playerUpdateQueue.isEmpty()) {
                            String playerName = playerUpdateQueue.poll();
                            if (playerName != null && 
                                !successfullyFetchedPlayers.contains(playerName) && 
                                !currentlyProcessing.contains(playerName) &&
                                (retryCount.getOrDefault(playerName, 0) < MAX_RETRIES)) {
                                
                                batch.add(playerName);
                                currentlyProcessing.add(playerName);
                            }
                        }

                        if (batch.isEmpty()) {
                            failedAttempts++;
                            Thread.sleep(100);
                            continue;
                        }

                        failedAttempts = 0;

                        for (String playerName : batch) {
                            try {
                                if (!canMakeRequest()) {
                                    Thread.sleep(1000);
                                    continue;
                                }

                                if (!isInPartyFinderMenu() || itemBeingProcessed != lastHoveredItem) {
                                    return;
                                }

                                lastHoverTime = System.currentTimeMillis();
                                
                                try {
                                    PartyMember player = new PartyMember(playerName);
                                    player.init();
                                    successfullyFetchedPlayers.add(playerName);
                                    retryCount.remove(playerName);
                                    playerCache.put(playerName, player);
                                    lastUpdateTimes.put(playerName, System.currentTimeMillis());
                                    processedCount++;

                                    Iterator<Map.Entry<String, String>> it = processedLines.entrySet().iterator();
                                    while (it.hasNext()) {
                                        Map.Entry<String, String> entry = it.next();
                                        if (entry.getKey().contains(playerName)) {
                                            it.remove();
                                        }
                                    }
                                } catch (Exception e) {
                                    String errorMsg = e.getMessage();
                                    if (errorMsg != null && errorMsg.contains("No valid SkyBlock profile")) {
                                        successfullyFetchedPlayers.add(playerName);
                                    } else {
                                        int retries = retryCount.getOrDefault(playerName, 0) + 1;
                                        retryCount.put(playerName, retries);
                                        if (retries < MAX_RETRIES) {
                                            playerUpdateQueue.offer(playerName);
                                        } else {
                                            successfullyFetchedPlayers.add(playerName);
                                        }
                                    }
                                }

                                Thread.sleep(REQUEST_DELAY);
                            } finally {
                                currentlyProcessing.remove(playerName);
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    isUpdating = false;
                }
            }
        }, "PlayerUpdateThread").start();
    }

    private void extractDungeonInfo(List<String> lore) {
        long currentTime = System.currentTimeMillis();
        String currentLoreHash = lore.size() > 4 ? 
            String.join("\n", lore.subList(0, 4)) : String.join("\n", lore);
        
        if (currentTime - lastLoreAnalysis < LORE_ANALYSIS_COOLDOWN && 
            currentLoreHash.equals(lastAnalyzedLore)) {
            return;
        }
        
        lastLoreAnalysis = currentTime;
        lastAnalyzedLore = currentLoreHash;

        String newDungeonType = null;
        Integer newFloorNumber = null;

        if (lore.size() > 0) {
            String dungeonLine = removeMOTDCodes(lore.get(0)).trim();
            if (dungeonLine.startsWith("Dungeon:")) {
                String dungeonInfo = dungeonLine.substring(8).trim();
                
                if (dungeonInfo.contains("Master Mode")) {
                    newDungeonType = "master_catacombs";
                } else if (dungeonInfo.contains("The Catacombs")) {
                    newDungeonType = "catacombs";
                }
            }
        }

        if (lore.size() > 1) {
            String floorLine = removeMOTDCodes(lore.get(1)).trim();
            if (floorLine.startsWith("Floor:")) {
                String floorInfo = floorLine.substring(6).trim();

                if (floorInfo.contains("Floor")) {
                    String romanNumeral = floorInfo.replaceAll(".*Floor\\s+([IVX]+).*", "$1").trim();
                    try {
                        if (!romanNumeral.isEmpty()) {
                            newFloorNumber = RomanNumeralConverter.romanToInt(romanNumeral);
                        } else {
                            String floorNum = floorInfo.replaceAll("[^0-9]", "");
                            if (!floorNum.isEmpty()) {
                                newFloorNumber = Integer.parseInt(floorNum);
                            }
                        }
                    } catch (Exception e) {
                        newFloorNumber = 7;
                    }
                } else if (floorInfo.contains("Entrance")) {
                    newFloorNumber = 0;
                }
            }
        }

        if (newDungeonType != null && !newDungeonType.equals(lastDungeonType)) {
            currentDungeonType = newDungeonType;
            lastDungeonType = newDungeonType;
        }

        if (newFloorNumber != null && newFloorNumber != lastFloorNumber) {
            currentFloorNumber = newFloorNumber;
            lastFloorNumber = newFloorNumber;
        }
    }

    private boolean canMakeRequest() {
        long currentTime = System.currentTimeMillis();
        synchronized (rateLimitLock) {
            while (!requestTimes.isEmpty() && requestTimes.peek() + 60000 < currentTime) {
                requestTimes.poll();
            }
            
            if (requestTimes.size() < MAX_REQUESTS_PER_MINUTE) {
                requestTimes.offer(currentTime);
                return true;
            }
            return false;
        }
    }

    private void stopUpdateThread() {
        isUpdating = false;
        playerUpdateQueue.clear();
    }

    private void stopUpdateThreadForCurrentItem() {
        if (isInPartyFinderMenu()) {
            playerUpdateQueue.clear();
            isUpdating = false;
        }
    }

    private void resetPartyFinderState() {
        lastHoveredItem = null;
        lastHoverTime = 0;
        stopUpdateThreadForCurrentItem();
    }

    private boolean shouldContinueProcessing(ItemStack item) {
        return lastHoveredItem == item && 
               System.currentTimeMillis() - lastHoverTime <= HOVER_TIMEOUT &&
               isInPartyFinderMenu();
    }

    private String getPlayerName(String line) {
        String cleanLine = removeMOTDCodes(line).trim();
        int colonIndex = cleanLine.indexOf(':');
        if (colonIndex > 0) {
            String name = cleanLine.substring(0, colonIndex).trim();
            if (!name.isEmpty() && !name.contains(" ")) {
                return name;
            }
        }
        return "";
    }

    private void queuePlayerForUpdate(final String playerName) {
        if (successfullyFetchedPlayers.contains(playerName) || 
            playerUpdateQueue.contains(playerName) ||
            (playerCache.containsKey(playerName) && 
             System.currentTimeMillis() - lastUpdateTimes.get(playerName) < UPDATE_COOLDOWN)) {
            return;
        }

        playerUpdateQueue.offer(playerName);
        startUpdateThread();
    }

    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent event) {
        if (!isInPartyFinderMenu()) {
            lastHoveredItem = null;
            return;
        }

        ItemStack currentItem = event.itemStack;
        long currentTime = System.currentTimeMillis();

        if (lastHoveredItem == currentItem) {
            lastHoverTime = currentTime;
        } else {
            if (lastHoveredItem != null) {
                stopUpdateThreadForCurrentItem();
            }
            lastHoveredItem = currentItem;
            lastHoverTime = currentTime;
        }

        processTooltip(event);
    }

    private void processTooltip(ItemTooltipEvent event) {
        lastHoverTime = System.currentTimeMillis();
        
        List<String> lore = event.toolTip;
        if (lore.isEmpty()) return;

        String itemName = lore.get(0);
        if (lore.size() <= 1) return;
        
        lore = lore.subList(1, lore.size());
        lore = filterLore(lore);
        if (lore.size() < 6) return;

        extractDungeonInfo(lore);
        processLoreLines(lore, itemName, event);
    }

    private List<String> filterLore(List<String> lore) {
        List<String> filteredLore = new ArrayList<String>();
        for (String line : lore) {
            if (!line.contains("minecraft:") && !line.contains("NBT:")) {
                filteredLore.add(line);
            }
        }
        return filteredLore;
    }

    private void processLoreLines(List<String> lore, String itemName, ItemTooltipEvent event) {
        boolean hasChanged = false;
        int startLine = Math.min(6, lore.size() - 1);
        int endLine = Math.min(10, lore.size() - 1);
        
        if (startLine > endLine) return;

        for (int i = startLine; i <= endLine; i++) {
            String line = lore.get(i);
            if (line.trim().equalsIgnoreCase("Empty")) continue;

            String cleanLine = removeMOTDCodes(line);
            if (PLAYER_PATTERN.matcher(cleanLine).matches()) {
                hasChanged |= processPlayerLine(line, lore, i);
            }
        }

        if (hasChanged) {
            updateTooltip(itemName, lore, event);
        }
    }

    private boolean processPlayerLine(String line, List<String> lore, int index) {
        String playerName = getPlayerName(line);
        if (playerName.isEmpty()) return false;

        long currentProcessTime = System.currentTimeMillis();
        String cachedLine = processedLines.get(line);
        
        if (cachedLine != null && lastUpdateTimes.containsKey(playerName) && 
            currentProcessTime - lastUpdateTimes.get(playerName) < UPDATE_COOLDOWN) {
            lore.set(index, cachedLine);
            return true;
        }

        PartyMember player = playerCache.get(playerName);
        if (player == null && !successfullyFetchedPlayers.contains(playerName)) {
            queuePlayerForUpdate(playerName);
            return false;
        }

        if (player != null) {
            String newLine = createSuffix(line, player);
            if (!line.equals(newLine)) {
                lore.set(index, newLine);
                processedLines.put(line, newLine);
                lastUpdateTimes.put(playerName, currentProcessTime);
                return true;
            }
        }
        return false;
    }

    private boolean isInPartyFinderMenu() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastPartyFinderCheck < PARTY_FINDER_CHECK_COOLDOWN) {
            return lastPartyFinderState;
        }

        lastPartyFinderCheck = currentTime;
        GuiScreen currentScreen = Minecraft.getMinecraft().currentScreen;
        if (currentScreen instanceof GuiChest) {
            GuiChest guiChest = (GuiChest) currentScreen;
            IInventory lowerChestInventory = getLowerChestInventory(guiChest);
            if (lowerChestInventory != null) {
                String chestName = lowerChestInventory.getDisplayName().getUnformattedText();
                lastPartyFinderState = chestName.equals("Party Finder") || chestName.contains("Party Finder");
                if (!lastPartyFinderState) {
                    resetPartyFinderState();
                }
                return lastPartyFinderState;
            }
        }
        
        lastPartyFinderState = false;
        resetPartyFinderState();
        return false;
    }

    private IInventory getLowerChestInventory(GuiChest guiChest) {
        try {
            Field lowerChestInventoryField = GuiChest.class.getDeclaredField("field_147015_w");
            lowerChestInventoryField.setAccessible(true);
            return (IInventory) lowerChestInventoryField.get(guiChest);
        } catch (Exception e) {
            return null;
        }
    }

    private void updateTooltip(String itemName, List<String> lore, ItemTooltipEvent event) {
        ArrayList<String> newLore = new ArrayList<String>();
        newLore.add(itemName);
        newLore.addAll(lore);
        event.toolTip.clear();
        event.toolTip.addAll(newLore);
    }

    private static String removeMOTDCodes(String line) {
        return line == null ? "" : line.replaceAll("\u00A7[0-9a-fklmnor]", "");
    }

    private String extractColorCodes(String line) {
        if (line == null) return "";
        StringBuilder codes = new StringBuilder();
        for (int i = 0; i < line.length() - 1; i++) {
            if (line.charAt(i) == '\u00A7') {
                codes.append('\u00A7').append(line.charAt(i + 1));
                i++;
            }
        }
        return codes.toString();
    }

    private String createSuffix(String line, PartyMember player) {
        try {
            int catacombsLevel = player.getCatacombsLevel();
            String floorTime = player.getDungeonTime(currentDungeonType, currentFloorNumber);
            
            String colorCodes = extractColorCodes(line);
            int lastParenIndex = line.lastIndexOf(')');
            String baseLine = lastParenIndex != -1 ? line.substring(0, lastParenIndex + 1) : line;
                
            String floorDisplay = currentDungeonType.equals("master_catacombs") ? 
                "M" + currentFloorNumber : "F" + currentFloorNumber;
                
            return String.format("%s \u00A78[%d \u00A7f%s\u00A78]%s", 
                baseLine, 
                catacombsLevel,
                floorTime != null && !floorTime.equals("N/A") ? floorTime : "No " + floorDisplay,
                colorCodes);
        } catch (Exception e) {
            return line;
        }
    }
}
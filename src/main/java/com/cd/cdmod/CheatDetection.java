package com.cd.cdmod;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Pattern;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

@Mod(modid = CheatDetection.MODID, name = CheatDetection.NAME, version = CheatDetection.VERSION, clientSideOnly = true)
public class CheatDetection {
    public static final String MODID = "playerdetector";
    public static final String NAME = "Player Detector";
    public static final String VERSION = "1.0";

    @EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new ChatScraper());
        ClientCommandHandler.instance.registerCommand(new SetApiKeyClientCommand());
        ClientCommandHandler.instance.registerCommand(new BlacklistCommand());
    }

    public static class ChatScraper {
        private static final Map<String, String> uuidCache = new HashMap<>();

        @SubscribeEvent
        public void onChat(ClientChatReceivedEvent event) {
            String message = event.message.getUnformattedText();
            String marker = "Opponent: ";
            if (message.contains(marker)) {
                int start = message.indexOf(marker) + marker.length();
                String remaining = message.substring(start).trim();
                String opponentName;
                if (remaining.startsWith("[")) {
                    int bracketEnd = remaining.indexOf("] ");
                    if (bracketEnd != -1 && bracketEnd + 2 < remaining.length()) {
                        opponentName = remaining.substring(bracketEnd + 2).trim();
                    } else {
                        opponentName = remaining;
                    }
                } else {
                    opponentName = remaining;
                }
                opponentName = opponentName.replaceAll("§[0-9a-fk-or]", "");
                opponentName = opponentName.replaceAll("[^a-zA-Z0-9_]", "");
                final String finalOpponentName = opponentName;
                System.out.println("[PlayerDetector] Found opponent: " + finalOpponentName);
                new Thread(() -> {
                    try {
                        Thread.sleep(750);
                        String apiKey = loadApiKey();
                        if (apiKey == null) return;

                        String uuid = fetchUUID(finalOpponentName);
                        if (uuid == null) {
                            sendChat(EnumChatFormatting.RED + "[PlayerDetector] Nick found! " + finalOpponentName + " (UUID not found)");
                            return;
                        }

                        Boolean isOnline = checkPlayerOnline(apiKey, uuid);
                        if (isOnline == null) {
                            sendChat(EnumChatFormatting.RED + "[CheatDetector] Couldn't reach the Hypixel network, your API key might have expired.");
                            return;
                        }

                        if (!isOnline) {
                            sendChat(EnumChatFormatting.YELLOW + "[PlayerDetector] Possible nick found! " + finalOpponentName + " (Player is offline)");
                        
                        }

                        String guildName = fetchGuildName(apiKey, uuid);
                        if (guildName == null) return;

                        Set<String> blacklistedGuilds = loadBlacklist();
                        if (blacklistedGuilds.stream().anyMatch(bg -> bg.equalsIgnoreCase(guildName))) {
                            sendChat(EnumChatFormatting.AQUA + "[PlayerDetector]: " + finalOpponentName + " is in the guild " + guildName + "!");
                        }

                    } catch (Exception e) {
                        sendChat(EnumChatFormatting.RED + "[CheatDetector] Couldn't reach the Hypixel network, your API key might have expired.");
                    }
                }).start();
            }
        }

        private void sendChat(String message) {
            Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(message));
        }

        private String loadApiKey() {
            try {
                File file = new File(Minecraft.getMinecraft().mcDataDir, "cheaterdetection/apikey.txt");
                if (!file.exists()) {
                    sendChat(EnumChatFormatting.RED + "API key not set. Use /setapikey");
                    return null;
                }
                try (Scanner scanner = new Scanner(file)) {
                    return scanner.nextLine().trim();
                }
            } catch (IOException e) {
                sendChat(EnumChatFormatting.RED + "Failed to read API key.");
                return null;
            }
        }

        private Set<String> loadBlacklist() {
            Set<String> blacklistedGuilds = new HashSet<>();
            File file = new File(Minecraft.getMinecraft().mcDataDir, "cheaterdetection/blacklist.txt");
            if (file.exists()) {
                try (Scanner scanner = new Scanner(file)) {
                    while (scanner.hasNextLine()) {
                        blacklistedGuilds.add(scanner.nextLine().trim());
                    }
                } catch (IOException e) {
                    sendChat(EnumChatFormatting.RED + "Failed to read blacklist.");
                }
            }
            return blacklistedGuilds;
        }

        private Boolean checkPlayerOnline(String apiKey, String uuid) {
            try {
                String urlStr = "https://api.hypixel.net/v2/status?uuid=" + uuid;
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("API-Key", apiKey);
                conn.setRequestProperty("User-Agent", "PlayerDetectorMod/1.0");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                int responseCode = conn.getResponseCode();
                if (responseCode != 200) return null;
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) response.append(line);
                in.close();
                JsonObject json = new Gson().fromJson(response.toString(), JsonObject.class);
                if (!json.get("success").getAsBoolean()) return null;
                JsonObject status = json.getAsJsonObject("session");
                return status.get("online").getAsBoolean();
            } catch (Exception e) {
                return null;
            }
        }

        private String fetchUUID(String ign) {
            if (uuidCache.containsKey(ign)) {
                return uuidCache.get(ign);
            }
            try {
                URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + ign);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "PlayerDetectorMod/1.0");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                if (conn.getResponseCode() != 200) return null;
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
                reader.close();
                JsonObject json = new Gson().fromJson(response.toString(), JsonObject.class);
                if (!json.has("id")) return null;
                String uuid = json.get("id").getAsString();
                uuidCache.put(ign, uuid);
                return uuid;
            } catch (Exception e) {
                return null;
            }
        }

        private String fetchGuildName(String apiKey, String uuid) {
            try {
                String urlStr = "https://api.hypixel.net/v2/guild?player=" + uuid;
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("API-Key", apiKey);
                conn.setRequestProperty("User-Agent", "PlayerDetectorMod/1.0");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                if (conn.getResponseCode() != 200) {
                    sendChat(EnumChatFormatting.RED + "[CheatDetector] Couldn't reach the Hypixel network, your API key might have expired.");
                    return null;
                }
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) response.append(line);
                in.close();
                JsonObject json = new Gson().fromJson(response.toString(), JsonObject.class);
                if (!json.get("success").getAsBoolean() || json.get("guild").isJsonNull()) return null;
                return json.get("guild").getAsJsonObject().get("name").getAsString();
            } catch (Exception e) {
                sendChat(EnumChatFormatting.RED + "[CheatDetector] Couldn't reach the Hypixel network, your API key might have expired.");
                return null;
            }
        }
    }

    public static class SetApiKeyClientCommand extends CommandBase {
        private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

        @Override
        public String getCommandName() {
            return "setapikey";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/setapikey <uuid>";
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (args.length != 1 || !UUID_PATTERN.matcher(args[0]).matches()) {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "Invalid API key. Must be UUID format."));
                return;
            }
            File dir = new File(Minecraft.getMinecraft().mcDataDir, "cheaterdetection");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "apikey.txt");
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(args[0]);
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "API key saved successfully."));
            } catch (IOException e) {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "Failed to save API key."));
            }
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }
    }

    public static class BlacklistCommand extends CommandBase {
        @Override
        public String getCommandName() {
            return "blacklist";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/blacklist <add/remove/list/clear> [guild]";
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (args.length < 1) {
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "Usage: /blacklist <add/remove/list/clear> [guild]"));
                return;
            }
            File dir = new File(Minecraft.getMinecraft().mcDataDir, "cheaterdetection");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "blacklist.txt");
            Set<String> blacklistedGuilds = new HashSet<>();
            if (file.exists()) {
                try (Scanner scanner = new Scanner(file)) {
                    while (scanner.hasNextLine()) blacklistedGuilds.add(scanner.nextLine().trim());
                } catch (IOException e) {
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "Failed to read blacklist."));
                    return;
                }
            }
            String action = args[0].toLowerCase();
            switch (action) {
                case "add":
                    if (args.length < 2) {
                        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "Usage: /blacklist add <guild>"));
                        return;
                    }
                    String guildToAdd = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
                    if (blacklistedGuilds.stream().anyMatch(bg -> bg.equalsIgnoreCase(guildToAdd))) {
                        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + guildToAdd + " is already blacklisted."));
                    } else {
                        blacklistedGuilds.add(guildToAdd);
                        saveBlacklist(blacklistedGuilds, file);
                        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "Added " + guildToAdd + " to blacklist."));
                    }
                    break;
                case "remove":
                    if (args.length < 2) {
                        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "Usage: /blacklist remove <guild>"));
                        return;
                    }
                    String guildToRemove = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
                    boolean removed = blacklistedGuilds.removeIf(bg -> bg.equalsIgnoreCase(guildToRemove));
                    if (removed) {
                        saveBlacklist(blacklistedGuilds, file);
                        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "Removed " + guildToRemove + " from blacklist."));
                    } else {
                        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + guildToRemove + " is not blacklisted."));
                    }
                    break;
                case "list":
                    if (blacklistedGuilds.isEmpty()) {
                        sender.addChatMessage(new ChatComponentText("Blacklist is empty."));
                    } else {
                        sender.addChatMessage(new ChatComponentText("Blacklisted guilds: " + String.join(", ", blacklistedGuilds)));
                    }
                    break;
                case "clear":
                    if (!blacklistedGuilds.isEmpty()) {
                        blacklistedGuilds.clear();
                        saveBlacklist(blacklistedGuilds, file);
                        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "Blacklist cleared."));
                    } else {
                        sender.addChatMessage(new ChatComponentText("Blacklist is already empty."));
                    }
                    break;
                default:
                    sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "Usage: /blacklist <add/remove/list/clear> [guild]"));
            }
        }

        private void saveBlacklist(Set<String> blacklistedGuilds, File file) {
            try (FileWriter writer = new FileWriter(file)) {
                for (String guild : blacklistedGuilds) {
                    writer.write(guild + "\n");
                }
            } catch (IOException ignored) {}
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }
    }
}

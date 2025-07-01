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
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

@Mod(modid = CheatDetection.MODID, name = CheatDetection.NAME, version = CheatDetection.VERSION, clientSideOnly = true)
public class CheatDetection {
    public static final String MODID = "cheaterdetector";
    public static final String NAME = "Cheater Detector";
    public static final String VERSION = "1.0";

    @EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new ChatScraper());
        ClientCommandHandler.instance.registerCommand(new SetApiKeyClientCommand());
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

                // Strip Minecraft formatting and non-name characters
                opponentName = opponentName.replaceAll("§[0-9a-fk-or]", "");
                opponentName = opponentName.replaceAll("[^a-zA-Z0-9_]", "");

                final String finalOpponentName = opponentName;

                System.out.println("[CheaterDetector] Found opponent: " + finalOpponentName);

                new Thread(() -> {
                    try {
                        Thread.sleep(1000); // Delay to avoid spam

                        String apiKey = loadApiKey();
                        if (apiKey == null) return;

                        String uuid = fetchUUID(finalOpponentName);
                        if (uuid == null) {
                            sendChat("[CheaterDetector]: Could not find UUID for " + finalOpponentName);
                            return;
                        }

                        String guildName = fetchGuildName(apiKey, uuid);
                        if (guildName == null) {
                            sendChat("[CheaterDetector]: " + finalOpponentName + " is not in a guild.");
                        } else {
                            sendChat("[CheaterDetector]: " + finalOpponentName + " is in the guild " + guildName + "!");
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        sendChat("[CheaterDetector]: Failed to check guild for " + finalOpponentName);
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
                Scanner scanner = new Scanner(file);
                String key = scanner.nextLine().trim();
                scanner.close();
                return key;
            } catch (IOException e) {
                e.printStackTrace();
                sendChat(EnumChatFormatting.RED + "Failed to read API key.");
                return null;
            }
        }

        private String fetchUUID(String ign) {
            if (uuidCache.containsKey(ign)) {
                return uuidCache.get(ign);
            }

            int maxRetries = 3;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    URL url = new URL("https://api.mojang.com/users/profiles/minecraft/" + ign);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent", "CheaterDetectorMod/1.0");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);

                    int responseCode = conn.getResponseCode();
                    if (responseCode != 200) {
                        sendChat("[CheaterDetector] Failed to fetch UUID for " + ign + ". HTTP Code: " + responseCode);
                        if (attempt < maxRetries) {
                            sendChat("[CheaterDetector] Retrying (" + attempt + "/" + maxRetries + ")...");
                            Thread.sleep(2000 * attempt);
                            continue;
                        }
                        return null;
                    }

                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    String responseStr = response.toString();
                    if (responseStr.isEmpty()) {
                        sendChat("[CheaterDetector] Empty response from Mojang API for " + ign + " (Attempt " + attempt + ")");
                        if (attempt < maxRetries) {
                            Thread.sleep(2000 * attempt);
                            continue;
                        }
                        return null;
                    }

                    Gson gson = new Gson();
                    JsonObject json = gson.fromJson(responseStr, JsonObject.class);
                    if (!json.has("id")) {
                        sendChat("[CheaterDetector] No UUID found in response for " + ign + ". Response: " + responseStr);
                        return null;
                    }
                    String uuid = json.get("id").getAsString();
                    uuidCache.put(ign, uuid);
                    return uuid;
                } catch (Exception e) {
                    e.printStackTrace();
                    sendChat("[CheaterDetector] Error fetching UUID for " + ign + " (Attempt " + attempt + "): " + e.getClass().getSimpleName() + " - " + e.getMessage());
                    if (attempt < maxRetries) {
                        try {
                            Thread.sleep(2000 * attempt);
                        } catch (InterruptedException ignored) {}
                        continue;
                    }
                    return null;
                }
            }
            return null;
        }

        private String fetchGuildName(String apiKey, String player) {
            try {
                String urlStr = "https://api.hypixel.net/v2/guild?player=" + player;
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("API-Key", apiKey);
                conn.setRequestProperty("User-Agent", "CheaterDetectorMod/1.0");

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    System.out.println("[CheaterDetector] API request failed. Code: " + responseCode);
                    BufferedReader errReader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                    StringBuilder error = new StringBuilder();
                    String line;
                    while ((line = errReader.readLine()) != null) error.append(line);
                    errReader.close();
                    System.out.println("[CheaterDetector] Error response: " + error.toString());
                    return null;
                }

                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) response.append(line);
                in.close();

                Gson gson = new Gson();
                JsonObject json = gson.fromJson(response.toString(), JsonObject.class);
                if (!json.get("success").getAsBoolean() || json.get("guild").isJsonNull()) return null;

                return json.get("guild").getAsJsonObject().get("name").getAsString();
            } catch (Exception e) {
                e.printStackTrace();
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
            FileWriter writer = null;
            try {
                writer = new FileWriter(file);
                writer.write(args[0]);
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "API key saved successfully."));
            } catch (IOException e) {
                e.printStackTrace();
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "Failed to save API key."));
            } finally {
                if (writer != null) try { writer.close(); } catch (IOException ignored) {}
            }
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }
    }
}
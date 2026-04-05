package net.redstone.cloud.node.discord;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.redstone.cloud.node.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

public class AuditLogManager {

    private static final File logFile = new File("local/auditlog.json");
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public AuditLogManager() {
        if (!logFile.getParentFile().exists()) {
            logFile.getParentFile().mkdirs();
        }
        if (!logFile.exists()) {
            try {
                FileWriter w = new FileWriter(logFile);
                w.write("[]");
                w.close();
            } catch (Exception e) {}
        }
    }

    public synchronized void logAction(String action, String discordId, String discordName, String discordAvatar) {
        try {
            JsonArray arr = new JsonArray();
            try (FileReader r = new FileReader(logFile)) {
                arr = com.google.gson.JsonParser.parseReader(r).getAsJsonArray();
            } catch (Exception e) {}

            JsonObject entry = new JsonObject();
            entry.addProperty("timestamp", System.currentTimeMillis());
            entry.addProperty("date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
            entry.addProperty("action", action);
            entry.addProperty("userId", discordId);
            entry.addProperty("userName", discordName);
            entry.addProperty("userAvatar", discordAvatar);

            arr.add(entry);

            // Keep only latest 1000 logs
            if (arr.size() > 1000) {
                JsonArray trimmed = new JsonArray();
                for (int i = arr.size() - 1000; i < arr.size(); i++) {
                    trimmed.add(arr.get(i));
                }
                arr = trimmed;
            }

            try (FileWriter w = new FileWriter(logFile)) {
                gson.toJson(arr, w);
            }
        } catch (Exception e) {
            Logger.error("Failed to write audit log: " + e.getMessage());
        }
    }

    public synchronized JsonArray getLogs() {
        try {
            if (!logFile.exists()) return new JsonArray();
            try (FileReader r = new FileReader(logFile)) {
                return com.google.gson.JsonParser.parseReader(r).getAsJsonArray();
            }
        } catch (Exception e) {
            return new JsonArray();
        }
    }
}

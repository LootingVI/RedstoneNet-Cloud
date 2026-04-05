package net.redstone.cloud.node.discord;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.redstone.cloud.node.CloudNode;
import net.redstone.cloud.node.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DiscordBot extends ListenerAdapter {

    private JDA jda;
    private final File configFile = new File("local/discord.json");
    private JsonObject config;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public DiscordBot() {
        loadConfig();
        startBot();
    }

    public synchronized void loadConfig() {
        if (!configFile.getParentFile().exists()) {
            configFile.getParentFile().mkdirs();
        }
        if (!configFile.exists()) {
            config = new JsonObject();
            config.addProperty("botToken", "");
            config.addProperty("guildId", "");
            // Maps role ID to RBAC settings
            JsonObject roleMappings = new JsonObject();
            config.add("roleMappings", roleMappings);
            try (FileWriter w = new FileWriter(configFile)) {
                gson.toJson(config, w);
            } catch (Exception e) {
                Logger.error("[Discord] Failed to create default config: " + e.getMessage());
            }
        } else {
            try (FileReader r = new FileReader(configFile)) {
                config = com.google.gson.JsonParser.parseReader(r).getAsJsonObject();
            } catch (Exception e) {
                Logger.error("[Discord] Failed to read config: " + e.getMessage());
            }
        }
    }

    public synchronized void saveConfig(JsonObject newConfig) {
        this.config = newConfig;
        try (FileWriter w = new FileWriter(configFile)) {
            gson.toJson(config, w);
        } catch (Exception e) {}
        CloudNode.getInstance().getAuditLogManager().logAction("Updated Discord Config", "SYSTEM", "CloudNode", "");
        startBot(); // Reload bot when config changes
    }

    public JsonObject getConfig() {
        return config;
    }

    public List<Role> getGuildRoles() {
        if (jda == null) return new ArrayList<>();
        String guildId = config.has("guildId") ? config.get("guildId").getAsString() : "";
        if (guildId.isEmpty() || jda.getGuildById(guildId) == null) return new ArrayList<>();
        return jda.getGuildById(guildId).getRoles();
    }

    private void startBot() {
        if (jda != null) {
            jda.shutdown();
            jda = null;
        }
        String token = config.has("botToken") ? config.get("botToken").getAsString() : "";
        if (token.isEmpty()) return;

        try {
            jda = JDABuilder.createDefault(token)
                    .addEventListeners(this)
                    .build();
            jda.awaitReady();
            
            String guildId = config.has("guildId") ? config.get("guildId").getAsString() : "";
            if (!guildId.isEmpty() && jda.getGuildById(guildId) != null) {
                jda.getGuildById(guildId).updateCommands()
                        .addCommands(Commands.slash("cloudlogin", "Generate a restricted Web-Dashboard login token based on your roles."))
                        .queue();
            }
            Logger.info("[DiscordBot] Connected to Discord successfully!");
        } catch (Exception e) {
            Logger.error("[DiscordBot] Failed to start: " + e.getMessage());
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("cloudlogin")) return;

        String guildId = config.has("guildId") ? config.get("guildId").getAsString() : "";
        if (event.getGuild() == null || !event.getGuild().getId().equals(guildId)) {
            event.reply("This command can only be used in the configured Cloud Guild.").setEphemeral(true).queue();
            return;
        }

        JsonObject roleMappings = config.has("roleMappings") ? config.getAsJsonObject("roleMappings") : new JsonObject();
        boolean hasAccess = false;
        boolean fullAccess = false;
        Map<String, List<String>> allowedGroups = new HashMap<>();

        StringBuilder debugRoles = new StringBuilder();
        for (Role role : event.getMember().getRoles()) {
            debugRoles.append(role.getName()).append("(").append(role.getId()).append(") ");
            if (roleMappings.has(role.getId())) {
                hasAccess = true;
                JsonObject mapping = roleMappings.getAsJsonObject(role.getId());
                if (mapping.has("fullAccess") && mapping.get("fullAccess").getAsBoolean()) {
                    fullAccess = true;
                }
                if (mapping.has("allowedGroups")) {
                    JsonObject groupPerms = mapping.getAsJsonObject("allowedGroups");
                    for (String gName : groupPerms.keySet()) {
                        allowedGroups.putIfAbsent(gName, new ArrayList<>());
                        for (var p : groupPerms.getAsJsonArray(gName)) {
                            if (!allowedGroups.get(gName).contains(p.getAsString())) {
                                allowedGroups.get(gName).add(p.getAsString());
                            }
                        }
                    }
                }
            }
        }

        if (!hasAccess) {
            Logger.info("[Discord] User " + event.getUser().getEffectiveName() + " denied login. Has Roles: " + debugRoles.toString());
            event.reply("❌ You do not have permission to generate a dashboard token. Make sure a role you own is configured in the Dashboard.").setEphemeral(true).queue();
            return;
        }

        String userId = event.getUser().getId();
        String userName = event.getUser().getEffectiveName();
        String avatarUrl = event.getUser().getAvatarUrl() != null ? event.getUser().getAvatarUrl() : "";

        // Revoke old tokens
        CloudNode.getInstance().getWebTokenManager().revokeByDiscordId(userId);

        // Gen new
        String token = CloudNode.getInstance().getWebTokenManager().generateToken(userId, userName, avatarUrl, fullAccess, allowedGroups);
        
        // Log it
        CloudNode.getInstance().getAuditLogManager().logAction("Discord /cloudlogin generate token", userId, userName, avatarUrl);

        event.reply("✅ **Dashboard Login Successful**\n" +
                "Your Web Token is: `" + token + "`\n" +
                "Or use the auto-login link: <http://" + CloudNode.getInstance().getHost() + ":3030/?token=" + token + ">\n" +
                "*(This token is valid for 24 hours and bound to your roles)*")
                .setEphemeral(true).queue();
    }
}

package net.redstone.cloud.node.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.redstone.cloud.node.CloudNode;
import net.redstone.cloud.node.process.CloudServerProcess;
import net.redstone.cloud.node.group.Group;
import net.redstone.cloud.node.logging.Logger;
import net.redstone.cloud.node.permission.PermissionManager;
import net.redstone.cloud.api.permission.PermissionGroup;
import net.redstone.cloud.api.permission.PermissionUser;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

public class WebServer {
    private HttpServer server;
    private final int port = 3030;
    private final Gson gson = new Gson();

    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", new StaticFileHandler());
            server.createContext("/api/auth", new AuthHandler());
            server.createContext("/api/data", new DataHandler());
            server.createContext("/api/command", new CommandHandler());
            server.createContext("/api/logs", new LogsHandler());
            server.createContext("/api/servercommand", new ServerCommandHandler());
            server.createContext("/api/permissions", new PermissionsHandler());
            server.createContext("/api/software", new SoftwareHandler());
            server.createContext("/api/maintenance", new MaintenanceHandler());
            server.createContext("/api/backup", new BackupHandler());
            server.createContext("/api/autoscale", new AutoScaleHandler());
            server.createContext("/api/assignpack", new AssignPackHandler());
            server.createContext("/packs/", new PackFileHandler());
            server.createContext("/api/discord/config", new DiscordConfigHandler());
            server.createContext("/api/discord/roles", new DiscordRolesHandler());
            server.createContext("/api/auditlog", new AuditLogHandler());
            server.createContext("/api/files", new FileManagerHandler());
            server.createContext("/api/plugins/install", new PluginInstallHandler());
            server.setExecutor(null);
            server.start();
            Logger.info("-> Web Dashboard started on http://127.0.0.1:" + port);
        } catch (Exception e) {
            Logger.error("Error starting web server: " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null)
            server.stop(0);
    }

    private void sendCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private boolean checkAuth(HttpExchange exchange) {
        String token = "";
        try {
            token = exchange.getRequestHeaders().getFirst("Authorization");
            if (token != null && token.startsWith("Bearer ")) {
                token = token.substring(7);
            } else {
                Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
                token = query.getOrDefault("token", "");
            }
        } catch (Exception e) {
        }

        WebTokenManager.TokenInfo info = CloudNode.getInstance().getWebTokenManager().getTokenInfo(token);
        if (info == null) {
            try {
                sendError(exchange, 401, "Unauthorized");
            } catch (IOException ignored) {
            }
            return false;
        }
        return true;
    }

    private WebTokenManager.TokenInfo getTokenInfo(HttpExchange exchange) {
        String token = "";
        try {
            token = exchange.getRequestHeaders().getFirst("Authorization");
            if (token != null && token.startsWith("Bearer "))
                token = token.substring(7);
            else
                token = parseQuery(exchange.getRequestURI().getQuery()).getOrDefault("token", "");
        } catch (Exception e) {
        }
        return CloudNode.getInstance().getWebTokenManager().getTokenInfo(token);
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null)
            return result;
        for (String param : query.split("&")) {
            String[] entry = param.split("=");
            if (entry.length > 1)
                result.put(entry[0], entry[1]);
        }
        return result;
    }

    private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
        JsonObject res = new JsonObject();
        res.addProperty("error", msg);
        byte[] bytes = gson.toJson(res).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendJson(HttpExchange exchange, JsonObject res) throws IOException {
        byte[] bytes = gson.toJson(res).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ===================== HANDLER =====================

    class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                sendCorsHeaders(exchange);
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!exchange.getRequestURI().getPath().equals("/")) {
                sendError(exchange, 404, "Not Found");
                return;
            }
            File webDir = new File("local/web");
            webDir.mkdirs();
            File indexFile = new File(webDir, "index.html");
            Files.writeString(indexFile.toPath(), buildHtml());
            byte[] bytes = Files.readAllBytes(indexFile.toPath());
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    class PluginInstallHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                sendCorsHeaders(exchange);
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            sendCorsHeaders(exchange);
            if (!checkAuth(exchange))
                return;

            try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject body = com.google.gson.JsonParser.parseReader(isr).getAsJsonObject();
                String urlString = body.get("url").getAsString();
                String filename = body.get("filename").getAsString();
                String groupName = body.get("group").getAsString();

                WebTokenManager.TokenInfo info = getTokenInfo(exchange);
                if (info != null && !info.fullAccess) {
                    boolean canUpload = info.groupPermissions.containsKey(groupName)
                            && info.groupPermissions.get(groupName).contains("FILE_UPLOAD");
                    if (!canUpload) {
                        sendError(exchange, 403, "No Permission");
                        return;
                    }
                }

                Group group = CloudNode.getInstance().getGroupManager().getGroup(groupName);
                if (group == null) {
                    sendError(exchange, 404, "Group not found");
                    return;
                }

                // Download location: template folder
                File templatePluginsDir = new File("templates/" + groupName + "/plugins");
                templatePluginsDir.mkdirs();
                File targetFile = new File(templatePluginsDir, filename);

                try (InputStream in = java.net.URI.create(urlString).toURL().openStream();
                        FileOutputStream out = new FileOutputStream(targetFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }

                if (group.isStaticService()) {
                    // It's static, copy it directly into running instances' plugin folders
                    for (CloudServerProcess process : CloudNode.getInstance().getServerManager().getActiveProcesses()) {
                        if (process.getServerName().startsWith(groupName + "-")) {
                            File activePluginDir = new File("local/" + process.getServerName() + "/plugins");
                            if (activePluginDir.exists()) {
                                java.nio.file.Files.copy(targetFile.toPath(),
                                        new File(activePluginDir, filename).toPath(),
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                    }
                }

                info = getTokenInfo(exchange);
                CloudNode.getInstance().getAuditLogManager().logAction(
                        info != null ? info.discordId : "System",
                        info != null ? info.discordName : "System",
                        info != null ? info.discordAvatar : "",
                        "Installed Plugin: " + filename + " to " + groupName);

                JsonObject res = new JsonObject();
                res.addProperty("success", true);
                sendJson(exchange, res);
            } catch (Exception e) {
                Logger.error("Plugin Install Error: " + e.getMessage());
                sendError(exchange, 500, "Error downloading plugin");
            }
        }
    }

    class AuthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject body = com.google.gson.JsonParser.parseReader(isr).getAsJsonObject();
                String token = body.has("token") ? body.get("token").getAsString() : "";
                JsonObject res = new JsonObject();
                WebTokenManager.TokenInfo info = CloudNode.getInstance().getWebTokenManager().getTokenInfo(token);
                if (info != null) {
                    res.addProperty("success", true);
                    res.addProperty("discordId", info.discordId);
                    res.addProperty("discordName", info.discordName);
                    res.addProperty("discordAvatar", info.discordAvatar);
                    res.addProperty("fullAccess", info.fullAccess);
                    JsonObject gPerms = new JsonObject();
                    info.groupPermissions.forEach((g, list) -> {
                        JsonArray pArr = new JsonArray();
                        list.forEach(pArr::add);
                        gPerms.add(g, pArr);
                    });
                    res.add("groupPermissions", gPerms);
                } else {
                    res.addProperty("success", false);
                }
                sendJson(exchange, res);
            } catch (Exception e) {
                e.printStackTrace();
                sendError(exchange, 400, "Bad Request");
            }
        }
    }

    class DataHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!checkAuth(exchange))
                return;

            JsonObject res = new JsonObject();
            JsonArray servers = new JsonArray();
            for (CloudServerProcess p : CloudNode.getInstance().getServerManager().getActiveProcesses()) {
                JsonObject s = new JsonObject();
                s.addProperty("name", p.getServerName());
                s.addProperty("playerCount", p.getPlayerCount());
                s.addProperty("online", p.isAlive());
                s.addProperty("restarts", p.getRestartCount());
                s.addProperty("tps", p.getTps());
                s.addProperty("cpu", p.getCpuUsage());
                s.addProperty("usedMemory", p.getUsedMemoryMB());
                s.addProperty("maxMemory", p.getMaxMemoryMB());
                s.addProperty("port", p.getPort());
                servers.add(s);
            }
            res.add("servers", servers);
            res.addProperty("totalPlayers", CloudNode.getInstance().getServerManager().getTotalPlayerCount());

            JsonArray groups = new JsonArray();
            for (Group g : CloudNode.getInstance().getGroupManager().getGroups().values()) {
                JsonObject gObj = new JsonObject();
                gObj.addProperty("name", g.getName());
                gObj.addProperty("memory", g.getMemory());
                gObj.addProperty("proxy", g.isProxy());
                gObj.addProperty("software", g.getSoftwareFile());
                gObj.addProperty("startPort", g.getStartPort());
                gObj.addProperty("minOnline", g.getMinOnline());
                gObj.addProperty("bedrock", g.hasBedrockSupport());
                gObj.addProperty("autoScaleEnabled", g.isAutoScaleEnabled());
                gObj.addProperty("autoScaleThreshold", g.getAutoScaleThreshold());
                gObj.addProperty("maxInstances", g.getMaxInstances());
                gObj.addProperty("maxPlayers", g.getMaxPlayers());
                gObj.addProperty("resourcePack", g.getResourcePack() != null ? g.getResourcePack() : "");
                gObj.addProperty("forceResourcePack", g.isForceResourcePack());
                groups.add(gObj);
            }
            res.add("groups", groups);

            JsonArray packs = new JsonArray();
            File packDir = new File("local/resourcepacks");
            packDir.mkdirs();
            File[] files = packDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith(".zip"))
                        packs.add(f.getName());
                }
            }
            res.add("packs", packs);

            sendJson(exchange, res);

        }
    }

    class CommandHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!checkAuth(exchange))
                return;
            WebTokenManager.TokenInfo t = getTokenInfo(exchange);

            try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject body = com.google.gson.JsonParser.parseReader(isr).getAsJsonObject();
                if (body.has("command")) {
                    String fullCmd = body.get("command").getAsString();
                    String[] args = fullCmd.split(" ");
                    String cmd = args[0].toLowerCase();

                    if (t.fullAccess) {
                        CloudNode.getInstance().dispatchCommand(fullCmd);
                        CloudNode.getInstance().getAuditLogManager().logAction("Command (Admin): " + fullCmd,
                                t.discordId, t.discordName, t.discordAvatar);
                    } else {
                        // Restricted Access Check
                        if (cmd.equals("start") && args.length >= 2) {
                            String group = args[1];
                            java.util.List<String> perms = t.groupPermissions.getOrDefault(group,
                                    new java.util.ArrayList<>());
                            if (perms.contains("START")) {
                                CloudNode.getInstance().dispatchCommand(fullCmd);
                                CloudNode.getInstance().getAuditLogManager().logAction("Start Group: " + group,
                                        t.discordId, t.discordName, t.discordAvatar);
                            } else {
                                sendError(exchange, 403, "No START permission for " + group);
                                return;
                            }
                        } else if (cmd.equals("stopserver") && args.length >= 2) {
                            String serverName = args[1];
                            String group = serverName.split("-")[0];
                            java.util.List<String> perms = t.groupPermissions.getOrDefault(group,
                                    new java.util.ArrayList<>());
                            if (perms.contains("STOP")) {
                                CloudNode.getInstance().dispatchCommand(fullCmd);
                                CloudNode.getInstance().getAuditLogManager().logAction("Stop Server: " + serverName,
                                        t.discordId, t.discordName, t.discordAvatar);
                            } else {
                                sendError(exchange, 403, "No STOP permission for " + group);
                                return;
                            }
                        } else {
                            sendError(exchange, 403, "Command Restricted");
                            return;
                        }
                    }

                    JsonObject res = new JsonObject();
                    res.addProperty("success", true);
                    sendJson(exchange, res);
                } else
                    sendError(exchange, 400, "Missing command field");
            } catch (Exception e) {
                sendError(exchange, 400, "Bad Request");
            }
        }
    }

    class LogsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!checkAuth(exchange))
                return;
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.startsWith("server=")) {
                String serverName = query.substring(7);
                CloudServerProcess p = CloudNode.getInstance().getServerManager().getProcess(serverName);
                JsonObject res = new JsonObject();
                JsonArray logs = new JsonArray();
                if (p != null) {
                    for (String line : p.getLogCache())
                        logs.add(line);
                }
                res.add("logs", logs);
                sendJson(exchange, res);
            } else
                sendError(exchange, 400, "Missing server name");
        }
    }

    class ServerCommandHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!checkAuth(exchange))
                return;
            WebTokenManager.TokenInfo t = getTokenInfo(exchange);

            try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject body = com.google.gson.JsonParser.parseReader(isr).getAsJsonObject();
                if (body.has("server") && body.has("command")) {
                    String serverName = body.get("server").getAsString();
                    String group = serverName.split("-")[0];
                    String cmd = body.get("command").getAsString();

                    boolean permitted = t.fullAccess
                            || t.groupPermissions.getOrDefault(group, new java.util.ArrayList<>()).contains("CONSOLE");

                    if (!permitted) {
                        sendError(exchange, 403, "No CONSOLE permission for " + group);
                        return;
                    }

                    CloudServerProcess p = CloudNode.getInstance().getServerManager().getProcess(serverName);
                    if (p != null) {
                        p.sendCommand(cmd);
                        CloudNode.getInstance().getAuditLogManager().logAction("Console [" + serverName + "]: " + cmd,
                                t.discordId, t.discordName, t.discordAvatar);
                    }
                    JsonObject res = new JsonObject();
                    res.addProperty("success", true);
                    sendJson(exchange, res);
                } else
                    sendError(exchange, 400, "Missing parameters");
            } catch (Exception e) {
                sendError(exchange, 400, "Bad Request");
            }
        }
    }

    class PermissionsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!checkAuth(exchange))
                return;

            PermissionManager pm = CloudNode.getInstance().getPermissionManager();

            if ("GET".equals(exchange.getRequestMethod())) {
                JsonObject res = new JsonObject();
                JsonArray groups = new JsonArray();
                for (PermissionGroup g : pm.getGroups()) {
                    JsonObject gObj = new JsonObject();
                    gObj.addProperty("name", g.getName());
                    gObj.addProperty("prefix", g.getPrefix());
                    gObj.addProperty("isDefault", g.isDefault());
                    JsonArray perms = new JsonArray();
                    g.getPermissions().forEach(perms::add);
                    gObj.add("permissions", perms);
                    groups.add(gObj);
                }
                res.add("groups", groups);
                JsonArray users = new JsonArray();
                for (PermissionUser u : pm.getUsers()) {
                    JsonObject uObj = new JsonObject();
                    uObj.addProperty("name", u.getName());
                    uObj.addProperty("group", u.getGroup());
                    JsonArray perms = new JsonArray();
                    u.getPermissions().forEach(perms::add);
                    uObj.add("permissions", perms);
                    users.add(uObj);
                }
                res.add("users", users);
                sendJson(exchange, res);

            } else if ("POST".equals(exchange.getRequestMethod())) {
                try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                    JsonObject body = com.google.gson.JsonParser.parseReader(isr).getAsJsonObject();
                    String action = body.has("action") ? body.get("action").getAsString() : "";
                    switch (action) {
                        case "createGroup": {
                            String name = body.get("name").getAsString();
                            String prefix = body.has("prefix") ? body.get("prefix").getAsString() : "";
                            boolean isDef = body.has("isDefault") && body.get("isDefault").getAsBoolean();
                            pm.getGroups()
                                    .add(new PermissionGroup(name, prefix, 10, isDef));
                            pm.save();
                            pm.broadcastUpdate();
                            break;
                        }
                        case "deleteGroup": {
                            pm.getGroups().removeIf(g -> g.getName().equalsIgnoreCase(body.get("name").getAsString()));
                            pm.save();
                            pm.broadcastUpdate();
                            break;
                        }
                        case "addGroupPerm": {
                            PermissionGroup g = pm
                                    .getGroup(body.get("group").getAsString());
                            String perm = body.get("perm").getAsString();
                            if (g != null && !g.getPermissions().contains(perm)) {
                                g.getPermissions().add(perm);
                                pm.save();
                                pm.broadcastUpdate();
                            }
                            break;
                        }
                        case "removeGroupPerm": {
                            PermissionGroup g = pm
                                    .getGroup(body.get("group").getAsString());
                            if (g != null) {
                                g.getPermissions().remove(body.get("perm").getAsString());
                                pm.save();
                                pm.broadcastUpdate();
                            }
                            break;
                        }
                        case "setUserGroup": {
                            String uname = body.get("user").getAsString();
                            String gname = body.get("group").getAsString();
                            PermissionUser u = pm.getUser(uname);
                            if (u == null)
                                pm.createUser(uname, gname);
                            else {
                                u.setGroup(gname);
                                pm.save();
                                pm.broadcastUpdate();
                            }
                            break;
                        }
                        case "addUserPerm": {
                            PermissionUser u = pm
                                    .getUser(body.get("user").getAsString());
                            String perm = body.get("perm").getAsString();
                            if (u != null && !u.getPermissions().contains(perm)) {
                                u.getPermissions().add(perm);
                                pm.save();
                                pm.broadcastUpdate();
                            }
                            break;
                        }
                        case "removeUserPerm": {
                            PermissionUser u = pm
                                    .getUser(body.get("user").getAsString());
                            if (u != null) {
                                u.getPermissions().remove(body.get("perm").getAsString());
                                pm.save();
                                pm.broadcastUpdate();
                            }
                            break;
                        }
                        case "deleteUser": {
                            pm.getUsers().removeIf(u -> u.getName().equalsIgnoreCase(body.get("user").getAsString()));
                            pm.save();
                            pm.broadcastUpdate();
                            break;
                        }
                    }
                    JsonObject res = new JsonObject();
                    res.addProperty("success", true);
                    sendJson(exchange, res);
                } catch (Exception e) {
                    sendError(exchange, 400, "Bad Request: " + e.getMessage());
                }
            }
        }
    }

    class SoftwareHandler implements HttpHandler {
        private boolean isPlugin(String name) {
            String lower = name.toLowerCase();
            return lower.startsWith("geyser-") || lower.startsWith("floodgate-");
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!checkAuth(exchange))
                return;
            File softwareDir = new File("local/software");
            softwareDir.mkdirs();
            JsonObject res = new JsonObject();
            JsonArray list = new JsonArray();
            File[] files = softwareDir.listFiles((dir, name) -> name.endsWith(".jar") && !isPlugin(name));
            if (files != null) {
                for (File f : files)
                    list.add(f.getName());
            }
            res.add("software", list);
            sendJson(exchange, res);
        }
    }

    class MaintenanceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!checkAuth(exchange))
                return;
            if ("GET".equals(exchange.getRequestMethod())) {
                JsonObject res = new JsonObject();
                res.addProperty("maintenance", CloudNode.getInstance().isMaintenance());
                sendJson(exchange, res);
            } else if ("POST".equals(exchange.getRequestMethod())) {
                try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                    JsonObject body = com.google.gson.JsonParser.parseReader(isr).getAsJsonObject();
                    boolean enabled = body.has("enabled") && body.get("enabled").getAsBoolean();
                    CloudNode.getInstance().setMaintenance(enabled);
                    JsonObject res = new JsonObject();
                    res.addProperty("success", true);
                    sendJson(exchange, res);
                } catch (Exception e) {
                    sendError(exchange, 400, "Bad Request");
                }
            }
        }
    }

    class BackupHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!checkAuth(exchange))
                return;
            try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject body = com.google.gson.JsonParser.parseReader(isr).getAsJsonObject();
                if (!body.has("server")) {
                    sendError(exchange, 400, "Missing server field");
                    return;
                }
                String name = body.get("server").getAsString();
                CloudServerProcess p = CloudNode.getInstance().getServerManager().getProcess(name);
                JsonObject res = new JsonObject();
                if (p != null) {
                    new Thread(() -> p.createBackup(true), "Backup-" + name).start();
                    res.addProperty("success", true);
                } else {
                    res.addProperty("success", false);
                    res.addProperty("error", "Server not found");
                }
                sendJson(exchange, res);
            } catch (Exception e) {
                sendError(exchange, 400, "Bad Request");
            }
        }
    }

    class AutoScaleHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!checkAuth(exchange))
                return;
            try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject body = com.google.gson.JsonParser.parseReader(isr).getAsJsonObject();
                if (!body.has("group")) {
                    sendError(exchange, 400, "Missing group");
                    return;
                }
                String name = body.get("group").getAsString();
                Group g = CloudNode.getInstance().getGroupManager().getGroup(name);
                if (g != null) {
                    if (body.has("enabled"))
                        g.setAutoScaleEnabled(body.get("enabled").getAsBoolean());
                    if (body.has("threshold"))
                        g.setAutoScaleThreshold(body.get("threshold").getAsInt());
                    CloudNode.getInstance().getGroupManager().saveGroups();
                    JsonObject res = new JsonObject();
                    res.addProperty("success", true);
                    sendJson(exchange, res);
                } else
                    sendError(exchange, 404, "Group not found");
            } catch (Exception e) {
                sendError(exchange, 400, "Bad Request");
            }
        }
    }

    class AssignPackHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!checkAuth(exchange))
                return;
            try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject body = com.google.gson.JsonParser.parseReader(isr).getAsJsonObject();
                if (!body.has("group")) {
                    sendError(exchange, 400, "Missing group");
                    return;
                }
                String name = body.get("group").getAsString();
                Group g = CloudNode.getInstance().getGroupManager().getGroup(name);
                if (g != null) {
                    if (body.has("pack"))
                        g.setResourcePack(body.get("pack").getAsString());
                    if (body.has("force"))
                        g.setForceResourcePack(body.get("force").getAsBoolean());
                    CloudNode.getInstance().getGroupManager().saveGroups();
                    JsonObject res = new JsonObject();
                    res.addProperty("success", true);
                    sendJson(exchange, res);
                } else
                    sendError(exchange, 404, "Group not found");
            } catch (Exception e) {
                sendError(exchange, 400, "Bad Request");
            }
        }
    }

    class PackFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            String path = exchange.getRequestURI().getPath().substring(7); // packs/ length
            File pack = new File("local/resourcepacks", path);
            if (!pack.exists() || !pack.isFile()) {
                sendError(exchange, 404, "Pack not found");
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, pack.length());
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                Files.copy(pack.toPath(), os);
            }
        }
    }

    class DiscordConfigHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!checkAuth(exchange))
                return;
            WebTokenManager.TokenInfo t = getTokenInfo(exchange);
            if (!t.fullAccess) {
                sendError(exchange, 403, "Access denied");
                return;
            }

            if ("GET".equals(exchange.getRequestMethod())) {
                JsonObject res = CloudNode.getInstance().getDiscordBot().getConfig();
                sendJson(exchange, res == null ? new JsonObject() : res);
            } else if ("POST".equals(exchange.getRequestMethod())) {
                try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                    JsonObject body = com.google.gson.JsonParser.parseReader(isr).getAsJsonObject();
                    CloudNode.getInstance().getDiscordBot().saveConfig(body);
                    CloudNode.getInstance().getAuditLogManager().logAction("Updated Discord Config", t.discordId,
                            t.discordName, t.discordAvatar);
                    JsonObject res = new JsonObject();
                    res.addProperty("success", true);
                    sendJson(exchange, res);
                } catch (Exception e) {
                    sendError(exchange, 400, "Bad Request");
                }
            }
        }
    }

    class AuditLogHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!checkAuth(exchange))
                return;
            WebTokenManager.TokenInfo t = getTokenInfo(exchange);
            if (!t.fullAccess) {
                sendError(exchange, 403, "Access denied");
                return;
            }
            JsonArray arr = CloudNode.getInstance().getAuditLogManager().getLogs();
            JsonObject res = new JsonObject();
            res.add("logs", arr);
            sendJson(exchange, res);
        }
    }

    class FileManagerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!checkAuth(exchange))
                return;
            WebTokenManager.TokenInfo t = getTokenInfo(exchange);

            Map<String, String> q = parseQuery(exchange.getRequestURI().getQuery());
            String group = q.getOrDefault("group", "");
            String specifiedServer = q.getOrDefault("server", "");
            String action = q.getOrDefault("action", "list");
            String rawPath = q.getOrDefault("path", "");
            String path = java.net.URLDecoder.decode(rawPath, "UTF-8");

            if (group.isEmpty() || path.contains("..")) {
                sendError(exchange, 400, "Bad Request");
                return;
            }

            boolean isFull = t.fullAccess;
            java.util.List<String> perms = t.groupPermissions.getOrDefault(group, new java.util.ArrayList<>());
            boolean canView = isFull || perms.contains("FILE_VIEW");
            boolean canEdit = isFull || perms.contains("FILE_EDIT");
            boolean canUpload = isFull || perms.contains("FILE_UPLOAD");
            boolean canDelete = isFull || perms.contains("FILE_DELETE");

            net.redstone.cloud.node.group.Group g = CloudNode.getInstance().getGroupManager().getGroup(group);
            if (g == null) {
                sendError(exchange, 404, "Group not found");
                return;
            }

            File baseDir;
            if (specifiedServer != null && !specifiedServer.isEmpty()) {
                baseDir = new File("local", specifiedServer);
                if (!baseDir.exists())
                    baseDir = new File("temp", specifiedServer);
            } else {
                baseDir = new File("local/templates", group);
            }
            File target = new File(baseDir, path);

            if ("list".equalsIgnoreCase(action)) {
                if (!canView) {
                    sendError(exchange, 403, "Refused to View");
                    return;
                }
                if (!target.exists())
                    target.mkdirs();
                JsonArray arr = new JsonArray();
                if (target.isDirectory()) {
                    File[] files = target.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            JsonObject o = new JsonObject();
                            o.addProperty("name", f.getName());
                            o.addProperty("isdir", f.isDirectory());
                            o.addProperty("size", f.length());
                            arr.add(o);
                        }
                    }
                }
                JsonObject res = new JsonObject();
                res.add("files", arr);
                sendJson(exchange, res);

            } else if ("delete".equalsIgnoreCase(action)) {
                if (!canDelete) {
                    sendError(exchange, 403, "Refused to Delete");
                    return;
                }
                if (target.exists())
                    deleteFileParam(target);
                CloudNode.getInstance().getAuditLogManager().logAction(
                        "Deleted File: " + path + " in " + baseDir.getName(), t.discordId, t.discordName,
                        t.discordAvatar);
                JsonObject res = new JsonObject();
                res.addProperty("success", true);
                sendJson(exchange, res);

            } else if ("read".equalsIgnoreCase(action)) {
                if (!canView) {
                    sendError(exchange, 403, "Refused to View");
                    return;
                }
                if (!target.exists() || target.isDirectory()) {
                    sendError(exchange, 404, "Not Found");
                    return;
                }
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, target.length());
                Files.copy(target.toPath(), exchange.getResponseBody());
                exchange.getResponseBody().close();

            } else if ("write".equalsIgnoreCase(action) && "POST".equals(exchange.getRequestMethod())) {
                if (!canEdit) {
                    sendError(exchange, 403, "Refused to Edit");
                    return;
                }
                target.getParentFile().mkdirs();
                try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                    JsonObject body = com.google.gson.JsonParser.parseReader(isr).getAsJsonObject();
                    String content = body.get("content").getAsString();
                    Files.write(target.toPath(), content.getBytes(StandardCharsets.UTF_8));
                }
                CloudNode.getInstance().getAuditLogManager().logAction(
                        "Edited File: " + path + " in " + baseDir.getName(), t.discordId, t.discordName,
                        t.discordAvatar);
                JsonObject res = new JsonObject();
                res.addProperty("success", true);
                sendJson(exchange, res);

            } else if ("upload".equalsIgnoreCase(action) && "POST".equals(exchange.getRequestMethod())) {
                if (!canUpload) {
                    sendError(exchange, 403, "Refused to Upload");
                    return;
                }
                target.getParentFile().mkdirs();
                Files.copy(exchange.getRequestBody(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                CloudNode.getInstance().getAuditLogManager().logAction(
                        "Uploaded File: " + path + " to " + baseDir.getName(), t.discordId, t.discordName,
                        t.discordAvatar);
                JsonObject res = new JsonObject();
                res.addProperty("success", true);
                sendJson(exchange, res);

            } else if ("mkdir".equalsIgnoreCase(action) && "POST".equals(exchange.getRequestMethod())) {
                if (!canUpload) {
                    sendError(exchange, 403, "Refused to Upload");
                    return;
                }
                target.mkdirs();
                CloudNode.getInstance().getAuditLogManager().logAction(
                        "Created Folder: " + path + " in " + baseDir.getName(), t.discordId, t.discordName,
                        t.discordAvatar);
                JsonObject res = new JsonObject();
                res.addProperty("success", true);
                sendJson(exchange, res);
            }
        }

        private void deleteFileParam(File file) {
            if (file.isDirectory()) {
                File[] c = file.listFiles();
                if (c != null)
                    for (File child : c)
                        deleteFileParam(child);
            }
            file.delete();
        }
    }

    class DiscordRolesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            sendCorsHeaders(exchange);
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!checkAuth(exchange))
                return;
            WebTokenManager.TokenInfo t = getTokenInfo(exchange);
            if (!t.fullAccess) {
                sendError(exchange, 403, "Access denied");
                return;
            }
            JsonArray arr = new JsonArray();
            for (net.dv8tion.jda.api.entities.Role r : CloudNode.getInstance().getDiscordBot().getGuildRoles()) {
                if (r.isPublicRole())
                    continue;
                JsonObject o = new JsonObject();
                o.addProperty("id", r.getId());
                o.addProperty("name", r.getName());
                o.addProperty("color",
                        r.getColor() != null ? String.format("#%06x", (r.getColor().getRGB() & 0xFFFFFF)) : "#99aab5");
                arr.add(o);
            }
            JsonObject res = new JsonObject();
            res.add("roles", arr);
            sendJson(exchange, res);
        }
    }

    // ===================== HTML =====================

    private String buildHtml() {
        String p1 = ""
                + "<!DOCTYPE html>\n"
                + "<html lang=\"en\">\n"
                + "<head>\n"
                + "<meta charset=\"UTF-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "<title>RedstoneNet Cloud Dashboard</title>\n"
                + "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">\n"
                + "<link href=\"https://fonts.googleapis.com/css2?family=DM+Mono:wght@400;500&family=Syne:wght@600;700;800&family=DM+Sans:wght@300;400;500;600&display=swap\" rel=\"stylesheet\">\n"
                + "<style>\n"
                + ":root {\n"
                + "  --bg: #07070d;\n"
                + "  --surface: #0e0e18;\n"
                + "  --surface2: #13131f;\n"
                + "  --surface3: #1a1a2a;\n"
                + "  --border: #1e1e32;\n"
                + "  --border2: #252540;\n"
                + "  --red: #ff4455;\n"
                + "  --red-dim: #c22233;\n"
                + "  --red-glow: rgba(255,68,85,0.2);\n"
                + "  --red-subtle: rgba(255,68,85,0.08);\n"
                + "  --green: #00e87a;\n"
                + "  --green-glow: rgba(0,232,122,0.15);\n"
                + "  --blue: #4d9fff;\n"
                + "  --blue-subtle: rgba(77,159,255,0.08);\n"
                + "  --amber: #ffb020;\n"
                + "  --text: #e2e2f0;\n"
                + "  --text2: #9898b8;\n"
                + "  --text3: #555570;\n"
                + "  --radius: 10px;\n"
                + "  --radius-lg: 16px;\n"
                + "  --nav-h: 56px;\n"
                + "}\n"
                + "\n"
                + "*, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }\n"
                + "\n"
                + "body {\n"
                + "  background: var(--bg);\n"
                + "  color: var(--text);\n"
                + "  font-family: 'DM Sans', sans-serif;\n"
                + "  min-height: 100vh;\n"
                + "  overflow-x: hidden;\n"
                + "  font-size: 14px;\n"
                + "}\n"
                + "\n"
                + "/* ─── NOISE TEXTURE ─── */\n"
                + "body::before {\n"
                + "  content: '';\n"
                + "  position: fixed;\n"
                + "  inset: 0;\n"
                + "  background-image: url(\"data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='0.025'/%3E%3C/svg%3E\");\n"
                + "  pointer-events: none;\n"
                + "  z-index: 0;\n"
                + "  opacity: 0.4;\n"
                + "}\n"
                + "\n"
                + "/* ─── AUTH ─── */\n"
                + "#auth-screen {\n"
                + "  display: flex;\n"
                + "  align-items: center;\n"
                + "  justify-content: center;\n"
                + "  min-height: 100vh;\n"
                + "  position: relative;\n"
                + "  z-index: 1;\n"
                + "}\n"
                + "\n"
                + "#auth-screen::before {\n"
                + "  content: '';\n"
                + "  position: fixed;\n"
                + "  top: -40%;\n"
                + "  left: 50%;\n"
                + "  transform: translateX(-50%);\n"
                + "  width: 600px;\n"
                + "  height: 600px;\n"
                + "  background: radial-gradient(circle, rgba(255,68,85,0.12) 0%, transparent 70%);\n"
                + "  pointer-events: none;\n"
                + "}\n"
                + "\n"
                + ".auth-box {\n"
                + "  background: var(--surface);\n"
                + "  border: 1px solid var(--border2);\n"
                + "  border-radius: 20px;\n"
                + "  padding: 44px 40px;\n"
                + "  width: 400px;\n"
                + "  text-align: center;\n"
                + "  box-shadow: 0 40px 80px rgba(0,0,0,0.6), 0 0 0 1px rgba(255,255,255,0.03) inset;\n"
                + "  position: relative;\n"
                + "  overflow: hidden;\n"
                + "}\n"
                + "\n"
                + ".auth-box::before {\n"
                + "  content: '';\n"
                + "  position: absolute;\n"
                + "  top: 0; left: 50%; transform: translateX(-50%);\n"
                + "  width: 200px; height: 1px;\n"
                + "  background: linear-gradient(90deg, transparent, var(--red), transparent);\n"
                + "}\n"
                + "\n"
                + ".auth-logo {\n"
                + "  font-family: 'Syne', sans-serif;\n"
                + "  font-size: 2em;\n"
                + "  font-weight: 800;\n"
                + "  color: var(--text);\n"
                + "  letter-spacing: -1px;\n"
                + "  margin-bottom: 6px;\n"
                + "}\n"
                + "\n"
                + ".auth-logo em {\n"
                + "  color: var(--red);\n"
                + "  font-style: normal;\n"
                + "}\n"
                + "\n"
                + ".auth-subtitle {\n"
                + "  color: var(--text3);\n"
                + "  font-size: 0.82em;\n"
                + "  letter-spacing: 0.08em;\n"
                + "  text-transform: uppercase;\n"
                + "  margin-bottom: 32px;\n"
                + "}\n"
                + "\n"
                + ".auth-box input {\n"
                + "  margin-bottom: 12px;\n"
                + "}\n"
                + "\n"
                + ".auth-hint {\n"
                + "  margin-top: 20px;\n"
                + "  font-size: 0.72em;\n"
                + "  color: var(--text3);\n"
                + "}\n"
                + "\n"
                + ".auth-hint code {\n"
                + "  color: var(--red);\n"
                + "  font-family: 'DM Mono', monospace;\n"
                + "  background: var(--red-subtle);\n"
                + "  padding: 2px 6px;\n"
                + "  border-radius: 4px;\n"
                + "}\n"
                + "\n"
                + "/* ─── NAVBAR ─── */\n"
                + ".navbar {\n"
                + "  background: rgba(14,14,24,0.85);\n"
                + "  backdrop-filter: blur(20px);\n"
                + "  border-bottom: 1px solid var(--border);\n"
                + "  padding: 0 24px;\n"
                + "  display: flex;\n"
                + "  align-items: center;\n"
                + "  height: var(--nav-h);\n"
                + "  position: sticky;\n"
                + "  top: 0;\n"
                + "  z-index: 100;\n"
                + "  gap: 24px;\n"
                + "}\n"
                + "\n"
                + ".logo {\n"
                + "  font-family: 'Syne', sans-serif;\n"
                + "  font-size: 1.15em;\n"
                + "  font-weight: 800;\n"
                + "  color: var(--text);\n"
                + "  letter-spacing: -0.5px;\n"
                + "  flex-shrink: 0;\n"
                + "  display: flex;\n"
                + "  align-items: center;\n"
                + "  gap: 10px;\n"
                + "}\n"
                + "\n"
                + ".logo-icon {\n"
                + "  width: 28px; height: 28px;\n"
                + "  background: var(--red-subtle);\n"
                + "  border: 1px solid rgba(255,68,85,0.3);\n"
                + "  border-radius: 8px;\n"
                + "  display: flex; align-items: center; justify-content: center;\n"
                + "  font-size: 14px;\n"
                + "}\n"
                + "\n"
                + ".logo em { color: var(--red); font-style: normal; }\n"
                + ".logo-cloud {\n"
                + "  font-size: 0.52em;\n"
                + "  color: var(--text3);\n"
                + "  font-weight: 400;\n"
                + "  font-family: 'DM Sans', sans-serif;\n"
                + "  letter-spacing: 0;\n"
                + "  margin-left: 2px;\n"
                + "}\n"
                + "\n"
                + ".nav-tabs {\n"
                + "  display: flex;\n"
                + "  gap: 2px;\n"
                + "  flex: 1;\n"
                + "}\n"
                + "\n"
                + ".nav-tab {\n"
                + "  background: none;\n"
                + "  border: none;\n"
                + "  color: var(--text3);\n"
                + "  padding: 6px 14px;\n"
                + "  border-radius: 8px;\n"
                + "  cursor: pointer;\n"
                + "  font-size: 0.82em;\n"
                + "  font-weight: 500;\n"
                + "  font-family: 'DM Sans', sans-serif;\n"
                + "  transition: all 0.15s;\n"
                + "  white-space: nowrap;\n"
                + "  display: flex;\n"
                + "  align-items: center;\n"
                + "  gap: 6px;\n"
                + "}\n"
                + "\n"
                + ".nav-tab:hover { background: var(--surface2); color: var(--text); transform: none; }\n"
                + "\n"
                + ".nav-tab.active {\n"
                + "  background: var(--red-subtle);\n"
                + "  color: var(--red);\n"
                + "  position: relative;\n"
                + "}\n"
                + "\n"
                + ".nav-tab.active::after {\n"
                + "  content: '';\n"
                + "  position: absolute;\n"
                + "  bottom: -1px; left: 50%; transform: translateX(-50%);\n"
                + "  width: 60%; height: 1px;\n"
                + "  background: var(--red);\n"
                + "  border-radius: 1px;\n"
                + "}\n"
                + "\n"
                + ".nav-tab-icon { font-size: 0.85em; opacity: 0.8; }\n"
                + "\n"
                + ".nav-right {\n"
                + "  display: flex;\n"
                + "  gap: 8px;\n"
                + "  align-items: center;\n"
                + "  flex-shrink: 0;\n"
                + "}\n"
                + "\n"
                + ".status-badge {\n"
                + "  display: flex;\n"
                + "  align-items: center;\n"
                + "  gap: 6px;\n"
                + "  background: var(--surface2);\n"
                + "  border: 1px solid var(--border2);\n"
                + "  padding: 5px 12px;\n"
                + "  border-radius: 20px;\n"
                + "  font-size: 0.75em;\n"
                + "  font-weight: 500;\n"
                + "  color: var(--text2);\n"
                + "  font-family: 'DM Mono', monospace;\n"
                + "}\n"
                + "\n"
                + ".status-badge .dot {\n"
                + "  width: 6px; height: 6px;\n"
                + "  border-radius: 50%;\n"
                + "  background: var(--green);\n"
                + "  box-shadow: 0 0 6px var(--green);\n"
                + "  animation: pulse 2s infinite;\n"
                + "}\n"
                + "\n"
                + "@keyframes pulse {\n"
                + "  0%, 100% { opacity: 1; }\n"
                + "  50% { opacity: 0.5; }\n"
                + "}\n"
                + "\n"
                + ".user-pill {\n"
                + "  display: none;\n"
                + "  align-items: center;\n"
                + "  gap: 8px;\n"
                + "  background: var(--surface2);\n"
                + "  border: 1px solid var(--border2);\n"
                + "  padding: 4px 12px 4px 4px;\n"
                + "  border-radius: 20px;\n"
                + "}\n"
                + "\n"
                + ".user-pill img {\n"
                + "  width: 24px; height: 24px;\n"
                + "  border-radius: 50%;\n"
                + "  display: none;\n"
                + "}\n"
                + "\n"
                + ".user-name { font-size: 0.8em; font-weight: 600; }\n"
                + "\n"
                + ".user-role {\n"
                + "  font-size: 0.68em;\n"
                + "  font-weight: 700;\n"
                + "  letter-spacing: 0.04em;\n"
                + "  padding: 2px 8px;\n"
                + "  border-radius: 10px;\n"
                + "  background: rgba(77,159,255,0.12);\n"
                + "  color: var(--blue);\n"
                + "  border: 1px solid rgba(77,159,255,0.25);\n"
                + "}\n"
                + "\n"
                + "/* ─── MAIN ─── */\n"
                + ".main {\n"
                + "  max-width: 1440px;\n"
                + "  margin: 0 auto;\n"
                + "  padding: 28px 24px;\n"
                + "  position: relative;\n"
                + "  z-index: 1;\n"
                + "}\n"
                + "\n"
                + ".page { display: none; }\n"
                + ".page.active { display: block; }\n"
                + "\n"
                + "/* ─── PANEL ─── */\n"
                + ".panel {\n"
                + "  background: var(--surface);\n"
                + "  border: 1px solid var(--border);\n"
                + "  border-radius: var(--radius-lg);\n"
                + "  padding: 0;\n"
                + "  margin-bottom: 20px;\n"
                + "  overflow: hidden;\n"
                + "}\n"
                + "\n"
                + ".panel-header {\n"
                + "  display: flex;\n"
                + "  justify-content: space-between;\n"
                + "  align-items: center;\n"
                + "  padding: 18px 22px;\n"
                + "  border-bottom: 1px solid var(--border);\n"
                + "  background: rgba(255,255,255,0.01);\n"
                + "}\n"
                + "\n"
                + ".panel-title {\n"
                + "  font-family: 'Syne', sans-serif;\n"
                + "  font-size: 0.9em;\n"
                + "  font-weight: 700;\n"
                + "  color: var(--text);\n"
                + "  display: flex;\n"
                + "  align-items: center;\n"
                + "  gap: 8px;\n"
                + "  letter-spacing: 0.01em;\n"
                + "}\n"
                + "\n"
                + ".panel-title-icon {\n"
                + "  width: 30px; height: 30px;\n"
                + "  background: var(--surface3);\n"
                + "  border: 1px solid var(--border2);\n"
                + "  border-radius: 8px;\n"
                + "  display: flex; align-items: center; justify-content: center;\n"
                + "  font-size: 13px;\n"
                + "}\n"
                + "\n"
                + ".panel-body { padding: 22px; }\n"
                + "\n"
                + ".panel-actions { display: flex; gap: 8px; }\n"
                + "\n"
                + "/* ─── GRID ─── */\n"
                + ".grid {\n"
                + "  display: grid;\n"
                + "  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));\n"
                + "  gap: 16px;\n"
                + "  padding: 20px 22px;\n"
                + "}\n"
                + "\n"
                + "/* ─── CARD ─── */\n"
                + ".card {\n"
                + "  background: var(--surface2);\n"
                + "  border: 1px solid var(--border);\n"
                + "  border-radius: var(--radius-lg);\n"
                + "  padding: 18px;\n"
                + "  position: relative;\n"
                + "  transition: border-color 0.2s, transform 0.2s, box-shadow 0.2s;\n"
                + "  overflow: hidden;\n"
                + "}\n"
                + "\n"
                + ".card::before {\n"
                + "  content: '';\n"
                + "  position: absolute;\n"
                + "  top: 0; left: 0; right: 0; height: 1px;\n"
                + "  background: linear-gradient(90deg, transparent, rgba(255,255,255,0.05), transparent);\n"
                + "}\n"
                + "\n"
                + ".card:hover {\n"
                + "  border-color: rgba(255,68,85,0.4);\n"
                + "  transform: translateY(-2px);\n"
                + "  box-shadow: 0 8px 30px rgba(0,0,0,0.3), 0 0 0 1px rgba(255,68,85,0.1);\n"
                + "}\n"
                + "\n"
                + ".card-header {\n"
                + "  display: flex;\n"
                + "  align-items: center;\n"
                + "  justify-content: space-between;\n"
                + "  margin-bottom: 14px;\n"
                + "}\n"
                + "\n"
                + ".card-title {\n"
                + "  font-weight: 700;\n"
                + "  font-size: 0.92em;\n"
                + "  display: flex;\n"
                + "  align-items: center;\n"
                + "  gap: 8px;\n"
                + "  font-family: 'Syne', sans-serif;\n"
                + "}\n"
                + "\n"
                + ".card-type-badge {\n"
                + "  font-size: 0.65em;\n"
                + "  font-weight: 600;\n"
                + "  padding: 2px 8px;\n"
                + "  border-radius: 6px;\n"
                + "  background: var(--surface3);\n"
                + "  color: var(--text3);\n"
                + "  border: 1px solid var(--border2);\n"
                + "  text-transform: uppercase;\n"
                + "  letter-spacing: 0.05em;\n"
                + "  font-family: 'DM Mono', monospace;\n"
                + "}\n"
                + "\n"
                + ".card-stats {\n"
                + "  display: grid;\n"
                + "  grid-template-columns: 1fr 1fr;\n"
                + "  gap: 8px;\n"
                + "  margin-bottom: 14px;\n"
                + "}\n"
                + "\n"
                + ".stat-item {\n"
                + "  background: var(--surface3);\n"
                + "  border: 1px solid var(--border);\n"
                + "  border-radius: 8px;\n"
                + "  padding: 8px 10px;\n"
                + "}\n"
                + "\n"
                + ".stat-label {\n"
                + "  font-size: 0.67em;\n"
                + "  color: var(--text3);\n"
                + "  text-transform: uppercase;\n"
                + "  letter-spacing: 0.06em;\n"
                + "  font-family: 'DM Mono', monospace;\n"
                + "  margin-bottom: 2px;\n"
                + "}\n"
                + "\n"
                + ".stat-value {\n"
                + "  font-size: 0.88em;\n"
                + "  font-weight: 600;\n"
                + "  color: var(--text);\n"
                + "  font-family: 'DM Mono', monospace;\n"
                + "}\n"
                + "\n"
                + ".card-autoscale {\n"
                + "  background: var(--surface3);\n"
                + "  border: 1px solid var(--border);\n"
                + "  border-radius: 8px;\n"
                + "  padding: 10px 12px;\n"
                + "  margin-bottom: 12px;\n"
                + "}\n"
                + "\n"
                + ".autoscale-header {\n"
                + "  display: flex;\n"
                + "  justify-content: space-between;\n"
                + "  align-items: center;\n"
                + "  margin-bottom: 8px;\n"
                + "  font-size: 0.77em;\n"
                + "}\n"
                + "\n"
                + ".autoscale-label {\n"
                + "  display: flex;\n"
                + "  align-items: center;\n"
                + "  gap: 6px;\n"
                + "  color: var(--text2);\n"
                + "  font-weight: 500;\n"
                + "}\n"
                + "\n"
                + ".autoscale-value {\n"
                + "  color: var(--text3);\n"
                + "  font-family: 'DM Mono', monospace;\n"
                + "  font-size: 0.88em;\n"
                + "}\n"
                + "\n"
                + "input[type=range] {\n"
                + "  -webkit-appearance: none;\n"
                + "  width: 100%;\n"
                + "  height: 4px;\n"
                + "  border-radius: 2px;\n"
                + "  background: var(--border2);\n"
                + "  outline: none;\n"
                + "  cursor: pointer;\n"
                + "}\n"
                + "\n"
                + "input[type=range]::-webkit-slider-thumb {\n"
                + "  -webkit-appearance: none;\n"
                + "  width: 14px; height: 14px;\n"
                + "  border-radius: 50%;\n"
                + "  background: var(--red);\n"
                + "  border: 2px solid var(--bg);\n"
                + "  box-shadow: 0 0 8px var(--red-glow);\n"
                + "  cursor: pointer;\n"
                + "}\n"
                + "\n"
                + ".card-pack {\n"
                + "  background: var(--surface3);\n"
                + "  border: 1px solid var(--border);\n"
                + "  border-radius: 8px;\n"
                + "  padding: 10px 12px;\n"
                + "  margin-bottom: 12px;\n"
                + "}\n"
                + "\n"
                + ".card-pack label {\n"
                + "  display: block;\n"
                + "  font-size: 0.72em;\n"
                + "  color: var(--text3);\n"
                + "  margin-bottom: 6px;\n"
                + "  text-transform: uppercase;\n"
                + "  letter-spacing: 0.05em;\n"
                + "  font-family: 'DM Mono', monospace;\n"
                + "}\n"
                + "\n"
                + ".pack-force {\n"
                + "  display: flex;\n"
                + "  align-items: center;\n"
                + "  gap: 6px;\n"
                + "  margin-top: 6px;\n"
                + "  font-size: 0.75em;\n"
                + "  color: var(--text3);\n"
                + "}\n"
                + "\n"
                + ".card-actions {\n"
                + "  display: flex;\n"
                + "  gap: 6px;\n"
                + "  flex-wrap: wrap;\n"
                + "  padding-top: 12px;\n"
                + "  border-top: 1px solid var(--border);\n"
                + "}\n"
                + "\n"
                + "/* Server cards */\n"
                + ".server-card {\n"
                + "  background: var(--surface2);\n"
                + "  border: 1px solid var(--border);\n"
                + "  border-radius: var(--radius-lg);\n"
                + "  padding: 18px;\n"
                + "  position: relative;\n"
                + "  transition: all 0.2s;\n"
                + "  overflow: hidden;\n"
                + "}\n"
                + "\n"
                + ".server-card.online { border-color: rgba(0,232,122,0.25); }\n"
                + ".server-card.offline { border-color: rgba(255,68,85,0.2); }\n"
                + "\n"
                + ".server-status-bar {\n"
                + "  position: absolute;\n"
                + "  top: 0; left: 0; right: 0; height: 2px;\n"
                + "}\n"
                + ".server-card.online .server-status-bar { background: var(--green); box-shadow: 0 0 10px var(--green); }\n"
                + ".server-card.offline .server-status-bar { background: var(--red); }\n"
                + "\n"
                + ".server-metrics {\n"
                + "  display: grid;\n"
                + "  grid-template-columns: repeat(4, 1fr);\n"
                + "  gap: 6px;\n"
                + "  margin: 12px 0;\n"
                + "}\n"
                + "\n"
                + ".metric {\n"
                + "  background: var(--surface3);\n"
                + "  border: 1px solid var(--border);\n"
                + "  border-radius: 7px;\n"
                + "  padding: 7px 8px;\n"
                + "  text-align: center;\n"
                + "}\n"
                + "\n"
                + ".metric-label {\n"
                + "  font-size: 0.62em;\n"
                + "  color: var(--text3);\n"
                + "  font-family: 'DM Mono', monospace;\n"
                + "  text-transform: uppercase;\n"
                + "  letter-spacing: 0.05em;\n"
                + "  margin-bottom: 2px;\n"
                + "}\n"
                + "\n"
                + ".metric-value {\n"
                + "  font-size: 0.85em;\n"
                + "  font-weight: 700;\n"
                + "  font-family: 'DM Mono', monospace;\n"
                + "}\n"
                + "\n"
                + "/* ─── STATUS DOT ─── */\n"
                + ".status-dot {\n"
                + "  display: inline-block;\n"
                + "  width: 8px; height: 8px;\n"
                + "  border-radius: 50%;\n"
                + "}\n"
                + ".status-dot.online { background: var(--green); box-shadow: 0 0 6px var(--green); }\n"
                + ".status-dot.offline { background: var(--red); }\n"
                + "\n"
                + "/* ─── BUTTONS ─── */\n"
                + "button {\n"
                + "  cursor: pointer;\n"
                + "  font-family: 'DM Sans', sans-serif;\n"
                + "  font-size: 0.8em;\n"
                + "  font-weight: 600;\n"
                + "  border-radius: 8px;\n"
                + "  padding: 7px 14px;\n"
                + "  border: 1px solid var(--border2);\n"
                + "  background: var(--surface2);\n"
                + "  color: var(--text2);\n"
                + "  transition: all 0.15s;\n"
                + "  display: inline-flex;\n"
                + "  align-items: center;\n"
                + "  justify-content: center;\n"
                + "  gap: 5px;\n"
                + "  outline: none;\n"
                + "  white-space: nowrap;\n"
                + "}\n"
                + "\n"
                + "button:hover {\n"
                + "  background: var(--surface3);\n"
                + "  color: var(--text);\n"
                + "  border-color: var(--border2);\n"
                + "  transform: none;\n"
                + "}\n"
                + "\n"
                + ".btn-primary {\n"
                + "  background: var(--red-subtle);\n"
                + "  border-color: rgba(255,68,85,0.3);\n"
                + "  color: var(--red);\n"
                + "}\n"
                + ".btn-primary:hover {\n"
                + "  background: var(--red);\n"
                + "  color: #fff;\n"
                + "  border-color: var(--red);\n"
                + "  box-shadow: 0 4px 15px var(--red-glow);\n"
                + "}\n"
                + "\n"
                + ".btn-green {\n"
                + "  background: rgba(0,232,122,0.08);\n"
                + "  border-color: rgba(0,232,122,0.25);\n"
                + "  color: var(--green);\n"
                + "}\n"
                + ".btn-green:hover {\n"
                + "  background: var(--green);\n"
                + "  color: #000;\n"
                + "  border-color: var(--green);\n"
                + "  box-shadow: 0 4px 15px var(--green-glow);\n"
                + "}\n"
                + "\n"
                + ".btn-blue {\n"
                + "  background: var(--blue-subtle);\n"
                + "  border-color: rgba(77,159,255,0.25);\n"
                + "  color: var(--blue);\n"
                + "}\n"
                + ".btn-blue:hover {\n"
                + "  background: var(--blue);\n"
                + "  color: #fff;\n"
                + "  border-color: var(--blue);\n"
                + "  box-shadow: 0 4px 15px rgba(77,159,255,0.25);\n"
                + "}\n"
                + "\n"
                + ".btn-icon {\n"
                + "  width: 32px; height: 32px;\n"
                + "  padding: 0;\n"
                + "  flex-shrink: 0;\n"
                + "}\n"
                + "\n"
                + "/* ─── INPUTS ─── */\n"
                + "input[type=text],\n"
                + "input[type=password],\n"
                + "input[type=number],\n"
                + "select {\n"
                + "  background: var(--surface2);\n"
                + "  border: 1px solid var(--border2);\n"
                + "  color: var(--text);\n"
                + "  padding: 9px 12px;\n"
                + "  border-radius: 8px;\n"
                + "  font-family: 'DM Sans', sans-serif;\n"
                + "  font-size: 0.85em;\n"
                + "  outline: none;\n"
                + "  transition: all 0.15s;\n"
                + "  width: 100%;\n"
                + "  -webkit-text-fill-color: var(--text);\n"
                + "}\n"
                + "\n"
                + "input:focus, select:focus {\n"
                + "  border-color: rgba(255,68,85,0.5);\n"
                + "  box-shadow: 0 0 0 3px var(--red-glow);\n"
                + "  background: var(--surface3);\n"
                + "}\n"
                + "\n"
                + "/* Fix browser autofill white background */\n"
                + "input:-webkit-autofill,\n"
                + "input:-webkit-autofill:hover,\n"
                + "input:-webkit-autofill:focus,\n"
                + "input:-webkit-autofill:active {\n"
                + "  -webkit-box-shadow: 0 0 0 1000px var(--surface2) inset !important;\n"
                + "  box-shadow: 0 0 0 1000px var(--surface2) inset !important;\n"
                + "  -webkit-text-fill-color: var(--text) !important;\n"
                + "  border-color: var(--border2) !important;\n"
                + "  caret-color: var(--text);\n"
                + "}\n"
                + "\n"
                + "/* ─── CUSTOM CHECKBOX ─── */\n"
                + "input[type=checkbox] {\n"
                + "  -webkit-appearance: none;\n"
                + "  appearance: none;\n"
                + "  width: 16px; height: 16px;\n"
                + "  border: 1.5px solid var(--border2);\n"
                + "  border-radius: 4px;\n"
                + "  background: var(--surface2);\n"
                + "  cursor: pointer;\n"
                + "  flex-shrink: 0;\n"
                + "  position: relative;\n"
                + "  transition: all 0.15s;\n"
                + "  vertical-align: middle;\n"
                + "}\n"
                + "\n"
                + "input[type=checkbox]:hover {\n"
                + "  border-color: rgba(255,68,85,0.5);\n"
                + "  background: var(--surface3);\n"
                + "}\n"
                + "\n"
                + "input[type=checkbox]:checked {\n"
                + "  background: var(--red);\n"
                + "  border-color: var(--red);\n"
                + "  box-shadow: 0 0 8px var(--red-glow);\n"
                + "}\n"
                + "\n"
                + "input[type=checkbox]:checked::after {\n"
                + "  content: '';\n"
                + "  position: absolute;\n"
                + "  top: 2px; left: 5px;\n"
                + "  width: 4px; height: 7px;\n"
                + "  border: 2px solid #fff;\n"
                + "  border-top: none;\n"
                + "  border-left: none;\n"
                + "  transform: rotate(45deg);\n"
                + "}\n"
                + "\n"
                + "input[type=checkbox]:focus {\n"
                + "  outline: none;\n"
                + "  box-shadow: 0 0 0 3px var(--red-glow);\n"
                + "}\n"
                + "\n"
                + "select option { background: var(--surface2); }\n"
                + "\n"
                + "/* ─── MODAL ─── */\n"
                + ".modal {\n"
                + "  display: none;\n"
                + "  position: fixed;\n"
                + "  inset: 0;\n"
                + "  background: rgba(0,0,0,0.75);\n"
                + "  backdrop-filter: blur(8px);\n"
                + "  align-items: center;\n"
                + "  justify-content: center;\n"
                + "  z-index: 1000;\n"
                + "  padding: 20px;\n"
                + "}\n"
                + "\n"
                + ".modal.open { display: flex; }\n"
                + "\n"
                + ".modal-box {\n"
                + "  background: var(--surface);\n"
                + "  border: 1px solid var(--border2);\n"
                + "  border-radius: 20px;\n"
                + "  padding: 0;\n"
                + "  width: 560px;\n"
                + "  max-width: 100%;\n"
                + "  box-shadow: 0 40px 100px rgba(0,0,0,0.7), 0 0 0 1px rgba(255,255,255,0.03) inset;\n"
                + "  display: flex;\n"
                + "  flex-direction: column;\n"
                + "  max-height: 90vh;\n"
                + "  overflow: hidden;\n"
                + "}\n"
                + "\n"
                + ".modal-box.wide { width: 880px; }\n"
                + "\n"
                + ".modal-header {\n"
                + "  padding: 18px 22px;\n"
                + "  border-bottom: 1px solid var(--border);\n"
                + "  display: flex;\n"
                + "  justify-content: space-between;\n"
                + "  align-items: center;\n"
                + "  flex-shrink: 0;\n"
                + "  background: rgba(255,255,255,0.01);\n"
                + "}\n"
                + "\n"
                + ".modal-header h3 {\n"
                + "  font-family: 'Syne', sans-serif;\n"
                + "  font-size: 0.95em;\n"
                + "  font-weight: 700;\n"
                + "  color: var(--text);\n"
                + "}\n"
                + "\n"
                + ".modal-close {\n"
                + "  background: none;\n"
                + "  border: none;\n"
                + "  color: var(--text3);\n"
                + "  font-size: 1.2em;\n"
                + "  cursor: pointer;\n"
                + "  padding: 4px 8px;\n"
                + "  border-radius: 6px;\n"
                + "  line-height: 1;\n"
                + "  transition: all 0.15s;\n"
                + "  width: auto; height: auto;\n"
                + "}\n"
                + "\n"
                + ".modal-close:hover { background: var(--red-subtle); color: var(--red); transform: none; }\n"
                + "\n"
                + ".modal-body { padding: 22px; overflow-y: auto; flex: 1; }\n"
                + "\n"
                + "/* ─── FORM ─── */\n"
                + ".form-row {\n"
                + "  margin-bottom: 16px;\n"
                + "  display: flex;\n"
                + "  flex-direction: column;\n"
                + "  gap: 6px;\n"
                + "}\n"
                + "\n"
                + ".form-row label {\n"
                + "  font-size: 0.77em;\n"
                + "  font-weight: 600;\n"
                + "  color: var(--text3);\n"
                + "  text-transform: uppercase;\n"
                + "  letter-spacing: 0.05em;\n"
                + "  font-family: 'DM Mono', monospace;\n"
                + "}\n"
                + "\n"
                + ".form-row-inline {\n"
                + "  display: flex;\n"
                + "  gap: 8px;\n"
                + "  align-items: center;\n"
                + "  margin-bottom: 12px;\n"
                + "}\n"
                + "\n"
                + ".form-row-inline label {\n"
                + "  font-size: 0.83em;\n"
                + "  color: var(--text2);\n"
                + "  cursor: pointer;\n"
                + "}\n"
                + "\n"
                + ".form-footer {\n"
                + "  padding: 16px 22px;\n"
                + "  border-top: 1px solid var(--border);\n"
                + "  flex-shrink: 0;\n"
                + "}\n"
                + "\n"
                + "/* ─── CONSOLE ─── */\n"
                + ".console-box {\n"
                + "  background: #050509;\n"
                + "  border: 1px solid var(--border);\n"
                + "  border-radius: 10px;\n"
                + "  padding: 14px 16px;\n"
                + "  height: 420px;\n"
                + "  overflow-y: auto;\n"
                + "  font-family: 'DM Mono', monospace;\n"
                + "  font-size: 0.78em;\n"
                + "  color: #b0b0cc;\n"
                + "  white-space: pre-wrap;\n"
                + "  word-break: break-all;\n"
                + "  line-height: 1.6;\n"
                + "}\n"
                + "\n"
                + ".console-box::-webkit-scrollbar { width: 4px; }\n"
                + ".console-box::-webkit-scrollbar-track { background: transparent; }\n"
                + ".console-box::-webkit-scrollbar-thumb { background: var(--border2); border-radius: 2px; }\n"
                + "\n"
                + ".console-input-row {\n"
                + "  display: flex;\n"
                + "  gap: 8px;\n"
                + "  padding: 14px 22px;\n"
                + "  border-top: 1px solid var(--border);\n"
                + "  flex-shrink: 0;\n"
                + "}\n"
                + "\n"
                + "/* ─── PERMISSIONS ─── */\n"
                + ".perm-tag {\n"
                + "  display: inline-flex;\n"
                + "  align-items: center;\n"
                + "  gap: 5px;\n"
                + "  background: var(--blue-subtle);\n"
                + "  border: 1px solid rgba(77,159,255,0.2);\n"
                + "  border-radius: 6px;\n"
                + "  padding: 3px 8px;\n"
                + "  font-size: 0.72em;\n"
                + "  color: var(--blue);\n"
                + "  font-family: 'DM Mono', monospace;\n"
                + "  margin: 3px;\n"
                + "}\n"
                + "\n"
                + ".perm-tag button {\n"
                + "  background: none;\n"
                + "  border: none;\n"
                + "  color: rgba(255,68,85,0.6);\n"
                + "  padding: 0;\n"
                + "  font-size: 1em;\n"
                + "  cursor: pointer;\n"
                + "  width: auto; height: auto;\n"
                + "  transition: color 0.15s;\n"
                + "}\n"
                + "\n"
                + ".perm-tag button:hover { color: var(--red); transform: none; }\n"
                + "\n"
                + ".perm-area {\n"
                + "  min-height: 44px;\n"
                + "  padding: 8px;\n"
                + "  border: 1px solid var(--border2);\n"
                + "  border-radius: 8px;\n"
                + "  background: var(--surface2);\n"
                + "  display: flex;\n"
                + "  flex-wrap: wrap;\n"
                + "  align-content: flex-start;\n"
                + "}\n"
                + "\n"
                + ".perm-container {\n"
                + "  display: grid;\n"
                + "  grid-template-columns: 160px 1fr;\n"
                + "  gap: 16px;\n"
                + "  align-items: center;\n"
                + "  padding: 14px 16px;\n"
                + "  border-bottom: 1px solid rgba(255,255,255,0.03);\n"
                + "}\n"
                + "\n"
                + ".perm-container:last-child { border-bottom: none; }\n"
                + "\n"
                + ".perm-group-name {\n"
                + "  font-weight: 700;\n"
                + "  font-size: 0.85em;\n"
                + "  color: var(--text);\n"
                + "  font-family: 'Syne', sans-serif;\n"
                + "}\n"
                + "\n"
                + ".perm-grid { display: flex; gap: 8px; flex-wrap: wrap; }\n"
                + "\n"
                + ".perm-item {\n"
                + "  display: flex;\n"
                + "  align-items: center;\n"
                + "  gap: 6px;\n"
                + "  font-size: 0.75em;\n"
                + "  color: var(--text3);\n"
                + "  background: var(--surface3);\n"
                + "  padding: 5px 10px;\n"
                + "  border-radius: 7px;\n"
                + "  border: 1px solid var(--border);\n"
                + "  cursor: pointer;\n"
                + "  transition: all 0.15s;\n"
                + "}\n"
                + "\n"
                + ".perm-item:hover { background: var(--border); color: var(--text2); }\n"
                + ".perm-item input[type=checkbox] { width: 14px; height: 14px; }\n";
        String p2 = ""
                + "\n"
                + "/* ─── TABLE ─── */\n"
                + ".table {\n"
                + "  width: 100%;\n"
                + "  border-collapse: collapse;\n"
                + "}\n"
                + "\n"
                + ".table th {\n"
                + "  background: var(--surface3);\n"
                + "  color: var(--text3);\n"
                + "  font-weight: 600;\n"
                + "  font-size: 0.68em;\n"
                + "  text-transform: uppercase;\n"
                + "  letter-spacing: 0.08em;\n"
                + "  padding: 12px 18px;\n"
                + "  text-align: left;\n"
                + "  font-family: 'DM Mono', monospace;\n"
                + "  border-bottom: 1px solid var(--border2);\n"
                + "}\n"
                + "\n"
                + ".table td {\n"
                + "  padding: 13px 18px;\n"
                + "  border-bottom: 1px solid var(--border);\n"
                + "  font-size: 0.84em;\n"
                + "  vertical-align: middle;\n"
                + "  color: var(--text2);\n"
                + "}\n"
                + "\n"
                + ".table td strong { color: var(--text); }\n"
                + ".table tr:last-child td { border-bottom: none; }\n"
                + ".table tr:hover td { background: rgba(255,255,255,0.01); }\n"
                + "\n"
                + "/* ─── BADGES ─── */\n"
                + ".badge {\n"
                + "  display: inline-flex;\n"
                + "  align-items: center;\n"
                + "  gap: 5px;\n"
                + "  padding: 3px 10px;\n"
                + "  border-radius: 20px;\n"
                + "  font-size: 0.72em;\n"
                + "  font-weight: 600;\n"
                + "  letter-spacing: 0.02em;\n"
                + "}\n"
                + "\n"
                + ".badge-green { background: rgba(0,232,122,0.1); color: var(--green); border: 1px solid rgba(0,232,122,0.25); }\n"
                + ".badge-red { background: var(--red-subtle); color: var(--red); border: 1px solid rgba(255,68,85,0.25); }\n"
                + ".badge-blue { background: var(--blue-subtle); color: var(--blue); border: 1px solid rgba(77,159,255,0.25); }\n"
                + ".badge-amber { background: rgba(255,176,32,0.1); color: var(--amber); border: 1px solid rgba(255,176,32,0.25); }\n"
                + "\n"
                + "/* ─── SECTION SEP ─── */\n"
                + ".section-sep { border: none; border-top: 1px solid var(--border); margin: 20px 0; }\n"
                + "\n"
                + "/* ─── EMPTY STATE ─── */\n"
                + ".empty-state {\n"
                + "  text-align: center;\n"
                + "  padding: 40px;\n"
                + "  color: var(--text3);\n"
                + "  font-size: 0.84em;\n"
                + "}\n"
                + "\n"
                + "/* ─── TOAST ─── */\n"
                + ".toast {\n"
                + "  position: fixed;\n"
                + "  bottom: 24px;\n"
                + "  right: 24px;\n"
                + "  background: var(--surface);\n"
                + "  border: 1px solid var(--border2);\n"
                + "  border-radius: 12px;\n"
                + "  padding: 12px 18px;\n"
                + "  font-size: 0.84em;\n"
                + "  color: var(--text);\n"
                + "  box-shadow: 0 20px 50px rgba(0,0,0,0.5);\n"
                + "  z-index: 9999;\n"
                + "  opacity: 0;\n"
                + "  transform: translateY(12px) scale(0.97);\n"
                + "  transition: all 0.25s cubic-bezier(0.4,0,0.2,1);\n"
                + "  pointer-events: none;\n"
                + "  max-width: 320px;\n"
                + "  display: flex;\n"
                + "  align-items: center;\n"
                + "  gap: 10px;\n"
                + "}\n"
                + "\n"
                + ".toast::before {\n"
                + "  content: '';\n"
                + "  width: 3px;\n"
                + "  align-self: stretch;\n"
                + "  border-radius: 2px;\n"
                + "  flex-shrink: 0;\n"
                + "}\n"
                + "\n"
                + ".toast.show { opacity: 1; transform: translateY(0) scale(1); }\n"
                + ".toast.success::before { background: var(--green); }\n"
                + ".toast.error::before { background: var(--red); }\n"
                + ".toast.info::before { background: var(--blue); }\n"
                + "\n"
                + "/* ─── FILE MANAGER ─── */\n"
                + ".fm-drop {\n"
                + "  display: none;\n"
                + "  position: absolute;\n"
                + "  inset: 0;\n"
                + "  background: rgba(0,232,122,0.1);\n"
                + "  border: 2px dashed var(--green);\n"
                + "  z-index: 100;\n"
                + "  align-items: center;\n"
                + "  justify-content: center;\n"
                + "  border-radius: 18px;\n"
                + "  backdrop-filter: blur(4px);\n"
                + "}\n"
                + "\n"
                + ".fm-drop h2 { color: var(--green); pointer-events: none; font-family: 'Syne', sans-serif; }\n"
                + "\n"
                + ".fm-toolbar {\n"
                + "  padding: 10px 16px;\n"
                + "  background: var(--surface3);\n"
                + "  border-bottom: 1px solid var(--border);\n"
                + "  display: flex;\n"
                + "  justify-content: space-between;\n"
                + "  align-items: center;\n"
                + "  flex-shrink: 0;\n"
                + "}\n"
                + "\n"
                + ".fm-path {\n"
                + "  font-family: 'DM Mono', monospace;\n"
                + "  font-size: 0.78em;\n"
                + "  color: var(--text3);\n"
                + "}\n"
                + "\n"
                + "/* ─── MAINTENANCE BUTTON ─── */\n"
                + "#maintenanceBtn {\n"
                + "  font-size: 0.78em;\n"
                + "  padding: 6px 12px;\n"
                + "}\n"
                + "\n"
                + "/* ─── TERMINAL PAGE ─── */\n"
                + ".terminal-wrapper {\n"
                + "  padding: 22px;\n"
                + "}\n"
                + "\n"
                + ".terminal-hint {\n"
                + "  color: var(--text3);\n"
                + "  font-size: 0.75em;\n"
                + "  margin-top: 8px;\n"
                + "  font-family: 'DM Mono', monospace;\n"
                + "  line-height: 1.8;\n"
                + "}\n"
                + "\n"
                + ".terminal-hint code {\n"
                + "  color: var(--blue);\n"
                + "  background: var(--blue-subtle);\n"
                + "  padding: 1px 6px;\n"
                + "  border-radius: 4px;\n"
                + "}\n"
                + "\n"
                + "/* ─── DISCORD PAGE ─── */\n"
                + ".discord-layout {\n"
                + "  display: flex;\n"
                + "  gap: 16px;\n"
                + "}\n"
                + "\n"
                + ".discord-sidebar {\n"
                + "  width: 230px;\n"
                + "  flex-shrink: 0;\n"
                + "}\n"
                + "\n"
                + ".role-config-area {\n"
                + "  flex: 1;\n"
                + "  background: var(--surface2);\n"
                + "  border: 1px solid var(--border);\n"
                + "  border-radius: 10px;\n"
                + "  padding: 18px;\n"
                + "}\n"
                + "\n"
                + "/* ─── AUDIT ─── */\n"
                + "#auditList td:first-child {\n"
                + "  font-family: 'DM Mono', monospace;\n"
                + "  font-size: 0.78em;\n"
                + "  color: var(--text3);\n"
                + "  white-space: nowrap;\n"
                + "}\n"
                + "\n"
                + "/* ─── SCROLLBAR ─── */\n"
                + "::-webkit-scrollbar { width: 5px; height: 5px; }\n"
                + "::-webkit-scrollbar-track { background: transparent; }\n"
                + "::-webkit-scrollbar-thumb { background: var(--border2); border-radius: 3px; }\n"
                + "::-webkit-scrollbar-thumb:hover { background: var(--border2); }\n"
                + "\n"
                + "/* ─── RESPONSIVE ─── */\n"
                + "@media (max-width: 768px) {\n"
                + "  .navbar { padding: 0 14px; gap: 12px; }\n"
                + "  .nav-tabs { display: none; }\n"
                + "  .main { padding: 16px; }\n"
                + "  .grid { grid-template-columns: 1fr; }\n"
                + "  .server-metrics { grid-template-columns: repeat(2, 1fr); }\n"
                + "}\n"
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "\n"
                + "<!-- AUTH -->\n"
                + "<div id=\"auth-screen\">\n"
                + "  <div class=\"auth-box\">\n"
                + "    <div class=\"auth-logo\"><em>Redstone</em>Net</div>\n"
                + "    <div class=\"auth-subtitle\">Cloud Administration</div>\n"
                + "    <input type=\"password\" id=\"tokenInput\" placeholder=\"Enter Security Token...\" autocomplete=\"off\">\n"
                + "    <button class=\"btn-primary\" onclick=\"login()\" style=\"width:100%;margin-top:4px;padding:10px;font-size:0.88em;justify-content:center;\">\n"
                + "      🔒 Login\n"
                + "    </button>\n"
                + "    <div class=\"auth-hint\">Generate token: <code>webtokens create</code></div>\n"
                + "  </div>\n"
                + "</div>\n"
                + "\n"
                + "<!-- DASHBOARD -->\n"
                + "<div id=\"dashboard\" style=\"display:none;\">\n"
                + "  <nav class=\"navbar\">\n"
                + "    <div class=\"logo\">\n"
                + "      <div class=\"logo-icon\">⚡</div>\n"
                + "      <span><em>Redstone</em>Net <span class=\"logo-cloud\">Cloud</span></span>\n"
                + "    </div>\n"
                + "\n"
                + "    <div class=\"nav-tabs\">\n"
                + "      <button class=\"nav-tab active\" onclick=\"showPage('servers')\" id=\"tab-servers\">\n"
                + "        <span class=\"nav-tab-icon\">⚙️</span> Servers\n"
                + "      </button>\n"
                + "      <button class=\"nav-tab\" onclick=\"showPage('permissions')\" id=\"tab-permissions\">\n"
                + "        <span class=\"nav-tab-icon\">🔑</span> Permissions\n"
                + "      </button>\n"
                + "      <button class=\"nav-tab\" onclick=\"showPage('discord')\" id=\"tab-discord\">\n"
                + "        <span class=\"nav-tab-icon\">💬</span> Discord & RBAC\n"
                + "      </button>\n"
                + "      <button class=\"nav-tab\" onclick=\"showPage('auditlog')\" id=\"tab-auditlog\">\n"
                + "        <span class=\"nav-tab-icon\">📜</span> Audit Log\n"
                + "      </button>\n"
                + "      <button class=\"nav-tab\" onclick=\"showPage('terminal')\" id=\"tab-terminal\">\n"
                + "        <span class=\"nav-tab-icon\">💻</span> Terminal\n"
                + "      </button>\n"
                + "      <button class=\"nav-tab\" onclick=\"showPage('plugins')\" id=\"tab-plugins\">\n"
                + "        <span class=\"nav-tab-icon\">🧩</span> Plugins\n"
                + "      </button>\n"
                + "    </div>\n"
                + "\n"
                + "    <div class=\"nav-right\">\n"
                + "      <div id=\"currentUser\" class=\"user-pill\">\n"
                + "        <img id=\"userAvatar\" src=\"\">\n"
                + "        <span id=\"userName\" class=\"user-name\"></span>\n"
                + "        <span id=\"userTag\" class=\"user-role\">User</span>\n"
                + "      </div>\n"
                + "      <div class=\"status-badge\">\n"
                + "        <span class=\"dot\"></span>\n"
                + "        <span id=\"onlineBadge\">0 Players</span>\n"
                + "      </div>\n"
                + "      <button id=\"maintenanceBtn\" onclick=\"toggleMaintenance()\">🔧 Maintenance: OFF</button>\n"
                + "      <button class=\"btn-primary\" onclick=\"logout()\">Logout</button>\n"
                + "    </div>\n"
                + "  </nav>\n"
                + "\n"
                + "  <div class=\"main\">\n"
                + "\n"
                + "    <!-- SERVERS PAGE -->\n"
                + "    <div class=\"page active\" id=\"page-servers\">\n"
                + "      <div class=\"panel\">\n"
                + "        <div class=\"panel-header\">\n"
                + "          <div class=\"panel-title\">\n"
                + "            <div class=\"panel-title-icon\">📋</div>\n"
                + "            Groups & Templates\n"
                + "          </div>\n"
                + "          <div class=\"panel-actions\">\n"
                + "            <button class=\"btn-green\" onclick=\"openModal('modalCreateGroup')\">+ Group</button>\n"
                + "            <button onclick=\"refresh()\">↻ Refresh</button>\n"
                + "          </div>\n"
                + "        </div>\n"
                + "        <div id=\"groupsGrid\" class=\"grid\"><div class=\"empty-state\">Loading...</div></div>\n"
                + "      </div>\n"
                + "\n"
                + "      <div class=\"panel\">\n"
                + "        <div class=\"panel-header\">\n"
                + "          <div class=\"panel-title\">\n"
                + "            <div class=\"panel-title-icon\"><span class=\"status-dot online\"></span></div>\n"
                + "            Active Instances\n"
                + "          </div>\n"
                + "        </div>\n"
                + "        <div id=\"serversGrid\" class=\"grid\"><div class=\"empty-state\">No active servers.</div></div>\n"
                + "      </div>\n"
                + "    </div>\n"
                + "\n"
                + "    <!-- PERMISSIONS PAGE -->\n"
                + "    <div class=\"page\" id=\"page-permissions\">\n"
                + "      <div class=\"panel\">\n"
                + "        <div class=\"panel-header\">\n"
                + "          <div class=\"panel-title\"><div class=\"panel-title-icon\">🛡️</div> Roles / Groups</div>\n"
                + "          <div class=\"panel-actions\">\n"
                + "            <button class=\"btn-green\" onclick=\"openModal('modalCreatePermGroup')\">+ Role</button>\n"
                + "            <button onclick=\"loadPermissions()\">↻ Load</button>\n"
                + "          </div>\n"
                + "        </div>\n"
                + "        <div id=\"permGroupsTable\"><div class=\"empty-state\">Loading...</div></div>\n"
                + "      </div>\n"
                + "\n"
                + "      <div class=\"panel\">\n"
                + "        <div class=\"panel-header\">\n"
                + "          <div class=\"panel-title\"><div class=\"panel-title-icon\">👤</div> Players</div>\n"
                + "          <button class=\"btn-blue\" onclick=\"openAddUserModal()\">+ Add Player</button>\n"
                + "        </div>\n"
                + "        <div id=\"permUsersTable\"><div class=\"empty-state\">No players saved yet.</div></div>\n"
                + "      </div>\n"
                + "    </div>\n"
                + "\n"
                + "    <!-- TERMINAL PAGE -->\n"
                + "    <div class=\"page\" id=\"page-terminal\">\n"
                + "      <div class=\"panel\">\n"
                + "        <div class=\"panel-header\">\n"
                + "          <div class=\"panel-title\"><div class=\"panel-title-icon\">💻</div> Cloud Node Terminal</div>\n"
                + "        </div>\n"
                + "        <div class=\"terminal-wrapper\">\n"
                + "          <div style=\"display:flex;gap:8px;\">\n"
                + "            <input type=\"text\" id=\"cmdInput\" style=\"flex:1;\" placeholder=\"Cloud command (e.g. perms user oFlori group set admin)\">\n"
                + "            <button class=\"btn-primary\" onclick=\"sendCommand()\">▶ Execute</button>\n"
                + "          </div>\n"
                + "          <div class=\"terminal-hint\">\n"
                + "            Commands: <code>help</code> · <code>start &lt;Group&gt;</code> · <code>stopserver &lt;Name&gt;</code> · <code>perms group &lt;name&gt; add &lt;perm&gt;</code> · <code>perms user &lt;name&gt; group set &lt;group&gt;</code>\n"
                + "          </div>\n"
                + "        </div>\n"
                + "      </div>\n"
                + "    </div>\n"
                + "\n"
                + "    <!-- DISCORD PAGE -->\n"
                + "    <div class=\"page\" id=\"page-discord\">\n"
                + "      <div class=\"panel\">\n"
                + "        <div class=\"panel-header\">\n"
                + "          <div class=\"panel-title\"><div class=\"panel-title-icon\">⚙️</div> Bot Configuration</div>\n"
                + "          <button onclick=\"loadDiscordConfig()\">↻ Refresh</button>\n"
                + "        </div>\n"
                + "        <div class=\"panel-body\">\n"
                + "          <div class=\"form-row\"><label>Bot Token</label><input type=\"password\" id=\"discordToken\" placeholder=\"MTEw...\"></div>\n"
                + "          <div class=\"form-row\"><label>Guild ID</label><input type=\"text\" id=\"discordGuildId\" placeholder=\"123456789...\"></div>\n"
                + "          <button class=\"btn-green\" onclick=\"saveDiscordConfig()\">💾 Save & Restart Bot</button>\n"
                + "        </div>\n"
                + "      </div>\n"
                + "\n"
                + "      <div class=\"panel\">\n"
                + "        <div class=\"panel-header\">\n"
                + "          <div class=\"panel-title\"><div class=\"panel-title-icon\">🛡️</div> Discord Role Permissions (RBAC)</div>\n"
                + "        </div>\n"
                + "        <div class=\"panel-body\">\n"
                + "          <div class=\"discord-layout\">\n"
                + "            <div class=\"discord-sidebar\">\n"
                + "              <div class=\"form-row\">\n"
                + "                <label>Discord Role</label>\n"
                + "                <select id=\"roleSelect\" onchange=\"renderRoleConfig(this.value)\">\n"
                + "                  <option value=\"\">[Select a Bot-cached Role]</option>\n"
                + "                </select>\n"
                + "              </div>\n"
                + "              <button class=\"btn-green\" onclick=\"saveDiscordConfig()\" style=\"width:100%;\">💾 Save Permissions</button>\n"
                + "            </div>\n"
                + "            <div class=\"role-config-area\" id=\"roleConfigArea\">\n"
                + "              <div class=\"empty-state\" style=\"padding:10px\">Please select a role to configure permissions.</div>\n"
                + "            </div>\n"
                + "          </div>\n"
                + "        </div>\n"
                + "      </div>\n"
                + "    </div>\n"
                + "\n"
                + "    <!-- AUDIT LOG PAGE -->\n"
                + "    <div class=\"page\" id=\"page-auditlog\">\n"
                + "      <div class=\"panel\">\n"
                + "        <div class=\"panel-header\">\n"
                + "          <div class=\"panel-title\"><div class=\"panel-title-icon\">📜</div> Audit Log</div>\n"
                + "          <button onclick=\"loadAuditLogs()\">↻ Refresh</button>\n"
                + "        </div>\n"
                + "        <table class=\"table\">\n"
                + "          <thead><tr><th>Date</th><th>User</th><th>Action</th></tr></thead>\n"
                + "          <tbody id=\"auditList\"><tr><td colspan=\"3\" class=\"empty-state\">Loading...</td></tr></tbody>\n"
                + "        </table>\n"
                + "      </div>\n"
                + "    </div>\n"
                + "\n"
                + "    <!-- PLUGINS PAGE -->\n"
                + "    <div class=\"page\" id=\"page-plugins\">\n"
                + "      <!-- PLUGIN BROWSE VIEW -->\n"
                + "      <div id=\"pluginBrowseView\">\n"
                + "        <div class=\"panel\">\n"
                + "          <div class=\"panel-header\">\n"
                + "            <div class=\"panel-title\"><div class=\"panel-title-icon\">🧩</div> Plugin Store <span class=\"badge badge-blue\" style=\"margin-left:6px\">Modrinth</span></div>\n"
                + "          </div>\n"
                + "          <div style=\"padding:16px 22px 0;\">\n"
                + "            <div style=\"display:flex;gap:8px;margin-bottom:14px;\">\n"
                + "              <input type=\"text\" id=\"pluginSearchInput\" placeholder=\"Search plugins on Modrinth...\" style=\"flex:1;\" onkeydown=\"if(event.key==='Enter')searchPlugins()\">\n"
                + "              <button class=\"btn-primary\" onclick=\"searchPlugins()\">🔍 Search</button>\n"
                + "            </div>\n"
                + "            <div id=\"pluginCategories\" style=\"display:flex;gap:6px;flex-wrap:wrap;margin-bottom:16px;\">\n"
                + "              <button class=\"perm-item active\" id=\"cat-btn-all\" onclick=\"filterCategory('') \" style=\"font-size:0.78em;\">🌐 All</button>\n"
                + "              <button class=\"perm-item\" id=\"cat-btn-adventure\" onclick=\"filterCategory('adventure')\" style=\"font-size:0.78em;\">⚔️ Adventure</button>\n"
                + "              <button class=\"perm-item\" id=\"cat-btn-economy\" onclick=\"filterCategory('economy')\" style=\"font-size:0.78em;\">💰 Economy</button>\n"
                + "              <button class=\"perm-item\" id=\"cat-btn-utility\" onclick=\"filterCategory('utility')\" style=\"font-size:0.78em;\">🔧 Utility</button>\n"
                + "              <button class=\"perm-item\" id=\"cat-btn-management\" onclick=\"filterCategory('management')\" style=\"font-size:0.78em;\">🛠️ Management</button>\n"
                + "              <button class=\"perm-item\" id=\"cat-btn-social\" onclick=\"filterCategory('social')\" style=\"font-size:0.78em;\">💬 Social</button>\n"
                + "              <button class=\"perm-item\" id=\"cat-btn-transportation\" onclick=\"filterCategory('transportation')\" style=\"font-size:0.78em;\">🚗 Transportation</button>\n"
                + "              <button class=\"perm-item\" id=\"cat-btn-mobs\" onclick=\"filterCategory('mobs')\" style=\"font-size:0.78em;\">🐉 Mobs</button>\n"
                + "              <button class=\"perm-item\" id=\"cat-btn-anti-griefing\" onclick=\"filterCategory('anti-griefing')\" style=\"font-size:0.78em;\">🛡️ Protection</button>\n"
                + "            </div>\n"
                + "            <div style=\"display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;\">\n"
                + "              <span id=\"pluginResultLabel\" style=\"font-size:0.78em;color:var(--text3);\">Loading top plugins...</span>\n"
                + "              <div style=\"display:flex;gap:6px;align-items:center;\">\n"
                + "                <span style=\"font-size:0.78em;color:var(--text3);\">Sort:</span>\n"
                + "                <select id=\"pluginSortSelect\" onchange=\"searchPlugins()\" style=\"font-size:0.78em;padding:4px 8px;\">\n"
                + "                  <option value=\"downloads\">Most Downloaded</option>\n"
                + "                  <option value=\"relevance\">Relevance</option>\n"
                + "                  <option value=\"newest\">Newest</option>\n"
                + "                  <option value=\"updated\">Recently Updated</option>\n"
                + "                  <option value=\"follows\">Most Followed</option>\n"
                + "                </select>\n"
                + "              </div>\n"
                + "            </div>\n"
                + "          </div>\n"
                + "          <div id=\"pluginsGrid\" style=\"display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:14px;padding:0 22px 20px;\"></div>\n"
                + "          <div style=\"display:flex;justify-content:center;gap:8px;padding:16px 22px;border-top:1px solid var(--border);\">\n"
                + "            <button id=\"pluginPrevBtn\" class=\"btn-primary\" onclick=\"pluginPageChange(-1)\" disabled style=\"padding:6px 16px;\">← Prev</button>\n"
                + "            <span id=\"pluginPageLabel\" style=\"display:flex;align-items:center;font-size:0.82em;color:var(--text3);padding:0 12px;\">Page 1</span>\n"
                + "            <button id=\"pluginNextBtn\" class=\"btn-primary\" onclick=\"pluginPageChange(1)\" style=\"padding:6px 16px;\">Next →</button>\n"
                + "          </div>\n"
                + "        </div>\n"
                + "      </div>\n"
                + "      <!-- PLUGIN DETAIL VIEW -->\n"
                + "      <div id=\"pluginDetailView\" style=\"display:none;\">\n"
                + "        <button onclick=\"closePluginDetail()\" style=\"margin-bottom:16px;background:var(--surface2);border:1px solid var(--border2);color:var(--text2);padding:7px 16px;border-radius:8px;cursor:pointer;font-size:0.82em;\">← Back to Plugin Store</button>\n"
                + "        <div class=\"panel\">\n"
                + "          <div style=\"padding:28px 28px 20px;border-bottom:1px solid var(--border);\">\n"
                + "            <div style=\"display:flex;gap:20px;align-items:flex-start;\">\n"
                + "              <img id=\"pdIcon\" src=\"\" style=\"width:72px;height:72px;border-radius:14px;background:var(--surface3);object-fit:cover;flex-shrink:0;\">\n"
                + "              <div style=\"flex:1;\">\n"
                + "                <div style=\"display:flex;align-items:center;gap:10px;flex-wrap:wrap;\">\n"
                + "                  <h2 id=\"pdTitle\" style=\"font-family:Syne,sans-serif;font-size:1.5em;font-weight:800;margin:0;\"></h2>\n"
                + "                  <span id=\"pdBadge\" class=\"badge badge-green\" style=\"font-size:0.68em;\"></span>\n"
                + "                </div>\n"
                + "                <div id=\"pdAuthor\" style=\"color:var(--text3);font-size:0.88em;margin-top:4px;\"></div>\n"
                + "                <div id=\"pdCategories\" style=\"display:flex;gap:6px;flex-wrap:wrap;margin-top:10px;\"></div>\n"
                + "              </div>\n"
                + "              <div style=\"display:flex;flex-direction:column;gap:8px;flex-shrink:0;\">\n"
                + "                <button id=\"pdInstallBtn\" class=\"btn-green\" style=\"padding:10px 22px;font-size:0.9em;justify-content:center;\" onclick=\"prepareInstall(window._pdProjectId, window._pdTitle)\">⬇️ Install</button>\n"
                + "                <a id=\"pdModrinthLink\" href=\"#\" target=\"_blank\" style=\"text-align:center;font-size:0.75em;color:var(--text3);text-decoration:none;\">View on Modrinth ↗</a>\n"
                + "              </div>\n"
                + "            </div>\n"
                + "          </div>\n"
                + "          <div style=\"display:grid;grid-template-columns:1fr 280px;gap:0;\">\n"
                + "            <div style=\"padding:24px 28px;border-right:1px solid var(--border);\">\n"
                + "              <h3 style=\"font-family:Syne,sans-serif;font-size:0.9em;font-weight:700;margin:0 0 14px;color:var(--text3);text-transform:uppercase;letter-spacing:0.06em;\">Description</h3>\n"
                + "              <div id=\"pdDescription\" style=\"font-size:0.88em;line-height:1.75;color:var(--text2);white-space:pre-wrap;\"></div>\n"
                + "            </div>\n"
                + "            <div style=\"padding:24px 22px;\">\n"
                + "              <h3 style=\"font-family:Syne,sans-serif;font-size:0.9em;font-weight:700;margin:0 0 14px;color:var(--text3);text-transform:uppercase;letter-spacing:0.06em;\">Stats</h3>\n"
                + "              <div id=\"pdStats\" style=\"display:flex;flex-direction:column;gap:10px;margin-bottom:24px;\"></div>\n"
                + "              <h3 style=\"font-family:Syne,sans-serif;font-size:0.9em;font-weight:700;margin:0 0 14px;color:var(--text3);text-transform:uppercase;letter-spacing:0.06em;\">Links</h3>\n"
                + "              <div id=\"pdLinks\" style=\"display:flex;flex-direction:column;gap:6px;margin-bottom:24px;\"></div>\n"
                + "              <h3 style=\"font-family:Syne,sans-serif;font-size:0.9em;font-weight:700;margin:0 0 10px;color:var(--text3);text-transform:uppercase;letter-spacing:0.06em;\">Versions</h3>\n"
                + "              <div id=\"pdVersions\" style=\"display:flex;flex-direction:column;gap:6px;\"><div style=\"color:var(--text3);font-size:0.8em;\">Loading...</div></div>\n"
                + "            </div>\n"
                + "          </div>\n"
                + "        </div>\n"
                + "      </div>\n"
                + "    </div>\n"
                + "\n"
                + "  </div>\n"
                + "</div>\n"
                + "\n"
                + "<!-- MODAL: Create Group -->\n"
                + "<div id=\"modalCreateGroup\" class=\"modal\">\n"
                + "  <div class=\"modal-box\">\n"
                + "    <div class=\"modal-header\">\n"
                + "      <h3>New Group / Template</h3>\n"
                + "      <button class=\"modal-close\" onclick=\"closeModal('modalCreateGroup')\">✕</button>\n"
                + "    </div>\n"
                + "    <div class=\"modal-body\">\n"
                + "      <div class=\"form-row\"><label>Group Name</label><input id=\"gName\" placeholder=\"e.g. Lobby\"></div>\n"
                + "      <div class=\"form-row\"><label>RAM (MB)</label><input type=\"number\" id=\"gMemory\" value=\"1024\"></div>\n"
                + "      <div class=\"form-row\"><label>Type</label>\n"
                + "        <select id=\"gProxy\">\n"
                + "          <option value=\"false\">Subserver (Paper/Spigot)</option>\n"
                + "          <option value=\"true\">Proxy (Velocity / BungeeCord)</option>\n"
                + "        </select>\n"
                + "      </div>\n"
                + "      <div class=\"form-row\"><label>Cloud Software JAR</label><select id=\"gSoftware\"><option value=\"\">Loading...</option></select></div>\n"
                + "      <div class=\"form-row\"><label>Static Port (0 = dynamisch)</label><input type=\"number\" id=\"gPort\" value=\"0\"></div>\n"
                + "      <div class=\"form-row-inline\"><input type=\"checkbox\" id=\"gStatic\"><label for=\"gStatic\">Static Service (Persistent Data)</label></div>\n"
                + "      <div class=\"form-row-inline\"><input type=\"checkbox\" id=\"gBedrock\"><label for=\"gBedrock\">Bedrock Support (GeyserMC & Floodgate)</label></div>\n"
                + "      <div class=\"form-row\"><label>Min. Instances</label><input type=\"number\" id=\"gMinOnline\" value=\"0\"></div>\n"
                + "    </div>\n"
                + "    <div class=\"form-footer\">\n"
                + "      <button class=\"btn-green\" style=\"width:100%;padding:10px;justify-content:center;font-size:0.88em;\" onclick=\"createGroup()\">💾 💾 Create</button>\n"
                + "    </div>\n"
                + "  </div>\n"
                + "</div>\n"
                + "\n"
                + "<!-- MODAL: Console -->\n"
                + "<div id=\"modalConsole\" class=\"modal\">\n"
                + "  <div class=\"modal-box wide\">\n"
                + "    <div class=\"modal-header\">\n"
                + "      <h3 id=\"consoleTitle\">Console</h3>\n"
                + "      <button class=\"modal-close\" onclick=\"closeConsole()\">✕</button>\n"
                + "    </div>\n"
                + "    <div class=\"modal-body\" style=\"padding:16px 22px;\">\n"
                + "      <div class=\"console-box\" id=\"consoleOutput\">Loading...</div>\n"
                + "    </div>\n"
                + "    <div class=\"console-input-row\">\n"
                + "      <input type=\"text\" id=\"serverCmdInput\" style=\"flex:1;\" placeholder=\"Befehl eingeben (ohne /)...\">\n"
                + "      <button class=\"btn-primary\" onclick=\"sendServerCommand()\">▶ Senden</button>\n"
                + "    </div>\n"
                + "  </div>\n"
                + "</div>\n"
                + "\n"
                + "<!-- MODAL: Create Perm Group -->\n"
                + "<div id=\"modalCreatePermGroup\" class=\"modal\">\n"
                + "  <div class=\"modal-box\">\n"
                + "    <div class=\"modal-header\">\n"
                + "      <h3>Create New Role</h3>\n"
                + "      <button class=\"modal-close\" onclick=\"closeModal('modalCreatePermGroup')\">✕</button>\n"
                + "    </div>\n"
                + "    <div class=\"modal-body\">\n"
                + "      <div class=\"form-row\"><label>Name</label><input id=\"pgName\" placeholder=\"e.g. moderator\"></div>\n"
                + "      <div class=\"form-row\"><label>Prefix</label><input id=\"pgPrefix\" placeholder=\"e.g. §9Mod | \"></div>\n"
                + "      <div class=\"form-row-inline\"><input type=\"checkbox\" id=\"pgDefault\"><label for=\"pgDefault\">Default Group (new players)</label></div>\n"
                + "    </div>\n"
                + "    <div class=\"form-footer\">\n"
                + "      <button class=\"btn-green\" style=\"width:100%;padding:10px;justify-content:center;\" onclick=\"createPermGroup()\">💾 Erstellen</button>\n"
                + "    </div>\n"
                + "  </div>\n"
                + "</div>\n"
                + "\n"
                + "<!-- MODAL: Add User -->\n"
                + "<div id=\"modalAddUser\" class=\"modal\">\n"
                + "  <div class=\"modal-box\">\n"
                + "    <div class=\"modal-header\">\n"
                + "      <h3>Add Player / Set Group</h3>\n"
                + "      <button class=\"modal-close\" onclick=\"closeModal('modalAddUser')\">✕</button>\n"
                + "    </div>\n"
                + "    <div class=\"modal-body\">\n"
                + "      <div class=\"form-row\"><label>Player Name</label><input id=\"auName\" placeholder=\"oFlori\"></div>\n"
                + "      <div class=\"form-row\"><label>Group</label><select id=\"auGroup\"></select></div>\n"
                + "    </div>\n"
                + "    <div class=\"form-footer\">\n"
                + "      <button class=\"btn-green\" style=\"width:100%;padding:10px;justify-content:center;\" onclick=\"addUser()\">Speichern</button>\n"
                + "    </div>\n"
                + "  </div>\n"
                + "</div>\n"
                + "\n"
                + "<!-- MODAL: Edit Group -->\n"
                + "<div id=\"modalEditGroup\" class=\"modal\">\n"
                + "  <div class=\"modal-box wide\">\n"
                + "    <div class=\"modal-header\">\n"
                + "      <h3 id=\"editGroupTitle\">Edit Group</h3>\n"
                + "      <button class=\"modal-close\" onclick=\"closeModal('modalEditGroup')\">✕</button>\n"
                + "    </div>\n"
                + "    <div class=\"modal-body\">\n"
                + "      <div class=\"form-row\"><label>Permissions</label></div>\n"
                + "      <div id=\"editGroupPerms\" class=\"perm-area\"></div>\n"
                + "      <div class=\"form-row-inline\" style=\"margin-top:10px;\">\n"
                + "        <input id=\"newGroupPerm\" style=\"flex:1;\" placeholder=\"Add permission\">\n"
                + "        <button class=\"btn-green\" onclick=\"addGroupPerm()\">+ Add</button>\n"
                + "      </div>\n"
                + "    </div>\n"
                + "  </div>\n"
                + "</div>\n"
                + "\n"
                + "<!-- MODAL: Edit User -->\n"
                + "<div id=\"modalEditUser\" class=\"modal\">\n"
                + "  <div class=\"modal-box wide\">\n"
                + "    <div class=\"modal-header\">\n"
                + "      <h3 id=\"editUserTitle\">Edit Player</h3>\n"
                + "      <button class=\"modal-close\" onclick=\"closeModal('modalEditUser')\">✕</button>\n"
                + "    </div>\n"
                + "    <div class=\"modal-body\">\n"
                + "      <div class=\"form-row\"><label>Group</label><select id=\"editUserGroup\"></select></div>\n"
                + "      <button class=\"btn-blue\" style=\"width:100%;margin-bottom:14px;padding:9px;justify-content:center;\" onclick=\"saveUserGroup()\">💾 💾 Save Group</button>\n"
                + "      <hr class=\"section-sep\">\n"
                + "      <div class=\"form-row\"><label>Extra Permissions</label></div>\n"
                + "      <div id=\"editUserPerms\" class=\"perm-area\"></div>\n"
                + "      <div class=\"form-row-inline\" style=\"margin-top:10px;\">\n"
                + "        <input id=\"newUserPerm\" style=\"flex:1;\" placeholder=\"Permission\">\n"
                + "        <button class=\"btn-green\" onclick=\"addUserPerm()\">+ Add</button>\n"
                + "      </div>\n"
                + "    </div>\n"
                + "  </div>\n"
                + "</div>\n"
                + "\n"
                + "<!-- MODAL: File Manager -->\n"
                + "<div id=\"modalFileManager\" class=\"modal\">\n"
                + "  <div class=\"modal-box wide\" style=\"max-width:820px;height:80vh;position:relative;\">\n"
                + "    <div id=\"fmDropZone\" class=\"fm-drop\"><h2>Drop files to upload...</h2></div>\n"
                + "    <div class=\"modal-header\">\n"
                + "      <h3 id=\"fmTitle\">Files:</h3>\n"
                + "      <button class=\"modal-close\" onclick=\"closeModal('modalFileManager')\">✕</button>\n"
                + "    </div>\n"
                + "    <div id=\"fmContent\" style=\"flex:1;overflow-y:auto;background:var(--surface);\"></div>\n"
                + "  </div>\n"
                + "</div>\n"
                + "\n"
                + "<!-- MODAL: Editor -->\n"
                + "<div id=\"modalEditor\" class=\"modal\">\n"
                + "  <div class=\"modal-box wide\" style=\"max-width:90vw;height:90vh;\">\n"
                + "    <div class=\"modal-header\">\n"
                + "      <h3 id=\"editorTitle\">Editing:</h3>\n"
                + "      <button class=\"modal-close\" onclick=\"closeModal('modalEditor')\">✕</button>\n"
                + "    </div>\n"
                + "    <div style=\"flex:1;display:flex;flex-direction:column;padding:16px 22px;gap:12px;overflow:hidden;\">\n"
                + "      <textarea id=\"editorText\" style=\"flex:1;background:#050509;color:#c8c8e0;font-family:'DM Mono',monospace;font-size:0.82em;padding:14px;border:1px solid var(--border2);border-radius:10px;resize:none;outline:none;line-height:1.6;\"></textarea>\n"
                + "      <button class=\"btn-green\" style=\"justify-content:center;padding:10px;\" onclick=\"fmSaveFile()\">💾 💾 Save File</button>\n"
                + "    </div>\n"
                + "  </div>\n"
                + "</div>\n"
                + "\n"
                + "<!-- MODAL: Install Plugin -->\n"
                + "<div id=\"modalInstallPlugin\" class=\"modal\">\n"
                + "  <div class=\"modal-box\">\n"
                + "    <div class=\"modal-header\">\n"
                + "      <h3 id=\"installPluginTitle\">Install Plugin</h3>\n"
                + "      <button class=\"modal-close\" onclick=\"closeModal('modalInstallPlugin')\">✕</button>\n"
                + "    </div>\n"
                + "    <div class=\"modal-body\">\n"
                + "      <p style=\"margin-bottom:15px;color:var(--text3);font-size:0.9em;\">Select a target group to install <strong id=\"installPluginName\" style=\"color:var(--text);\"></strong> to.</p>\n"
                + "      <div class=\"form-row\"><label>Target Group / Template</label><select id=\"installPluginGroup\"></select></div>\n"
                + "    </div>\n"
                + "    <div class=\"form-footer\">\n"
                + "      <button class=\"btn-green\" style=\"width:100%;padding:10px;justify-content:center;\" onclick=\"confirmInstallPlugin()\">⬇️ Download & Install</button>\n"
                + "    </div>\n"
                + "  </div>\n"
                + "</div>\n"
                + "\n"
                + "<script>\n"
                + "let token=localStorage.getItem('rn_tok')||'';\n"
                + "let currentConsoleServer=null,consoleTimer=null,maintenanceState=false;\n"
                + "let permData={groups:[],users:[]};\n"
                + "let editingGroup=null,editingUser=null;\n"
                + "\n"
                + "window.onload=function(){if(token){document.getElementById('tokenInput').value=token;login();}};\n"
                + "\n"
                + "async function login(){\n"
                + "  const t=document.getElementById('tokenInput').value;if(!t)return;\n"
                + "  const r=await fetch('/api/auth',{method:'POST',body:JSON.stringify({token:t})});\n"
                + "  const d=await r.json();\n"
                + "  if(d.success){\n"
                + "    token=t;localStorage.setItem('rn_tok',t);\n"
                + "    document.getElementById('auth-screen').style.display='none';\n"
                + "    document.getElementById('dashboard').style.display='block';\n"
                + "    if(d.discordId!=='CONSOLE'){\n"
                + "      const u=document.getElementById('currentUser');u.style.display='flex';\n"
                + "      document.getElementById('userName').textContent=d.discordName;\n"
                + "      if(d.discordAvatar){const img=document.getElementById('userAvatar');img.src=d.discordAvatar;img.style.display='block';}\n"
                + "      document.getElementById('userTag').textContent=d.fullAccess?'Admin':'Staff';\n"
                + "    }\n"
                + "    refresh();setInterval(refresh,3000);loadPerms();loadMaintenance();\n"
                + "  }else{toast('Invalid Token','error');}\n"
                + "}\n"
                + "\n"
                + "function logout(){token='';localStorage.removeItem('rn_tok');location.reload();}\n"
                + "\n"
                + "async function api(url,opts={}){\n"
                + "  opts.headers={...(opts.headers||{}),'Authorization':'Bearer '+token};\n"
                + "  const r=await fetch(url,opts);\n"
                + "  if(r.status===401)logout();\n"
                + "  if(r.status===403)toast('⛔ Access Denied / Missing Role Rights','error');\n"
                + "  return r;\n"
                + "}\n"
                + "\n"
                + "function showPage(p){\n"
                + "  document.querySelectorAll('.page').forEach(x=>x.classList.remove('active'));\n"
                + "  document.querySelectorAll('.nav-tab').forEach(x=>x.classList.remove('active'));\n"
                + "  document.getElementById('page-'+p).classList.add('active');\n"
                + "  document.getElementById('tab-'+p).classList.add('active');\n"
                + "  if(p==='discord')loadDiscordConfig();\n"
                + "  if(p==='auditlog')loadAuditLogs();\n"
                + "  if(p==='permissions')loadPerms();\n"
                + "  if(p==='plugins' && document.getElementById('pluginsGrid').innerHTML==='')searchPlugins();\n"
                + "}\n"
                + "\n"
                + "function openModal(id){document.getElementById(id).classList.add('open');if(id==='modalCreateGroup')loadSoftware();}\n"
                + "function closeModal(id){document.getElementById(id).classList.remove('open');}\n"
                + "\n"
                + "async function refresh(){\n"
                + "  if(!token)return;\n"
                + "  const r=await api('/api/data');if(r.status!==200)return;\n"
                + "  const d=await r.json();\n"
                + "  window.groupsData=d.groups;\n"
                + "  document.getElementById('onlineBadge').textContent=(d.totalPlayers||0)+' Players';\n"
                + "  const gG=document.getElementById('groupsGrid');gG.innerHTML='';\n"
                + "  d.groups.forEach(g=>{\n"
                + "    const esc=g.name.replace(/'/g,\"\\\\'\");\n"
                + "    let packOpts='<option value=\"\">[No Pack]</option>';\n"
                + "    d.packs.forEach(p=>{packOpts+=`<option value=\"${p}\" ${g.resourcePack===p?'selected':''}>${p}</option>`;});\n"
                + "    gG.innerHTML+=`<div class=\"card\">\n"
                + "      <div class=\"card-header\">\n"
                + "        <div class=\"card-title\">${g.proxy?'🌐':'📦'} ${g.name}</div>\n"
                + "        <div style=\"display:flex;gap:6px;align-items:center;\">\n"
                + "          ${g.bedrock?'<span class=\"badge badge-amber\">📱 Bedrock</span>':''}\n"
                + "          <span class=\"badge ${g.proxy?'badge-blue':'badge-green'}\" style=\"font-size:0.65em;\">${g.proxy?'Proxy':'Server'}</span>\n"
                + "        </div>\n"
                + "      </div>\n"
                + "      <div class=\"card-stats\">\n"
                + "        <div class=\"stat-item\"><div class=\"stat-label\">RAM</div><div class=\"stat-value\">${g.memory} MB</div></div>\n"
                + "        <div class=\"stat-item\"><div class=\"stat-label\">Port</div><div class=\"stat-value\">${g.startPort>0?g.startPort:'dynamic'}</div></div>\n"
                + "        <div class=\"stat-item\"><div class=\"stat-label\">Software</div><div class=\"stat-value\" style=\"font-size:0.75em;\">${g.software}</div></div>\n"
                + "        <div class=\"stat-item\"><div class=\"stat-label\">Min. Online</div><div class=\"stat-value\" style=\"color:var(--green);\">${g.minOnline}</div></div>\n"
                + "      </div>\n"
                + "      <div class=\"card-autoscale\">\n"
                + "        <div class=\"autoscale-header\">\n"
                + "          <label class=\"autoscale-label\"><input type=\"checkbox\" onchange=\"updateAutoScale('${esc}',this.checked,${g.autoScaleThreshold})\" ${g.autoScaleEnabled?'checked':''}> Auto-Scale</label>\n"
                + "          <span class=\"autoscale-value\" id=\"asLabel-${esc}\">${g.autoScaleThreshold}% · Max ${g.maxInstances}</span>\n"
                + "        </div>\n"
                + "        <input type=\"range\" min=\"10\" max=\"100\" step=\"5\" value=\"${g.autoScaleThreshold}\" onchange=\"updateAutoScale('${esc}',${g.autoScaleEnabled},this.value)\" oninput=\"document.getElementById('asLabel-${esc}').textContent=this.value+'% · Max ${g.maxInstances}'\">\n"
                + "      </div>\n"
                + "      ${!g.proxy?`<div class=\"card-pack\"><label>🖼️ ResourcePack</label><select onchange=\"updateGrpPack('${esc}',this.value,document.getElementById('forcePack-${esc}').checked)\">${packOpts}</select><div class=\"pack-force\"><input type=\"checkbox\" id=\"forcePack-${esc}\" onchange=\"updateGrpPack('${esc}','${g.resourcePack||''}',this.checked)\" ${g.forceResourcePack?'checked':''}}><label for=\"forcePack-${esc}\">Pack erzwingen</label></div></div>`:''}\n"
                + "      <div class=\"card-actions\">\n"
                + "        <input type=\"number\" min=\"1\" max=\"10\" value=\"1\" class=\"start-cnt\" data-group=\"${esc}\" style=\"width:44px;text-align:center;padding:6px 4px;\">\n"
                + "        <button class=\"btn-green\" style=\"flex:1\" onclick=\"startGroup('${esc}')\">▶ Start</button>\n"
                + "        <button class=\"btn-blue\" onclick=\"openFileManager('${esc}',true,null)\">📁</button>\n"
                + "        <button class=\"btn-primary btn-icon\" onclick=\"if(confirm('Group ${esc} delete?'))runCmd('deletegroup ${esc}')\">🗑</button>\n"
                + "      </div>\n"
                + "    </div>`;\n"
                + "  });\n"
                + "\n"
                + "  const sG=document.getElementById('serversGrid');sG.innerHTML='';\n"
                + "  if(!d.servers.length){sG.innerHTML='<div class=\"empty-state\">No active servers.</div>';return;}\n"
                + "  d.servers.forEach(s=>{\n"
                + "    const isOn=s.online;const esc=s.name.replace(/'/g,\"\\\\'\");\n"
                + "    const tpsColor=(s.tps||20)<15?'var(--red)':(s.tps||20)<18?'var(--amber)':'var(--green)';\n"
                + "    const cpuColor=(s.cpu||0)>80?'var(--red)':(s.cpu||0)>50?'var(--amber)':'var(--green)';\n"
                + "    sG.innerHTML+=`<div class=\"server-card ${isOn?'online':'offline'}\">\n"
                + "      <div class=\"server-status-bar\"></div>\n"
                + "      <div class=\"card-header\" style=\"margin-bottom:10px;\">\n"
                + "        <div class=\"card-title\"><span class=\"status-dot ${isOn?'online':'offline'}\"></span>${s.name}</div>\n"
                + "        <span class=\"badge ${isOn?'badge-green':'badge-red'}\">${isOn?'ONLINE':'OFFLINE'}</span>\n"
                + "      </div>\n"
                + "      <div class=\"server-metrics\">\n"
                + "        <div class=\"metric\"><div class=\"metric-label\">Players</div><div class=\"metric-value\">${s.playerCount||0}</div></div>\n"
                + "        <div class=\"metric\"><div class=\"metric-label\">Port</div><div class=\"metric-value\">${s.port}</div></div>\n"
                + "        <div class=\"metric\"><div class=\"metric-label\">TPS</div><div class=\"metric-value\" style=\"color:${tpsColor}\">${(s.tps||20.0).toFixed(1)}</div></div>\n"
                + "        <div class=\"metric\"><div class=\"metric-label\">CPU</div><div class=\"metric-value\" style=\"color:${cpuColor}\">${(s.cpu||0).toFixed(1)}%</div></div>\n"
                + "        <div class=\"metric\" style=\"grid-column:span 2\"><div class=\"metric-label\">RAM</div><div class=\"metric-value\">${s.usedMemory||0} / ${s.maxMemory||0} MB</div></div>\n"
                + "      </div>\n"
                + "      <div class=\"card-actions\">\n"
                + "        <button class=\"btn-blue\" style=\"flex:1\" onclick=\"openConsole('${esc}')\">💻 Console</button>\n"
                + "        <button class=\"btn-blue\" onclick=\"openFileManager('${esc.split('-')[0]}',false,'${esc}')\">📁</button>\n"
                + "        <button onclick=\"backupServer('${esc}')\">📦</button>\n"
                + "        <button class=\"btn-primary btn-icon\" onclick=\"runCmd('stopserver ${esc}')\">⏹</button>\n"
                + "      </div>\n"
                + "    </div>`;\n"
                + "  });\n"
                + "}\n"
                + "\n"
                + "async function runCmd(cmd){await api('/api/command',{method:'POST',body:JSON.stringify({command:cmd})});setTimeout(refresh,500);}\n"
                + "function sendCommand(){const v=document.getElementById('cmdInput').value;if(v){runCmd(v);document.getElementById('cmdInput').value='';}}\n"
                + "\n"
                + "function createGroup(){\n"
                + "  const n=document.getElementById('gName').value.trim();\n"
                + "  const m=document.getElementById('gMemory').value||1024;\n"
                + "  const p=document.getElementById('gProxy').value;\n"
                + "  const s=document.getElementById('gSoftware').value.trim()||'paper.jar';\n"
                + "  const port=document.getElementById('gPort').value||0;\n"
                + "  const st=document.getElementById('gStatic').checked;\n"
                + "  const bedrock=document.getElementById('gBedrock').checked;\n"
                + "  const mo=document.getElementById('gMinOnline').value||0;\n"
                + "  if(!n){toast('⚠️ Group name is required!','error');return;}\n"
                + "  runCmd(`create ${n} ${m} ${st} ${p} ${s} ${bedrock} ${port} ${mo}`);\n"
                + "  closeModal('modalCreateGroup');\n"
                + "}\n"
                + "\n"
                + "function openConsole(name){currentConsoleServer=name;document.getElementById('consoleTitle').textContent='Console: '+name;document.getElementById('consoleOutput').textContent='Loading...';openModal('modalConsole');loadLogs();if(consoleTimer)clearInterval(consoleTimer);consoleTimer=setInterval(loadLogs,2000);}\n"
                + "function closeConsole(){currentConsoleServer=null;if(consoleTimer)clearInterval(consoleTimer);closeModal('modalConsole');}\n"
                + "async function loadLogs(){if(!currentConsoleServer)return;const r=await api('/api/logs?server='+currentConsoleServer);if(r.status!==200)return;const d=await r.json();const box=document.getElementById('consoleOutput');box.textContent=d.logs.join('\\n')||'No logs.';box.scrollTop=box.scrollHeight;}\n"
                + "async function sendServerCommand(){if(!currentConsoleServer)return;const v=document.getElementById('serverCmdInput').value;if(!v)return;await api('/api/servercommand',{method:'POST',body:JSON.stringify({server:currentConsoleServer,command:v})});document.getElementById('serverCmdInput').value='';setTimeout(loadLogs,200);}\n"
                + "\n"
                + "async function loadPerms(){const r=await api('/api/permissions');if(r.status!==200)return;permData=await r.json();renderPermGroups();renderPermUsers();}\n"
                + "function loadPermissions(){loadPerms();}\n"
                + "\n"
                + "function renderPermGroups(){\n"
                + "  const el=document.getElementById('permGroupsTable');\n"
                + "  let html='<table class=\"table\"><thead><tr><th>Name</th><th>Prefix</th><th>Default</th><th>Permissions</th><th>Actions</th></tr></thead><tbody>';\n"
                + "  permData.groups.forEach(g=>{\n"
                + "    const permsHtml=g.permissions.map(p=>`<span class=\"perm-tag\">${p}</span>`).join('')||'<span style=\"color:var(--text3);font-size:0.8em;\">—</span>';\n"
                + "    html+=`<tr><td><strong>${g.name}</strong></td><td><code style=\"font-family:'DM Mono',monospace;font-size:0.8em;color:var(--text2);\">${g.prefix||'—'}</code></td><td>${g.isDefault?'<span class=\"badge badge-green\">✓ Default</span>':''}</td><td style=\"max-width:280px;\">${permsHtml}</td><td><button class=\"btn-blue btn-icon\" onclick=\"openEditGroup('${g.name}')\">✏️</button> <button class=\"btn-primary btn-icon\" onclick=\"deletePermGroup('${g.name}')\">🗑</button></td></tr>`;\n"
                + "  });\n"
                + "  html+='</tbody></table>';el.innerHTML=html;\n"
                + "}\n"
                + "\n"
                + "function renderPermUsers(){\n"
                + "  const el=document.getElementById('permUsersTable');\n"
                + "  let html='<table class=\"table\"><thead><tr><th>Player</th><th>Group</th><th>Extra Permissions</th><th>Actions</th></tr></thead><tbody>';\n"
                + "  permData.users.forEach(u=>{\n"
                + "    const permsHtml=u.permissions.map(p=>`<span class=\"perm-tag\">${p}</span>`).join('')||'<span style=\"color:var(--text3);font-size:0.8em;\">—</span>';\n"
                + "    html+=`<tr><td><strong>${u.name}</strong></td><td><span class=\"badge badge-blue\">${u.group}</span></td><td>${permsHtml}</td><td><button class=\"btn-blue btn-icon\" onclick=\"openEditUser('${u.name}')\">✏️</button> <button class=\"btn-primary btn-icon\" onclick=\"deletePermUser('${u.name}')\">🗑</button></td></tr>`;\n"
                + "  });\n"
                + "  html+='</tbody></table>';el.innerHTML=html;\n"
                + "}\n"
                + "\n"
                + "async function permPost(body){await api('/api/permissions',{method:'POST',body:JSON.stringify(body)});await loadPerms();}\n"
                + "function createPermGroup(){const n=document.getElementById('pgName').value;if(!n)return;permPost({action:'createGroup',name:n,prefix:document.getElementById('pgPrefix').value,isDefault:document.getElementById('pgDefault').checked});closeModal('modalCreatePermGroup');}\n"
                + "async function deletePermGroup(name){if(confirm('Delete role '+name+'?'))await permPost({action:'deleteGroup',name});}\n"
                + "function openEditGroup(name){editingGroup=name;document.getElementById('editGroupTitle').textContent='Role: '+name;const g=permData.groups.find(x=>x.name===name);document.getElementById('editGroupPerms').innerHTML=(g.permissions||[]).map(p=>`<span class=\"perm-tag\">${p}<button onclick=\"removeGroupPerm('${p}')\">✕</button></span>`).join('');openModal('modalEditGroup');}\n"
                + "async function addGroupPerm(){const p=document.getElementById('newGroupPerm').value.trim();if(!p)return;await permPost({action:'addGroupPerm',group:editingGroup,perm:p});document.getElementById('newGroupPerm').value='';openEditGroup(editingGroup);}\n"
                + "async function removeGroupPerm(p){await permPost({action:'removeGroupPerm',group:editingGroup,perm:p});openEditGroup(editingGroup);}\n"
                + "function openAddUserModal(){const sel=document.getElementById('auGroup');sel.innerHTML='';permData.groups.forEach(g=>sel.innerHTML+=`<option value=\"${g.name}\">${g.name}</option>`);openModal('modalAddUser');}\n"
                + "async function addUser(){const n=document.getElementById('auName').value.trim();if(!n)return;await permPost({action:'setUserGroup',user:n,group:document.getElementById('auGroup').value});closeModal('modalAddUser');}\n"
                + "function openEditUser(name){editingUser=name;document.getElementById('editUserTitle').textContent='Player: '+name;const u=permData.users.find(x=>x.name===name);const sel=document.getElementById('editUserGroup');sel.innerHTML='';permData.groups.forEach(g=>sel.innerHTML+=`<option value=\"${g.name}\"${g.name===u.group?' selected':''}>${g.name}</option>`);document.getElementById('editUserPerms').innerHTML=(u.permissions||[]).map(p=>`<span class=\"perm-tag\">${p}<button onclick=\"removeUserPerm('${p}')\">✕</button></span>`).join('');openModal('modalEditUser');}\n"
                + "async function saveUserGroup(){const g=document.getElementById('editUserGroup').value;await permPost({action:'setUserGroup',user:editingUser,group:g});openEditUser(editingUser);}\n"
                + "async function addUserPerm(){const p=document.getElementById('newUserPerm').value.trim();if(!p)return;await permPost({action:'addUserPerm',user:editingUser,perm:p});document.getElementById('newUserPerm').value='';openEditUser(editingUser);}\n"
                + "async function removeUserPerm(p){await permPost({action:'removeUserPerm',user:editingUser,perm:p});openEditUser(editingUser);}\n"
                + "async function deletePermUser(name){if(confirm('Remove player '+name+'?'))await permPost({action:'deleteUser',user:name});}\n"
                + "\n"
                + "async function loadMaintenance(){const r=await api('/api/maintenance');if(r.status!==200)return;const d=await r.json();maintenanceState=d.maintenance;updateMaintenanceBtn();}\n"
                + "function updateMaintenanceBtn(){const btn=document.getElementById('maintenanceBtn');if(!btn)return;if(maintenanceState){btn.textContent='🔧 Maintenance: ON';btn.style.cssText='background:rgba(255,68,85,0.2);color:var(--red);border-color:rgba(255,68,85,0.4);';}else{btn.textContent='🔧 Maintenance: OFF';btn.style.cssText='';}}\n"
                + "async function toggleMaintenance(){maintenanceState=!maintenanceState;await api('/api/maintenance',{method:'POST',body:JSON.stringify({enabled:maintenanceState})});updateMaintenanceBtn();toast(maintenanceState?'🔧 Maintenance ENABLED':'✅ Maintenance disabled',maintenanceState?'error':'success');}\n"
                + "\n"
                + "async function updateAutoScale(grp,enabled,threshold){await api('/api/autoscale',{method:'POST',body:JSON.stringify({group:grp,enabled:enabled,threshold:Number(threshold)})});}\n"
                + "async function updateGrpPack(grp,packName,forcePack){await api('/api/assignpack',{method:'POST',body:JSON.stringify({group:grp,pack:packName,force:forcePack})});}\n"
                + "function startGroup(name){const inp=document.querySelector('.start-cnt[data-group=\"'+name+'\"]');const cnt=inp?Number(inp.value)||1:1;runCmd('start '+name+' '+cnt);toast('▶ Starting '+cnt+'× '+name,'success');}\n"
                + "async function backupServer(name){const r=await api('/api/backup',{method:'POST',body:JSON.stringify({server:name})});const d=await r.json();if(d.success)toast('📦 Backup started: '+name,'success');else toast('❌ '+(d.error||'Error'),'error');}\n"
                + "async function loadSoftware(){const r=await api('/api/software');if(r.status!==200)return;const d=await r.json();const sel=document.getElementById('gSoftware');sel.innerHTML='';if(!d.software||d.software.length===0){sel.innerHTML='<option value=\"\">(No software found in local/software/)</option>';return;}d.software.forEach(s=>sel.innerHTML+=`<option value=\"${s}\">${s}</option>`);}\n"
                + "\n"
                + "let discordCfgData={};\n"
                + "async function loadDiscordConfig(){const r=await api('/api/discord/config');if(r.status!==200)return;const d=await r.json();discordCfgData=d;document.getElementById('discordToken').value=d.botToken||'';document.getElementById('discordGuildId').value=d.guildId||'';const ro=await api('/api/discord/roles');if(ro.status==200){const rod=await ro.json();const rs=document.getElementById('roleSelect');rs.innerHTML='<option value=\"\">[Select a Bot-cached Role]</option>';rod.roles.forEach(role=>{rs.innerHTML+=`<option value=\"${role.id}\" style=\"color:${role.color}\">${role.name}</option>`;});}}\n"
                + "async function saveDiscordConfig(){try{discordCfgData.botToken=document.getElementById('discordToken').value;discordCfgData.guildId=document.getElementById('discordGuildId').value;await api('/api/discord/config',{method:'POST',body:JSON.stringify(discordCfgData)});toast('Discord Config Saved & Bot Reloaded','success');setTimeout(loadDiscordConfig,1000);}catch(e){toast('Error saving config','error');}}\n"
                + "\n"
                + "function renderRoleConfig(roleId){\n"
                + "  const area=document.getElementById('roleConfigArea');\n"
                + "  if(!roleId){area.innerHTML='<div class=\"empty-state\" style=\"padding:10px\">Please select a role to configure permissions.</div>';return;}\n"
                + "  if(!discordCfgData.roleMappings)discordCfgData.roleMappings={};\n"
                + "  let m=discordCfgData.roleMappings[roleId]||{fullAccess:false,allowedGroups:{}};\n"
                + "  let html=`<div style=\"margin-bottom:16px;\"><label style=\"display:flex;align-items:center;gap:8px;font-weight:600;font-size:0.88em;\"><input type=\"checkbox\" ${m.fullAccess?'checked':''} onchange=\"updateRoleFullAccess('${roleId}',this.checked)\"> 🌟 FULL ADMIN ACCESS (Global)</label><p style=\"font-size:0.75em;color:var(--text3);margin-top:4px;\">Grants all permissions automatically, ignoring group settings.</p></div><hr class=\"section-sep\"><p style=\"font-size:0.77em;color:var(--text3);margin-bottom:10px;text-transform:uppercase;letter-spacing:0.06em;font-family:'DM Mono',monospace;\">Per-Group Access</p>`;\n"
                + "  (window.groupsData||[]).forEach(g=>{\n"
                + "    const p=m.allowedGroups[g.name]||[];\n"
                + "    html+=`<div class=\"perm-container\"><div class=\"perm-group-name\">${g.name}</div><div class=\"perm-grid\">\n"
                + "      <label class=\"perm-item\"><input type=\"checkbox\" ${p.includes('START')?'checked':''} onchange=\"updateRolePerm('${roleId}','${g.name}','START',this.checked)\"> Start</label>\n"
                + "      <label class=\"perm-item\"><input type=\"checkbox\" ${p.includes('STOP')?'checked':''} onchange=\"updateRolePerm('${roleId}','${g.name}','STOP',this.checked)\"> Stop</label>\n"
                + "      <label class=\"perm-item\"><input type=\"checkbox\" ${p.includes('CONSOLE')?'checked':''} onchange=\"updateRolePerm('${roleId}','${g.name}','CONSOLE',this.checked)\"> Console</label>\n"
                + "      <label class=\"perm-item\"><input type=\"checkbox\" ${p.includes('FILE_VIEW')?'checked':''} onchange=\"updateRolePerm('${roleId}','${g.name}','FILE_VIEW',this.checked)\"> Files View</label>\n"
                + "      <label class=\"perm-item\"><input type=\"checkbox\" ${p.includes('FILE_EDIT')?'checked':''} onchange=\"updateRolePerm('${roleId}','${g.name}','FILE_EDIT',this.checked)\"> Files Edit</label>\n"
                + "      <label class=\"perm-item\"><input type=\"checkbox\" ${p.includes('FILE_UPLOAD')?'checked':''} onchange=\"updateRolePerm('${roleId}','${g.name}','FILE_UPLOAD',this.checked)\"> Upload</label>\n"
                + "      <label class=\"perm-item\"><input type=\"checkbox\" ${p.includes('FILE_DELETE')?'checked':''} onchange=\"updateRolePerm('${roleId}','${g.name}','FILE_DELETE',this.checked)\"> Delete</label>\n"
                + "    </div></div>`;\n"
                + "  });\n"
                + "  area.innerHTML=html;\n"
                + "}\n"
                + "\n"
                + "function updateRoleFullAccess(roleId,state){if(!discordCfgData.roleMappings[roleId])discordCfgData.roleMappings[roleId]={allowedGroups:{}};discordCfgData.roleMappings[roleId].fullAccess=state;}\n"
                + "function updateRolePerm(roleId,groupName,perm,state){if(!discordCfgData.roleMappings[roleId])discordCfgData.roleMappings[roleId]={allowedGroups:{}};if(!discordCfgData.roleMappings[roleId].allowedGroups[groupName])discordCfgData.roleMappings[roleId].allowedGroups[groupName]=[];const arr=discordCfgData.roleMappings[roleId].allowedGroups[groupName];if(state&&!arr.includes(perm))arr.push(perm);if(!state&&arr.includes(perm))arr.splice(arr.indexOf(perm),1);}\n"
                + "\n"
                + "let fmGroup='',fmPath='',fmServer='';\n"
                + "function openFileManager(grp,isTemplate,srv){fmGroup=grp;fmServer=srv||'';fmPath='';document.getElementById('fmTitle').textContent=`Files: ${fmServer?fmServer:(grp+' (Template)')}`;openModal('modalFileManager');loadFiles();}\n"
                + "async function loadFiles(){\n"
                + "  const r=await api(`/api/files?action=list&group=${fmGroup}&server=${fmServer}&path=${encodeURIComponent(fmPath)}`);\n"
                + "  if(r.status!==200){if(r.status===403)toast('No permission!','error');else toast('Directory not found.','error');return;}\n"
                + "  const d=await r.json();\n"
                + "  let h=`<div class=\"fm-toolbar\"><span class=\"fm-path\">/${fmPath}</span><div style=\"display:flex;gap:6px;\"><button onclick=\"fmCreateFolder()\" class=\"btn-blue\">+ Folder</button><label class=\"btn-green\" style=\"cursor:pointer;display:inline-flex;align-items:center;gap:5px;\">Upload<input type=\"file\" style=\"display:none\" onchange=\"fmUploadFile(this)\" multiple></label></div></div><table class=\"table\"><tbody>`;\n"
                + "  if(fmPath.length>0)h+=`<tr><td colspan=\"3\" style=\"cursor:pointer;color:var(--blue);\" onclick=\"fmUp()\">📁 ..</td></tr>`;\n"
                + "  d.files.sort((a,b)=>b.isdir-a.isdir||a.name.localeCompare(b.name)).forEach(f=>{\n"
                + "    if(f.isdir)h+=`<tr><td style=\"cursor:pointer;\" onclick=\"fmEnter('${f.name}')\">📁 ${f.name}</td><td style=\"color:var(--text3);\">—</td><td style=\"text-align:right;\"><button class=\"btn-primary btn-icon\" onclick=\"fmDelete('${f.name}')\">🗑</button></td></tr>`;\n"
                + "    else h+=`<tr><td>📄 ${f.name}</td><td style=\"color:var(--text3);font-family:'DM Mono',monospace;font-size:0.78em;\">${(f.size/1024).toFixed(1)} KB</td><td style=\"text-align:right;display:flex;gap:6px;justify-content:flex-end;\"><button class=\"btn-blue btn-icon\" onclick=\"fmEdit('${f.name}')\">✏️</button><button class=\"btn-primary btn-icon\" onclick=\"fmDelete('${f.name}')\">🗑</button></td></tr>`;\n"
                + "  });\n"
                + "  h+='</tbody></table>';document.getElementById('fmContent').innerHTML=h;\n"
                + "}\n"
                + "\n"
                + "function fmUp(){fmPath=fmPath.split('/').slice(0,-1).join('/');loadFiles();}\n"
                + "function fmEnter(dir){fmPath=fmPath?fmPath+'/'+dir:dir;loadFiles();}\n"
                + "async function fmDelete(name){if(!confirm(name+' delete?'))return;const p=fmPath?fmPath+'/'+name:name;await api(`/api/files?action=delete&group=${fmGroup}&server=${fmServer}&path=${encodeURIComponent(p)}`);loadFiles();}\n"
                + "async function fmCreateFolder(){const n=prompt('Folder name:');if(!n)return;const p=fmPath?fmPath+'/'+n:n;await api(`/api/files?action=mkdir&group=${fmGroup}&server=${fmServer}&path=${encodeURIComponent(p)}`,{method:'POST'});loadFiles();}\n"
                + "async function fmUploadFile(inp){for(const f of inp.files){const p=fmPath?fmPath+'/'+f.name:f.name;toast('Uploading '+f.name+'...','info');await fetch(`/api/files?action=upload&group=${fmGroup}&server=${fmServer}&path=${encodeURIComponent(p)}`,{method:'POST',headers:{'Authorization':'Bearer '+token},body:f});}loadFiles();}\n"
                + "\n"
                + "let fmEditFile='';\n"
                + "async function fmEdit(name){fmEditFile=fmPath?fmPath+'/'+name:name;const r=await api(`/api/files?action=read&group=${fmGroup}&server=${fmServer}&path=${encodeURIComponent(fmEditFile)}`);if(r.status!==200){toast('Cannot read file','error');return;}const t=await r.text();document.getElementById('editorTitle').textContent=`Editing: ${name}`;document.getElementById('editorText').value=t;openModal('modalEditor');}\n"
                + "async function fmSaveFile(){await api(`/api/files?action=write&group=${fmGroup}&server=${fmServer}&path=${encodeURIComponent(fmEditFile)}`,{method:'POST',body:JSON.stringify({content:document.getElementById('editorText').value})});toast('Saved!','success');closeModal('modalEditor');}\n"
                + "\n"
                + "['dragover','dragenter'].forEach(e=>window.addEventListener(e,ev=>{ev.preventDefault();if(document.getElementById('modalFileManager').classList.contains('open'))document.getElementById('fmDropZone').style.display='flex';}));\n"
                + "['dragleave','dragend','drop'].forEach(e=>window.addEventListener(e,ev=>{if(document.getElementById('modalFileManager').classList.contains('open'))document.getElementById('fmDropZone').style.display='none';}));\n"
                + "window.addEventListener('drop',async ev=>{ev.preventDefault();if(document.getElementById('modalFileManager').classList.contains('open')&&ev.dataTransfer.files.length>0){document.getElementById('fmDropZone').style.display='none';fmUploadFile({files:ev.dataTransfer.files});}});\n"
                + "\n"
                + "async function loadAuditLogs(){const r=await api('/api/auditlog');if(r.status!==200)return;const d=await r.json();const list=document.getElementById('auditList');list.innerHTML='';if(!d.logs||!d.logs.length){list.innerHTML='<tr><td colspan=\"3\" class=\"empty-state\">No logs found.</td></tr>';return;}[...d.logs].reverse().forEach(l=>{list.innerHTML+=`<tr><td>${l.date}</td><td><div style=\"display:flex;align-items:center;gap:8px;\">${l.userAvatar?`<img src=\"${l.userAvatar}\" style=\"width:28px;height:28px;border-radius:50%;object-fit:cover;border:1px solid var(--border2);flex-shrink:0;\" onerror=\"this.style.display='none'\">`:''}<span style=\"color:var(--blue);font-weight:600;\">${l.userName}</span><span style=\"font-size:0.72em;color:var(--text3);\">(${l.userId})</span></div></td><td>${l.action}</td></tr>`;});}\n"
                + "\n"
                + "function toast(msg,type='success'){\n"
                + "  let t=document.getElementById('_toast');\n"
                + "  if(!t){t=document.createElement('div');t.id='_toast';t.className='toast';document.body.appendChild(t);}\n"
                + "  t.textContent=msg;t.className='toast '+type;\n"
                + "  requestAnimationFrame(()=>requestAnimationFrame(()=>t.classList.add('show')));\n"
                + "  clearTimeout(t._tm);t._tm=setTimeout(()=>t.classList.remove('show'),3200);\n"
                + "}\n"
                + "\n"
                + "document.addEventListener('keydown',e=>{\n"
                + "  if(e.key==='Enter'){\n"
                + "    if(document.activeElement===document.getElementById('cmdInput'))sendCommand();\n"
                + "    if(document.activeElement===document.getElementById('serverCmdInput'))sendServerCommand();\n"
                + "    if(document.activeElement===document.getElementById('tokenInput'))login();\n"
                + "  }\n"
                + "});\n"
                + "\n"
                + "/* PLUGINS */\n"
                + "let currentInstallUrl='',currentInstallFilename='';\n"
                + "let pluginCurrentPage=0,pluginCurrentCategory='',pluginCurrentQuery='';\n"
                + "const PLUGINS_PER_PAGE=20;\n"
                + "\n"
                + "async function searchPlugins(resetPage){\n"
                + "  if(resetPage!==false)pluginCurrentPage=0;\n"
                + "  pluginCurrentQuery=document.getElementById('pluginSearchInput').value.trim();\n"
                + "  const sort=document.getElementById('pluginSortSelect').value;\n"
                + "  const grid=document.getElementById('pluginsGrid');\n"
                + "  const label=document.getElementById('pluginResultLabel');\n"
                + "  grid.innerHTML='<div class=\"empty-state\" style=\"grid-column:1/-1;\">Loading plugins from Modrinth...</div>';\n"
                + "  label.textContent='Loading...';\n"
                + "  try{\n"
                + "    let facets=[[\"project_type:plugin\"]];\n"
                + "    if(pluginCurrentCategory)facets.push([`categories:${pluginCurrentCategory}`]);\n"
                + "    const offset=pluginCurrentPage*PLUGINS_PER_PAGE;\n"
                + "    const url=`https://api.modrinth.com/v2/search?query=${encodeURIComponent(pluginCurrentQuery)}&facets=${encodeURIComponent(JSON.stringify(facets))}&limit=${PLUGINS_PER_PAGE}&offset=${offset}&index=${sort}`;\n"
                + "    const r=await fetch(url);\n"
                + "    const d=await r.json();\n"
                + "    const total=d.total_hits||0;\n"
                + "    label.textContent=total===0?'No plugins found':`Showing ${offset+1}–${Math.min(offset+PLUGINS_PER_PAGE,total)} of ${total.toLocaleString()} plugins`;\n"
                + "    document.getElementById('pluginPageLabel').textContent=`Page ${pluginCurrentPage+1} of ${Math.max(1,Math.ceil(total/PLUGINS_PER_PAGE))}`;\n"
                + "    document.getElementById('pluginPrevBtn').disabled=pluginCurrentPage===0;\n"
                + "    document.getElementById('pluginNextBtn').disabled=offset+PLUGINS_PER_PAGE>=total;\n"
                + "    if(!d.hits||d.hits.length===0){grid.innerHTML='<div class=\"empty-state\" style=\"grid-column:1/-1;\">No plugins found.</div>';return;}\n"
                + "    grid.innerHTML='';\n"
                + "    d.hits.forEach(p=>{\n"
                + "      const icon=p.icon_url?`<img src=\"${p.icon_url}\" style=\"width:44px;height:44px;border-radius:10px;object-fit:cover;flex-shrink:0;background:var(--surface3);\">\n"
                + "               `:`<div style=\"width:44px;height:44px;border-radius:10px;background:var(--surface3);display:flex;align-items:center;justify-content:center;font-size:20px;flex-shrink:0;\">🧩</div>`;\n"
                + "      const dl=p.downloads>=1000000?`${(p.downloads/1000000).toFixed(1)}M`:p.downloads>=1000?`${(p.downloads/1000).toFixed(0)}K`:p.downloads;\n"
                + "      const titleE=p.title.replace(/'/g,\"\\\\'\");\n"
                + "      grid.innerHTML+=`\n"
                + "        <div class=\"card\" style=\"display:flex;flex-direction:column;gap:0;padding:0;overflow:hidden;cursor:pointer;\" onclick=\"openPluginDetail('${p.project_id}','${titleE}')\">\n"
                + "          <div style=\"padding:16px;display:flex;gap:12px;align-items:flex-start;\">\n"
                + "            ${icon}\n"
                + "            <div style=\"flex:1;min-width:0;\">\n"
                + "              <div style=\"font-weight:700;font-size:0.95em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;\">${p.title}</div>\n"
                + "              <div style=\"font-size:0.72em;color:var(--text3);margin-top:2px;\">by ${p.author||'Unknown'}</div>\n"
                + "            </div>\n"
                + "          </div>\n"
                + "          <div style=\"padding:0 16px 12px;font-size:0.8em;color:var(--text2);line-height:1.5;height:52px;overflow:hidden;\">${p.description}</div>\n"
                + "          <div style=\"padding:10px 16px;border-top:1px solid var(--border);display:flex;justify-content:space-between;align-items:center;background:rgba(255,255,255,0.01);\">\n"
                + "            <div style=\"display:flex;gap:12px;font-size:0.72em;color:var(--text3);\">\n"
                + "              <span>⬇️ ${dl}</span>\n"
                + "              <span>❤️ ${p.follows||0}</span>\n"
                + "            </div>\n"
                + "            <button class=\"btn-green\" style=\"padding:5px 12px;font-size:0.75em;justify-content:center;\" onclick=\"event.stopPropagation();prepareInstall('${p.project_id}','${titleE}')\">⬇️ Install</button>\n"
                + "          </div>\n"
                + "        </div>`;\n"
                + "    });\n"
                + "  }catch(e){grid.innerHTML='<div class=\"empty-state\" style=\"grid-column:1/-1;color:var(--red);\">Error loading from Modrinth API.</div>';}\n"
                + "}\n"
                + "\n"
                + "function pluginPageChange(dir){pluginCurrentPage+=dir;searchPlugins(false);}\n"
                + "\n"
                + "function filterCategory(cat){\n"
                + "  pluginCurrentCategory=cat;\n"
                + "  document.querySelectorAll('#pluginCategories .perm-item').forEach(b=>b.classList.remove('active'));\n"
                + "  document.getElementById('cat-btn-'+(cat||'all')).classList.add('active');\n"
                + "  searchPlugins();\n"
                + "}\n"
                + "\n"
                + "function closePluginDetail(){\n"
                + "  document.getElementById('pluginDetailView').style.display='none';\n"
                + "  document.getElementById('pluginBrowseView').style.display='';\n"
                + "}\n"
                + "\n"
                + "async function openPluginDetail(projectId, title){\n"
                + "  document.getElementById('pluginBrowseView').style.display='none';\n"
                + "  document.getElementById('pluginDetailView').style.display='block';\n"
                + "  window._pdProjectId=projectId; window._pdTitle=title;\n"
                + "  document.getElementById('pdTitle').textContent='Loading...';\n"
                + "  document.getElementById('pdDescription').textContent='Fetching plugin data...';\n"
                + "  document.getElementById('pdAuthor').textContent='';\n"
                + "  document.getElementById('pdStats').innerHTML='';\n"
                + "  document.getElementById('pdLinks').innerHTML='';\n"
                + "  document.getElementById('pdCategories').innerHTML='';\n"
                + "  document.getElementById('pdVersions').innerHTML='<div style=\"color:var(--text3);font-size:0.8em;\">Loading...</div>';\n"
                + "  document.getElementById('pdIcon').src='';\n"
                + "  document.getElementById('pdBadge').textContent='';\n"
                + "  document.getElementById('pdModrinthLink').href=`https://modrinth.com/plugin/${projectId}`;\n"
                + "  try{\n"
                + "    const [pRes,vRes]=await Promise.all([\n"
                + "      fetch(`https://api.modrinth.com/v2/project/${projectId}`),\n"
                + "      fetch(`https://api.modrinth.com/v2/project/${projectId}/version?limit=10`)\n"
                + "    ]);\n"
                + "    const p=await pRes.json();\n"
                + "    const versions=await vRes.json();\n"
                + "    document.getElementById('pdIcon').src=p.icon_url||'';\n"
                + "    document.getElementById('pdTitle').textContent=p.title||title;\n"
                + "    document.getElementById('pdAuthor').textContent='by '+p.team;\n"
                + "    document.getElementById('pdBadge').textContent=p.status==='approved'?'✓ Approved':p.status||'';\n"
                + "    const cats=document.getElementById('pdCategories');\n"
                + "    (p.categories||[]).forEach(c=>{cats.innerHTML+=`<span class=\"badge badge-blue\" style=\"font-size:0.68em;\">${c}</span>`;});\n"
                + "    const fmt=n=>n>=1000000?`${(n/1000000).toFixed(1)}M`:n>=1000?`${(n/1000).toFixed(0)}K`:n;\n"
                + "    const updated=new Date(p.updated).toLocaleDateString('en-GB',{day:'2-digit',month:'short',year:'numeric'});\n"
                + "    const created=new Date(p.published).toLocaleDateString('en-GB',{day:'2-digit',month:'short',year:'numeric'});\n"
                + "    document.getElementById('pdStats').innerHTML=\n"
                + "      `<div style=\"display:flex;justify-content:space-between;font-size:0.82em;\"><span style=\"color:var(--text3);\">Downloads</span><span style=\"font-weight:700;\">${fmt(p.downloads||0)}</span></div>\n"
                + "       <div style=\"display:flex;justify-content:space-between;font-size:0.82em;\"><span style=\"color:var(--text3);\">Followers</span><span style=\"font-weight:700;\">${fmt(p.followers||0)}</span></div>\n"
                + "       <div style=\"display:flex;justify-content:space-between;font-size:0.82em;\"><span style=\"color:var(--text3);\">Updated</span><span style=\"font-weight:700;\">${updated}</span></div>\n"
                + "       <div style=\"display:flex;justify-content:space-between;font-size:0.82em;\"><span style=\"color:var(--text3);\">Published</span><span style=\"font-weight:700;\">${created}</span></div>\n"
                + "       <div style=\"display:flex;justify-content:space-between;font-size:0.82em;\"><span style=\"color:var(--text3);\">License</span><span style=\"font-weight:700;\">${(p.license&&p.license.id)||'Unknown'}</span></div>`;\n"
                + "    const links=document.getElementById('pdLinks');\n"
                + "    links.innerHTML='';\n"
                + "    if(p.source_url)links.innerHTML+=`<a href=\"${p.source_url}\" target=\"_blank\" style=\"font-size:0.8em;color:var(--blue);text-decoration:none;\">🔗 Source Code</a>`;\n"
                + "    if(p.issues_url)links.innerHTML+=`<a href=\"${p.issues_url}\" target=\"_blank\" style=\"font-size:0.8em;color:var(--blue);text-decoration:none;\">🐛 Issue Tracker</a>`;\n"
                + "    if(p.wiki_url)links.innerHTML+=`<a href=\"${p.wiki_url}\" target=\"_blank\" style=\"font-size:0.8em;color:var(--blue);text-decoration:none;\">📖 Wiki</a>`;\n"
                + "    if(p.discord_url)links.innerHTML+=`<a href=\"${p.discord_url}\" target=\"_blank\" style=\"font-size:0.8em;color:var(--blue);text-decoration:none;\">💬 Discord</a>`;\n"
                + "    if(!links.innerHTML)links.innerHTML='<span style=\"font-size:0.8em;color:var(--text3);\">No links available</span>';\n"
                + "    const simpleMd=t=>{if(!t)return 'No description.';return t.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/#{1,6}\\s*(.*)/g,'<h4 style=\"margin:14px 0 6px;color:var(--text1);\">$1</h4>').replace(/\\*\\*(.*?)\\*\\*/g,'<strong>$1</strong>').replace(/\\*(.*?)\\*/g,'<em>$1</em>').replace(/\\[(.*?)\\]\\((.*?)\\)/g,'<a href=\"$2\" target=\"_blank\" style=\"color:var(--blue);\">$1</a>').replace(/`([^`]+)`/g,'<code style=\"background:var(--surface2);padding:2px 4px;border-radius:4px;font-family:monospace;\">$1</code>').replace(/\\n\\s*[\\*\\-]\\s+(.*)/g,'<li style=\"margin-left:20px;\">$1</li>').replace(/\\n/g,'<br>');};\n"
                + "    document.getElementById('pdDescription').innerHTML=simpleMd(p.body||p.description);\n"
                + "    const vdiv=document.getElementById('pdVersions');\n"
                + "    vdiv.innerHTML='';\n"
                + "    if(!versions||!versions.length){vdiv.innerHTML='<div style=\"font-size:0.8em;color:var(--text3);\">No versions found.</div>';return;}\n"
                + "    window._pdVersionFiles=[];\n"
                + "    versions.slice(0,8).forEach((v,i)=>{\n"
                + "      const file=v.files&&(v.files.find(f=>f.primary)||v.files[0]);\n"
                + "      if(!file)return;\n"
                + "      window._pdVersionFiles.push({url:file.url,filename:file.filename});\n"
                + "      const mc=(v.game_versions||[]).slice(-1)[0]||'?';\n"
                + "      const idx=window._pdVersionFiles.length-1;\n"
                + "      vdiv.innerHTML+=`<div style=\"display:flex;justify-content:space-between;align-items:center;padding:7px 10px;background:var(--surface2);border-radius:7px;gap:8px;\">\n"
                + "        <div>\n"
                + "          <div style=\"font-size:0.8em;font-weight:600;\">${v.name||v.version_number}</div>\n"
                + "          <div style=\"font-size:0.7em;color:var(--text3);\">MC ${mc} &middot; ${v.version_type||'release'}</div>\n"
                + "        </div>\n"
                + "        <button class=\"btn-green\" style=\"padding:4px 10px;font-size:0.72em;justify-content:center;flex-shrink:0;\" onclick=\"installVersionByIndex(${idx})\">&#11015;&#65039;</button>\n"
                + "      </div>`;\n"
                + "    });\n"
                + "  }catch(e){document.getElementById('pdDescription').textContent='Failed to load plugin details.';}\n"
                + "}\n"
                + "\n"
                + "function installVersionByIndex(idx){\n"
                + "  const v=window._pdVersionFiles&&window._pdVersionFiles[idx];\n"
                + "  if(!v)return;\n"
                + "  installSpecificVersion(v.url,v.filename,window._pdTitle||'Plugin');\n"
                + "}\n"
                + "\n"
                + "function installSpecificVersion(url,filename,title){\n"
                + "  currentInstallUrl=url; currentInstallFilename=filename;\n"
                + "  document.getElementById('installPluginName').textContent=title;\n"
                + "  const sel=document.getElementById('installPluginGroup');\n"
                + "  sel.innerHTML='';\n"
                + "  (window.groupsData||[]).forEach(g=>{sel.innerHTML+=`<option value=\"${g.name}\">${g.name}</option>`;});\n"
                + "  if(!window.groupsData||!window.groupsData.length){toast('No groups to install to!','error');return;}\n"
                + "  openModal('modalInstallPlugin');\n"
                + "}\n"
                + "\n"
                + "async function prepareInstall(projectId, title){\n"
                + "  try{\n"
                + "    toast('Fetching latest version...','info');\n"
                + "    const r=await fetch(`https://api.modrinth.com/v2/project/${projectId}/version?limit=1`);\n"
                + "    const v=await r.json();\n"
                + "    if(!v||v.length===0){toast('No file versions found!','error');return;}\n"
                + "    const file=v[0].files.find(f=>f.primary)||v[0].files[0];\n"
                + "    if(!file){toast('No download file found!','error');return;}\n"
                + "    installSpecificVersion(file.url,file.filename,title);\n"
                + "  }catch(e){toast('Failed to fetch plugin data.','error');}\n"
                + "}\n"
                + "\n"
                + "async function confirmInstallPlugin(){\n"
                + "  const targetGroup=document.getElementById('installPluginGroup').value;\n"
                + "  if(!targetGroup||!currentInstallUrl)return;\n"
                + "  toast(`Downloading ${currentInstallFilename}...`,'info');\n"
                + "  closeModal('modalInstallPlugin');\n"
                + "  const r=await api('/api/plugins/install',{method:'POST',body:JSON.stringify({url:currentInstallUrl,filename:currentInstallFilename,group:targetGroup})});\n"
                + "  if(r.status===200)toast('✅ Plugin installed! Restart group to apply!','success');\n"
                + "  else if(r.status!==403)toast('❌ Install failed.','error');\n"
                + "}\n"
                + "</script>\n"
                + "</body>\n"
                + "</html>\n"
                + "";
        return p1 + p2;
    }
}

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

    private boolean checkAuth(HttpExchange exchange) throws IOException {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (CloudNode.getInstance().getWebTokenManager().validateToken(token))
                return true;
        }
        sendError(exchange, 401, "Unauthorized");
        return false;
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
                res.addProperty("success", CloudNode.getInstance().getWebTokenManager().validateToken(token));
                sendJson(exchange, res);
            } catch (Exception e) {
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
                groups.add(gObj);
            }
            res.add("groups", groups);
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
            try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject body = com.google.gson.JsonParser.parseReader(isr).getAsJsonObject();
                if (body.has("command")) {
                    CloudNode.getInstance().dispatchCommand(body.get("command").getAsString());
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
            try (InputStreamReader isr = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
                JsonObject body = com.google.gson.JsonParser.parseReader(isr).getAsJsonObject();
                if (body.has("server") && body.has("command")) {
                    CloudServerProcess p = CloudNode.getInstance().getServerManager()
                            .getProcess(body.get("server").getAsString());
                    if (p != null)
                        p.sendCommand(body.get("command").getAsString());
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

    // ===================== HTML =====================

    private String buildHtml() {
        return "<!DOCTYPE html>\n"
                + "<html lang=\"de\">\n"
                + "<head>\n"
                + "<meta charset=\"UTF-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "<title>RedstoneNet Cloud Dashboard</title>\n"
                + "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">\n"
                + "<link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700&display=swap\" rel=\"stylesheet\">\n"
                + "<style>\n"
                + ":root{--bg:#09090f;--panel:#111118;--panel2:#161620;--border:#252535;--red:#ff3a3a;--red-dim:#cc2222;--red-glow:rgba(255,58,58,0.25);--green:#2dff8e;--blue:#3a8fff;--text:#e8e8f0;--muted:#666680;--radius:12px;}\n"
                + "*{box-sizing:border-box;margin:0;padding:0;}\n"
                + "body{background:var(--bg);color:var(--text);font-family:'Inter',sans-serif;min-height:100vh;}\n"
                + ".navbar{background:var(--panel);border-bottom:1px solid var(--border);padding:0 30px;display:flex;align-items:center;justify-content:space-between;height:60px;position:sticky;top:0;z-index:100;}\n"
                + ".logo{color:var(--red);font-size:1.3em;font-weight:700;letter-spacing:-0.5px;text-shadow:0 0 20px var(--red-glow);}\n"
                + ".logo span{color:var(--text);font-weight:300;}\n"
                + ".nav-tabs{display:flex;gap:5px;}\n"
                + ".nav-tab{background:none;border:none;color:var(--muted);padding:8px 18px;border-radius:8px;cursor:pointer;font-size:0.9em;font-weight:500;transition:all 0.2s;}\n"
                + ".nav-tab:hover{background:var(--panel2);color:var(--text);}\n"
                + ".nav-tab.active{background:rgba(255,58,58,0.15);color:var(--red);border:none;box-shadow:none;}\n"
                + ".nav-right{display:flex;gap:10px;align-items:center;}\n"
                + ".badge{background:rgba(45,255,142,0.15);color:var(--green);border:1px solid rgba(45,255,142,0.3);padding:3px 10px;border-radius:20px;font-size:0.75em;font-weight:600;}\n"
                + ".main{max-width:1300px;margin:0 auto;padding:25px;}\n"
                + ".page{display:none;}.page.active{display:block;}\n"
                + ".panel{background:var(--panel);border:1px solid var(--border);border-radius:var(--radius);padding:20px;margin-bottom:20px;}\n"
                + ".panel-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:18px;padding-bottom:15px;border-bottom:1px solid var(--border);}\n"
                + ".panel-title{font-size:1em;font-weight:600;color:var(--text);display:flex;align-items:center;gap:8px;}\n"
                + ".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:12px;}\n"
                + ".card{background:var(--panel2);border:1px solid var(--border);border-radius:10px;padding:16px;position:relative;transition:border-color 0.2s;}\n"
                + ".card:hover{border-color:#3a3a55;}\n"
                + ".card-title{font-weight:600;margin-bottom:8px;font-size:0.95em;}\n"
                + ".card-meta{color:var(--muted);font-size:0.8em;margin-bottom:12px;display:flex;flex-direction:column;gap:3px;}\n"
                + ".card-actions{display:flex;gap:6px;}\n"
                + ".status-dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:var(--green);box-shadow:0 0 6px var(--green);margin-right:5px;}\n"
                + "button{cursor:pointer;font-family:'Inter',sans-serif;font-size:0.85em;font-weight:500;border-radius:7px;padding:8px 14px;border:1px solid var(--border);background:var(--panel2);color:var(--text);transition:all 0.18s;}\n"
                + "button:hover{background:#1e1e2e;border-color:#3a3a55;}\n"
                + ".btn-red{background:rgba(255,58,58,0.1);border-color:rgba(255,58,58,0.4);color:var(--red);}\n"
                + ".btn-red:hover{background:var(--red);color:#fff;box-shadow:0 0 14px var(--red-glow);border-color:var(--red);}\n"
                + ".btn-green{background:rgba(45,255,142,0.1);border-color:rgba(45,255,142,0.3);color:var(--green);}\n"
                + ".btn-green:hover{background:var(--green);color:#000;box-shadow:0 0 14px rgba(45,255,142,0.3);}\n"
                + ".btn-blue{background:rgba(58,143,255,0.1);border-color:rgba(58,143,255,0.3);color:var(--blue);}\n"
                + ".btn-blue:hover{background:var(--blue);color:#fff;}\n"
                + "input,select{background:var(--panel2);border:1px solid var(--border);color:var(--text);padding:9px 12px;border-radius:7px;font-family:'Inter',sans-serif;font-size:0.85em;outline:none;transition:border-color 0.2s;}\n"
                + "input:focus,select:focus{border-color:var(--red);box-shadow:0 0 8px var(--red-glow);}\n"
                + "input[type=checkbox]{width:16px;height:16px;cursor:pointer;}\n"
                + "#auth-screen{display:flex;align-items:center;justify-content:center;min-height:100vh;}\n"
                + ".auth-box{background:var(--panel);border:1px solid var(--border);border-radius:16px;padding:40px;width:400px;text-align:center;box-shadow:0 20px 60px rgba(0,0,0,0.5);}\n"
                + ".auth-box h2{font-size:1.6em;font-weight:700;color:var(--red);text-shadow:0 0 20px var(--red-glow);margin-bottom:8px;}\n"
                + ".auth-box p{color:var(--muted);margin-bottom:24px;font-size:0.9em;}\n"
                + ".auth-box input{width:100%;margin-bottom:12px;}\n"
                + ".auth-box button{width:100%;padding:11px;font-weight:600;}\n"
                + ".modal{display:none;position:fixed;inset:0;background:rgba(0,0,0,0.75);align-items:center;justify-content:center;z-index:500;backdrop-filter:blur(4px);}\n"
                + ".modal.open{display:flex;}\n"
                + ".modal-box{background:var(--panel);border:1px solid var(--border);border-radius:14px;padding:24px;width:520px;max-width:95%;}\n"
                + ".modal-box.wide{width:750px;}\n"
                + ".modal-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:18px;padding-bottom:14px;border-bottom:1px solid var(--border);}\n"
                + ".modal-header h3{font-size:1em;font-weight:600;}\n"
                + ".modal-close{background:none;border:none;color:var(--muted);font-size:1.3em;padding:4px;}\n"
                + ".modal-close:hover{color:var(--red);background:none;}\n"
                + ".form-row{margin-bottom:13px;display:flex;flex-direction:column;gap:5px;}\n"
                + ".form-row label{font-size:0.8em;color:var(--muted);font-weight:500;}\n"
                + ".form-row input,.form-row select{width:100%;}\n"
                + ".form-row-inline{display:flex;gap:8px;align-items:center;margin-bottom:13px;}\n"
                + ".console-box{background:#0a0a0f;border:1px solid #1e1e2e;border-radius:8px;padding:12px;height:380px;overflow-y:auto;font-family:'Courier New',monospace;font-size:0.8em;color:#a0a0b0;white-space:pre-wrap;word-break:break-all;}\n"
                + ".console-input-row{display:flex;gap:8px;margin-top:10px;}\n"
                + ".perm-tag{display:inline-flex;align-items:center;gap:5px;background:rgba(58,143,255,0.12);border:1px solid rgba(58,143,255,0.25);border-radius:5px;padding:3px 8px;font-size:0.75em;color:var(--blue);margin:3px;}\n"
                + ".perm-tag button{background:none;border:none;color:rgba(255,58,58,0.6);padding:0 2px;font-size:1em;}\n"
                + ".perm-tag button:hover{color:var(--red);background:none;}\n"
                + ".perm-area{min-height:36px;padding:6px;border:1px solid var(--border);border-radius:7px;background:var(--panel2);margin-top:6px;display:flex;flex-wrap:wrap;}\n"
                + ".section-sep{border:none;border-top:1px solid var(--border);margin:15px 0;}\n"
                + ".table{width:100%;border-collapse:collapse;}\n"
                + ".table th,.table td{padding:10px 14px;text-align:left;border-bottom:1px solid var(--border);font-size:0.85em;}\n"
                + ".table th{color:var(--muted);font-weight:500;background:var(--panel2);}\n"
                + ".table tr:hover td{background:var(--panel2);}\n"
                + ".empty-state{text-align:center;padding:40px;color:var(--muted);font-size:0.9em;}\n"
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "<div id=\"auth-screen\">\n"
                + "  <div class=\"auth-box\">\n"
                + "    <h2>⚡ RedstoneNet</h2>\n"
                + "    <p>Cloud Administration Dashboard</p>\n"
                + "    <input type=\"password\" id=\"tokenInput\" placeholder=\"Enter Security Token...\" autocomplete=\"off\">\n"
                + "    <button class=\"btn-red\" onclick=\"login()\" style=\"margin-top:4px;\">🔒 Login</button>\n"
                + "    <p style=\"margin-top:16px;font-size:0.75em;color:var(--muted);\">Generate token: <code style=\"color:var(--red);\">webtokens create</code></p>\n"
                + "  </div>\n"
                + "</div>\n"
                + "<div id=\"dashboard\" style=\"display:none;\">\n"
                + "  <nav class=\"navbar\">\n"
                + "    <div class=\"logo\">Redstone<span>Net</span> <span style=\"font-size:0.6em;color:var(--muted);\">Cloud</span></div>\n"
                + "    <div class=\"nav-tabs\">\n"
                + "      <button class=\"nav-tab active\" onclick=\"showPage('servers')\" id=\"tab-servers\">⚙️ Servers</button>\n"
                + "      <button class=\"nav-tab\" onclick=\"showPage('permissions')\" id=\"tab-permissions\">🔑 Permissions</button>\n"
                + "      <button class=\"nav-tab\" onclick=\"showPage('terminal')\" id=\"tab-terminal\">💻 Terminal</button>\n"
                + "    </div>\n"
                + "    <div class=\"nav-right\">\n"
                + "      <span class=\"badge\" id=\"onlineBadge\">● ONLINE</span>\n"
                + "      <button class=\"btn-red\" onclick=\"logout()\">Logout</button>\n"
                + "    </div>\n"
                + "  </nav>\n"
                + "  <div class=\"main\">\n"
                + "    <div class=\"page active\" id=\"page-servers\">\n"
                + "      <div class=\"panel\">\n"
                + "        <div class=\"panel-header\">\n"
                + "          <div class=\"panel-title\">📋 Groups & Templates</div>\n"
                + "          <div style=\"display:flex;gap:8px;\">\n"
                + "            <button class=\"btn-green\" onclick=\"openModal('modalCreateGroup')\">+ Group</button>\n"
                + "            <button onclick=\"refresh()\">↻ Refresh</button>\n"
                + "          </div>\n"
                + "        </div>\n"
                + "        <div id=\"groupsGrid\" class=\"grid\"><div class=\"empty-state\">Loading...</div></div>\n"
                + "      </div>\n"
                + "      <div class=\"panel\">\n"
                + "        <div class=\"panel-header\">\n"
                + "          <div class=\"panel-title\"><span class=\"status-dot\"></span>Active Instances</div>\n"
                + "        </div>\n"
                + "        <div id=\"serversGrid\" class=\"grid\"><div class=\"empty-state\">No active servers.</div></div>\n"
                + "      </div>\n"
                + "    </div>\n"
                + "    <div class=\"page\" id=\"page-permissions\">\n"
                + "      <div class=\"panel\">\n"
                + "        <div class=\"panel-header\">\n"
                + "          <div class=\"panel-title\">🛡️ Roles / Groups</div>\n"
                + "          <div style=\"display:flex;gap:8px;\">\n"
                + "            <button class=\"btn-green\" onclick=\"openModal('modalCreatePermGroup')\">+ Role</button>\n"
                + "            <button onclick=\"loadPermissions()\">↻ Load</button>\n"
                + "          </div>\n"
                + "        </div>\n"
                + "        <div id=\"permGroupsTable\"><div class=\"empty-state\">Loading...</div></div>\n"
                + "      </div>\n"
                + "      <div class=\"panel\">\n"
                + "        <div class=\"panel-header\">\n"
                + "          <div class=\"panel-title\">👤 Players</div>\n"
                + "          <button class=\"btn-blue\" onclick=\"openAddUserModal()\">+ Add Player</button>\n"
                + "        </div>\n"
                + "        <div id=\"permUsersTable\"><div class=\"empty-state\">No players saved yet.</div></div>\n"
                + "      </div>\n"
                + "    </div>\n"
                + "    <div class=\"page\" id=\"page-terminal\">\n"
                + "      <div class=\"panel\">\n"
                + "        <div class=\"panel-header\"><div class=\"panel-title\">🖥️ Cloud Node Terminal</div></div>\n"
                + "        <div style=\"display:flex;gap:8px;\">\n"
                + "          <input type=\"text\" id=\"cmdInput\" style=\"flex:1;\" placeholder=\"Cloud command (e.g. perms user oFlori group set admin)\">\n"
                + "          <button class=\"btn-red\" onclick=\"sendCommand()\">Execute</button>\n"
                + "        </div>\n"
                + "        <p style=\"color:var(--muted);font-size:0.78em;margin-top:10px;\">Commands: help · start &lt;Group&gt; · stopserver &lt;Name&gt; · perms group &lt;name&gt; add &lt;perm&gt; · perms user &lt;name&gt; group set &lt;group&gt;</p>\n"
                + "      </div>\n"
                + "    </div>\n"
                + "  </div>\n"
                + "</div>\n"
                + "<div id=\"modalCreateGroup\" class=\"modal\">\n"
                + " <div class=\"modal-box\">\n"
                + "  <div class=\"modal-header\"><h3>New Group / Template</h3><button class=\"modal-close\" onclick=\"closeModal('modalCreateGroup')\">✕</button></div>\n"
                + "  <div class=\"form-row\"><label>Group Name</label><input id=\"gName\" placeholder=\"e.g. Lobby\"></div>\n"
                + "  <div class=\"form-row\"><label>RAM (MB)</label><input type=\"number\" id=\"gMemory\" value=\"1024\"></div>\n"
                + "  <div class=\"form-row\"><label>Type</label><select id=\"gProxy\"><option value=\"false\">Subserver (Paper/Spigot)</option><option value=\"true\">Proxy (Velocity / BungeeCord)</option></select></div>\n"
                + "  <div class=\"form-row\"><label>Cloud Software JAR</label><input id=\"gSoftware\" placeholder=\"e.g. paper.jar\"></div>\n"
                + "  <div class=\"form-row\"><label>Static Port (0 = dynamic)</label><input type=\"number\" id=\"gPort\" value=\"0\"></div>\n"
                + "  <div class=\"form-row-inline\" style=\"margin-bottom:13px;\"><input type=\"checkbox\" id=\"gStatic\"><label style=\"margin-left:7px;font-size:0.85em;\">Static Service (Persistent Data)</label></div>\n"
                + "  <div class=\"form-row-inline\" style=\"margin-bottom:13px;\"><input type=\"checkbox\" id=\"gBedrock\"><label style=\"margin-left:7px;font-size:0.85em;\">Bedrock Support (GeyserMC & Floodgate)</label></div>\n"
                + "  <div class=\"form-row\"><label>Minimum Instances (min. online)</label><input type=\"number\" id=\"gMinOnline\" value=\"0\"></div>\n"
                + "  <button class=\"btn-green\" style=\"width:100%;margin-top:8px;\" onclick=\"createGroup()\">💾 Create</button>\n"
                + " </div>\n"
                + "</div>\n"
                + "<div id=\"modalConsole\" class=\"modal\">\n"
                + " <div class=\"modal-box wide\">\n"
                + "  <div class=\"modal-header\"><h3 id=\"consoleTitle\">Console</h3><button class=\"modal-close\" onclick=\"closeConsole()\">✕</button></div>\n"
                + "  <div class=\"console-box\" id=\"consoleOutput\">Loading...</div>\n"
                + "  <div class=\"console-input-row\">\n"
                + "   <input type=\"text\" id=\"serverCmdInput\" style=\"flex:1;\" placeholder=\"Enter command (without /)...\">\n"
                + "   <button class=\"btn-red\" onclick=\"sendServerCommand()\">▶ Send</button>\n"
                + "  </div>\n"
                + " </div>\n"
                + "</div>\n"
                + "<div id=\"modalCreatePermGroup\" class=\"modal\">\n"
                + " <div class=\"modal-box\">\n"
                + "  <div class=\"modal-header\"><h3>Create New Role</h3><button class=\"modal-close\" onclick=\"closeModal('modalCreatePermGroup')\">✕</button></div>\n"
                + "  <div class=\"form-row\"><label>Name</label><input id=\"pgName\" placeholder=\"e.g. moderator\"></div>\n"
                + "  <div class=\"form-row\"><label>Prefix</label><input id=\"pgPrefix\" placeholder=\"e.g. §9Mod | \"></div>\n"
                + "  <div class=\"form-row-inline\"><input type=\"checkbox\" id=\"pgDefault\"><label>Default Group (new players)</label></div>\n"
                + "  <button class=\"btn-green\" style=\"width:100%;\" onclick=\"createPermGroup()\">💾 Create</button>\n"
                + " </div>\n"
                + "</div>\n"
                + "<div id=\"modalAddUser\" class=\"modal\">\n"
                + " <div class=\"modal-box\">\n"
                + "  <div class=\"modal-header\"><h3>Add Player / Set Group</h3><button class=\"modal-close\" onclick=\"closeModal('modalAddUser')\">✕</button></div>\n"
                + "  <div class=\"form-row\"><label>Player Name</label><input id=\"auName\" placeholder=\"oFlori\"></div>\n"
                + "  <div class=\"form-row\"><label>Group</label><select id=\"auGroup\" style=\"width:100%;\"></select></div>\n"
                + "  <button class=\"btn-green\" style=\"width:100%;\" onclick=\"addUser()\">Save</button>\n"
                + " </div>\n"
                + "</div>\n"
                + "<div id=\"modalEditGroup\" class=\"modal\">\n"
                + " <div class=\"modal-box wide\">\n"
                + "  <div class=\"modal-header\"><h3 id=\"editGroupTitle\">Edit Group</h3><button class=\"modal-close\" onclick=\"closeModal('modalEditGroup')\">✕</button></div>\n"
                + "  <p style=\"color:var(--muted);font-size:0.82em;margin-bottom:10px;\">Permissions</p>\n"
                + "  <div id=\"editGroupPerms\" class=\"perm-area\"></div>\n"
                + "  <div class=\"form-row-inline\" style=\"margin-top:10px;\">\n"
                + "   <input id=\"newGroupPerm\" style=\"flex:1;\" placeholder=\"Add permission\"><button class=\"btn-green\" onclick=\"addGroupPerm()\">+ Add</button>\n"
                + "  </div>\n"
                + " </div>\n"
                + "</div>\n"
                + "<div id=\"modalEditUser\" class=\"modal\">\n"
                + " <div class=\"modal-box wide\">\n"
                + "  <div class=\"modal-header\"><h3 id=\"editUserTitle\">Edit Player</h3><button class=\"modal-close\" onclick=\"closeModal('modalEditUser')\">✕</button></div>\n"
                + "  <div class=\"form-row\"><label>Group</label><select id=\"editUserGroup\" style=\"width:100%;\"></select></div>\n"
                + "  <button class=\"btn-blue\" style=\"width:100%;margin-bottom:12px;\" onclick=\"saveUserGroup()\">💾 Save Group</button>\n"
                + "  <hr class=\"section-sep\">\n"
                + "  <p style=\"color:var(--muted);font-size:0.82em;margin-bottom:10px;\">Extra Permissions</p>\n"
                + "  <div id=\"editUserPerms\" class=\"perm-area\"></div>\n"
                + "  <div class=\"form-row-inline\" style=\"margin-top:10px;\">\n"
                + "   <input id=\"newUserPerm\" style=\"flex:1;\" placeholder=\"Permission\"><button class=\"btn-green\" onclick=\"addUserPerm()\">+ Add</button>\n"
                + "  </div>\n"
                + " </div>\n"
                + "</div>\n"
                + "<script>"
                + "let token=localStorage.getItem('rn_tok')||'';"
                + "let currentConsoleServer=null,consoleTimer=null;"
                + "let permData={groups:[],users:[]};"
                + "let editingGroup=null,editingUser=null;"
                + "if(token)document.getElementById('tokenInput').value=token;"
                + "async function login(){"
                + "  const t=document.getElementById('tokenInput').value;"
                + "  const r=await fetch('/api/auth',{method:'POST',body:JSON.stringify({token:t})});"
                + "  const d=await r.json();"
                + "  if(d.success){token=t;localStorage.setItem('rn_tok',t);document.getElementById('auth-screen').style.display='none';document.getElementById('dashboard').style.display='block';refresh();loadPermissions();setInterval(refresh,5000);}"
                + "  else alert('Token invalid!');"
                + "}"
                + "function logout(){token='';localStorage.removeItem('rn_tok');location.reload();}"
                + "async function api(url,opts={}){opts.headers={...(opts.headers||{}),'Authorization':'Bearer '+token};const r=await fetch(url,opts);if(r.status===401)logout();return r;}"
                + "function showPage(p){document.querySelectorAll('.page').forEach(x=>x.classList.remove('active'));document.querySelectorAll('.nav-tab').forEach(x=>x.classList.remove('active'));document.getElementById('page-'+p).classList.add('active');document.getElementById('tab-'+p).classList.add('active');}"
                + "function openModal(id){document.getElementById(id).classList.add('open');}"
                + "function closeModal(id){document.getElementById(id).classList.remove('open');}"
                + "async function refresh(){"
                + "  if(!token)return;"
                + "  const r=await api('/api/data');if(r.status!==200)return;"
                + "  const d=await r.json();"
                + "  document.getElementById('onlineBadge').textContent='● '+(d.totalPlayers||0)+' Players online';"
                + "  const gG=document.getElementById('groupsGrid');gG.innerHTML='';"
                + "  d.groups.forEach(g=>{"
                + "    const esc=g.name.replace(/'/g,\"\\\\'\");"
                + "    gG.innerHTML+=`<div class=\"card\">"
                + "      <div class=\"card-title\">${g.proxy?'🌐':'📦'} ${g.name} ${g.bedrock?'<span class=\"badge\" style=\"background:rgba(255,165,0,0.15);color:orange;border-color:orange\">📱 Bedrock</span>':''}</div>"
                + "      <div class=\"card-meta\"><span>💾 ${g.memory}MB RAM</span><span>${g.proxy?'Proxy':'Subserver'}</span><span>📍 Port: ${g.startPort > 0 ? g.startPort : 'dynamic'}</span><span style=\"color:var(--green);\">min. ${g.minOnline} online</span></div>"
                + "      <div class=\"card-actions\">"
                + "        <button class=\"btn-green\" style=\"flex:1\" onclick=\"runCmd('start ${esc} 1')\">▶ Start</button>"
                + "        <button class=\"btn-red\" onclick=\"if(confirm('Delete group ${esc}?'))runCmd('deletegroup ${esc}')\">🗑</button>"
                + "      </div>"
                + "    </div>`;"
                + "  });"
                + "  const sG=document.getElementById('serversGrid');sG.innerHTML='';"
                + "  d.servers.forEach(s=>{"
                + "    const isOn=s.online;const esc=s.name.replace(/'/g,\"\\\\'\");"
                + "    sG.innerHTML+=`<div class=\"card\" style=\"border-color:${isOn?'rgba(45,255,142,0.35)':'rgba(255,58,58,0.35)'}\">"
                + "      <div class=\"card-title\"><span class=\"status-dot\" style=\"background:${isOn?'var(--green)':'var(--red)'};\"></span>${s.name}</div>"
                + "      <div class=\"card-meta\">"
                + "        <span style=\"color:${isOn?'var(--green)':'var(--red)'};\">● ${isOn?'ONLINE':'OFFLINE'}</span>"
                + "        <span style=\"color:var(--text);font-size:0.9em;\">👤 <strong>${s.playerCount||0}</strong> Players | 📍 Port: <strong>${s.port}</strong> | ⚡ TPS: <strong style=\"color:${(s.tps||20.0)<15.0?'var(--red)':'var(--green)'}\">${(s.tps||20.0).toFixed(1)}</strong><br>💻 CPU: <strong style=\"color:${s.cpu>80?'var(--red)':s.cpu>50?'orange':'var(--green)'}\">${(s.cpu||0).toFixed(1)}%</strong> | 💾 RAM: <strong>${s.usedMemory||0} MB</strong> / ${s.maxMemory||0} MB</span>"
                + "      </div>"
                + "      <div class=\"card-actions\">"
                + "        <button class=\"btn-blue\" style=\"flex:1\" onclick=\"openConsole('${esc}')\">💻 Console</button>"
                + "        <button class=\"btn-red\" onclick=\"runCmd('stopserver ${esc}')\">⏹</button>"
                + "      </div>"
                + "    </div>`;"
                + "  });"
                + "}"
                + "async function runCmd(cmd){await api('/api/command',{method:'POST',body:JSON.stringify({command:cmd})});setTimeout(refresh,500);}"
                + "function sendCommand(){const v=document.getElementById('cmdInput').value;if(v){runCmd(v);document.getElementById('cmdInput').value='';}}"
                + "function createGroup(){"
                + "  const n=document.getElementById('gName').value.trim();"
                + "  const m=document.getElementById('gMemory').value||1024;"
                + "  const p=document.getElementById('gProxy').value;"
                + "  const s=document.getElementById('gSoftware').value.trim()||'paper.jar';"
                + "  const port=document.getElementById('gPort').value||0;"
                + "  const st=document.getElementById('gStatic').checked;"
                + "  const bedrock=document.getElementById('gBedrock').checked;"
                + "  const mo=document.getElementById('gMinOnline').value||0;"
                + "  if(!n)return alert('Name missing!');"
                + "  runCmd(`create ${n} ${m} ${st} ${p} ${s} ${bedrock} ${port} ${mo}`);"
                + "  closeModal('modalCreateGroup');"
                + "}"
                + "function openConsole(name){currentConsoleServer=name;document.getElementById('consoleTitle').textContent='Console: '+name;document.getElementById('consoleOutput').textContent='Loading...';openModal('modalConsole');loadLogs();if(consoleTimer)clearInterval(consoleTimer);consoleTimer=setInterval(loadLogs,2000);}"
                + "function closeConsole(){currentConsoleServer=null;if(consoleTimer)clearInterval(consoleTimer);closeModal('modalConsole');}"
                + "async function loadLogs(){if(!currentConsoleServer)return;const r=await api('/api/logs?server='+currentConsoleServer);if(r.status!==200)return;const d=await r.json();const box=document.getElementById('consoleOutput');box.textContent=d.logs.join('\\n')||'No logs.';box.scrollTop=box.scrollHeight;}"
                + "async function sendServerCommand(){if(!currentConsoleServer)return;const v=document.getElementById('serverCmdInput').value;if(!v)return;await api('/api/servercommand',{method:'POST',body:JSON.stringify({server:currentConsoleServer,command:v})});document.getElementById('serverCmdInput').value='';setTimeout(loadLogs,200);}"
                + "async function loadPermissions(){"
                + "  const r=await api('/api/permissions');if(r.status!==200)return;"
                + "  permData=await r.json();renderPermGroups();renderPermUsers();"
                + "}"
                + "function renderPermGroups(){"
                + "  const el=document.getElementById('permGroupsTable');"
                + "  let html='<table class=\"table\"><thead><tr><th>Name</th><th>Prefix</th><th>Default</th><th>Permissions</th><th>Actions</th></tr></thead><tbody>';"
                + "  permData.groups.forEach(g=>{"
                + "    const permsHtml=g.permissions.map(p=>`<span class=\"perm-tag\">${p}</span>`).join('')||'-';"
                + "    html+=`<tr><td><strong>${g.name}</strong></td><td><code>${g.prefix||'-'}</code></td><td>${g.isDefault?'✓':''}</td><td style=\"max-width:300px;\">${permsHtml}</td><td><button class=\"btn-blue\" onclick=\"openEditGroup('${g.name}')\">✏️</button> <button class=\"btn-red\" onclick=\"deletePermGroup('${g.name}')\">🗑</button></td></tr>`;"
                + "  });"
                + "  html+='</tbody></table>';el.innerHTML=html;"
                + "}"
                + "function renderPermUsers(){"
                + "  const el=document.getElementById('permUsersTable');"
                + "  let html='<table class=\"table\"><thead><tr><th>Player</th><th>Group</th><th>Extra Permissions</th><th>Actions</th></tr></thead><tbody>';"
                + "  permData.users.forEach(u=>{"
                + "    const permsHtml=u.permissions.map(p=>`<span class=\"perm-tag\">${p}</span>`).join('')||'-';"
                + "    html+=`<tr><td><strong>${u.name}</strong></td><td><span class=\"badge\">${u.group}</span></td><td>${permsHtml}</td><td><button class=\"btn-blue\" onclick=\"openEditUser('${u.name}')\">✏️</button> <button class=\"btn-red\" onclick=\"deletePermUser('${u.name}')\">🗑</button></td></tr>`;"
                + "  });"
                + "  html+='</tbody></table>';el.innerHTML=html;"
                + "}"
                + "async function permPost(body){await api('/api/permissions',{method:'POST',body:JSON.stringify(body)});await loadPermissions();}"
                + "function createPermGroup(){const n=document.getElementById('pgName').value;if(!n)return;permPost({action:'createGroup',name:n,prefix:document.getElementById('pgPrefix').value,isDefault:document.getElementById('pgDefault').checked});closeModal('modalCreatePermGroup');}"
                + "async function deletePermGroup(name){if(confirm('Delete role '+name+'?'))await permPost({action:'deleteGroup',name});}"
                + "function openEditGroup(name){editingGroup=name;document.getElementById('editGroupTitle').textContent='Role: '+name;const g=permData.groups.find(x=>x.name===name);document.getElementById('editGroupPerms').innerHTML=(g.permissions||[]).map(p=>`<span class=\"perm-tag\">${p}<button onclick=\"removeGroupPerm('${p}')\">✕</button></span>`).join('');openModal('modalEditGroup');}"
                + "async function addGroupPerm(){const p=document.getElementById('newGroupPerm').value.trim();if(!p)return;await permPost({action:'addGroupPerm',group:editingGroup,perm:p});document.getElementById('newGroupPerm').value='';openEditGroup(editingGroup);}"
                + "async function removeGroupPerm(p){await permPost({action:'removeGroupPerm',group:editingGroup,perm:p});openEditGroup(editingGroup);}"
                + "function openAddUserModal(){const sel=document.getElementById('auGroup');sel.innerHTML='';permData.groups.forEach(g=>sel.innerHTML+=`<option value=\"${g.name}\">${g.name}</option>`);openModal('modalAddUser');}"
                + "async function addUser(){const n=document.getElementById('auName').value.trim();if(!n)return;await permPost({action:'setUserGroup',user:n,group:document.getElementById('auGroup').value});closeModal('modalAddUser');}"
                + "function openEditUser(name){editingUser=name;document.getElementById('editUserTitle').textContent='Player: '+name;const u=permData.users.find(x=>x.name===name);const sel=document.getElementById('editUserGroup');sel.innerHTML='';permData.groups.forEach(g=>sel.innerHTML+=`<option value=\"${g.name}\"${g.name===u.group?' selected':''}>${g.name}</option>`);document.getElementById('editUserPerms').innerHTML=(u.permissions||[]).map(p=>`<span class=\"perm-tag\">${p}<button onclick=\"removeUserPerm('${p}')\">✕</button></span>`).join('');openModal('modalEditUser');}"
                + "async function saveUserGroup(){const g=document.getElementById('editUserGroup').value;await permPost({action:'setUserGroup',user:editingUser,group:g});openEditUser(editingUser);}"
                + "async function addUserPerm(){const p=document.getElementById('newUserPerm').value.trim();if(!p)return;await permPost({action:'addUserPerm',user:editingUser,perm:p});document.getElementById('newUserPerm').value='';openEditUser(editingUser);}"
                + "async function removeUserPerm(p){await permPost({action:'removeUserPerm',user:editingUser,perm:p});openEditUser(editingUser);}"
                + "async function deletePermUser(name){if(confirm('Remove player '+name+'?'))await permPost({action:'deleteUser',user:name});}"
                + "document.addEventListener('keydown',e=>{if(e.key==='Enter'){if(document.activeElement===document.getElementById('cmdInput'))sendCommand();if(document.activeElement===document.getElementById('serverCmdInput'))sendServerCommand();if(document.activeElement===document.getElementById('tokenInput'))login();}});</script>\n"
                + "</body>\n"
                + "</html>";
    }
}

package net.redstone.cloud.node;

import net.redstone.cloud.api.network.packet.SyncConfigPacket;
import net.redstone.cloud.node.network.NetServer;
import net.redstone.cloud.node.group.GroupManager;
import net.redstone.cloud.node.process.ServerManager;
import net.redstone.cloud.node.process.SoftwareDownloader;
import net.redstone.cloud.node.process.CloudServerProcess;
import net.redstone.cloud.node.permission.PermissionManager;
import net.redstone.cloud.node.web.WebServer;
import net.redstone.cloud.node.web.WebTokenManager;
import net.redstone.cloud.node.logging.Logger;
import net.redstone.cloud.api.permission.PermissionGroup;
import net.redstone.cloud.api.permission.PermissionUser;
import net.redstone.cloud.api.network.packet.MaintenanceUpdatePacket;
import net.redstone.cloud.api.network.packet.ServerStatusPacket;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import org.jline.terminal.*;
import org.jline.reader.*;

public class CloudNode {

    private boolean running = true;
    private NetServer netServer;
    private CloudServerProcess currentScreen = null;
    private GroupManager groupManager;
    private ServerManager serverManager;
    private PermissionManager permissionManager;
    private SoftwareDownloader softwareDownloader;
    private WebServer webServer;
    private WebTokenManager webTokenManager;

    private static CloudNode instance;
    private String host = "127.0.0.1";
    private int port = 3000;
    private String authKey = "cloud-secret";

    private boolean maintenance = false;
    private Map<String, String> networkSettings = new HashMap<>();

    public boolean isMaintenance() {
        return maintenance;
    }

    public void setMaintenance(boolean maintenance) {
        this.maintenance = maintenance;
        if (netServer != null) {
        netServer.broadcastPacket(new MaintenanceUpdatePacket(maintenance));
        }
        Logger
                .info("Maintenance mode: " + (maintenance ? "ENABLED" : "DISABLED"));
    }

    public Map<String, String> getNetworkSettings() {
        return networkSettings;
    }

    public void loadNetworkSettings() {
        try {
            Properties props = new Properties();
            File file = new File("settings.properties");
            if (!file.exists()) {
                props.setProperty("motd.enabled", "true");
                props.setProperty("tablist.enabled", "true");
                props.setProperty("nametags.enabled", "true");
                props.setProperty("msg.maintenanceKick",
                        "&cDas Netzwerk befindet sich im Wartungsmodus!\n&7Komm spaeter wieder.");
                props.setProperty("msg.fallbackKick", "&cDu wurdest auf die Lobby gesendet: &7%reason%");
                props.setProperty("sign.line1.online", "&b[Cloud]");
                props.setProperty("sign.line2.online", "&7%server%");
                props.setProperty("sign.line3.online", "&aOnline");
                props.setProperty("sign.line4.online", "&8%online% Players");
                props.setProperty("sign.line1.offline", "&b[Cloud]");
                props.setProperty("sign.line2.offline", "&7%server%");
                props.setProperty("sign.line3.offline", "&cOffline");
                props.setProperty("sign.line4.offline", "&8-");
                props.setProperty("sign.line4.offline", "&8-");
                props.setProperty("motd.line1", "&3&lRedstoneNet &8| &bCloudSystem");
                props.setProperty("motd.line2", "&7We are &aONLINE&7! Come and play with us.");
                props.setProperty("motd.maintenance.line1", "&3&lRedstoneNet &8| &bCloudSystem &c[Maintenance]");
                props.setProperty("motd.maintenance.line2", "&cMaintenance &8| &7We'll be back soon!");
                props.setProperty("motd.player.info", "&e%online% Players");
                props.setProperty("tablist.header", "&3&lRedstoneNet &8- &bCloud\n&7You are playing on: &e%server%");
                props.setProperty("tablist.footer", "&7Shop: &bshop.redstone.net\n&e%online% &7Players online");
                try (OutputStream out = new FileOutputStream(file)) {
                    props.store(out, "RedstoneNet Global Cloud Settings");
                }
            } else {
                try (InputStream in = new FileInputStream(file)) {
                    props.load(in);
                }
                boolean added = false;
                String[][] defaults = {
                    {"motd.enabled", "true"},
                    {"tablist.enabled", "true"},
                    {"nametags.enabled", "true"},
                    {"tablist.header", "&3&lRedstoneNet &8- &bCloud\\n&7You are playing on: &e%server%"},
                    {"tablist.footer", "&7Shop: &bshop.redstone.net\\n&e%online% &7Players online"},
                    {"msg.maintenanceKick", "&cThe network is currently in maintenance mode!\\n&7Please come back later."},
                    {"msg.fallbackKick", "&cYou were sent to the lobby: &7%reason%"},
                    {"sign.line1.online", "&b[Cloud]"},
                    {"sign.line2.online", "&7%server%"},
                    {"sign.line3.online", "&aOnline"},
                    {"sign.line4.online", "&8%online% Players"},
                    {"sign.line1.offline", "&b[Cloud]"},
                    {"sign.line2.offline", "&7%server%"},
                    {"sign.line3.offline", "&cOffline"},
                    {"sign.line4.offline", "&8-"},
                    {"motd.line1", "&3&lRedstoneNet &8| &bCloudSystem"},
                    {"motd.line2", "&7We are &aONLINE&7! Come and play with us."},
                    {"motd.maintenance.line1", "&3&lRedstoneNet &8| &bCloudSystem &c[Maintenance]"},
                    {"motd.maintenance.line2", "&cMaintenance &8| &7We'll be back soon!"},
                    {"backup.static.enabled", "false"},
                };
                for (String[] def : defaults) {
                    if (!props.containsKey(def[0])) {
                        props.setProperty(def[0], def[1]);
                        added = true;
                    }
                }
                if (added) {
                    try (OutputStream out = new FileOutputStream(file)) {
                        props.store(out, "RedstoneNet Global Cloud Settings");
                    }
                }
            }
            networkSettings.clear();
            for (String key : props.stringPropertyNames()) {
                networkSettings.put(key, props.getProperty(key).replace('&', '§'));
            }
        } catch (Exception e) {
            Logger.error("Error loading settings.properties: " + e.getMessage());
        }
    }

    public void broadcastSettings() {
        if (netServer != null) {
            netServer.broadcastPacket(new SyncConfigPacket(networkSettings));
        }
    }

    public static void main(String[] args) {
        instance = new CloudNode();
        instance.start();
    }

    public static CloudNode getInstance() {
        return instance;
    }

    public GroupManager getGroupManager() {
        return groupManager;
    }

    public ServerManager getServerManager() {
        return serverManager;
    }

    public PermissionManager getPermissionManager() {
        return permissionManager;
    }

    public NetServer getNetServer() {
        return netServer;
    }

    public WebTokenManager getWebTokenManager() {
        return webTokenManager;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getAuthKey() {
        return authKey;
    }

    public void start() {
        Logger.raw("\u001B[31m  ____          _     _                      _   _      _   \u001B[0m");
        Logger.raw("\u001B[31m |  _ \\ ___  __| |___| |_ ___  _ __   ___  | \\ | | ___| |_ \u001B[0m");
        Logger.raw("\u001B[31m | |_) / _ \\/ _` / __| __/ _ \\| '_ \\ / _ \\ |  \\| |/ _ \\ __|\u001B[0m");
        Logger.raw("\u001B[31m |  _ <  __/ (_| \\__ \\ || (_) | | | |  __/ | |\\  |  __/ |_ \u001B[0m");
        Logger.raw("\u001B[31m |_| \\_\\___|\\__,_|___/\\__\\___/|_| |_|\\___| |_| \\_|\\___|\\__|\u001B[0m");
        Logger.raw("");
        Logger.info("RedstoneNet CloudNode v1.0 initializing...");

        loadConfig();
        loadNetworkSettings();
        
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

        netServer = new NetServer(this.port);
        netServer.start();

        // Initialize managers & load data
        groupManager = new GroupManager();
        groupManager.loadGroups();
        serverManager = new ServerManager();
        permissionManager = new PermissionManager();
        softwareDownloader = new SoftwareDownloader();

        webTokenManager = new WebTokenManager();
        webServer = new WebServer();
        webServer.start();

        // Auto-start static servers & proxies
        new Thread(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
            serverManager.startStaticServers();
        }, "AutoStart-Thread").start();

        new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(3000);
                    if (netServer != null) {
                        for (CloudServerProcess process : serverManager
                                .getActiveProcesses()) {
                            netServer.broadcastPacket(new ServerStatusPacket(
                                    process.getServerName(),
                                    process.isAlive() ? "ONLINE" : "STARTING",
                                    process.getPlayerCount(),
                                    -1));
                        }
                    }
                } catch (InterruptedException ignored) {
                }
            }
        }, "CloudStatus-Thread").start();

        Logger.info("-------------------------------------------------");
        Logger
                .success(" Cloud Node v1.0 (" + host + ":" + port + ") started successfully!");
        Logger.info(" Type 'help' for a list of commands.");
        Logger.info("-------------------------------------------------");
        startConsole();
    }

    private void startConsole() {
        try {
            Terminal terminal = TerminalBuilder.builder().system(true).build();
            LineReader lineReader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer((reader, line, candidates) -> {
                        String buffer = line.word().toLowerCase();
                        String[] cmds = { "help", "stop", "clear", "create", "start", "list", "stopserver", "software",
                                "webtokens", "perms", "maintenance", "reload", "leave", "screen", "backup" };
                        for (String cmd : cmds) {
                            if (cmd.startsWith(buffer)) {
                                candidates.add(new Candidate(cmd));
                            }
                        }
                    })
                    .build();
            Logger.lineReader = lineReader;

            new Thread(() -> {
                while (running) {
                    try {
                        String prompt = currentScreen == null ? "\u001B[36m\u001B[1mRedstoneCloud \u001B[90m~ \u001B[0m"
                                : "\u001B[35m\u001B[1m" + currentScreen.getServerName() + " \u001B[90m~ \u001B[0m";

                        String inputLine = lineReader.readLine(prompt);
                        if (inputLine == null || inputLine.trim().isEmpty())
                            continue;

                        if (currentScreen != null) {
                            if (inputLine.equalsIgnoreCase("leave")) {
                                currentScreen.toggleScreen(false);
                                currentScreen = null;
                                Logger.info("Back in Cloud console.");
                            } else {
                                currentScreen.sendCommand(inputLine);
                            }
                            continue;
                        }

                        dispatchCommand(inputLine);
                    } catch (UserInterruptException | EndOfFileException e) {
                        shutdown();
                        break;
                    } catch (Exception e) {
                    }
                }
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void dispatchCommand(String line) {
        if (line == null || line.trim().isEmpty())
            return;
        String[] args = line.split(" ");
        String command = args[0].toLowerCase();

        switch (command) {
            case "webtokens":
                if (args.length == 2 && args[1].equalsIgnoreCase("create")) {
                    String token = webTokenManager.generateToken();
                    Logger.info("-> New web token (secret, valid for 1 hour):");
                    Logger.success(token);
                } else {
                    Logger.warn("Usage: webtokens create");
                }
                break;
            case "stop":
            case "shutdown":
                Logger.info("Cloud is shutting down...");
                System.exit(0); // Automatically triggers the shutdown hook!
                break;
            case "maintenance":
                if (args.length == 2) {
                    boolean state = Boolean.parseBoolean(args[1]) || args[1].equalsIgnoreCase("on");
                    setMaintenance(state);
                } else {
                    Logger.info("Current maintenance status: " + maintenance);
                    Logger.info("Usage: maintenance <true/false/on/off>");
                }
                break;
            case "reload":
                loadNetworkSettings();
                broadcastSettings();
                Logger.success("Settings reloaded and sent to all servers!");
                break;
            case "help":
                Logger.info(
                        "Commands: help, stop, clear, create, start, backup, list, stopserver, software, webtokens, perms, maintenance, reload");
                break;
            case "backup":
                if (args.length == 2) {
                    CloudServerProcess p = serverManager.getProcess(args[1]);
                    if (p != null) {
                        Logger.info("Started: Manual backup for " + args[1] + "...");
                        new Thread(() -> {
                            p.createBackup(true); // trigger manual backup
                        }, "BackupThread-" + p.getServerName()).start();
                    } else {
                        Logger.warn("Server '" + args[1] + "' does not exist or is not running!");
                    }
                } else {
                    Logger.warn("Usage: backup <ServerName>");
                }
                break;
            case "clear":
                for (int i = 0; i < 50; ++i)
                    Logger.raw("");
                break;
            case "screen":
                if (args.length == 2) {
                    CloudServerProcess p = serverManager.getProcess(args[1]);
                    if (p != null) {
                        currentScreen = p;
                        p.toggleScreen(true);
                    } else {
                        Logger.warn("Server " + args[1] + " not found!");
                    }
                } else {
                    Logger.warn("Usage: screen <ServerName>");
                }
                break;
            case "list":
                groupManager.listGroups();
                serverManager.listServers();
                break;
            case "create":
                if (args.length >= 7 && args.length <= 9) {
                    try {
                        String name = args[1];
                        int memory = Integer.parseInt(args[2]);
                        boolean isStatic = Boolean.parseBoolean(args[3]);
                        boolean isProxy = Boolean.parseBoolean(args[4]);
                        String software = args[5];
                        boolean bedrockSupport = Boolean.parseBoolean(args[6]);
                        int startPort = args.length >= 8 ? Integer.parseInt(args[7]) : 0;
                        int minOnline = args.length == 9 ? Integer.parseInt(args[8]) : (isStatic ? 1 : 0);
                        groupManager.createGroup(name, memory, isStatic, isProxy, software, startPort, minOnline, bedrockSupport);
                    } catch (Exception e) {
                        Logger.error("Usage: create <Name> <MB> <isStatic> <isProxy> <JAR> <BedrockSupport:true/false> [Port] [minOnline]");
                    }
                } else {
                    Logger.info("Usage:   create <Name> <MB> <isStatic> <isProxy> <JAR> <BedrockSupport:true/false> [Port] [minOnline]");
                    Logger.info("Example: create Lobby 1024 true false paper.jar true 25566 1");
                    Logger.info("Example: create Proxy 512 false true velocity.jar false 25565 1");
                    Logger.info("Tip:     Use port 0 for dynamic/random ports.");
                }
                break;
            case "start":
                if (args.length == 3) {
                    try {
                        String name = args[1];
                        int amount = Integer.parseInt(args[2]);
                        serverManager.startServer(name, amount);
                    } catch (Exception e) {
                        Logger.error("Usage: start <Group> <Count>");
                    }
                } else if (args.length == 2) {
                    serverManager.startServer(args[1], 1);
                } else {
                    Logger.warn("Usage: start <Group> [Count]");
                }
                break;
            case "deletegroup":
                if (args.length == 2) {
                    groupManager.deleteGroup(args[1]);
                } else {
                    Logger.warn("Usage: deletegroup <Name>");
                }
                break;
            case "stopserver":
                if (args.length == 2) {
                    serverManager.stopServer(args[1]);
                } else {
                    Logger.warn("Usage: stop <ServerName>");
                }
                break;
            case "software":
                if (args.length == 3) {
                    String type = args[1].toLowerCase(); // paper, velocity oder waterfall
                    String version = args[2];
                    softwareDownloader.downloadPaperVelocity(type, version);
                } else {
                    Logger
                            .info("Usage: software <paper/velocity/waterfall> <Version>");
                    Logger.info("Example: software paper 1.20.4");
                }
                break;
            case "perms":
                if (args.length >= 3) {
                    if (args[1].equalsIgnoreCase("group")) {
                        String groupName = args[2];
                        String action = args[3];
                        if (action.equalsIgnoreCase("add") && args.length == 5) {
                            PermissionGroup group = permissionManager
                                    .getGroup(groupName);
                            if (group != null) {
                                group.getPermissions().add(args[4]);
                                permissionManager.save();
                                permissionManager.broadcastUpdate();
                                Logger.success(
                                        "Permission " + args[4] + " added to group " + groupName + ".");
                            } else {
                                Logger.warn("Group not found.");
                            }
                        }
                    } else if (args[1].equalsIgnoreCase("user")) {
                        String userName = args[2];
                        String action = args[3];
                        if (action.equalsIgnoreCase("group") && args.length == 6 && args[4].equalsIgnoreCase("set")) {
                            PermissionUser user = permissionManager.getUser(userName);
                            if (user == null) {
                                permissionManager.createUser(userName, args[5]);
                                Logger
                                        .success("User " + userName + " created in group " + args[5] + ".");
                            } else {
                                user.setGroup(args[5]);
                                permissionManager.save();
                                permissionManager.broadcastUpdate();
                                Logger
                                        .success("Group for user " + userName + " set to " + args[5] + ".");
                            }
                        } else if (action.equalsIgnoreCase("add") && args.length == 5) {
                            PermissionUser user = permissionManager.getUser(userName);
                            if (user != null) {
                                user.getPermissions().add(args[4]);
                                permissionManager.save();
                                permissionManager.broadcastUpdate();
                                Logger
                                        .success("Permission " + args[4] + " added to user " + userName + ".");
                            } else {
                                Logger
                                        .warn("User not found. Please assign a group first.");
                            }
                        }
                    }
                } else {
                    Logger.raw("Verwendung:");
                    Logger.raw("perms group <name> add/remove <perm>");
                    Logger.raw("perms user <name> group set <group>");
                    Logger.raw("perms user <name> add/remove <perm>");
                }
                break;
            default:
                Logger.warn("Unknown command. Type 'help'.");
                break;
        }
    }

    private void loadConfig() {
        try {
            File file = new File("config.properties");
            if (!file.exists()) {
                file.createNewFile();
                Files.writeString(file.toPath(), "host=127.0.0.1\nport=3000\nauthKey=\n");
            }
            Properties props = new Properties();
            props.load(Files.newInputStream(file.toPath()));
            this.host = props.getProperty("host", "127.0.0.1");
            this.port = Integer.parseInt(props.getProperty("port", "3000"));
            this.authKey = props.getProperty("authKey", "cloud-secret");
            Logger.info("Configuration loaded! (" + host + ":" + port + ")");
        } catch (Exception e) {
            Logger.error("Error loading config: " + e.getMessage());
        }
    }

    private void shutdown() {
        Logger.info("Cloud shutdown triggered. Stopping active sub-servers...");
        if (serverManager != null) {
            serverManager.stopAll();
        }
        if (netServer != null) {
            netServer.stop();
        }
        if (webServer != null) {
            webServer.stop();
        }
        Logger.success("All servers stopped. Goodbye!");
    }
}

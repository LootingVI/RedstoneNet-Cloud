package net.redstone.cloud.plugin;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PermissionCheckEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.TabExecutor;
import net.md_5.bungee.event.EventHandler;
import net.redstone.cloud.api.network.packet.*;
import net.redstone.cloud.api.network.packet.api.GlobalPlayerListPacket;
import net.redstone.cloud.api.network.packet.api.PrivateMessagePacket;
import net.redstone.cloud.api.network.packet.api.SendPlayerPacket;
import net.redstone.cloud.api.network.packet.api.SyncGroupsPacket;
import net.redstone.cloud.api.permission.PermissionGroup;
import net.redstone.cloud.api.permission.PermissionUser;

import java.io.File;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class BungeeBridge extends Plugin implements Listener {

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;

    private static List<PermissionGroup> cachedGroups = new ArrayList<>();
    private static List<PermissionUser> cachedUsers = new ArrayList<>();
    private static boolean maintenance = false;
    private String maintenanceKickMessage = "§cMaintenance!";
    private String fallbackKickMessage = "§cYou were sent to the lobby: §7%reason%";
    private final java.util.Map<String, ServerStatusPacket> serverStatus = new java.util.HashMap<>();
    private java.util.Map<String, String> settings = new java.util.HashMap<>();
    private final java.util.Map<String, String> replyMap = new java.util.HashMap<>();
    private CloudAPIImpl cloudAPI;

    @Override
    public void onEnable() {
        getLogger().info("[CloudPlugin] Cloud-Bridge (BungeeCord) starting...");
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerCommand(this, new HubCommand());
        getProxy().getPluginManager().registerCommand(this, new CloudCommand());
        getProxy().getPluginManager().registerCommand(this, new SendCommand());
        getProxy().getPluginManager().registerCommand(this, new MsgCommand());
        getProxy().getPluginManager().registerCommand(this, new ReplyCommand());
        connectToNode();
    }

    private class CloudCommand extends Command implements TabExecutor {
        public CloudCommand() {
            super("cloud", "redstonecloud.admin");
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (args.length == 0) {
                sender.sendMessage(new TextComponent("§bCloud §8» §7Commands:"));
                sender.sendMessage(new TextComponent("§7/cloud list §8- §7List all servers"));
                sender.sendMessage(new TextComponent("§7/cloud players §8- §7List all players"));
                sender.sendMessage(new TextComponent("§7/cloud start <group> <count> §8- §7Start servers"));
                sender.sendMessage(new TextComponent("§7/cloud stop <server> §8- §7Stop a server"));
                sender.sendMessage(new TextComponent("§7/cloud send <player> <server> §8- §7Send a player"));
                sender.sendMessage(new TextComponent("§7/cloud maintenance <on|off> §8- §7Toggle maintenance"));
                sender.sendMessage(new TextComponent("§7/cloud group <list|create|delete> §8- §7Manage groups"));
                return;
            }

            if (args[0].equalsIgnoreCase("list")) {
                sender.sendMessage(new TextComponent("§bCloud §8» §7Active Servers:"));
                serverStatus.values().forEach(status -> {
                    sender.sendMessage(new TextComponent("§8- §e" + status.getServerName() + " §8(§a" + status.getState() + "§8) §7" + status.getPlayerCount() + " Players"));
                });
            } else if (args[0].equalsIgnoreCase("players")) {
                List<net.redstone.cloud.api.player.CloudPlayer> players = cloudAPI.getOnlinePlayers();
                sender.sendMessage(new TextComponent("§bCloud §8» §7Online Players (§e" + players.size() + "§7):"));
                String names = players.stream().map(p -> "§e" + p.getName() + " §8(§7" + p.getGameServer() + "§8)").reduce((a, b) -> a + "§7, " + b).orElse("§cNone");
                sender.sendMessage(new TextComponent(names));
            } else if (args[0].equalsIgnoreCase("send") && args.length >= 3) {
                cloudAPI.sendPlayer(args[1], args[2]);
                sender.sendMessage(new TextComponent("§bCloud §8» §aTransfer request for §e" + args[1] + " §asent."));
            } else if (args[0].equalsIgnoreCase("maintenance") && args.length >= 2) {
                cloudAPI.dispatchCloudCommand(sender.getName(), "maintenance " + args[1]);
            } else {
                cloudAPI.dispatchCloudCommand(sender.getName(), String.join(" ", args));
            }
        }

        @Override
        public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
            if (args.length == 1) {
                return java.util.Arrays.asList("list", "players", "start", "stop", "send", "maintenance", "group");
            }
            if (args.length == 2) {
                if (args[0].equalsIgnoreCase("stop")) return new ArrayList<>(serverStatus.keySet());
                if (args[0].equalsIgnoreCase("group")) return java.util.Arrays.asList("list", "create", "delete");
                if (args[0].equalsIgnoreCase("send"))
                    return cloudAPI.getOnlinePlayers().stream().map(p -> p.getName()).collect(java.util.stream.Collectors.toList());
            }
            if (args.length == 3) {
                if (args[0].equalsIgnoreCase("send")) return new ArrayList<>(serverStatus.keySet());
                if (args[0].equalsIgnoreCase("group") && args[1].equalsIgnoreCase("delete")) return new ArrayList<>(cloudAPI.getOnlineGroups());
            }
            return new ArrayList<>();
        }
    }

    private class SendCommand extends Command implements TabExecutor {
        public SendCommand() {
            super("send", "redstonecloud.admin");
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (args.length < 2) {
                sender.sendMessage(new TextComponent("§bCloud §8» §7/send <player> <server>"));
                return;
            }
            cloudAPI.sendPlayer(args[0], args[1]);
            sender.sendMessage(new TextComponent("§bCloud §8» §aTransfer request for §e" + args[0] + " §asent."));
        }

        @Override
        public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
            if (args.length == 1)
                return cloudAPI.getOnlinePlayers().stream().map(p -> p.getName()).collect(java.util.stream.Collectors.toList());
            if (args.length == 2) return new ArrayList<>(serverStatus.keySet());
            return new ArrayList<>();
        }
    }

    private void connectToNode() {
        getProxy().getScheduler().runAsync(this, () -> {
            try {
                Properties props = new Properties();
                File wrapperFile = new File("wrapper.properties");
                if (!wrapperFile.exists()) {
                    getLogger().severe("[CloudPlugin] No wrapper.properties found!");
                    return;
                }
                props.load(Files.newInputStream(wrapperFile.toPath()));

                String host = props.getProperty("cloud.host");
                int port = Integer.parseInt(props.getProperty("cloud.port"));
                String serverName = props.getProperty("cloud.serverName");
                String authKey = props.getProperty("cloud.authKey");
                int serverPort = Integer.parseInt(props.getProperty("cloud.serverPort", "0"));
                boolean isProxy = Boolean.parseBoolean(props.getProperty("cloud.isProxy", "true"));

                socket = new Socket(host, port);
                out = new ObjectOutputStream(socket.getOutputStream());
                in = new ObjectInputStream(socket.getInputStream());

                getLogger().info("[CloudPlugin] Connected to CloudNode (" + host + ":" + port + ") as " + serverName);

                out.writeObject(new AuthPacket(serverName, authKey, serverPort, isProxy));
                out.flush();

                cloudAPI = new CloudAPIImpl(out, serverName);
                getLogger().info("[CloudPlugin] CloudAPI initialized! Use CloudAPI.getInstance() in your plugins.");

                new Thread(() -> {
                    try {
                        while (socket != null && !socket.isClosed()) {
                            Object obj = in.readObject();
                            if (obj instanceof RegisterServerPacket) {
                                RegisterServerPacket p = (RegisterServerPacket) obj;
                                ServerInfo info = getProxy().constructServerInfo(
                                        p.getName(),
                                        new InetSocketAddress(p.getHost(), p.getPort()),
                                        "RedstoneNet Cloud Server", false);
                                getProxy().getServers().put(p.getName(), info);
                                if (p.getName().toLowerCase().contains("lobby")) {
                                    getProxy().getConfig().getListeners().forEach(listener -> {
                                        if (!listener.getServerPriority().contains(p.getName())) {
                                            listener.getServerPriority().add(p.getName());
                                        }
                                    });
                                }
                                getLogger().info("[CloudPlugin] Server " + p.getName() + " dynamically registered.");
                            } else if (obj instanceof UnregisterServerPacket) {
                                UnregisterServerPacket p = (UnregisterServerPacket) obj;
                                getProxy().getServers().remove(p.getName());
                                getLogger().info("[CloudPlugin] Server " + p.getName() + " removed.");
                            } else if (obj instanceof CloudMessagePacket) {
                                CloudMessagePacket msg = (CloudMessagePacket) obj;
                                ProxiedPlayer p = getProxy().getPlayer(msg.getTargetName());
                                if (p != null)
                                    p.sendMessage(new TextComponent(msg.getMessage()));
                                if ("CONSOLE".equals(msg.getTargetName()))
                                    getProxy().getConsole().sendMessage(new TextComponent(msg.getMessage()));
                            } else if (obj instanceof PermissionUpdatePacket) {
                                PermissionUpdatePacket pkt = (PermissionUpdatePacket) obj;
                                cachedGroups = pkt.getGroups();
                                cachedUsers = pkt.getUsers();
                                getLogger().info("[CloudPlugin] Permissions updated (" + cachedGroups.size() + " groups).");
                            } else if (obj instanceof MaintenanceUpdatePacket) {
                                maintenance = ((MaintenanceUpdatePacket) obj).isMaintenance();
                                if (cloudAPI != null) cloudAPI.setMaintenance(maintenance);
                                getLogger().info("[CloudPlugin] Maintenance mode updated: " + maintenance);
                            } else if (obj instanceof SyncConfigPacket) {
                                settings = ((SyncConfigPacket) obj).getSettings();
                                maintenanceKickMessage = settings.getOrDefault("msg.maintenanceKick",
                                        maintenanceKickMessage);
                                fallbackKickMessage = settings.getOrDefault("msg.fallbackKick", fallbackKickMessage);
                                getLogger().info("[CloudPlugin] Settings synchronized!");
                            } else if (obj instanceof ServerStatusPacket) {
                                ServerStatusPacket pkt = (ServerStatusPacket) obj;
                                serverStatus.put(pkt.getServerName(), pkt);
                                if (cloudAPI != null) cloudAPI.updateServerStatus(pkt);
                            } else if (obj instanceof GlobalPlayerListPacket) {
                                GlobalPlayerListPacket gpl = (GlobalPlayerListPacket) obj;
                                if (cloudAPI != null) cloudAPI.updatePlayerList(gpl.getPlayers());
                            } else if (obj instanceof SendPlayerPacket) {
                                SendPlayerPacket sp = (SendPlayerPacket) obj;
                                ProxiedPlayer target = getProxy().getPlayer(sp.getPlayerName());
                                ServerInfo targetServer = getProxy().getServerInfo(sp.getTargetServer());
                                if (target != null && targetServer != null) {
                                    target.connect(targetServer);
                                    target.sendMessage(new TextComponent("§aSending you to §e" + sp.getTargetServer() + "§a..."));
                                }
                            } else if (obj instanceof PrivateMessagePacket) {
                                PrivateMessagePacket pm = (PrivateMessagePacket) obj;
                                ProxiedPlayer receiver = getProxy().getPlayer(pm.getReceiver());
                                if (receiver != null) {
                                    receiver.sendMessage(new TextComponent("§bFrom §7" + pm.getSender() + " §8» §f" + pm.getMessage()));
                                    replyMap.put(pm.getReceiver(), pm.getSender());
                                }
                                ProxiedPlayer sender = getProxy().getPlayer(pm.getSender());
                                if (sender != null) {
                                    sender.sendMessage(new TextComponent("§bTo §7" + pm.getReceiver() + " §8» §f" + pm.getMessage()));
                                }
                            } else if (obj instanceof SyncGroupsPacket) {
                                cloudAPI.updateGroups(((SyncGroupsPacket) obj).getGroups());
                            }
                        }
                    } catch (Exception e) {
                        getLogger().severe("[CloudPlugin] Network error: " + e.getMessage());
                    }
                }, "Cloud-Receiver").start();

            } catch (Exception e) {
                getLogger().severe("[CloudPlugin] Connection error: " + e.getMessage() + " – retrying in 5s...");
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                }
                connectToNode();
            }
        });
    }

    @Override
    public void onDisable() {
        try {
            if (out != null)
                out.close();
            if (socket != null && !socket.isClosed())
                socket.close();
        } catch (Exception ignored) {
        }
    }

    @EventHandler
    public void onPlayerJoin(PostLoginEvent event) {
        try {
            if (out != null) {
                out.writeObject(new PlayerActivityPacket(event.getPlayer().getName(), event.getPlayer().getUniqueId(), true));
                out.flush();
            }
        } catch (Exception ignored) {
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerDisconnectEvent event) {
        try {
            if (out != null) {
                out.writeObject(new PlayerActivityPacket(event.getPlayer().getName(), event.getPlayer().getUniqueId(), false));
                out.flush();
            }
        } catch (Exception ignored) {
        }
    }

    @EventHandler
    public void onPermissionCheck(PermissionCheckEvent event) {
        if (!(event.getSender() instanceof ProxiedPlayer))
            return;
        ProxiedPlayer p = (ProxiedPlayer) event.getSender();

        PermissionUser user = cachedUsers.stream()
                .filter(u -> u.getName().equalsIgnoreCase(p.getName()))
                .findFirst().orElse(null);

        PermissionGroup group = null;
        if (user != null) {
            group = cachedGroups.stream()
                    .filter(g -> g.getName().equalsIgnoreCase(user.getGroup()))
                    .findFirst().orElse(null);
            if (user.getPermissions().contains(event.getPermission())
                    || user.getPermissions().contains("*")) {
                event.setHasPermission(true);
                return;
            }
        }
        if (group == null) {
            group = cachedGroups.stream().filter(PermissionGroup::isDefault).findFirst().orElse(null);
        }
        if (group != null) {
            if (group.getPermissions().contains(event.getPermission())
                    || group.getPermissions().contains("*")) {
                event.setHasPermission(true);
            }
        }
    }

    @EventHandler
    public void onServerKick(net.md_5.bungee.api.event.ServerKickEvent event) {
        if (event.getPlayer().getServer() == null)
            return;

        String kickedFrom = event.getKickedFrom().getName();
        String reason = net.md_5.bungee.api.ChatColor
                .stripColor(net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&',
                        net.md_5.bungee.api.chat.BaseComponent.toPlainText(event.getKickReasonComponent())));

        if (reason.toLowerCase().contains("banned") || reason.toLowerCase().contains("kicked")) {
            return;
        }

        ServerInfo fallback = null;
        for (ServerInfo si : getProxy().getServers().values()) {
            if (si.getName().toLowerCase().contains("lobby") && !si.getName().equalsIgnoreCase(kickedFrom)) {
                fallback = si;
                break;
            }
        }

        if (fallback != null) {
            event.setCancelled(true);
            event.setCancelServer(fallback);
            event.getPlayer().sendMessage(new TextComponent(fallbackKickMessage.replace("%reason%", reason)));
        }
    }

    @EventHandler
    public void onPreLogin(net.md_5.bungee.api.event.PreLoginEvent event) {
        if (maintenance) {
            String name = event.getConnection().getName();
            boolean hasPerm = false;
            PermissionUser user = cachedUsers.stream().filter(u -> u.getName().equalsIgnoreCase(name)).findFirst()
                    .orElse(null);
            if (user != null) {
                PermissionGroup group = cachedGroups.stream().filter(g -> g.getName().equalsIgnoreCase(user.getGroup()))
                        .findFirst().orElse(null);
                if (user.getPermissions().contains("cloud.maintenance") || user.getPermissions().contains("*"))
                    hasPerm = true;
                else if (group != null && (group.getPermissions().contains("cloud.maintenance")
                        || group.getPermissions().contains("*")))
                    hasPerm = true;
            }
            if (!hasPerm) {
                event.setCancelled(true);
                event.setCancelReason(new TextComponent(maintenanceKickMessage));
            }
        }
    }

    @EventHandler
    public void onPing(net.md_5.bungee.api.event.ProxyPingEvent event) {
        net.md_5.bungee.api.ServerPing response = event.getResponse();
        String line1 = settings.getOrDefault(maintenance ? "motd.maintenance.line1" : "motd.line1",
                "RedstoneNet Cloud");
        String line2 = settings.getOrDefault(maintenance ? "motd.maintenance.line2" : "motd.line2",
                "Cloud System is ready");
        response.setDescriptionComponent(new TextComponent(line1 + "\n" + line2));

        if (maintenance) {
            response.setVersion(new net.md_5.bungee.api.ServerPing.Protocol("Maintenance", 0));
        } else {
            int totalOnline = getProxy().getOnlineCount();
            String pInfo = settings.getOrDefault("motd.player.info", "§e%online% Players").replace("%online%",
                    String.valueOf(totalOnline));
            response.setVersion(new net.md_5.bungee.api.ServerPing.Protocol(
                    org.bukkit.ChatColor.translateAlternateColorCodes('&', pInfo),
                    response.getVersion().getProtocol()));
        }
        event.setResponse(response);
    }

    private void connectToBestLobby(ProxiedPlayer p) {
        ServerInfo bestLobby = null;
        int minPlayers = Integer.MAX_VALUE;

        for (ServerInfo si : getProxy().getServers().values()) {
            if (si.getName().toLowerCase().contains("lobby")) {
                ServerStatusPacket status = serverStatus.get(si.getName());
                if (status != null && "ONLINE".equals(status.getState())) {
                    if (status.getPlayerCount() < minPlayers) {
                        minPlayers = status.getPlayerCount();
                        bestLobby = si;
                    }
                }
            }
        }
        if (bestLobby != null && !p.getServer().getInfo().getName().equals(bestLobby.getName())) {
            p.connect(bestLobby);
            p.sendMessage(new TextComponent("§aYou were connected to the lobby: §e" + bestLobby.getName()));
        } else if (bestLobby == null) {
            p.sendMessage(new TextComponent("§cNo fallback lobby found!"));
        } else {
            p.sendMessage(new TextComponent("§cYou are already on a lobby!"));
        }
    }

    public class HubCommand extends net.md_5.bungee.api.plugin.Command {
        public HubCommand() {
            super("hub", null, "lobby", "l", "leave");
        }

        @Override
        public void execute(net.md_5.bungee.api.CommandSender sender, String[] args) {
            if (sender instanceof ProxiedPlayer) {
                connectToBestLobby((ProxiedPlayer) sender);
            }
        }
    }

    private class MsgCommand extends Command implements TabExecutor {
        public MsgCommand() {
            super("msg", null, "w", "tell", "whisper");
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (!(sender instanceof ProxiedPlayer)) return;
            ProxiedPlayer p = (ProxiedPlayer) sender;
            if (args.length < 2) {
                p.sendMessage(new TextComponent("§bCloud §8» §7/msg <player> <message>"));
                return;
            }
            String target = args[0];
            String message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
            sendPrivateMessage(p.getName(), target, message);
        }

        @Override
        public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
            if (args.length == 1)
                return cloudAPI.getOnlinePlayers().stream().map(p -> p.getName()).collect(java.util.stream.Collectors.toList());
            return new ArrayList<>();
        }
    }

    private class ReplyCommand extends Command {
        public ReplyCommand() {
            super("reply", null, "r");
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            if (!(sender instanceof ProxiedPlayer)) return;
            ProxiedPlayer p = (ProxiedPlayer) sender;
            if (args.length < 1) {
                p.sendMessage(new TextComponent("§bCloud §8» §7/r <message>"));
                return;
            }
            String target = replyMap.get(p.getName());
            if (target == null) {
                p.sendMessage(new TextComponent("§cYou have nobody to reply to."));
                return;
            }
            String message = String.join(" ", args);
            sendPrivateMessage(p.getName(), target, message);
        }
    }

    private void sendPrivateMessage(String sender, String receiver, String message) {
        try {
            if (out != null) {
                out.writeObject(new PrivateMessagePacket(sender, receiver, message));
                out.flush();
            }
        } catch (Exception ignored) {
        }
    }
}

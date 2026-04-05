package net.redstone.cloud.plugin;

import net.md_5.bungee.api.ChatColor;
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
import net.md_5.bungee.event.EventHandler;
import net.redstone.cloud.api.network.packet.AuthPacket;
import net.redstone.cloud.api.network.packet.CloudCommandPacket;
import net.redstone.cloud.api.network.packet.CloudMessagePacket;
import net.redstone.cloud.api.network.packet.PermissionUpdatePacket;
import net.redstone.cloud.api.network.packet.PlayerActivityPacket;
import net.redstone.cloud.api.network.packet.RegisterServerPacket;
import net.redstone.cloud.api.network.packet.UnregisterServerPacket;
import net.redstone.cloud.api.network.packet.MaintenanceUpdatePacket;
import net.redstone.cloud.api.network.packet.SyncConfigPacket;
import net.redstone.cloud.api.network.packet.ServerStatusPacket;
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

    @Override
    public void onEnable() {
        getLogger().info("[CloudPlugin] Cloud-Bridge (BungeeCord) starting...");
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerCommand(this, new HubCommand());
        getProxy().getPluginManager().registerCommand(this, new Command("cloud", "redstonecloud.admin") {
            @Override
            public void execute(CommandSender sender, String[] args) {
                if (args.length == 0) {
                    sender.sendMessage(new TextComponent(ChatColor.AQUA + "Cloud " + ChatColor.DARK_GRAY + "» "
                            + ChatColor.GRAY + "/cloud <list|start|stop>"));
                    return;
                }
                String cmdLine = String.join(" ", args);
                try {
                    if (out != null) {
                        out.writeObject(new CloudCommandPacket(sender.getName(), cmdLine));
                        out.flush();
                    }
                } catch (Exception ignored) {
                }
            }
        });
        connectToNode();
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
                            }
                        }
                    } catch (Exception e) {
                        getLogger().severe("[CloudPlugin] Network error: " + e.getMessage());
                    }
                }, "Cloud-Receiver").start();

            } catch (Exception e) {
                getLogger().severe("[CloudPlugin] Connection error: " + e.getMessage());
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
                out.writeObject(new PlayerActivityPacket(event.getPlayer().getName(), true));
                out.flush();
            }
        } catch (Exception ignored) {
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerDisconnectEvent event) {
        try {
            if (out != null) {
                out.writeObject(new PlayerActivityPacket(event.getPlayer().getName(), false));
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
}

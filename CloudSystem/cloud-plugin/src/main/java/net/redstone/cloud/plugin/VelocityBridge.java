package net.redstone.cloud.plugin;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.redstone.cloud.api.network.packet.AuthPacket;
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
import net.redstone.cloud.api.network.packet.api.GlobalPlayerListPacket;
import net.redstone.cloud.api.network.packet.api.SendPlayerPacket;
import net.redstone.cloud.api.network.packet.api.PrivateMessagePacket;
import org.slf4j.Logger;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;

import java.io.File;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

@Plugin(id = "cloud-plugin", name = "CloudPlugin", version = "1.0", authors = { "RedstoneNet" })
public class VelocityBridge {

    private final ProxyServer server;
    private final Logger logger;

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

    @Inject
    public VelocityBridge(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("[CloudPlugin] Cloud-Bridge (Velocity) starting...");
        server.getScheduler().buildTask(this, this::connectToNode).schedule();

        server.getCommandManager().register(
                server.getCommandManager().metaBuilder("cloud").plugin(this).build(),
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                        if (!invocation.source().hasPermission("redstonecloud.admin")) {
                            invocation.source().sendMessage(Component.text("§cNo Permissions."));
                            return;
                        }
                        String[] args = invocation.arguments();
                        if (args.length == 0) {
                            invocation.source().sendMessage(Component.text("§bCloud §8» §7Commands:"));
                            invocation.source().sendMessage(Component.text("§7/cloud list §8- §7List all servers"));
                            invocation.source().sendMessage(Component.text("§7/cloud players §8- §7List all players"));
                            invocation.source().sendMessage(Component.text("§7/cloud start <group> <count> §8- §7Start servers"));
                            invocation.source().sendMessage(Component.text("§7/cloud stop <server> §8- §7Stop a server"));
                            invocation.source().sendMessage(Component.text("§7/cloud send <player> <server> §8- §7Send a player"));
                            invocation.source().sendMessage(Component.text("§7/cloud maintenance <on|off> §8- §7Toggle maintenance"));
                            return;
                        }

                        String senderName = (invocation.source() instanceof Player)
                                ? ((Player) invocation.source()).getUsername()
                                : "Console";

                        if (args[0].equalsIgnoreCase("list")) {
                            invocation.source().sendMessage(Component.text("§bCloud §8» §7Active Servers:"));
                            serverStatus.values().forEach(status -> {
                                invocation.source().sendMessage(Component.text("§8- §e" + status.getServerName() + " §8(§a" + status.getState() + "§8) §7" + status.getPlayerCount() + " Players"));
                            });
                        } else if (args[0].equalsIgnoreCase("players")) {
                            List<net.redstone.cloud.api.player.CloudPlayer> players = cloudAPI.getOnlinePlayers();
                            invocation.source().sendMessage(Component.text("§bCloud §8» §7Online Players (§e" + players.size() + "§7):"));
                            String names = players.stream().map(p -> "§e" + p.getName() + " §8(§7" + p.getGameServer() + "§8)").reduce((a, b) -> a + "§7, " + b).orElse("§cNone");
                            invocation.source().sendMessage(Component.text(names));
                        } else if (args[0].equalsIgnoreCase("send") && args.length >= 3) {
                            cloudAPI.sendPlayer(args[1], args[2]);
                            invocation.source().sendMessage(Component.text("§bCloud §8» §aTransfer request for §e" + args[1] + " §asent."));
                        } else if (args[0].equalsIgnoreCase("maintenance") && args.length >= 2) {
                            cloudAPI.dispatchCloudCommand(senderName, "maintenance " + args[1]);
                        } else {
                            cloudAPI.dispatchCloudCommand(senderName, String.join(" ", args));
                        }
                    }

                    @Override
                    public java.util.List<String> suggest(Invocation invocation) {
                        String[] args = invocation.arguments();
                        if (args.length <= 1) {
                            return java.util.Arrays.asList("list", "players", "start", "stop", "send", "maintenance");
                        }
                        if (args.length == 2) {
                            if (args[0].equalsIgnoreCase("stop")) return new ArrayList<>(serverStatus.keySet());
                            if (args[0].equalsIgnoreCase("send")) return cloudAPI.getOnlinePlayers().stream().map(p -> p.getName()).collect(java.util.stream.Collectors.toList());
                        }
                        if (args.length == 3 && args[0].equalsIgnoreCase("send")) {
                            return new ArrayList<>(serverStatus.keySet());
                        }
                        return new ArrayList<>();
                    }
                });

        server.getCommandManager().register(
                server.getCommandManager().metaBuilder("send").plugin(this).build(),
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                        if (!invocation.source().hasPermission("redstonecloud.admin")) {
                            invocation.source().sendMessage(Component.text("§cNo Permissions."));
                            return;
                        }
                        String[] args = invocation.arguments();
                        if (args.length < 2) {
                            invocation.source().sendMessage(Component.text("§bCloud §8» §7/send <player> <server>"));
                            return;
                        }
                        cloudAPI.sendPlayer(args[0], args[1]);
                        invocation.source().sendMessage(Component.text("§bCloud §8» §aTransfer request for §e" + args[0] + " §asent."));
                    }

                    @Override
                    public java.util.List<String> suggest(Invocation invocation) {
                        String[] args = invocation.arguments();
                        if (args.length <= 1) return cloudAPI.getOnlinePlayers().stream().map(p -> p.getName()).collect(java.util.stream.Collectors.toList());
                        if (args.length == 2) return new ArrayList<>(serverStatus.keySet());
                        return new ArrayList<>();
                    }
                });

        server.getCommandManager().register(
                server.getCommandManager().metaBuilder("msg").aliases("w", "tell", "whisper").plugin(this).build(),
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                        if (!(invocation.source() instanceof Player)) return;
                        Player sender = (Player) invocation.source();
                        String[] args = invocation.arguments();
                        if (args.length < 2) {
                            sender.sendMessage(Component.text("§bCloud §8» §7/msg <player> <message>"));
                            return;
                        }
                        String target = args[0];
                        String message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                        sendPrivateMessage(sender.getUsername(), target, message);
                    }

                    @Override
                    public java.util.List<String> suggest(Invocation invocation) {
                        if (invocation.arguments().length <= 1) {
                            return cloudAPI.getOnlinePlayers().stream().map(p -> p.getName()).collect(java.util.stream.Collectors.toList());
                        }
                        return new ArrayList<>();
                    }
                });

        server.getCommandManager().register(
                server.getCommandManager().metaBuilder("reply").aliases("r").plugin(this).build(),
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                        if (!(invocation.source() instanceof Player)) return;
                        Player sender = (Player) invocation.source();
                        String[] args = invocation.arguments();
                        if (args.length < 1) {
                            sender.sendMessage(Component.text("§bCloud §8» §7/r <message>"));
                            return;
                        }
                        String target = replyMap.get(sender.getUsername());
                        if (target == null) {
                            sender.sendMessage(Component.text("§cYou have nobody to reply to."));
                            return;
                        }
                        String message = String.join(" ", args);
                        sendPrivateMessage(sender.getUsername(), target, message);
                    }
                });

        server.getCommandManager().register(
                server.getCommandManager().metaBuilder("hub").aliases("lobby", "l", "leave").build(),
                (SimpleCommand) invocation -> {
                    if (invocation.source() instanceof Player) {
                        connectToBestLobby((Player) invocation.source());
                    }
                });
    }

    private void connectToNode() {
        try {
            Properties props = new Properties();
            File wrapperFile = new File("wrapper.properties");
            if (!wrapperFile.exists()) {
                logger.error("[CloudPlugin] KEINE wrapper.properties gefunden!");
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

            logger.info("[CloudPlugin] Connected to CloudNode ({}:{}) as {}", host, port, serverName);

            out.writeObject(new AuthPacket(serverName, authKey, serverPort, isProxy));
            out.flush();

            cloudAPI = new CloudAPIImpl(out, serverName);
            logger.info("[CloudPlugin] CloudAPI initialized! Use CloudAPI.getInstance() in your plugins.");

            new Thread(() -> {
                try {
                    while (socket != null && !socket.isClosed()) {
                        Object obj = in.readObject();
                        if (obj instanceof RegisterServerPacket) {
                            RegisterServerPacket p = (RegisterServerPacket) obj;
                            ServerInfo info = new ServerInfo(p.getName(),
                                    new InetSocketAddress(p.getHost(), p.getPort()));
                            server.registerServer(info);
                            logger.info("[CloudPlugin] Server {} dynamically registered in proxy.", p.getName());
                        } else if (obj instanceof UnregisterServerPacket) {
                            UnregisterServerPacket p = (UnregisterServerPacket) obj;
                            server.getServer(p.getName()).ifPresent(s -> server.unregisterServer(s.getServerInfo()));
                            logger.info("[CloudPlugin] Server {} removed from proxy.", p.getName());
                        } else if (obj instanceof CloudMessagePacket) {
                            CloudMessagePacket msg = (CloudMessagePacket) obj;
                            server.getPlayer(msg.getTargetName())
                                    .ifPresent(p -> p.sendMessage(Component.text(msg.getMessage())));
                            if ("Console".equals(msg.getTargetName())) {
                                logger.info("[Cloud] {}", msg.getMessage());
                            }
                        } else if (obj instanceof PermissionUpdatePacket) {
                            PermissionUpdatePacket pkt = (PermissionUpdatePacket) obj;
                            cachedGroups = pkt.getGroups();
                            cachedUsers = pkt.getUsers();
                            logger.info("[CloudPlugin] Permissions updated ({} groups, {} users).",
                                    cachedGroups.size(), cachedUsers.size());
                        } else if (obj instanceof MaintenanceUpdatePacket) {
                            maintenance = ((MaintenanceUpdatePacket) obj).isMaintenance();
                            if (cloudAPI != null) cloudAPI.setMaintenance(maintenance);
                            logger.info("[CloudPlugin] Maintenance mode updated: {}", maintenance);
                        } else if (obj instanceof SyncConfigPacket) {
                            settings = ((SyncConfigPacket) obj).getSettings();
                            maintenanceKickMessage = settings.getOrDefault("msg.maintenanceKick",
                                    maintenanceKickMessage);
                            fallbackKickMessage = settings.getOrDefault("msg.fallbackKick", fallbackKickMessage);
                            logger.info("[CloudPlugin] Settings synchronisiert!");
                        } else if (obj instanceof ServerStatusPacket) {
                            ServerStatusPacket pkt = (ServerStatusPacket) obj;
                            serverStatus.put(pkt.getServerName(), pkt);
                            if (cloudAPI != null) cloudAPI.updateServerStatus(pkt);
                        } else if (obj instanceof GlobalPlayerListPacket) {
                            GlobalPlayerListPacket gpl = (GlobalPlayerListPacket) obj;
                            if (cloudAPI != null) cloudAPI.updatePlayerList(gpl.getPlayers());
                        } else if (obj instanceof SendPlayerPacket) {
                            SendPlayerPacket sp = (SendPlayerPacket) obj;
                            server.getPlayer(sp.getPlayerName()).ifPresent(p -> {
                                server.getServer(sp.getTargetServer()).ifPresent(target -> {
                                    p.createConnectionRequest(target).fireAndForget();
                                    p.sendMessage(Component.text("§aSending you to §e" + sp.getTargetServer() + "§a..."));
                                });
                            });
                        } else if (obj instanceof PrivateMessagePacket) {
                            PrivateMessagePacket pm = (PrivateMessagePacket) obj;
                            server.getPlayer(pm.getReceiver()).ifPresent(receiver -> {
                                receiver.sendMessage(Component.text("§bFrom §7" + pm.getSender() + " §8» §f" + pm.getMessage()));
                                replyMap.put(pm.getReceiver(), pm.getSender());
                            });
                            server.getPlayer(pm.getSender()).ifPresent(sender -> {
                                sender.sendMessage(Component.text("§bTo §7" + pm.getReceiver() + " §8» §f" + pm.getMessage()));
                            });
                        }
                    }
                } catch (Exception e) {
                    logger.error("[CloudPlugin] Network error in receiver: {}", e.getMessage());
                }
            }, "Cloud-Receiver").start();

        } catch (Exception e) {
            logger.error("[CloudPlugin] Connection error: {} – retrying in 5s...", e.getMessage());
            try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
            connectToNode();
        }
    }

    @Subscribe
    public void onChooseInitial(PlayerChooseInitialServerEvent event) {
        for (RegisteredServer srv : server.getAllServers()) {
            if (srv.getServerInfo().getName().toLowerCase().startsWith("lobby-")) {
                event.setInitialServer(srv);
                return;
            }
        }
    }

    @Subscribe
    public void onPlayerJoin(PostLoginEvent event) {
        try {
            if (out != null) {
                out.writeObject(new PlayerActivityPacket(event.getPlayer().getUsername(), event.getPlayer().getUniqueId(), true));
                out.flush();
            }
        } catch (Exception ignored) {
        }
    }

    @Subscribe
    public void onPlayerQuit(DisconnectEvent event) {
        try {
            if (out != null) {
                out.writeObject(new PlayerActivityPacket(event.getPlayer().getUsername(), event.getPlayer().getUniqueId(), false));
                out.flush();
            }
        } catch (Exception ignored) {
        }
    }

    @Subscribe
    public void onPermissionSetup(PermissionsSetupEvent event) {
        if (!(event.getSubject() instanceof Player))
            return;
        Player p = (Player) event.getSubject();
        event.setProvider(subject -> permission -> {
            PermissionUser user = cachedUsers.stream()
                    .filter(u -> u.getName().equalsIgnoreCase(p.getUsername()))
                    .findFirst().orElse(null);

            PermissionGroup group = null;
            if (user != null) {
                group = cachedGroups.stream()
                        .filter(g -> g.getName().equalsIgnoreCase(user.getGroup()))
                        .findFirst().orElse(null);
                if (user.getPermissions().contains(permission) || user.getPermissions().contains("*")) {
                    return Tristate.TRUE;
                }
            }
            if (group == null) {
                group = cachedGroups.stream().filter(PermissionGroup::isDefault).findFirst().orElse(null);
            }
            if (group != null) {
                if (group.getPermissions().contains(permission) || group.getPermissions().contains("*")) {
                    return Tristate.TRUE;
                }
            }
            return Tristate.UNDEFINED;
        });
    }

    @Subscribe
    public void onKickedFromServer(KickedFromServerEvent event) {
        if (event.kickedDuringServerConnect())
            return;

        String reason = event.getServerKickReason().map(
                c -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c))
                .orElse("");
        if (reason.toLowerCase().contains("banned") || reason.toLowerCase().contains("kicked")) {
            return;
        }

        String kickedFrom = event.getServer().getServerInfo().getName();
        RegisteredServer fallback = null;
        for (RegisteredServer rs : server.getAllServers()) {
            if (rs.getServerInfo().getName().toLowerCase().contains("lobby")
                    && !rs.getServerInfo().getName().equalsIgnoreCase(kickedFrom)) {
                fallback = rs;
                break;
            }
        }

        if (fallback != null) {
            event.setResult(KickedFromServerEvent.RedirectPlayer.create(fallback));
            event.getPlayer().sendMessage(Component.text(fallbackKickMessage.replace("%reason%", reason)));
        }
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        if (maintenance) {
            String name = event.getUsername();
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
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(Component.text(maintenanceKickMessage)));
            }
        }
    }

    @Subscribe
    public void onPing(com.velocitypowered.api.event.proxy.ProxyPingEvent event) {
        if (!Boolean.parseBoolean(settings.getOrDefault("motd.enabled", "true")))
            return;

        com.velocitypowered.api.proxy.server.ServerPing ping = event.getPing();
        String line1 = settings.getOrDefault(maintenance ? "motd.maintenance.line1" : "motd.line1",
                "RedstoneNet Cloud");
        String line2 = settings.getOrDefault(maintenance ? "motd.maintenance.line2" : "motd.line2",
                "Cloud System ist da");

        com.velocitypowered.api.proxy.server.ServerPing.Builder builder = ping.asBuilder();
        builder.description(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .deserialize(line1 + "\n" + line2));

        if (maintenance) {
            builder.version(new com.velocitypowered.api.proxy.server.ServerPing.Version(0, "Maintenance"));
        } else {
            String pInfo = settings.getOrDefault("motd.player.info", "§e%online% Players")
                    .replace("%online%", String.valueOf(builder.getOnlinePlayers()))
                    .replace('&', '§');
            builder.version(new com.velocitypowered.api.proxy.server.ServerPing.Version(ping.getVersion().getProtocol(), pInfo));
        }
        event.setPing(builder.build());
    }

    private void connectToBestLobby(Player p) {
        com.velocitypowered.api.proxy.server.RegisteredServer bestLobby = null;
        int minPlayers = Integer.MAX_VALUE;

        for (com.velocitypowered.api.proxy.server.RegisteredServer si : server.getAllServers()) {
            if (si.getServerInfo().getName().toLowerCase().contains("lobby")) {
                ServerStatusPacket status = serverStatus
                        .get(si.getServerInfo().getName());
                if (status != null && "ONLINE".equals(status.getState())) {
                    if (status.getPlayerCount() < minPlayers) {
                        minPlayers = status.getPlayerCount();
                        bestLobby = si;
                    }
                }
            }
        }
        if (bestLobby != null && !p.getCurrentServer().isPresent() || (p.getCurrentServer().isPresent()
                && !p.getCurrentServer().get().getServerInfo().getName().equals(bestLobby.getServerInfo().getName()))) {
            p.createConnectionRequest(bestLobby).fireAndForget();
            p.sendMessage(
                    Component.text("§aYou were connected to the lobby: §e" + bestLobby.getServerInfo().getName()));
        } else if (bestLobby == null) {
            p.sendMessage(Component.text("§cNo fallback lobby found!"));
        } else {
            p.sendMessage(Component.text("§cYou are already on a lobby!"));
        }
    }

    private void sendPrivateMessage(String sender, String receiver, String message) {
        try {
            if (out != null) {
                out.writeObject(new PrivateMessagePacket(sender, receiver, message));
                out.flush();
            }
        } catch (Exception ignored) {}
    }
}

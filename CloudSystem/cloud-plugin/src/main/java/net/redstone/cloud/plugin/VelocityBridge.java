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
                (SimpleCommand) invocation -> {
                    if (!invocation.source().hasPermission("redstonecloud.admin")) {
                        invocation.source().sendMessage(Component.text("§cKeine Rechte."));
                        return;
                    }
                    if (invocation.arguments().length == 0) {
                        invocation.source().sendMessage(Component.text("§bCloud §8» §7/cloud <list|start|stop>"));
                        return;
                    }
                    String cmdLine = String.join(" ", invocation.arguments());
                    String senderName = (invocation.source() instanceof Player)
                            ? ((Player) invocation.source()).getUsername()
                            : "Console";
                    try {
                        if (out != null) {
                            out.writeObject(new CloudCommandPacket(senderName, cmdLine));
                            out.flush();
                        }
                    } catch (Exception ignored) {
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
                        }
                    }
                } catch (Exception e) {
                    logger.error("[CloudPlugin] Network error in receiver: {}", e.getMessage());
                }
            }, "Cloud-Receiver").start();

        } catch (Exception e) {
            logger.error("[CloudPlugin] Connection error: {}", e.getMessage());
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
                out.writeObject(new PlayerActivityPacket(event.getPlayer().getUsername(), true));
                out.flush();
            }
        } catch (Exception ignored) {
        }
    }

    @Subscribe
    public void onPlayerQuit(DisconnectEvent event) {
        try {
            if (out != null) {
                out.writeObject(new PlayerActivityPacket(event.getPlayer().getUsername(), false));
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
}

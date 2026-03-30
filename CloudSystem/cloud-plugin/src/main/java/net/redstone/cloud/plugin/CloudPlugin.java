package net.redstone.cloud.plugin;

import net.redstone.cloud.api.network.packet.AuthPacket;
import net.redstone.cloud.api.network.packet.CloudCommandPacket;
import net.redstone.cloud.api.network.packet.CloudMessagePacket;
import net.redstone.cloud.api.network.packet.PermissionUpdatePacket;
import net.redstone.cloud.api.network.packet.PerformancePacket;
import net.redstone.cloud.api.network.packet.PlayerActivityPacket;
import net.redstone.cloud.api.network.packet.PlayerCountPacket;
import net.redstone.cloud.api.network.packet.ServerStatusPacket;
import net.redstone.cloud.api.network.packet.SyncConfigPacket;
import net.redstone.cloud.api.permission.PermissionGroup;
import net.redstone.cloud.api.permission.PermissionUser;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

public class CloudPlugin extends JavaPlugin implements Listener {

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;

    private static List<PermissionGroup> cachedGroups = new ArrayList<>();
    private static List<PermissionUser> cachedUsers = new ArrayList<>();
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();

    private final List<Location> cloudSigns = new ArrayList<>();
    private final Map<String, ServerStatusPacket> serverStatus = new HashMap<>();
    private Map<String, String> settings = new HashMap<>();

    @Override
    public void onEnable() {
        getLogger().info("[CloudPlugin] Cloud-Bridge starting...");
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        loadSigns();
        connectToNode();

        if (getCommand("cloud") != null) {
            getCommand("cloud").setExecutor((sender, command, label, args) -> {
                if (!sender.hasPermission("redstonecloud.admin")) {
                    sender.sendMessage("§cKeine Rechte.");
                    return true;
                }
                if (args.length == 0) {
                    sender.sendMessage("§bCloud §8» §7/cloud <list|start|stop>");
                    return true;
                }
                String cmdLine = String.join(" ", args);
                try {
                    if (out != null) {
                        out.writeObject(new CloudCommandPacket(sender.getName(), cmdLine));
                        out.flush();
                    }
                } catch (Exception ignored) {
                }
                return true;
            });
        }
    }

    private void loadSigns() {
        if (getConfig().contains("signs")) {
            for (String key : getConfig().getConfigurationSection("signs").getKeys(false)) {
                String[] p = key.split(",");
                World w = getServer().getWorld(p[0]);
                if (w != null) {
                    cloudSigns.add(
                            new Location(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])));
                }
            }
        }
    }

    private void saveSign(Location loc) {
        String key = loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
        getConfig().set("signs." + key, true);
        saveConfig();
        cloudSigns.add(loc);
    }

    private void connectToNode() {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Properties props = new Properties();
                File wrapperFile = new File("wrapper.properties");
                if (!wrapperFile.exists()) {
                    getLogger().severe("[CloudPlugin] NO wrapper.properties found!");
                    return;
                }
                props.load(java.nio.file.Files.newInputStream(wrapperFile.toPath()));

                String host = props.getProperty("cloud.host");
                int port = Integer.parseInt(props.getProperty("cloud.port"));
                String serverName = props.getProperty("cloud.serverName");
                String authKey = props.getProperty("cloud.authKey");
                int serverPort = Integer.parseInt(props.getProperty("cloud.serverPort", "0"));
                boolean isProxy = Boolean.parseBoolean(props.getProperty("cloud.isProxy", "false"));

                socket = new Socket(host, port);
                out = new ObjectOutputStream(socket.getOutputStream());
                in = new ObjectInputStream(socket.getInputStream());

                getLogger().info("[CloudPlugin] Connected to CloudNode (" + host + ":" + port + ") as " + serverName);

                final String finalServerName = serverName;
                out.writeObject(new AuthPacket(serverName, authKey, serverPort, isProxy));
                out.flush();

                getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
                    try {
                        if (out != null) {
                            out.writeObject(
                                    new PlayerCountPacket(finalServerName, getServer().getOnlinePlayers().size()));
                            out.flush();

                            double tps = 20.0;
                            try {
                                Object tpsArray = getServer().getClass().getMethod("getTPS").invoke(getServer());
                                tps = ((double[]) tpsArray)[0];
                            } catch (Throwable t) {
                            }

                            long total = Runtime.getRuntime().totalMemory() / 1024 / 1024;
                            long free = Runtime.getRuntime().freeMemory() / 1024 / 1024;
                            long max = Runtime.getRuntime().maxMemory() / 1024 / 1024;
                            long used = total - free;

                            double cpu = 0.0;
                            try {
                                java.lang.management.OperatingSystemMXBean osBean = java.lang.management.ManagementFactory
                                        .getOperatingSystemMXBean();
                                if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                                    cpu = ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuLoad()
                                            * 100.0;
                                }
                            } catch (Throwable t) {
                            }

                            out.writeObject(new PerformancePacket(finalServerName, tps, cpu, used, max));
                            out.flush();
                        }
                    } catch (Exception ignored) {
                    }
                }, 200L, 200L);

                new Thread(() -> {
                    try {
                        while (socket != null && !socket.isClosed()) {
                            Object obj = in.readObject();
                            if (obj instanceof CloudMessagePacket) {
                                CloudMessagePacket msg = (CloudMessagePacket) obj;
                                Player p = getServer().getPlayerExact(msg.getTargetName());
                                if (p != null)
                                    p.sendMessage(msg.getMessage());
                                if ("CONSOLE".equals(msg.getTargetName()))
                                    getLogger().info(msg.getMessage());
                            } else if (obj instanceof PermissionUpdatePacket) {
                                PermissionUpdatePacket pkt = (PermissionUpdatePacket) obj;
                                cachedGroups = pkt.getGroups();
                                cachedUsers = pkt.getUsers();
                                getLogger().info("[CloudPlugin] Permissions updated (" + cachedGroups.size() + " groups).");
                                getServer().getScheduler().runTask(CloudPlugin.this, () -> {
                                    for (Player player : getServer().getOnlinePlayers()) {
                                        setupPermissions(player);
                                    }
                                });
                            } else if (obj instanceof ServerStatusPacket) {
                                ServerStatusPacket pkt = (ServerStatusPacket) obj;
                                serverStatus.put(pkt.getServerName(), pkt);
                                getServer().getScheduler().runTask(CloudPlugin.this, this::updateSigns);
                            } else if (obj instanceof SyncConfigPacket) {
                                settings = ((SyncConfigPacket) obj).getSettings();
                                getLogger().info("[CloudPlugin] Settings synchronisiert!");
                                getServer().getScheduler().runTask(CloudPlugin.this, () -> {
                                    updateSigns();
                                    for (Player player : getServer().getOnlinePlayers()) {
                                        updateTablist(player);
                                        setupPermissions(player);
                                    }
                                });
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }, "Cloud-Receiver").start();

            } catch (Exception e) {
                getLogger().severe("[CloudPlugin] Connection error: " + e.getMessage());
            }
        });
    }

    private void updateSigns() {
        for (Location loc : new ArrayList<>(cloudSigns)) {
            if (loc.getBlock().getState() instanceof Sign) {
                Sign sign = (Sign) loc.getBlock().getState();
                if (sign.getLine(0).contains("[Cloud]")) {
                    String targetName = net.md_5.bungee.api.ChatColor.stripColor(sign.getLine(1));
                    ServerStatusPacket status = serverStatus.get(targetName);

                    if (status != null && status.getState().equals("ONLINE")) {
                        String count = String.valueOf(status.getPlayerCount());
                        sign.setLine(0, settings.getOrDefault("sign.line1.online", "§b[Cloud]")
                                .replace("%server%", targetName).replace("%online%", count));
                        sign.setLine(1, settings.getOrDefault("sign.line2.online", "§7%server%")
                                .replace("%server%", targetName).replace("%online%", count));
                        sign.setLine(2, settings.getOrDefault("sign.line3.online", "§aOnline")
                                .replace("%server%", targetName).replace("%online%", count));
                        sign.setLine(3, settings.getOrDefault("sign.line4.online", "\u00a78%online% Players")
                                .replace("%server%", targetName).replace("%online%", count));
                    } else {
                        sign.setLine(0, settings.getOrDefault("sign.line1.offline", "§b[Cloud]").replace("%server%",
                                targetName));
                        sign.setLine(1, settings.getOrDefault("sign.line2.offline", "§7%server%").replace("%server%",
                                targetName));
                        sign.setLine(2, settings.getOrDefault("sign.line3.offline", "§cOffline").replace("%server%",
                                targetName));
                        sign.setLine(3,
                                settings.getOrDefault("sign.line4.offline", "§8-").replace("%server%", targetName));
                    }
                    sign.update();
                }
            }
        }
    }

    @Override
    public void onDisable() {
        try {
            if (out != null)
                out.close();
            if (in != null)
                in.close();
            if (socket != null && !socket.isClosed())
                socket.close();
        } catch (Exception e) {
            getLogger().warning("[CloudPlugin] Error closing connection: " + e.getMessage());
        }
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        if (event.getLine(0) != null && event.getLine(0).equalsIgnoreCase("[Cloud]")) {
            if (!event.getPlayer().hasPermission("redstonecloud.admin")) {
                event.setLine(0, "§cNo Permission");
                return;
            }
            String target = event.getLine(1);
            if (target == null || target.isEmpty())
                return;

            event.setLine(0, "§b[Cloud]");
            event.setLine(1, "§7" + target);
            event.setLine(2, "§eLoading...");

            saveSign(event.getBlock().getLocation());
            event.getPlayer().sendMessage("\u00a7aCloud sign created!");
            updateSigns();
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            if (event.getClickedBlock().getState() instanceof Sign) {
                Sign sign = (Sign) event.getClickedBlock().getState();
                if (sign.getLine(0).contains("[Cloud]")) {
                    String targetName = net.md_5.bungee.api.ChatColor.stripColor(sign.getLine(1));
                    connectToServer(event.getPlayer(), targetName);
                }
            }
        }
    }

    private void connectToServer(Player player, String serverName) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);
            out.writeUTF("Connect");
            out.writeUTF(serverName);
            player.sendPluginMessage(this, "BungeeCord", b.toByteArray());
        } catch (Exception ignored) {
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        try {
            if (out != null) {
                out.writeObject(new PlayerActivityPacket(event.getPlayer().getName(), true));
                out.flush();
            }
        } catch (Exception ignored) {
        }
        setupPermissions(event.getPlayer());
        updateTablist(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        try {
            if (out != null) {
                out.writeObject(new PlayerActivityPacket(event.getPlayer().getName(), false));
                out.flush();
            }
        } catch (Exception ignored) {
        }
        UUID uuid = event.getPlayer().getUniqueId();
        if (attachments.containsKey(uuid)) {
            event.getPlayer().removeAttachment(attachments.remove(uuid));
        }
    }

    private void setupPermissions(Player p) {
        UUID uuid = p.getUniqueId();
        if (attachments.containsKey(uuid)) {
            p.removeAttachment(attachments.remove(uuid));
        }
        PermissionAttachment attachment = p.addAttachment(this);
        attachments.put(uuid, attachment);

        PermissionUser user = cachedUsers.stream()
                .filter(u -> u.getName().equalsIgnoreCase(p.getName()))
                .findFirst().orElse(null);

        PermissionGroup group = null;
        if (user != null) {
            group = cachedGroups.stream()
                    .filter(g -> g.getName().equalsIgnoreCase(user.getGroup()))
                    .findFirst().orElse(null);
            for (String perm : user.getPermissions()) {
                attachment.setPermission(perm, true);
            }
        }
        if (group == null) {
            group = cachedGroups.stream()
                    .filter(PermissionGroup::isDefault)
                    .findFirst().orElse(null);
        }
        if (group != null) {
            for (String perm : group.getPermissions()) {
                attachment.setPermission(perm, true);
            }
            if (group.getPrefix() != null && !group.getPrefix().isEmpty()) {
                p.setPlayerListName(group.getPrefix() + p.getName());
                p.setDisplayName(group.getPrefix() + p.getName());
                if (Boolean.parseBoolean(settings.getOrDefault("nametags.enabled", "true"))) {
                    updateNametag(p, group.getPrefix(), group.getWeight());
                }
            }
        }
    }

    private void updateTablist(Player p) {
        if (!Boolean.parseBoolean(settings.getOrDefault("tablist.enabled", "true")))
            return;

        String header = settings.getOrDefault("tablist.header",
                "§3§lRedstoneNet §8- §bCloud\n§7You are playing on: §e%server%");
        String footer = settings.getOrDefault("tablist.footer",
                "\u00a77Shop: \u00a7bshop.redstone.net\n\u00a7e%online% \u00a77Players online");

        int totalOnline = serverStatus.values().stream().mapToInt(ServerStatusPacket::getPlayerCount).sum();

        header = header.replace("%server%", getServer().getName()).replace("%online%", String.valueOf(totalOnline));
        footer = footer.replace("%server%", getServer().getName()).replace("%online%", String.valueOf(totalOnline));

        p.setPlayerListHeader(header);
        p.setPlayerListFooter(footer);
    }

    private void updateNametag(Player p, String prefix, int weight) {
        org.bukkit.scoreboard.Scoreboard sb = p.getScoreboard();
        if (sb == getServer().getScoreboardManager().getMainScoreboard()) {
            sb = getServer().getScoreboardManager().getNewScoreboard();
            p.setScoreboard(sb);
        }

        String teamName = (1000 - weight) + "_" + p.getName();
        if (teamName.length() > 16)
            teamName = teamName.substring(0, 16);

        org.bukkit.scoreboard.Team team = sb.getTeam(p.getName());
        if (team == null)
            team = sb.registerNewTeam(p.getName());

        team.setPrefix(prefix);
        team.addEntry(p.getName());

        // Broadcast nametag to others
        for (Player all : getServer().getOnlinePlayers()) {
            if (all == p)
                continue;
            org.bukkit.scoreboard.Scoreboard asb = all.getScoreboard();
            org.bukkit.scoreboard.Team t = asb.getTeam(p.getName());
            if (t == null)
                t = asb.registerNewTeam(p.getName());
            t.setPrefix(prefix);
            t.addEntry(p.getName());
        }
    }
}

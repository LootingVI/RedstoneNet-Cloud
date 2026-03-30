package net.redstone.cloud.node.network;

import net.redstone.cloud.api.network.Packet;
import net.redstone.cloud.api.network.packet.AuthPacket;
import net.redstone.cloud.api.network.packet.CloudCommandPacket;
import net.redstone.cloud.api.network.packet.CloudMessagePacket;
import net.redstone.cloud.api.network.packet.PermissionUpdatePacket;
import net.redstone.cloud.api.network.packet.PlayerActivityPacket;
import net.redstone.cloud.api.network.packet.PlayerCountPacket;
import net.redstone.cloud.api.network.packet.RegisterServerPacket;
import net.redstone.cloud.api.network.packet.UnregisterServerPacket;
import net.redstone.cloud.api.network.packet.PerformancePacket;
import net.redstone.cloud.api.network.packet.MaintenanceUpdatePacket;
import net.redstone.cloud.api.network.packet.SyncConfigPacket;
import net.redstone.cloud.node.CloudNode;
import net.redstone.cloud.node.logging.Logger;
import net.redstone.cloud.node.process.CloudServerProcess;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NetServer {

    private final int port;
    private boolean running;
    private ServerSocket serverSocket;
    private final ExecutorService executor;

    private final List<ObjectOutputStream> proxies = new CopyOnWriteArrayList<>();
    private final List<ObjectOutputStream> allClients = new CopyOnWriteArrayList<>();
    private final Map<String, AuthPacket> activeServers = new ConcurrentHashMap<>();

    public NetServer(int port) {
        this.port = port;
        this.executor = Executors.newCachedThreadPool();
    }

    public void start() {
        running = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                Logger.info("NetServer listening on port " + port + " ...");
                while (running) {
                    Socket client = serverSocket.accept();
                    executor.submit(() -> handleClient(client));
                }
            } catch (Exception e) {
                if (running) Logger.error("NetServer error: " + e.getMessage());
            }
        }, "NetServer-Thread").start();
    }

    private void handleClient(Socket socket) {
        ObjectOutputStream outX = null;
        String associatedServer = null;
        try {
            ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
            ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
            outX = out;
            allClients.add(out);
            Logger.info("New client connected: " + socket.getInetAddress());

            while (running && !socket.isClosed()) {
                Object obj = in.readObject();
                if (obj instanceof Packet) {
                    String result = processPacket((Packet) obj, socket, out, associatedServer);
                    if (result != null) associatedServer = result;
                }
            }
        } catch (Exception e) {
            Logger.info("Client disconnected: " + socket.getInetAddress());
        } finally {
            if (associatedServer != null) {
                activeServers.remove(associatedServer);
                for (ObjectOutputStream pout : proxies) {
                    try {
                        pout.writeObject(new UnregisterServerPacket(associatedServer));
                        pout.flush();
                    } catch (Exception ignored) {}
                }
            }
            if (outX != null) {
                proxies.remove(outX);
                allClients.remove(outX);
            }
        }
    }

    public void broadcastPacket(Packet packet) {
        for (ObjectOutputStream out : allClients) {
            try {
                out.writeObject(packet);
                out.flush();
            } catch (Exception ignored) {}
        }
    }

    private String processPacket(Packet packet, Socket socket, ObjectOutputStream out, String serverName) {
        if (packet instanceof AuthPacket) {
            AuthPacket auth = (AuthPacket) packet;
            if (!auth.getAuthKey().equals(CloudNode.getInstance().getAuthKey())) {
                Logger.error("Auth failed for " + auth.getServerName() + " (Invalid key!)");
                try { socket.close(); } catch (Exception ignored) {}
                return null;
            }
            Logger.success("Authentication OK [Proxy=" + auth.isProxy() + "]: " + auth.getServerName());
            activeServers.put(auth.getServerName(), auth);

            if (auth.isProxy()) {
                proxies.add(out);
                // Send all already active sub-servers to the new proxy
                for (AuthPacket srv : activeServers.values()) {
                    if (!srv.isProxy()) {
                        try {
                            out.writeObject(new RegisterServerPacket(srv.getServerName(), "127.0.0.1", srv.getPort()));
                            out.flush();
                        } catch (Exception ignored) {}
                    }
                }
            } else {
                // Announce new sub-server to all proxies
                for (ObjectOutputStream pOut : proxies) {
                    try {
                        pOut.writeObject(new RegisterServerPacket(auth.getServerName(), "127.0.0.1", auth.getPort()));
                        pOut.flush();
                    } catch (Exception ignored) {}
                }
            }
            // Send permissions immediately on connect
            try {
                out.writeObject(new PermissionUpdatePacket(
                        CloudNode.getInstance().getPermissionManager().getGroups(),
                        CloudNode.getInstance().getPermissionManager().getUsers()
                ));
                out.flush();
                out.writeObject(new MaintenanceUpdatePacket(
                        CloudNode.getInstance().isMaintenance()
                ));
                out.flush();
                out.writeObject(new SyncConfigPacket(
                        CloudNode.getInstance().getNetworkSettings()
                ));
                out.flush();
            } catch (Exception ignored) {}
            return auth.getServerName();

        } else if (packet instanceof CloudCommandPacket) {
            CloudCommandPacket cmd = (CloudCommandPacket) packet;
            String[] args = cmd.getCommandLine().split(" ");
            String command = args[0].toLowerCase();
            try {
                if (command.equals("list")) {
                    out.writeObject(new CloudMessagePacket(cmd.getSenderName(),
                            "§bCloud §8» §7Active servers: §e"
                                    + CloudNode.getInstance().getServerManager().getActiveProcesses().size()));
                } else if (command.equals("start") && args.length == 3) {
                    CloudNode.getInstance().getServerManager().startServer(args[1], Integer.parseInt(args[2]));
                    out.writeObject(new CloudMessagePacket(cmd.getSenderName(),
                            "§bCloud §8» §aServer §e" + args[1] + " §ais starting!"));
                } else if (command.equals("stop") && args.length == 2) {
                    CloudNode.getInstance().getServerManager().stopServer(args[1]);
                    out.writeObject(new CloudMessagePacket(cmd.getSenderName(),
                            "§bCloud §8» §cServer §e" + args[1] + " §cis stopping!"));
                } else {
                    out.writeObject(new CloudMessagePacket(cmd.getSenderName(),
                            "§bCloud §8» §7/cloud <list|start <group> <count>|stop <name>>"));
                }
                out.flush();
            } catch (Exception ignored) {}

        } else if (packet instanceof PlayerActivityPacket) {
            PlayerActivityPacket act = (PlayerActivityPacket) packet;
            if (act.isJoin()) {
                Logger.info("» [PlayerLog] " + act.getPlayerName() + " joined " + serverName + ".");
            } else {
                Logger.info("« [PlayerLog] " + act.getPlayerName() + " left " + serverName + ".");
            }
        } else if (packet instanceof PlayerCountPacket) {
            PlayerCountPacket pcp = (PlayerCountPacket) packet;
            CloudNode.getInstance().getServerManager().updatePlayerCount(pcp.getServerName(), pcp.getPlayerCount());
        } else if (packet instanceof PerformancePacket) {
            PerformancePacket perf = (PerformancePacket) packet;
            CloudServerProcess p = CloudNode.getInstance().getServerManager().getProcess(perf.getServerName());
            if (p != null) {
                p.setTps(perf.getTps());
                p.setCpuUsage(perf.getCpuUsage());
                p.setMemoryUsage(perf.getUsedMemoryMB(), perf.getMaxMemoryMB());
                if (perf.getTps() < 15.0) {
                    Logger.warn("⚠️ [Anti-Lag] " + perf.getServerName() + " is lagging severely! (TPS: " + String.format("%.1f", perf.getTps()) + ")");
                }
            }
        }
        return null;
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
            executor.shutdownNow();
        } catch (Exception e) {
            Logger.error("Error stopping NetServer: " + e.getMessage());
        }
    }
}

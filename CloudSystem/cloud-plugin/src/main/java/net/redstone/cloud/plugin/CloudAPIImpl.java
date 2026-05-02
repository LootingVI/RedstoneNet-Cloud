package net.redstone.cloud.plugin;

import net.redstone.cloud.api.CloudAPI;
import net.redstone.cloud.api.network.packet.CloudCommandPacket;
import net.redstone.cloud.api.network.packet.CloudMessagePacket;
import net.redstone.cloud.api.network.packet.ServerStatusPacket;
import net.redstone.cloud.api.network.packet.api.SendPlayerPacket;
import net.redstone.cloud.api.network.packet.api.StartServerPacket;
import net.redstone.cloud.api.network.packet.api.StopServerPacket;
import net.redstone.cloud.api.player.CloudPlayer;

import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CloudAPIImpl extends CloudAPI {

    private final ObjectOutputStream out;
    private final String serverName;
    private final Map<UUID, CloudPlayer> onlinePlayers = new ConcurrentHashMap<>();
    private final Map<String, ServerStatusPacket> serverStatus = new ConcurrentHashMap<>();
    private boolean maintenance = false;

    public CloudAPIImpl(ObjectOutputStream out, String serverName) {
        this.out = out;
        this.serverName = serverName;
        CloudAPI.setInstance(this);
    }

    @Override
    public void startServer(String groupName, int count) {
        sendPacket(new StartServerPacket(groupName, count));
    }

    @Override
    public void stopServer(String serverName) {
        sendPacket(new StopServerPacket(serverName));
    }

    @Override
    public void broadcastMessage(String message) {
        sendPacket(new CloudMessagePacket("ALL", message));
    }

    @Override
    public List<CloudPlayer> getOnlinePlayers() {
        return new ArrayList<>(onlinePlayers.values());
    }

    @Override
    public CloudPlayer getPlayer(String name) {
        return onlinePlayers.values().stream()
                .filter(p -> p.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    @Override
    public void dispatchCloudCommand(String sender, String commandLine) {
        sendPacket(new CloudCommandPacket(sender, commandLine));
    }

    @Override
    public void sendPlayer(String playerName, String targetServer) {
        sendPacket(new SendPlayerPacket(playerName, targetServer));
    }

    @Override
    public String getThisServerName() {
        return serverName;
    }

    @Override
    public int getOnlineCount() {
        return onlinePlayers.size();
    }

    @Override
    public boolean isServerOnline(String serverName) {
        ServerStatusPacket status = serverStatus.get(serverName);
        return status != null && "ONLINE".equals(status.getState());
    }

    @Override
    public boolean isMaintenance() {
        return maintenance;
    }

    // --- Internal update methods called by bridge plugins ---

    public void updatePlayerList(List<CloudPlayer> players) {
        onlinePlayers.clear();
        for (CloudPlayer cp : players) {
            onlinePlayers.put(cp.getUuid(), cp);
        }
    }

    public void updateServerStatus(ServerStatusPacket pkt) {
        serverStatus.put(pkt.getServerName(), pkt);
    }

    public void setMaintenance(boolean maintenance) {
        this.maintenance = maintenance;
    }

    public void addPlayer(CloudPlayer player) {
        onlinePlayers.put(player.getUuid(), player);
    }

    public void removePlayer(UUID uuid) {
        onlinePlayers.remove(uuid);
    }

    private void sendPacket(Object packet) {
        try {
            if (out != null) {
                synchronized (out) {
                    out.writeObject(packet);
                    out.flush();
                }
            }
        } catch (Exception e) {
            System.err.println("[CloudAPI] Failed to send packet: " + e.getMessage());
        }
    }
}

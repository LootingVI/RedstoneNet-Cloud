package net.redstone.cloud.api.network.packet;

import net.redstone.cloud.api.network.Packet;

public class ServerStatusPacket extends Packet {
    private final String serverName;
    private final String state; // ONLINE, OFFLINE
    private final int playerCount;
    private final int maxPlayers;

    public ServerStatusPacket(String serverName, String state, int playerCount, int maxPlayers) {
        this.serverName = serverName;
        this.state = state;
        this.playerCount = playerCount;
        this.maxPlayers = maxPlayers;
    }

    public String getServerName() { return serverName; }
    public String getState() { return state; }
    public int getPlayerCount() { return playerCount; }
    public int getMaxPlayers() { return maxPlayers; }
}

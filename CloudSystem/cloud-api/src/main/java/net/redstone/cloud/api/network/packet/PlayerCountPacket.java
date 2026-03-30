package net.redstone.cloud.api.network.packet;

import net.redstone.cloud.api.network.Packet;

public class PlayerCountPacket extends Packet {
    private final String serverName;
    private final int playerCount;

    public PlayerCountPacket(String serverName, int playerCount) {
        this.serverName = serverName;
        this.playerCount = playerCount;
    }

    public String getServerName() { return serverName; }
    public int getPlayerCount() { return playerCount; }
}

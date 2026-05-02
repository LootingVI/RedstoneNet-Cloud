package net.redstone.cloud.api.network.packet.api;

import net.redstone.cloud.api.network.Packet;

public class SendPlayerPacket extends Packet {
    private final String playerName;
    private final String targetServer;

    public SendPlayerPacket(String playerName, String targetServer) {
        this.playerName = playerName;
        this.targetServer = targetServer;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getTargetServer() {
        return targetServer;
    }
}

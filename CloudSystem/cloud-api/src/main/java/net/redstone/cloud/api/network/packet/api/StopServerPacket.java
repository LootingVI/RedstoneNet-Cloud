package net.redstone.cloud.api.network.packet.api;

import net.redstone.cloud.api.network.Packet;

public class StopServerPacket extends Packet {
    private final String serverName;

    public StopServerPacket(String serverName) {
        this.serverName = serverName;
    }

    public String getServerName() {
        return serverName;
    }
}

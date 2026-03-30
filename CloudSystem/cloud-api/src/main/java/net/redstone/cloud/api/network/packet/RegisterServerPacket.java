package net.redstone.cloud.api.network.packet;

import net.redstone.cloud.api.network.Packet;

public class RegisterServerPacket extends Packet {

    private final String name;
    private final String host;
    private final int port;

    public RegisterServerPacket(String name, String host, int port) {
        this.name = name;
        this.host = host;
        this.port = port;
    }

    public String getName() {
        return name;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
}

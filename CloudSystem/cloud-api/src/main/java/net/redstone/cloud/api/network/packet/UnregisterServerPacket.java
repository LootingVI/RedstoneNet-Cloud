package net.redstone.cloud.api.network.packet;

import net.redstone.cloud.api.network.Packet;

public class UnregisterServerPacket extends Packet {

    private final String name;

    public UnregisterServerPacket(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}

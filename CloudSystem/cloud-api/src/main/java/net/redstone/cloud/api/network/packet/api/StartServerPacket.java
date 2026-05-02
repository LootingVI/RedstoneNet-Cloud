package net.redstone.cloud.api.network.packet.api;

import net.redstone.cloud.api.network.Packet;

public class StartServerPacket extends Packet {
    private final String groupName;
    private final int count;

    public StartServerPacket(String groupName, int count) {
        this.groupName = groupName;
        this.count = count;
    }

    public String getGroupName() {
        return groupName;
    }

    public int getCount() {
        return count;
    }
}

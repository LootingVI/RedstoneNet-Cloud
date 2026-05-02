package net.redstone.cloud.api.network.packet.api;

import net.redstone.cloud.api.network.Packet;
import java.util.List;

public class SyncGroupsPacket extends Packet {
    private final List<String> groups;

    public SyncGroupsPacket(List<String> groups) {
        this.groups = groups;
    }

    public List<String> getGroups() {
        return groups;
    }
}

package net.redstone.cloud.api.network.packet;

import net.redstone.cloud.api.network.Packet;
import java.util.Map;

public class SyncConfigPacket extends Packet {
    private final Map<String, String> settings;

    public SyncConfigPacket(Map<String, String> settings) {
        this.settings = settings;
    }

    public Map<String, String> getSettings() {
        return settings;
    }
}

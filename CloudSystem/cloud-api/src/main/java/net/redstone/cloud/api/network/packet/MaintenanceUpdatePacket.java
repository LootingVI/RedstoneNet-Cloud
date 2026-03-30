package net.redstone.cloud.api.network.packet;

import net.redstone.cloud.api.network.Packet;

public class MaintenanceUpdatePacket extends Packet {
    private final boolean maintenance;

    public MaintenanceUpdatePacket(boolean maintenance) {
        this.maintenance = maintenance;
    }

    public boolean isMaintenance() { return maintenance; }
}

package net.redstone.cloud.api.event.network;

import net.redstone.cloud.api.event.Event;

public class CloudPacketReceiveEvent extends Event {

    private final Object packet;

    public CloudPacketReceiveEvent(Object packet) {
        this.packet = packet;
    }

    public Object getPacket() {
        return packet;
    }
}

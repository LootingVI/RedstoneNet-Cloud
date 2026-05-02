package net.redstone.cloud.api.network.packet.api;

import net.redstone.cloud.api.network.Packet;

public class PrivateMessagePacket extends Packet {
    private final String sender;
    private final String receiver;
    private final String message;

    public PrivateMessagePacket(String sender, String receiver, String message) {
        this.sender = sender;
        this.receiver = receiver;
        this.message = message;
    }

    public String getSender() {
        return sender;
    }

    public String getReceiver() {
        return receiver;
    }

    public String getMessage() {
        return message;
    }
}

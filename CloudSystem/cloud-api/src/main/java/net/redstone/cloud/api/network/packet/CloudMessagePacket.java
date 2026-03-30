package net.redstone.cloud.api.network.packet;

import net.redstone.cloud.api.network.Packet;

public class CloudMessagePacket extends Packet {
    private final String targetName;
    private final String message;

    public CloudMessagePacket(String targetName, String message) {
        this.targetName = targetName;
        this.message = message;
    }

    public String getTargetName() { return targetName; }
    public String getMessage() { return message; }
}

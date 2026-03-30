package net.redstone.cloud.api.network.packet;

import net.redstone.cloud.api.network.Packet;

public class CloudCommandPacket extends Packet {
    private final String senderName;
    private final String commandLine;

    public CloudCommandPacket(String senderName, String commandLine) {
        this.senderName = senderName;
        this.commandLine = commandLine;
    }

    public String getSenderName() { return senderName; }
    public String getCommandLine() { return commandLine; }
}

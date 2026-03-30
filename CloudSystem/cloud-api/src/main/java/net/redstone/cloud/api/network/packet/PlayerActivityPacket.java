package net.redstone.cloud.api.network.packet;

import net.redstone.cloud.api.network.Packet;

public class PlayerActivityPacket extends Packet {
    private String playerName;
    private boolean join;

    public PlayerActivityPacket(String playerName, boolean join) {
        this.playerName = playerName;
        this.join = join;
    }

    public String getPlayerName() {
        return playerName;
    }

    public boolean isJoin() {
        return join;
    }
}

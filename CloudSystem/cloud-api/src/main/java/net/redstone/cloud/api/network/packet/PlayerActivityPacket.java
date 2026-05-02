package net.redstone.cloud.api.network.packet;

import net.redstone.cloud.api.network.Packet;

import java.util.UUID;

public class PlayerActivityPacket extends Packet {
    private String playerName;
    private UUID uuid;
    private boolean join;

    public PlayerActivityPacket(String playerName, UUID uuid, boolean join) {
        this.playerName = playerName;
        this.uuid = uuid;
        this.join = join;
    }

    public String getPlayerName() {
        return playerName;
    }

    public UUID getUuid() {
        return uuid;
    }

    public boolean isJoin() {
        return join;
    }
}

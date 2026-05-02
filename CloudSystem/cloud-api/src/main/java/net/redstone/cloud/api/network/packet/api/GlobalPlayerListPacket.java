package net.redstone.cloud.api.network.packet.api;

import net.redstone.cloud.api.network.Packet;
import net.redstone.cloud.api.player.CloudPlayer;

import java.util.List;

public class GlobalPlayerListPacket extends Packet {
    private final List<CloudPlayer> players;

    public GlobalPlayerListPacket(List<CloudPlayer> players) {
        this.players = players;
    }

    public List<CloudPlayer> getPlayers() {
        return players;
    }
}

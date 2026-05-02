package net.redstone.cloud.node.player;

import net.redstone.cloud.api.player.CloudPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerManager {
    private final Map<UUID, CloudPlayer> onlinePlayers = new ConcurrentHashMap<>();

    public void addPlayer(CloudPlayer player) {
        onlinePlayers.put(player.getUuid(), player);
    }

    public void removePlayer(UUID uuid) {
        onlinePlayers.remove(uuid);
    }

    public CloudPlayer getPlayer(UUID uuid) {
        return onlinePlayers.get(uuid);
    }

    public CloudPlayer getPlayer(String name) {
        return onlinePlayers.values().stream()
                .filter(p -> p.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    public List<CloudPlayer> getOnlinePlayers() {
        return new ArrayList<>(onlinePlayers.values());
    }
}

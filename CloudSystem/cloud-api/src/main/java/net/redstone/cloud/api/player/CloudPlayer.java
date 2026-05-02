package net.redstone.cloud.api.player;

import java.io.Serializable;
import java.util.UUID;

public class CloudPlayer implements Serializable {
    private final UUID uuid;
    private final String name;
    private String proxyServer;
    private String gameServer;

    public CloudPlayer(UUID uuid, String name, String proxyServer) {
        this.uuid = uuid;
        this.name = name;
        this.proxyServer = proxyServer;
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public String getProxyServer() {
        return proxyServer;
    }

    public void setProxyServer(String proxyServer) {
        this.proxyServer = proxyServer;
    }

    public String getGameServer() {
        return gameServer;
    }

    public void setGameServer(String gameServer) {
        this.gameServer = gameServer;
    }
}

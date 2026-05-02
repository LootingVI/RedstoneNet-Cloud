package net.redstone.cloud.api.event.server;

import net.redstone.cloud.api.event.Event;

public class CloudServerStartEvent extends Event {

    private final String serverName;
    private final int port;

    public CloudServerStartEvent(String serverName, int port) {
        this.serverName = serverName;
        this.port = port;
    }

    public String getServerName() {
        return serverName;
    }

    public int getPort() {
        return port;
    }
}

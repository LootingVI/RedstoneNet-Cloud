package net.redstone.cloud.api.event.server;

import net.redstone.cloud.api.event.Event;

public class CloudServerCrashEvent extends Event {

    private final String serverName;

    public CloudServerCrashEvent(String serverName) {
        this.serverName = serverName;
    }

    public String getServerName() {
        return serverName;
    }
}

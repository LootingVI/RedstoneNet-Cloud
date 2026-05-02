package net.redstone.cloud.api.event.server;

import net.redstone.cloud.api.event.Event;

public class CloudServerStopEvent extends Event {

    private final String serverName;

    public CloudServerStopEvent(String serverName) {
        this.serverName = serverName;
    }

    public String getServerName() {
        return serverName;
    }
}

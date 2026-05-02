package net.redstone.cloud.api.event.node;

import net.redstone.cloud.api.event.Event;

public class CloudNodeStartEvent extends Event {

    private final String version;
    private final int port;

    public CloudNodeStartEvent(String version, int port) {
        this.version = version;
        this.port = port;
    }

    public String getVersion() {
        return version;
    }

    public int getPort() {
        return port;
    }
}

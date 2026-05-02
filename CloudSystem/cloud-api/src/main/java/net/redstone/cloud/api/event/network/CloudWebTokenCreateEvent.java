package net.redstone.cloud.api.event.network;

import net.redstone.cloud.api.event.Event;

public class CloudWebTokenCreateEvent extends Event {

    private final String token;

    public CloudWebTokenCreateEvent(String token) {
        this.token = token;
    }

    public String getToken() {
        return token;
    }
}

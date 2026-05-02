package net.redstone.cloud.api.event;

public abstract class Event {
    public String getEventName() {
        return getClass().getSimpleName();
    }
}

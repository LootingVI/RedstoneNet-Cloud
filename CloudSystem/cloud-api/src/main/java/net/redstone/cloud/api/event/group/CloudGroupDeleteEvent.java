package net.redstone.cloud.api.event.group;

import net.redstone.cloud.api.event.Event;

public class CloudGroupDeleteEvent extends Event {

    private final String groupName;

    public CloudGroupDeleteEvent(String groupName) {
        this.groupName = groupName;
    }

    public String getGroupName() {
        return groupName;
    }
}

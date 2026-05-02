package net.redstone.cloud.api.event.group;

import net.redstone.cloud.api.event.Event;

public class CloudGroupCreateEvent extends Event {

    private final String groupName;

    public CloudGroupCreateEvent(String groupName) {
        this.groupName = groupName;
    }

    public String getGroupName() {
        return groupName;
    }
}

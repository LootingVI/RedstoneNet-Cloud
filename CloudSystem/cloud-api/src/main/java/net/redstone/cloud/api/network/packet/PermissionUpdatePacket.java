package net.redstone.cloud.api.network.packet;

import net.redstone.cloud.api.network.Packet;
import net.redstone.cloud.api.permission.PermissionGroup;
import net.redstone.cloud.api.permission.PermissionUser;

import java.util.List;

public class PermissionUpdatePacket extends Packet {
    private List<PermissionGroup> groups;
    private List<PermissionUser> users;

    public PermissionUpdatePacket(List<PermissionGroup> groups, List<PermissionUser> users) {
        this.groups = groups;
        this.users = users;
    }

    public List<PermissionGroup> getGroups() { return groups; }
    public List<PermissionUser> getUsers() { return users; }
}

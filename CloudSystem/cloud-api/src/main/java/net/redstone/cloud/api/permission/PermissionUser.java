package net.redstone.cloud.api.permission;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class PermissionUser implements Serializable {
    private String name;
    private String group;
    private List<String> permissions;

    public PermissionUser(String name, String group) {
        this.name = name;
        this.group = group;
        this.permissions = new ArrayList<>();
    }

    public String getName() { return name; }
    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }
    public List<String> getPermissions() { return permissions; }
}

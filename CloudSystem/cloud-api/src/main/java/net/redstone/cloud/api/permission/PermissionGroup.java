package net.redstone.cloud.api.permission;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class PermissionGroup implements Serializable {
    private String name;
    private String prefix;
    private int weight;
    private boolean isDefault;
    private List<String> permissions;

    public PermissionGroup(String name, String prefix, int weight, boolean isDefault) {
        this.name = name;
        this.prefix = prefix;
        this.weight = weight;
        this.isDefault = isDefault;
        this.permissions = new ArrayList<>();
    }

    public String getName() { return name; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
    public int getWeight() { return weight; }
    public void setWeight(int weight) { this.weight = weight; }
    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
    public List<String> getPermissions() { return permissions; }
}

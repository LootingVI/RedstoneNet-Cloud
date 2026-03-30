package net.redstone.cloud.node.permission;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.redstone.cloud.api.permission.PermissionGroup;
import net.redstone.cloud.api.permission.PermissionUser;
import net.redstone.cloud.api.network.packet.PermissionUpdatePacket;
import net.redstone.cloud.node.CloudNode;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class PermissionManager {
    private final File file = new File("local/permissions.json");
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private List<PermissionGroup> groups = new CopyOnWriteArrayList<>();
    private List<PermissionUser> users = new CopyOnWriteArrayList<>();

    public PermissionManager() {
        load();
    }

    public void load() {
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            PermissionGroup defaultGroup = new PermissionGroup("default", "§7Player | ", 10, true);
            defaultGroup.getPermissions().add("cloud.join");
            PermissionGroup adminGroup = new PermissionGroup("admin", "§cAdmin | ", 100, false);
            adminGroup.getPermissions().add("*");
            adminGroup.getPermissions().add("redstonecloud.admin");

            groups.add(defaultGroup);
            groups.add(adminGroup);
            save();
            return;
        }

        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<ConfigFormat>(){}.getType();
            ConfigFormat format = gson.fromJson(reader, type);
            if (format != null) {
                if (format.groups != null) {
                    groups.clear();
                    groups.addAll(format.groups);
                }
                if (format.users != null) {
                    users.clear();
                    users.addAll(format.users);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void save() {
        try (FileWriter writer = new FileWriter(file)) {
            ConfigFormat format = new ConfigFormat();
            format.groups = new ArrayList<>(groups);
            format.users = new ArrayList<>(users);
            gson.toJson(format, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public PermissionGroup getGroup(String name) {
        return groups.stream().filter(g -> g.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }
    
    public PermissionUser getUser(String name) {
        return users.stream().filter(u -> u.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }
    
    public void createUser(String name, String group) {
        if (getUser(name) == null) {
            users.add(new PermissionUser(name, group));
            save();
            broadcastUpdate();
        }
    }

    public void broadcastUpdate() {
        PermissionUpdatePacket packet = new PermissionUpdatePacket(
            new ArrayList<>(groups), new ArrayList<>(users)
        );
        CloudNode.getInstance().getNetServer().broadcastPacket(packet);
    }

    public List<PermissionGroup> getGroups() { return groups; }
    public List<PermissionUser> getUsers() { return users; }

    private static class ConfigFormat {
        List<PermissionGroup> groups;
        List<PermissionUser> users;
    }
}

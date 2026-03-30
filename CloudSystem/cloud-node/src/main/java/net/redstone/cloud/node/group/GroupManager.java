package net.redstone.cloud.node.group;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.redstone.cloud.node.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class GroupManager {

    private final Map<String, Group> groups = new HashMap<>();
    private final File groupsFile = new File("groups.json");
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public GroupManager() {
        loadGroups();
    }

    public void loadGroups() {
        if (!groupsFile.exists()) return;
        try (FileReader reader = new FileReader(groupsFile)) {
            Type type = new TypeToken<Map<String, Group>>(){}.getType();
            Map<String, Group> loaded = gson.fromJson(reader, type);
            if (loaded != null) groups.putAll(loaded);
            Logger.info("Loaded " + groups.size() + " groups from config.");
        } catch (Exception e) {
            Logger.error("Error loading groups.json: " + e.getMessage());
        }
    }

    public void saveGroups() {
        try (FileWriter writer = new FileWriter(groupsFile)) {
            gson.toJson(groups, writer);
        } catch (Exception e) {
            Logger.error("Error saving groups.json: " + e.getMessage());
        }
    }

    public void createGroup(String name, int memory, boolean staticService, boolean proxy, String softwareFile, boolean bedrockSupport) {
        createGroup(name, memory, staticService, proxy, softwareFile, 0, bedrockSupport);
    }

    public void createGroup(String name, int memory, boolean staticService, boolean proxy, String softwareFile, int startPort, boolean bedrockSupport) {
        createGroup(name, memory, staticService, proxy, softwareFile, startPort, staticService ? 1 : 0, bedrockSupport);
    }

    public void createGroup(String name, int memory, boolean staticService, boolean proxy, String softwareFile, int startPort, int minOnline, boolean bedrockSupport) {
        if (groups.containsKey(name.toLowerCase())) {
            Logger.warn("A group with this name already exists!");
            return;
        }
        Group g = new Group(name, memory, staticService, proxy, softwareFile, startPort, bedrockSupport);
        g.setMinOnline(minOnline);
        groups.put(name.toLowerCase(), g);
        saveGroups();
        Logger.success("Group '" + name + "' created! (port=" + (startPort == 0 ? "dynamic" : startPort) + ", static=" + staticService + ", bedrock=" + bedrockSupport + ")");
    }

    public void deleteGroup(String name) {
        if (groups.remove(name.toLowerCase()) != null) {
            saveGroups();
            Logger.success("Group " + name + " deleted!");
        } else {
            Logger.warn("Group " + name + " not found!");
        }
    }

    public Group getGroup(String name) {
        return groups.get(name.toLowerCase());
    }

    public void listGroups() {
        Logger.info("Available groups (" + groups.size() + "):");
        for (Group g : groups.values()) {
            Logger.info("- " + g.getName() + " (" + g.getMemory() + "MB, Proxy: " + g.isProxy() + ", Bedrock: " + g.hasBedrockSupport() + ")");
        }
    }

    public Map<String, Group> getGroups() {
        return groups;
    }
}

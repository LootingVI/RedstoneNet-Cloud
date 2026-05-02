package net.redstone.cloud.api;

import net.redstone.cloud.api.player.CloudPlayer;
import java.util.List;

public abstract class CloudAPI {
    private static CloudAPI instance;

    public static CloudAPI getInstance() {
        return instance;
    }

    public static void setInstance(CloudAPI cloudAPI) {
        instance = cloudAPI;
    }

    /** Start one or more instances of a server group. */
    public abstract void startServer(String groupName, int count);

    /** Stop a specific running server by name. */
    public abstract void stopServer(String serverName);

    /** Send a message to all online players across the network. */
    public abstract void broadcastMessage(String message);

    /** Get all players currently online on the network. */
    public abstract List<CloudPlayer> getOnlinePlayers();

    /** Find a player by name. Returns null if not online. */
    public abstract CloudPlayer getPlayer(String name);

    /** Execute a cloud command remotely (as if typed in the cloud console). */
    public abstract void dispatchCloudCommand(String sender, String commandLine);

    /** Transfer a player to another server on the network. */
    public abstract void sendPlayer(String playerName, String targetServer);

    /** Get the name of this server (e.g. "Lobby-1"). */
    public abstract String getThisServerName();

    /** Get the total online player count across the network. */
    public abstract int getOnlineCount();

    /** Check if a specific server is currently online. */
    public abstract boolean isServerOnline(String serverName);

    /** Check if the network is in maintenance mode. */
    public abstract boolean isMaintenance();

    /** Get a list of all existing server groups. */
    public abstract List<String> getOnlineGroups();

    /** Get a list of all active server names. */
    public abstract List<String> getOnlineServers();
}

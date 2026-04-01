package net.redstone.cloud.node.process;

import net.redstone.cloud.node.CloudNode;
import net.redstone.cloud.node.group.Group;
import net.redstone.cloud.node.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.redstone.cloud.node.group.Group;

public class ServerManager {

    private final List<CloudServerProcess> activeProcesses = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextPort = new AtomicInteger(40000);
    private final ConcurrentLinkedDeque<Integer> freePorts = new ConcurrentLinkedDeque<>();
    private final Map<String, Long> scaleUpCooldown = new ConcurrentHashMap<>();
    private final Map<String, Long> scaleDownCooldown = new ConcurrentHashMap<>();

    // Player count per server (serverName → count)
    private final Map<String, Integer> playerCounts = new ConcurrentHashMap<>();

    public List<CloudServerProcess> getActiveProcesses() {
        return activeProcesses;
    }

    // Auto-start static/proxy servers on cloud startup
    public void startStaticServers() {
        Logger.info("Checking static servers and minimum instances...");
        for (Group group : CloudNode.getInstance().getGroupManager().getGroups().values()) {
            if (group.getMinOnline() > 0) {
                long running = activeProcesses.stream()
                        .filter(p -> p.getServerName().startsWith(group.getName() + "-"))
                        .count();
                int toStart = group.getMinOnline() - (int) running;
                if (toStart > 0) {
                    Logger.info("Auto-starting group '" + group.getName() + "': " + toStart + " instance(s)");
                    startServer(group.getName(), toStart);
                }
            }
        }
    }

    public void startServer(String groupName, int amount) {
        Group group = CloudNode.getInstance().getGroupManager().getGroup(groupName);
        if (group == null) {
            Logger.error("Group '" + groupName + "' not found!");
            return;
        }
        for (int i = 0; i < amount; i++) {
            int id = getNextId(group);
            int port;
            if (group.getStartPort() > 0) {
                port = group.getStartPort() + id - 1;
            } else {
                Integer recycled = freePorts.pollFirst();
                port = recycled != null ? recycled : nextPort.getAndIncrement();
            }
            CloudServerProcess process = new CloudServerProcess(group, id, port);
            activeProcesses.add(process);
            process.start();
        }
    }

    public void stopServer(String serverName) {
        CloudServerProcess target = getProcess(serverName);
        if (target != null) {
            if (target.getGroup().getStartPort() <= 0) freePorts.addLast(target.getPort());
            target.stop();
            activeProcesses.remove(target);
            playerCounts.remove(serverName);
        } else {
            Logger.warn("Server '" + serverName + "' not found or not running.");
        }
    }

    public void stopAll() {
        for (CloudServerProcess process : new ArrayList<>(activeProcesses)) {
            process.stop();
        }
        activeProcesses.clear();
        playerCounts.clear();
    }

    public CloudServerProcess getProcess(String name) {
        return activeProcesses.stream()
                .filter(p -> p.getServerName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    // Called by CloudServerProcess when restart limit is reached
    public void onProcessCrashed(CloudServerProcess process) {
        Logger.error("Server " + process.getServerName() + " has permanently failed!");
        if (process.getGroup().getStartPort() <= 0) freePorts.addLast(process.getPort());
        activeProcesses.remove(process);
        playerCounts.remove(process.getServerName());

            // Maintain minimum instances: start a replacement instead of the crashed one
        Group group = process.getGroup();
        if (group != null && group.getMinOnline() > 0) {
            long running = activeProcesses.stream()
                    .filter(p -> p.getServerName().startsWith(group.getName() + "-"))
                    .count();
            if (running < group.getMinOnline()) {
                Logger.warn("Starting replacement instance for group '" + group.getName() + "'...");
                startServer(group.getName(), 1);
            }
        }
    }

    // Update player count
    public void updatePlayerCount(String serverName, int count) {
        playerCounts.put(serverName, count);
        CloudServerProcess proc = getProcess(serverName);
        if (proc != null) {
            proc.setPlayerCount(count);
            String groupName = serverName.contains("-")
                ? serverName.substring(0, serverName.lastIndexOf("-"))
                : serverName;
            checkAutoScale(groupName);
        }
    }

    public void checkAutoScale(String groupName) {
        Group group = CloudNode.getInstance().getGroupManager().getGroup(groupName);
        if (group == null || !group.isAutoScaleEnabled()) return;

        List<CloudServerProcess> procs = activeProcesses.stream()
                .filter(p -> p.getServerName().startsWith(group.getName() + "-") && p.isAlive())
                .collect(Collectors.toList());
        int running = procs.size();
        int maxP = group.getMaxPlayers() > 0 ? group.getMaxPlayers() : 100;
        long now = System.currentTimeMillis();

        // --- Scale UP ---
        boolean anyOverloaded = procs.stream()
                .anyMatch(p -> (p.getPlayerCount() * 100.0 / maxP) >= group.getAutoScaleThreshold());
        long lastUp = scaleUpCooldown.getOrDefault(groupName, 0L);
        if (anyOverloaded && running < group.getMaxInstances() && now - lastUp > 30_000L) {
            Logger.info("[AutoScale] " + groupName + ": scaling UP (" + running + "/" + group.getMaxInstances() + " running, threshold " + group.getAutoScaleThreshold() + "%)");
            scaleUpCooldown.put(groupName, now);
            startServer(groupName, 1);
            return;
        }

        // --- Scale DOWN ---
        if (running > Math.max(group.getMinOnline(), 1)) {
            long lastDown = scaleDownCooldown.getOrDefault(groupName, 0L);
            if (now - lastDown > 60_000L) {
                procs.stream().filter(p -> p.getPlayerCount() == 0).findFirst().ifPresent(p -> {
                    Logger.info("[AutoScale] " + groupName + ": scaling DOWN (stopping idle " + p.getServerName() + ")");
                    scaleDownCooldown.put(groupName, now);
                    stopServer(p.getServerName());
                });
            }
        }
    }

    public int getPlayerCount(String serverName) {
        return playerCounts.getOrDefault(serverName, 0);
    }

    public int getTotalPlayerCount() {
        return playerCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public void listServers() {
        Logger.info("Active Servers (" + activeProcesses.size() + ") | Total Players: " + getTotalPlayerCount());
        for (CloudServerProcess process : activeProcesses) {
            Logger.info("  - " + process.getServerName()
                    + " | Port: " + process.getPort()
                    + " | Players: " + process.getPlayerCount()
                    + " | Restarts: " + process.getRestartCount()
                    + (process.isAlive() ? " \u001B[32m[ONLINE]\u001B[0m" : " \u001B[31m[OFFLINE]\u001B[0m"));
        }
    }

    private int getNextId(Group group) {
        int max = 0;
        for (CloudServerProcess p : activeProcesses) {
            if (p.getServerName().startsWith(group.getName() + "-")) {
                String[] parts = p.getServerName().split("-");
                try {
                    int id = Integer.parseInt(parts[parts.length - 1]);
                    if (id > max) max = id;
                } catch (Exception ignored) {}
            }
        }
        return max + 1;
    }
}

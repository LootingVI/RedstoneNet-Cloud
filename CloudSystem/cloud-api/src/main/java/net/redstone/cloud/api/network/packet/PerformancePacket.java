package net.redstone.cloud.api.network.packet;

import net.redstone.cloud.api.network.Packet;

public class PerformancePacket extends Packet {
    private final String serverName;
    private final double tps;
    private final double cpuUsage; 
    private final long usedMemoryMB;
    private final long maxMemoryMB;

    public PerformancePacket(String serverName, double tps, double cpuUsage, long usedMemoryMB, long maxMemoryMB) {
        this.serverName = serverName;
        this.tps = tps;
        this.cpuUsage = cpuUsage;
        this.usedMemoryMB = usedMemoryMB;
        this.maxMemoryMB = maxMemoryMB;
    }

    public String getServerName() { return serverName; }
    public double getTps() { return tps; }
    public double getCpuUsage() { return cpuUsage; }
    public long getUsedMemoryMB() { return usedMemoryMB; }
    public long getMaxMemoryMB() { return maxMemoryMB; }
}

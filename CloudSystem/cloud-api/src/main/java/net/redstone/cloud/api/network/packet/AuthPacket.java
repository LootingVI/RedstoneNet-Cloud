package net.redstone.cloud.api.network.packet;

import net.redstone.cloud.api.network.Packet;

/**
 * Dieses Paket wird vom Plugin (Minecraft-Server) an den Node gesendet,
 * um sich direkt nach dem Start am Node zu authentifizieren.
 */
public class AuthPacket extends Packet {

    private final String serverName;
    private final String authKey;
    private final int port;
    private final boolean proxy;

    public AuthPacket(String serverName, String authKey, int port, boolean proxy) {
        this.serverName = serverName;
        this.authKey = authKey;
        this.port = port;
        this.proxy = proxy;
    }

    public String getServerName() {
        return serverName;
    }

    public String getAuthKey() {
        return authKey;
    }

    public int getPort() {
        return port;
    }

    public boolean isProxy() {
        return proxy;
    }
}

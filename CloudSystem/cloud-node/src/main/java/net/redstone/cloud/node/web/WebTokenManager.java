package net.redstone.cloud.node.web;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class WebTokenManager {
    public static class TokenInfo {
        public long expiresAt;
        public String discordId = "CONSOLE";
        public String discordName = "Console";
        public String discordAvatar = "";
        public boolean fullAccess = true;
        // groupName -> List of Permissions ("START", "STOP", "CONSOLE")
        public Map<String, List<String>> groupPermissions = new HashMap<>();
    }
    
    private final Map<String, TokenInfo> tokens = new HashMap<>();

    public String generateToken() {
        return generateToken("CONSOLE", "Console", "", true, new HashMap<>());
    }

    public String generateToken(String discordId, String name, String avatar, boolean fullAccess, Map<String, List<String>> groupPermissions) {
        String tokenStr = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        TokenInfo info = new TokenInfo();
        info.expiresAt = System.currentTimeMillis() + (1000 * 60 * 60 * 24); // 24 Stunden by default
        info.discordId = discordId;
        info.discordName = name;
        info.discordAvatar = avatar;
        info.fullAccess = fullAccess;
        if (groupPermissions != null) info.groupPermissions.putAll(groupPermissions);
        tokens.put(tokenStr, info);
        return tokenStr;
    }

    public boolean validateToken(String tokenStr) {
        return getTokenInfo(tokenStr) != null;
    }

    public TokenInfo getTokenInfo(String tokenStr) {
        if (tokenStr == null) return null;
        TokenInfo info = tokens.get(tokenStr);
        if (info == null) return null;
        if (System.currentTimeMillis() > info.expiresAt) {
            tokens.remove(tokenStr);
            return null;
        }
        return info;
    }
    
    public TokenInfo getInfoByDiscordId(String discordId) {
        for (TokenInfo info : tokens.values()) {
            if (info.discordId.equals(discordId) && System.currentTimeMillis() < info.expiresAt) {
                return info;
            }
        }
        return null;
    }

    public void revokeByDiscordId(String discordId) {
        tokens.entrySet().removeIf(e -> e.getValue().discordId.equals(discordId));
    }
}

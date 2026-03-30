package net.redstone.cloud.node.web;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class WebTokenManager {
    private static class TokenInfo {
        long expiresAt;
    }
    private final Map<String, TokenInfo> tokens = new HashMap<>();

    public String generateToken() {
        String tokenStr = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        TokenInfo info = new TokenInfo();
        info.expiresAt = System.currentTimeMillis() + (1000 * 60 * 60); // 1 Stunde
        tokens.put(tokenStr, info);
        return tokenStr;
    }

    public boolean validateToken(String tokenStr) {
        if (tokenStr == null) return false;
        TokenInfo info = tokens.get(tokenStr);
        if (info == null) return false;
        if (System.currentTimeMillis() > info.expiresAt) {
            tokens.remove(tokenStr);
            return false;
        }
        return true;
    }
}

package net.redstone.cloud.api.command;

public interface CommandSender {
    String getName();

    void sendMessage(String message);

    boolean hasPermission(String permission);
}

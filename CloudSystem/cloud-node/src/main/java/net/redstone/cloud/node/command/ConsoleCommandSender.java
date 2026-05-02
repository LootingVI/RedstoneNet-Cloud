package net.redstone.cloud.node.command;

import net.redstone.cloud.api.command.CommandSender;
import net.redstone.cloud.node.logging.Logger;

public class ConsoleCommandSender implements CommandSender {

    @Override
    public String getName() {
        return "Console";
    }

    @Override
    public void sendMessage(String message) {
        Logger.info(message);
    }

    @Override
    public boolean hasPermission(String permission) {
        return true; // Console has all permissions
    }
}

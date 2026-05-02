package net.redstone.cloud.node.command;

import net.redstone.cloud.api.command.Command;
import net.redstone.cloud.api.command.CommandSender;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CommandManager {

    private final List<Command> commands = new ArrayList<>();

    public void registerCommand(Command command) {
        commands.add(command);
    }

    public void unregisterCommand(Command command) {
        commands.remove(command);
    }

    public List<Command> getCommands() {
        return commands;
    }

    public boolean dispatchCommand(CommandSender sender, String line) {
        if (line == null || line.trim().isEmpty()) return false;

        String[] parts = line.split(" ");
        String name = parts[0].toLowerCase();
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);

        for (Command command : commands) {
            if (command.getName().toLowerCase().equals(name)) {
                command.execute(sender, args);
                return true;
            }
            for (String alias : command.getAliases()) {
                if (alias.toLowerCase().equals(name)) {
                    command.execute(sender, args);
                    return true;
                }
            }
        }
        return false;
    }
}

package net.redstone.cloud.api.event.node;

import net.redstone.cloud.api.event.Event;

public class CloudConsoleCommandExecuteEvent extends Event {

    private final String commandLine;

    public CloudConsoleCommandExecuteEvent(String commandLine) {
        this.commandLine = commandLine;
    }

    public String getCommandLine() {
        return commandLine;
    }
}

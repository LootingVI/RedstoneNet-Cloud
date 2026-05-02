package net.redstone.cloud.testplugin;

import net.redstone.cloud.api.command.Command;
import net.redstone.cloud.api.command.CommandSender;
import net.redstone.cloud.api.event.CloudEventHandler;
import net.redstone.cloud.api.event.Listener;
import net.redstone.cloud.api.event.node.CloudConsoleCommandExecuteEvent;
import net.redstone.cloud.api.event.server.CloudServerStartEvent;
import net.redstone.cloud.api.plugin.CloudPlugin;
import net.redstone.cloud.node.CloudNode;
import net.redstone.cloud.node.logging.Logger;

public class TestPlugin extends CloudPlugin implements Listener {

    @Override
    public void onLoad() {
        Logger.info("[TestPlugin] onLoad() called! Plugin is being loaded into memory.");
    }

    @Override
    public void onEnable() {
        Logger.success("[TestPlugin] onEnable() called! Plugin " + getDescription().getName() + " v" + getDescription().getVersion() + " by " + getDescription().getAuthor() + " is now active.");

        // Registering a Cloud Command 
        CloudNode.getInstance().getCommandManager().registerCommand(new Command("testcmd", "A simple test command", "test") {
            @Override
            public void execute(CommandSender sender, String[] args) {
                sender.sendMessage("Hello " + sender.getName() + " from the Cloud Test Plugin!");
                sender.sendMessage("You provided " + args.length + " arguments: " + String.join(" ", args));
            }
        });

        // Registering Events
        CloudNode.getInstance().getEventManager().registerListeners(this);
    }

    @CloudEventHandler
    public void onServerStart(CloudServerStartEvent event) {
        Logger.info("[TestPlugin] Intercepted event: Server " + event.getServerName() + " started on port " + event.getPort() + "!");
    }

    @CloudEventHandler
    public void onConsoleCommand(CloudConsoleCommandExecuteEvent event) {
        Logger.info("[TestPlugin] Intercepted event: Console executed command -> " + event.getCommandLine());
    }

    @Override
    public void onDisable() {
        // Unregister listeners
        CloudNode.getInstance().getEventManager().unregisterListeners(this);
        Logger.info("[TestPlugin] onDisable() called! Shutting down gracefully...");
    }
}

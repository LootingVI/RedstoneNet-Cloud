package net.redstone.cloud.api.plugin;

public abstract class CloudPlugin {

    private CloudPluginDescription description;

    public final void init(CloudPluginDescription description) {
        if (this.description != null) {
            throw new IllegalStateException("Plugin already initialized!");
        }
        this.description = description;
    }

    public CloudPluginDescription getDescription() {
        return description;
    }

    public void onLoad() {
    }

    public void onEnable() {
    }

    public void onDisable() {
    }

}

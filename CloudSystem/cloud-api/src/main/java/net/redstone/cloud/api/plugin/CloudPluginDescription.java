package net.redstone.cloud.api.plugin;

public class CloudPluginDescription {

    private String name;
    private String version;
    private String author;
    private String main;

    public CloudPluginDescription(String name, String version, String author, String main) {
        this.name = name;
        this.version = version;
        this.author = author;
        this.main = main;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public String getAuthor() {
        return author;
    }

    public String getMain() {
        return main;
    }
}

package net.redstone.cloud.node.group;

public class Group {

    private final String name;
    private final int memory;
    private final boolean staticService;
    private final boolean proxy;
    private final String softwareFile;
    private int startPort; // 0 means random/dynamic port
    private int minOnline; 
    private int maxRestarts; 
    private String templateName;
    private boolean bedrockSupport;
    private boolean autoScaleEnabled = false;
    private int autoScaleThreshold = 80;
    private int maxInstances = 5;
    private int maxPlayers = 100; // max players per instance (for scale calculation)
    private String resourcePack = "";
    private boolean forceResourcePack = false;

    public Group(String name, int memory, boolean staticService, boolean proxy, String softwareFile, boolean bedrockSupport) {
        this(name, memory, staticService, proxy, softwareFile, 0, bedrockSupport);
    }

    public Group(String name, int memory, boolean staticService, boolean proxy, String softwareFile, int startPort, boolean bedrockSupport) {
        this.name = name;
        this.memory = memory;
        this.staticService = staticService;
        this.proxy = proxy;
        this.softwareFile = softwareFile;
        this.startPort = startPort;
        this.minOnline = staticService ? 1 : 0;
        this.maxRestarts = 3;
        this.bedrockSupport = bedrockSupport;
    }

    public String getName() { return name; }
    public int getMemory() { return memory; }
    public boolean isStaticService() { return staticService; }
    public boolean isProxy() { return proxy; }
    public String getSoftwareFile() { return softwareFile; }
    public int getStartPort() { return startPort; }
    public void setStartPort(int startPort) { this.startPort = startPort; }
    public int getMinOnline() { return minOnline; }
    public void setMinOnline(int minOnline) { this.minOnline = minOnline; }
    public int getMaxRestarts() { return maxRestarts; }
    public void setMaxRestarts(int maxRestarts) { this.maxRestarts = maxRestarts; }
    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }
    public boolean hasBedrockSupport() { return bedrockSupport; }
    public void setBedrockSupport(boolean bedrockSupport) { this.bedrockSupport = bedrockSupport; }
    public boolean isAutoScaleEnabled() { return autoScaleEnabled; }
    public void setAutoScaleEnabled(boolean autoScaleEnabled) { this.autoScaleEnabled = autoScaleEnabled; }
    public int getAutoScaleThreshold() { return autoScaleThreshold; }
    public void setAutoScaleThreshold(int autoScaleThreshold) { this.autoScaleThreshold = autoScaleThreshold; }
    public int getMaxInstances() { return maxInstances; }
    public void setMaxInstances(int maxInstances) { this.maxInstances = maxInstances; }
    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }
    public String getResourcePack() { return resourcePack; }
    public void setResourcePack(String resourcePack) { this.resourcePack = resourcePack; }
    public boolean isForceResourcePack() { return forceResourcePack; }
    public void setForceResourcePack(boolean forceResourcePack) { this.forceResourcePack = forceResourcePack; }
}

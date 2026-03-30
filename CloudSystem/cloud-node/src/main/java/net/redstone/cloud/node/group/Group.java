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
}

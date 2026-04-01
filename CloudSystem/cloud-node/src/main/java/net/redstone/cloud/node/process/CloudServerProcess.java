package net.redstone.cloud.node.process;

import net.redstone.cloud.node.group.Group;
import net.redstone.cloud.node.logging.Logger;
import net.redstone.cloud.node.CloudNode;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.net.URLEncoder;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class CloudServerProcess {

    private final Group group;
    private final int serverId;
    private final int port;
    private final String serverName;
    private Process process;
    private final File workingDirectory;
    private final ConcurrentLinkedQueue<String> logCache = new ConcurrentLinkedQueue<>();
    private boolean screenEnabled = false;
    private BufferedWriter processOutput;

    private final AtomicBoolean shutdownIntentional = new AtomicBoolean(false);
    private final AtomicInteger restartCount = new AtomicInteger(0);
    private final AtomicInteger playerCount = new AtomicInteger(0);
    private double currentTps = 20.0;
    private double cpuUsage = 0.0;
    private long usedMemoryMB = 0;
    private long maxMemoryMB = 0;

    public CloudServerProcess(Group group, int serverId, int port) {
        this.group = group;
        this.serverId = serverId;
        this.port = port;
        this.serverName = group.getName() + "-" + serverId;

        if (group.isStaticService()) {
            this.workingDirectory = new File("local/" + serverName);
        } else {
            this.workingDirectory = new File("temp/" + serverName);
        }
    }

    public void start() {
        try {
            Logger.info("Preparing " + serverName + "... (Port: " + port + ")");
            prepareDirectory();

            ProcessBuilder builder = new ProcessBuilder(
                    "java",
                    "-Xmx" + group.getMemory() + "M",
                    "-Xms" + group.getMemory() + "M",
                    "-jar",
                    "server.jar",
                    "nogui",
                    "--port", String.valueOf(port));
            builder.directory(workingDirectory);
            builder.redirectErrorStream(true);

            this.process = builder.start();
            this.processOutput = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

            new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (logCache.size() >= 150)
                            logCache.poll();
                        logCache.add(line);
                        if (screenEnabled) {
                            Logger
                                    .raw("\u001B[90m[" + serverName + "] \u001B[0m" + line);
                        }
                    }
                } catch (Exception ignored) {
                }
                onProcessExit();
            }, "Log-" + serverName).start();

            Logger.success("Server " + serverName + " started! (Port: " + port + ")");

        } catch (Exception e) {
            Logger.error("Could not start " + serverName + ": " + e.getMessage());
        }
    }

    private void onProcessExit() {
        if (shutdownIntentional.get()) {
            Logger.info(serverName + " was shut down gracefully.");
            return;
        }

        int exitCode = process != null ? process.exitValue() : -1;
        
        // Crash detection and crash reporter
        if (exitCode != 0 && exitCode != 130 && exitCode != 143) { // 130 = SIGINT/Ctrl+C, 143 = SIGTERM
            Logger.warn(serverName + " terminated unexpectedly! (Exit Code: " + exitCode + ")");
            try {
                File crashDir = new File("local/crash-reports");
                crashDir.mkdirs();
                File crashFile = new File(crashDir, serverName + "-crash-" + System.currentTimeMillis() + ".log");
                Files.write(crashFile.toPath(), logCache);
                Logger.info(">>> Crash log saved to: " + crashFile.getPath());
            } catch (Exception e) {
                Logger.error("Could not save crash log for " + serverName + ": " + e.getMessage());
            }
        } else {
            Logger.warn(serverName + " stopped. (Exit Code: " + exitCode + ")");
        }

        if (restartCount.get() >= group.getMaxRestarts()) {
            Logger.error(serverName + " reached the restart limit (" + group.getMaxRestarts()
                    + "). No further restarts.");
            CloudNode.getInstance().getServerManager().onProcessCrashed(this);
            if (!group.isStaticService()) {
                try { deleteDirectory(workingDirectory); } catch (Exception ignored) {}
            } else {
                createBackup(false);
            }
            return;
        }

        int attempt = restartCount.incrementAndGet();
        Logger.warn("Restarting " + serverName + "... (Attempt " + attempt + "/" + group.getMaxRestarts() + ")");
        logCache.add("[CLOUD] >>> Auto-Restart " + attempt + "/" + group.getMaxRestarts() + " <<<");

        new Thread(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            start();
        }, "Restart-" + serverName).start();
    }

    private void prepareDirectory() throws IOException {
        if (!group.isStaticService() && workingDirectory.exists()) {
            deleteDirectory(workingDirectory);
        }
        workingDirectory.mkdirs();
        Logger.info("[" + serverName + "] Home: " + workingDirectory.getAbsolutePath() + " (Static: "
                + group.isStaticService() + ")");

        File templateDir = new File("templates/" + group.getName());
        if (templateDir.exists()) {
            if (group.isStaticService()) {
                copyMissingFiles(templateDir.toPath(), workingDirectory.toPath());
            } else {
                copyDirectory(templateDir.toPath(), workingDirectory.toPath());
            }
        } else {
            templateDir.mkdirs();
        }

        File assignedSoftware = new File("local/software/" + group.getSoftwareFile());
        if (assignedSoftware.exists()) {
            Files.copy(assignedSoftware.toPath(), new File(workingDirectory, "server.jar").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        } else {
            Logger.warn("[" + serverName + "] Software '" + group.getSoftwareFile() + "' not found!");
        }

        if (group.hasBedrockSupport()) {
            String platform = group.isProxy() ? "velocity" : "spigot";
            File pluginsDir = new File(workingDirectory, "plugins");
            pluginsDir.mkdirs();
            
            // GeyserMC - Nur auf dem Proxy installieren!
            if (group.isProxy()) {
                File geyserFile = new File("local/software/Geyser-" + platform + ".jar");
                if (!geyserFile.exists()) {
                    Logger.info("[" + serverName + "] Auto-downloading GeyserMC for " + platform + "...");
                    try {
                        SoftwareDownloader.downloadFile("https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/" + platform, geyserFile.getAbsolutePath());
                    } catch (Exception e) {
                        Logger.error("Error downloading GeyserMC: " + e.getMessage());
                    }
                }
                if (geyserFile.exists()) {
                    Files.copy(geyserFile.toPath(), new File(pluginsDir, "Geyser-" + platform + ".jar").toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            
            // Floodgate - goes on Proxy AND Spigot servers
            File floodgateFile = new File("local/software/Floodgate-" + platform + ".jar");
            if (!floodgateFile.exists()) {
                Logger.info("[" + serverName + "] Auto-downloading Floodgate for " + platform + "...");
                try {
                    SoftwareDownloader.downloadFile("https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/" + platform, floodgateFile.getAbsolutePath());
                } catch (Exception e) {
                    Logger.error("Error downloading Floodgate: " + e.getMessage());
                }
            }
            if (floodgateFile.exists()) {
                Files.copy(floodgateFile.toPath(), new File(pluginsDir, "Floodgate-" + platform + ".jar").toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            
            // key.pem kopieren
            File globalFloodgateKey = new File("local/global_floodgate_key.pem");
            File floodgateConfigDir = new File(pluginsDir, "floodgate");
            if (globalFloodgateKey.exists()) {
                floodgateConfigDir.mkdirs();
                try {
                    Files.copy(globalFloodgateKey.toPath(), new File(floodgateConfigDir, "key.pem").toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    Logger.error("Error copying Floodgate key.pem: " + e.getMessage());
                }
            } else if (group.isProxy()) {
                Logger.warn("[" + serverName + "] key.pem missing! Cloud is waiting for Floodgate to generate the key on proxy start...");
                new Thread(() -> {
                    File generatedKey = new File(floodgateConfigDir, "key.pem");
                    // Wait up to 30 seconds for the file
                    for (int i = 0; i < 30; i++) {
                        if (generatedKey.exists()) {
                            try {
                                Files.copy(generatedKey.toPath(), globalFloodgateKey.toPath());
                                Logger.success("[" + serverName + "] Floodgate key.pem successfully generated by proxy and saved globally!");
                                break;
                            } catch (Exception e) {}
                        }
                        try { Thread.sleep(1000); } catch (InterruptedException e) {}
                    }
                }).start();
            } else {
                Logger.warn("[" + serverName + "] Bedrock support enabled, but 'local/global_floodgate_key.pem' missing! Please start the Bedrock proxy first so the key can be generated.");
            }
        }

        File globalSecret = new File("local/global_forwarding.secret");
        if (!globalSecret.exists()) {
            globalSecret.getParentFile().mkdirs();
            Files.writeString(globalSecret.toPath(), "RedstoneNet-Cloud-Secret-" + System.currentTimeMillis());
        }
        String secretKey = Files.readString(globalSecret.toPath()).trim();

        if (group.isProxy()) {
            Files.writeString(new File(workingDirectory, "forwarding.secret").toPath(), secretKey);
            File vConfig = new File(workingDirectory, "velocity.toml");
            if (!vConfig.exists()) {
                Files.writeString(vConfig.toPath(), 
                    "bind = \"0.0.0.0:" + port + "\"\n" +
                    "player-info-forwarding-mode = \"modern\"\n" +
                    "forwarding-secret = \"" + secretKey + "\"\n" +
                    "online-mode = true\n"
                );
            }
        } else {
            Files.writeString(new File(workingDirectory, "eula.txt").toPath(), "eula=true");
            File propsFile = new File(workingDirectory, "server.properties");
            Properties props = new Properties();
            if (propsFile.exists()) {
                try (FileInputStream in = new FileInputStream(propsFile)) {
                    props.load(in);
                }
            }
            props.setProperty("server-port", String.valueOf(port));
            props.setProperty("online-mode", "false");
            props.setProperty("enforce-secure-profile", "false");
            props.setProperty("network-compression-threshold", "-1");
            try (FileOutputStream out = new FileOutputStream(propsFile)) {
                props.store(out, "Cloud Instance");
            }

            File configDir = new File(workingDirectory, "config");
            configDir.mkdirs();
            File paperGlobal = new File(configDir, "paper-global.yml");
            if (!paperGlobal.exists()) {
                Files.writeString(paperGlobal.toPath(),
                        "proxies:\n  bungee-cord:\n    online-mode: false\n  velocity:\n    enabled: true\n    online-mode: true\n    secret: \""
                                + secretKey + "\"\n");
            }
        }

        File pluginFolder = new File(workingDirectory, "plugins");
        pluginFolder.mkdirs();

        // 1. Global Plugins
        File globalPlugins = new File("local/global/plugins");
        if (globalPlugins.exists() && globalPlugins.isDirectory()) {
            File[] files = globalPlugins.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().endsWith(".jar")) {
                        Logger.info("[" + serverName + "] Sync Global Plugin: " + f.getName());
                        Files.copy(f.toPath(), new File(pluginFolder, f.getName()).toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }

        // 2. Cloud Plugin
        File pluginJar = new File("local/cloud-plugin.jar");
        if (pluginJar.exists()) {
            Files.copy(pluginJar.toPath(), new File(pluginFolder, "cloud-plugin.jar").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        // 3. Log all detected plugins for debugging
        File[] allPlugins = pluginFolder.listFiles();
        if (allPlugins != null) {
            for (File f : allPlugins) {
                if (f.getName().endsWith(".jar")) {
                    Logger.info("[" + serverName + "] Active Plugin: " + f.getName());
                }
            }
        }

        String resourcePackUrl = "";
        String resourcePackHash = "";
        if (!group.isProxy() && group.getResourcePack() != null && !group.getResourcePack().isEmpty()) {
            File pack = new File("local/resourcepacks", group.getResourcePack());
            if (pack.exists()) {
                try {
                    String encodedName = URLEncoder.encode(group.getResourcePack(), "UTF-8").replace("+", "%20");
                    resourcePackUrl = "http://" + CloudNode.getInstance().getHost() + ":3030/packs/" + encodedName;
                    MessageDigest digest = MessageDigest.getInstance("SHA-1");
                    byte[] bytes = Files.readAllBytes(pack.toPath());
                    byte[] hashed = digest.digest(bytes);
                    StringBuilder sb = new StringBuilder();
                    for (byte b : hashed) sb.append(String.format("%02x", b));
                    resourcePackHash = sb.toString();
                } catch (Exception e) {
                    Logger.error("Failed to prepare resource pack: " + e.getMessage());
                }
            }
        }

        File wrapperConfig = new File(workingDirectory, "wrapper.properties");
        String wrapperContent = "cloud.host=" + CloudNode.getInstance().getHost() + "\n"
                + "cloud.port=" + CloudNode.getInstance().getPort() + "\n"
                + "cloud.serverName=" + serverName + "\n"
                + "cloud.authKey=" + CloudNode.getInstance().getAuthKey() + "\n"
                + "cloud.serverPort=" + port + "\n"
                + "cloud.isProxy=" + group.isProxy() + "\n"
                + "cloud.resourcePackUrl=" + resourcePackUrl + "\n"
                + "cloud.resourcePackHash=" + resourcePackHash + "\n"
                + "cloud.resourcePackForced=" + group.isForceResourcePack() + "\n";
        Files.writeString(wrapperConfig.toPath(), wrapperContent);
    }

    public void stop() {
        shutdownIntentional.set(true);
        if (process != null && process.isAlive()) {
            Logger.info("Stopping " + serverName + "...");
            sendCommand(group.isProxy() ? "end" : "stop");
            try {
                if (!process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                process.destroyForcibly();
            }
        }
        if (!group.isStaticService()) {
            try { deleteDirectory(workingDirectory); } catch (IOException ignored) {}
        } else {
            createBackup(false);
        }
    }

    public void createBackup(boolean manual) {
        if (!group.isStaticService()) {
            if (manual) Logger.error("Backups can only be created for STATIC servers.");
            return;
        }
        if (!manual && !"true".equalsIgnoreCase(CloudNode.getInstance().getNetworkSettings().getOrDefault("backup.static.enabled", "false"))) return;
        
        try {
            File backupDir = new File("local/backups/" + serverName);
            backupDir.mkdirs();
            File zipFile = new File(backupDir, serverName + "-backup-" + System.currentTimeMillis() + ".zip");
            Logger.info(">>> Creating automatic backup for " + serverName + "...");
            
            try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new FileOutputStream(zipFile))) {
                Files.walkFileTree(workingDirectory.toPath(), new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        String relative = workingDirectory.toPath().relativize(file).toString().replace("\\\\", "/");
                        zos.putNextEntry(new java.util.zip.ZipEntry(relative));
                        Files.copy(file, zos);
                        zos.closeEntry();
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            Logger.success(">>> Backup saved successfully: " + zipFile.getName());
        } catch (Exception e) {
            Logger.error("Error creating backup for " + serverName + ": " + e.getMessage());
        }
    }

    public void setPlayerCount(int count) {
        this.playerCount.set(count);
    }

    public int getPlayerCount() {
        return playerCount.get();
    }

    public void setTps(double tps) {
        this.currentTps = tps;
    }

    public double getTps() {
        return currentTps;
    }
    
    public void setCpuUsage(double cpu) { this.cpuUsage = cpu; }
    public double getCpuUsage() { return cpuUsage; }
    
    public void setMemoryUsage(long used, long max) {
        this.usedMemoryMB = used;
        this.maxMemoryMB = max;
    }
    public long getUsedMemoryMB() { return usedMemoryMB; }
    public long getMaxMemoryMB() { return maxMemoryMB; }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    public String getServerName() {
        return serverName;
    }

    public Group getGroup() {
        return group;
    }

    public int getRestartCount() {
        return restartCount.get();
    }

    public int getPort() {
        return port;
    }

    public java.util.Collection<String> getLogCache() {
        return logCache;
    }

    public void toggleScreen(boolean enable) {
        this.screenEnabled = enable;
        if (enable) {
            Logger.info("--- Console for " + serverName + " ---");
            for (String log : logCache) {
                Logger.raw("\u001B[90m[" + serverName + "] \u001B[0m" + log);
            }
        } else {
            Logger.info("--- Screen closed (" + serverName + ") ---");
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void copyMissingFiles(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path dest = target.resolve(source.relativize(file));
                if (!Files.exists(dest)) {
                    Files.copy(file, dest);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteDirectory(File dir) throws IOException {
        if (!dir.exists())
            return;
        Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public void sendCommand(String command) {
        if (processOutput != null && process.isAlive()) {
            try {
                processOutput.write(command + "\n");
                processOutput.flush();
            } catch (Exception ignored) {
            }
        }
    }
}

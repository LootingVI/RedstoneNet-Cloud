package net.redstone.cloud.node.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.redstone.cloud.api.plugin.CloudPlugin;
import net.redstone.cloud.api.plugin.CloudPluginDescription;
import net.redstone.cloud.node.logging.Logger;

import java.io.File;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class CloudPluginManager {

    private final File pluginDirectory;
    private final List<CloudPlugin> plugins;
    private final List<URLClassLoader> loaders;
    private final Gson gson;

    public CloudPluginManager() {
        this.pluginDirectory = new File("cloud-plugins");
        this.plugins = new ArrayList<>();
        this.loaders = new ArrayList<>();
        this.gson = new Gson();

        if (!pluginDirectory.exists()) {
            pluginDirectory.mkdirs();
        }
    }

    public void loadPlugins() {
        File[] files = pluginDirectory.listFiles((dir, name) -> name.endsWith(".jar"));
        if (files == null || files.length == 0) return;

        for (File file : files) {
            try (ZipFile zipFile = new ZipFile(file)) {
                ZipEntry entry = zipFile.getEntry("cloud-plugin.json");
                if (entry == null) {
                    Logger.error("Failed to load plugin from " + file.getName() + ": Missing cloud-plugin.json");
                    continue;
                }

                try (InputStreamReader isr = new InputStreamReader(zipFile.getInputStream(entry))) {
                    JsonObject json = gson.fromJson(isr, JsonObject.class);

                    String name = json.get("name").getAsString();
                    String version = json.get("version").getAsString();
                    String author = json.has("author") ? json.get("author").getAsString() : "Unknown";
                    String mainClass = json.get("main").getAsString();

                    CloudPluginDescription description = new CloudPluginDescription(name, version, author, mainClass);

                    URLClassLoader classLoader = new URLClassLoader(
                            new URL[]{file.toURI().toURL()},
                            this.getClass().getClassLoader()
                    );
                    loaders.add(classLoader);

                    Class<?> clazz = classLoader.loadClass(mainClass);
                    if (!CloudPlugin.class.isAssignableFrom(clazz)) {
                        Logger.error("Main class " + mainClass + " does not extend CloudPlugin!");
                        continue;
                    }

                    CloudPlugin plugin = (CloudPlugin) clazz.getDeclaredConstructor().newInstance();
                    plugin.init(description);

                    Logger.info("Loading CloudPlugin: " + name + " v" + version);
                    plugin.onLoad();
                    plugins.add(plugin);

                }
            } catch (Exception e) {
                Logger.error("Error loading plugin " + file.getName() + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void enablePlugins() {
        for (CloudPlugin plugin : plugins) {
            try {
                Logger.info("Enabling CloudPlugin: " + plugin.getDescription().getName());
                plugin.onEnable();
            } catch (Exception e) {
                Logger.error("Error enabling plugin " + plugin.getDescription().getName());
                e.printStackTrace();
            }
        }
    }

    public void disablePlugins() {
        for (CloudPlugin plugin : plugins) {
            try {
                Logger.info("Disabling CloudPlugin: " + plugin.getDescription().getName());
                plugin.onDisable();
            } catch (Exception e) {
                Logger.error("Error disabling plugin " + plugin.getDescription().getName());
                e.printStackTrace();
            }
        }
        for (URLClassLoader loader : loaders) {
            try {
                loader.close();
            } catch (Exception ignored) {
            }
        }
        loaders.clear();
        plugins.clear();
    }

    public List<CloudPlugin> getPlugins() {
        return plugins;
    }
}

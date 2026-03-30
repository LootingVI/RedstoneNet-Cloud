package net.redstone.cloud.node.process;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.redstone.cloud.node.logging.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class SoftwareDownloader {

    File softwareDir = new File("local/software");

    public SoftwareDownloader() {
        if (!softwareDir.exists()) softwareDir.mkdirs();
    }

    public void downloadPaperVelocity(String type, String version) {
        new Thread(() -> {
            try {
                Logger.info("-> Searching PaperMC for " + type + " version " + version + "...");
                // The API URL works for both Paper and Velocity (e.g. type="paper" or type="velocity")
                URL url = new URL("https://api.papermc.io/v2/projects/" + type + "/versions/" + version);
                HttpURLConnection request = (HttpURLConnection) url.openConnection();
                request.connect();

                // Read latest build
                JsonObject root = JsonParser.parseReader(new InputStreamReader((InputStream) request.getContent())).getAsJsonObject();
                JsonArray builds = root.getAsJsonArray("builds");
                int latestBuild = builds.get(builds.size() - 1).getAsInt();

                Logger.info("-> Downloading " + type + " build " + latestBuild + "...");
                
                String downloadUrl = "https://api.papermc.io/v2/projects/" + type + "/versions/" + version + "/builds/" + latestBuild + "/downloads/" + type + "-" + version + "-" + latestBuild + ".jar";
                downloadFile(downloadUrl, "local/software/" + type + "-" + version + ".jar");
                
                Logger.success("-> " + type.toUpperCase() + " " + version + " downloaded successfully!");

            } catch (Exception e) {
                Logger.error("-> Error downloading software (" + type + "): " + e.getMessage());
            }
        }).start();
    }

    public static void downloadFile(String urlStr, String destPath) throws Exception {
        File file = new File(destPath);
        file.getParentFile().mkdirs();
        
        URL url = new URL(urlStr);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestProperty("User-Agent", "RedstoneNet-CloudSystem");
        
        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}

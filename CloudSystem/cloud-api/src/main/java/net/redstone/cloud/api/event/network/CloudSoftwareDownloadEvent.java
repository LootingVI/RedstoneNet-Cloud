package net.redstone.cloud.api.event.network;

import net.redstone.cloud.api.event.Event;

public class CloudSoftwareDownloadEvent extends Event {

    private final String url;
    private final String fileName;

    public CloudSoftwareDownloadEvent(String url, String fileName) {
        this.url = url;
        this.fileName = fileName;
    }

    public String getUrl() {
        return url;
    }

    public String getFileName() {
        return fileName;
    }
}

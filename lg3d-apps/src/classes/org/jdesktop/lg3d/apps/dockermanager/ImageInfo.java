package org.jdesktop.lg3d.apps.dockermanager;

public class ImageInfo {
    String repository;
    String tag;
    String id;
    String created;
    String size;

    ImageInfo(String repository, String tag, String id, String created, String size) {
        this.repository = repository;
        this.tag = tag;
        this.id = id;
        this.created = created;
        this.size = size;
    }
}

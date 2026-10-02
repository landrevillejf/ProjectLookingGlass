package org.jdesktop.lg3d.apps.dockermanager;

// Data classes
public class ContainerInfo {
    String id;
    String name;
    String image;
    String status;
    String ports;
    String created;

    ContainerInfo(String id, String name, String image, String status, String ports, String created) {
        this.id = id;
        this.name = name;
        this.image = image;
        this.status = status;
        this.ports = ports;
        this.created = created;
    }
}

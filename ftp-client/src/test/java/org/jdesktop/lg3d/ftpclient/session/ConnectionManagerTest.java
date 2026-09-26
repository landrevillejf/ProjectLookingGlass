/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville, All Rights Reserved
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.ftpclient.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.jdesktop.lg3d.ftpclient.model.AppSettings;
import org.jdesktop.lg3d.ftpclient.model.ProfileStore;
import org.jdesktop.lg3d.ftpclient.model.Protocol;
import org.jdesktop.lg3d.ftpclient.model.SiteProfile;
import org.jdesktop.lg3d.ftpclient.net.RemoteClient;
import org.jdesktop.lg3d.ftpclient.net.RemoteClientFactory;
import org.jdesktop.lg3d.ftpclient.net.RemoteEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Covers {@link ConnectionManager}: profile CRUD against a {@code @TempDir}
 * store, connection lifecycle through an injected mock {@link RemoteClientFactory}
 * (so no socket is opened), the delegating remote operations, and the
 * single-active-session invariants on failure, drop and shutdown.
 */
class ConnectionManagerTest {

    @TempDir
    Path dir;

    private ProfileStore store;
    private RemoteClientFactory factory;
    private RemoteClient client;
    private ConnectionManager manager;

    @BeforeEach
    void setUp() {
        store = new ProfileStore(dir);
        factory = mock(RemoteClientFactory.class);
        client = mock(RemoteClient.class);
        when(client.isConnected()).thenReturn(true);
        when(factory.create(any(SiteProfile.class), any())).thenReturn(client);
        manager = new ConnectionManager(store, factory);
    }

    private SiteProfile site(String name) {
        SiteProfile p = new SiteProfile(name, Protocol.SFTP, "sftp.example.com");
        p.setUser("alice");
        p.setPassword("stored");
        return p;
    }

    // ------------------------------------------------------------------
    // profiles
    // ------------------------------------------------------------------

    @Test
    @DisplayName("profiles are added, found, updated and removed, and persisted")
    void profileCrud() {
        SiteProfile p = site("one");
        manager.addProfile(p);
        manager.addProfile(null); // ignored, no throw
        assertThat(manager.getProfiles()).hasSize(1);
        assertThat(manager.getProfile(p.getId())).contains(p);

        SiteProfile updated = p.copy();
        updated.setName("renamed");
        manager.updateProfile(updated);
        assertThat(manager.getProfiles()).hasSize(1);
        assertThat(manager.getProfile(p.getId()).orElseThrow().getName()).isEqualTo("renamed");

        // Persisted: a fresh store over the same directory sees it.
        assertThat(new ProfileStore(dir).loadProfiles()).hasSize(1);

        manager.removeProfile(p.getId());
        assertThat(manager.getProfiles()).isEmpty();
        assertThat(new ProfileStore(dir).loadProfiles()).isEmpty();
    }

    @Test
    @DisplayName("getProfiles is an immutable view")
    void profilesImmutable() {
        manager.addProfile(site("x"));
        List<SiteProfile> view = manager.getProfiles();
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, view::clear);
    }

    // ------------------------------------------------------------------
    // connection
    // ------------------------------------------------------------------

    @Test
    @DisplayName("connect builds a client, connects it and records the active profile")
    void connect() throws IOException {
        SiteProfile p = site("one");
        RemoteClient c = manager.connect(p, null);
        assertThat(c).isSameAs(client);
        verify(client).connect();
        assertThat(manager.isConnected()).isTrue();
        assertThat(manager.getClient()).isSameAs(client);
        assertThat(manager.getActiveProfile()).isNotNull();
        assertThat(manager.getActiveProfile().getId()).isEqualTo(p.getId());
    }

    @Test
    @DisplayName("an explicit password overrides the stored one without mutating the source")
    void passwordOverride() throws IOException {
        SiteProfile p = site("one");
        manager.connect(p, "secret");
        ArgumentCaptor<SiteProfile> captor = ArgumentCaptor.forClass(SiteProfile.class);
        verify(factory).create(captor.capture(), any());
        assertThat(captor.getValue().getPassword()).isEqualTo("secret");
        // The caller's profile object is copied, never mutated.
        assertThat(p.getPassword()).isEqualTo("stored");
    }

    @Test
    @DisplayName("a null password falls back to the profile's stored password")
    void storedPasswordUsed() throws IOException {
        manager.connect(site("one"), null);
        ArgumentCaptor<SiteProfile> captor = ArgumentCaptor.forClass(SiteProfile.class);
        verify(factory).create(captor.capture(), any());
        assertThat(captor.getValue().getPassword()).isEqualTo("stored");
    }

    @Test
    @DisplayName("connect by saved id resolves the profile first")
    void connectById() throws IOException {
        SiteProfile p = site("one");
        manager.addProfile(p);
        assertThat(manager.connect(p.getId(), null)).isSameAs(client);
        assertThatThrownBy(() -> manager.connect("unknown-id", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a failed connect closes the client and leaves no active session")
    void connectFailure() throws IOException {
        doThrow(new IOException("refused")).when(client).connect();
        assertThatThrownBy(() -> manager.connect(site("one"), null))
                .isInstanceOf(IOException.class);
        verify(client).close();
        assertThat(manager.isConnected()).isFalse();
        assertThat(manager.getClient()).isNull();
        assertThat(manager.getActiveProfile()).isNull();
    }

    @Test
    @DisplayName("a dropped connection is forgotten by getClient")
    void droppedConnection() throws IOException {
        manager.connect(site("one"), null);
        assertThat(manager.isConnected()).isTrue();
        when(client.isConnected()).thenReturn(false);
        assertThat(manager.getClient()).isNull();
        assertThat(manager.isConnected()).isFalse();
        assertThat(manager.getActiveProfile()).isNull();
        verify(client).close();
    }

    @Test
    @DisplayName("connecting twice closes the previous session first")
    void reconnectClosesPrevious() throws IOException {
        manager.connect(site("one"), null);
        RemoteClient second = mock(RemoteClient.class);
        when(second.isConnected()).thenReturn(true);
        when(factory.create(any(SiteProfile.class), any())).thenReturn(second);

        manager.connect(site("two"), null);
        verify(client).close(); // previous session closed by disconnect()
        assertThat(manager.getClient()).isSameAs(second);
    }

    @Test
    @DisplayName("disconnect closes the session and is safe when not connected")
    void disconnect() throws IOException {
        manager.disconnect(); // no active session: must not throw
        manager.connect(site("one"), null);
        manager.disconnect();
        assertThat(manager.isConnected()).isFalse();
        assertThat(manager.getClient()).isNull();
        verify(client).close();
    }

    @Test
    @DisplayName("removing the active profile disconnects it")
    void removeActiveProfileDisconnects() throws IOException {
        SiteProfile p = site("one");
        manager.connect(p, null);
        manager.removeProfile(p.getId());
        assertThat(manager.isConnected()).isFalse();
        verify(client).close();
    }

    // ------------------------------------------------------------------
    // delegating remote operations
    // ------------------------------------------------------------------

    @Test
    @DisplayName("remote operations delegate to the active client")
    void delegatingOps() throws IOException {
        manager.connect(site("one"), null);
        RemoteEntry entry = new RemoteEntry("f.txt", 10L, 0L, false);
        when(client.list("/pub")).thenReturn(List.of(entry));
        when(client.getWorkingDirectory()).thenReturn("/home");
        when(client.exists("/pub/f.txt")).thenReturn(true);
        when(client.size("/pub/f.txt")).thenReturn(10L);

        assertThat(manager.list("/pub")).containsExactly(entry);
        assertThat(manager.getWorkingDirectory()).isEqualTo("/home");
        assertThat(manager.exists("/pub/f.txt")).isTrue();
        assertThat(manager.size("/pub/f.txt")).isEqualTo(10L);

        manager.changeDirectory("/pub");
        manager.makeDirectory("/pub/new");
        manager.rename("/a", "/b");
        manager.delete("/pub/f.txt");
        manager.removeDirectory("/pub/new");
        verify(client).changeDirectory("/pub");
        verify(client).makeDirectory("/pub/new");
        verify(client).rename("/a", "/b");
        verify(client).delete("/pub/f.txt");
        verify(client).removeDirectory("/pub/new");
    }

    @Test
    @DisplayName("remote operations fail with a clear error when not connected")
    void opsWhenNotConnected() {
        assertThatThrownBy(() -> manager.list("/pub"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Not connected");
        assertThatThrownBy(manager::getWorkingDirectory).isInstanceOf(IOException.class);
    }

    // ------------------------------------------------------------------
    // settings, transfers, shutdown
    // ------------------------------------------------------------------

    @Test
    @DisplayName("saveSettings adopts, persists and propagates to the transfer manager")
    void saveSettings() {
        AppSettings s = new AppSettings();
        s.setRetryCount(8);
        manager.saveSettings(s);
        assertThat(manager.getSettings()).isSameAs(s);
        assertThat(manager.getTransfers().getSettings()).isSameAs(s);
        assertThat(new ProfileStore(dir).loadSettings().getRetryCount()).isEqualTo(8);

        manager.saveSettings(null);
        assertThat(manager.getSettings().getRetryCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("the transfer manager is exposed and bound to the session")
    void transfersExposed() {
        assertThat(manager.getTransfers()).isNotNull();
        assertThat(manager.getStore()).isSameAs(store);
    }

    @Test
    @DisplayName("shutdown cancels queued jobs and closes the session")
    void shutdown() throws IOException {
        manager.connect(site("one"), null);
        TransferJob job = manager.getTransfers().enqueueDownload("/remote/f", dir.resolve("f"));
        manager.shutdown();
        assertThat(job.isCancelRequested()).isTrue();
        assertThat(manager.isConnected()).isFalse();
        verify(client).close();
    }

    @Test
    @DisplayName("the store-backed constructor loads persisted profiles and settings")
    void loadsPersistedState() {
        SiteProfile p = site("persisted");
        store.saveProfiles(List.of(p));
        AppSettings s = new AppSettings();
        s.setRetryCount(5);
        store.saveSettings(s);

        ConnectionManager fresh = new ConnectionManager(new ProfileStore(dir));
        assertThat(fresh.getProfiles()).hasSize(1);
        assertThat(fresh.getSettings().getRetryCount()).isEqualTo(5);
        assertThat(fresh.getTransfers()).isNotNull();
        assertThat(fresh.isConnected()).isFalse();
        verify(client, never()).close();
    }
}

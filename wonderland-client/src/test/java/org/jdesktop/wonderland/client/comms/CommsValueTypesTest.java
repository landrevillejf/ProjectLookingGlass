/*
 * M2b pin: the comms value objects and exception hierarchy.
 *
 * These are the small typed contracts the connection / login layer (M2c)
 * raises and carries. The behaviours that actually matter and are easy to get
 * subtly wrong are pinned here: LoginFailureException(Throwable) folds the
 * cause's message into its own (so a bare-cause throw still reads
 * sensibly), ServerUnavailableException really is a LoginFailureException,
 * and WonderlandServerInfo compares by (hostname, port) -- which is what lets
 * it key maps and dedupe -- including the null-hostname hashCode branch.
 * Pure JDK, runs headless.
 */
package org.jdesktop.wonderland.client.comms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CommsValueTypesTest {

    @Test
    void loginFailureWrappingCauseAdoptsTheCauseMessage() {
        Throwable cause = new IllegalStateException("socket reset");
        LoginFailureException ex = new LoginFailureException(cause);
        assertThat(ex.getMessage()).isEqualTo("socket reset");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void loginFailureMessageAndCauseConstructorKeepsBoth() {
        Throwable cause = new RuntimeException("inner");
        LoginFailureException ex = new LoginFailureException("outer", cause);
        assertThat(ex.getMessage()).isEqualTo("outer");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void everyLoginFailureConstructorShapeIsUsable() {
        assertThat(new LoginFailureException().getMessage()).isNull();
        assertThat(new LoginFailureException("bare").getMessage()).isEqualTo("bare");
    }

    @Test
    void serverUnavailableIsALoginFailure() {
        ServerUnavailableException ex = new ServerUnavailableException("down");
        assertThat(ex).isInstanceOf(LoginFailureException.class);
        assertThat(ex.getMessage()).isEqualTo("down");

        Throwable cause = new java.io.IOException("refused");
        assertThat(new ServerUnavailableException(cause).getCause()).isSameAs(cause);
        assertThat(new ServerUnavailableException().getMessage()).isNull();

        ServerUnavailableException both = new ServerUnavailableException("nope", cause);
        assertThat(both.getMessage()).isEqualTo("nope");
        assertThat(both.getCause()).isSameAs(cause);
    }

    @Test
    void everyConnectionFailureConstructorShapeIsUsable() {
        Throwable cause = new IllegalStateException("no route");

        assertThatThrownBy(() -> {
            throw new ConnectionFailureException("cannot reach host", cause);
        }).isInstanceOf(ConnectionFailureException.class)
          .hasMessage("cannot reach host")
          .hasCause(cause);

        assertThat(new ConnectionFailureException().getMessage()).isNull();
        assertThat(new ConnectionFailureException("msg").getMessage()).isEqualTo("msg");

        ConnectionFailureException wrapped = new ConnectionFailureException(cause);
        assertThat(wrapped.getCause()).isSameAs(cause);
    }

    @Test
    void loginParametersRoundTripTheirAccessors() {
        LoginParameters params = new LoginParameters("alice", "secret".toCharArray());
        assertThat(params.getUserName()).isEqualTo("alice");
        assertThat(params.getPassword()).isEqualTo("secret".toCharArray());

        // The setters are package-private: reachable from this same-package test.
        params.setUserName("bob");
        params.setPassword("hunter2".toCharArray());
        assertThat(params.getUserName()).isEqualTo("bob");
        assertThat(params.getPassword()).isEqualTo("hunter2".toCharArray());
    }

    @Test
    void serverInfoComparesByHostnameAndPort() {
        WonderlandServerInfo a = new WonderlandServerInfo("host", 1234);
        WonderlandServerInfo same = new WonderlandServerInfo("host", 1234);
        WonderlandServerInfo otherPort = new WonderlandServerInfo("host", 5678);
        WonderlandServerInfo otherHost = new WonderlandServerInfo("other", 1234);

        assertThat(a).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(a).isNotEqualTo(otherPort).isNotEqualTo(otherHost);
        assertThat(a).isNotEqualTo("host:1234"); // not even the same type
        assertThat(a.getHostname()).isEqualTo("host");
        assertThat(a.getSgsPort()).isEqualTo(1234);
    }

    @Test
    void serverInfoHashCodeToleratesNullHostname() {
        // hashCode() has an explicit null guard on the hostname branch; equal
        // (null-host, same-port) instances must agree and must not throw. The
        // decompiled equals() has no such guard (it calls hostname.equals), so
        // this case deliberately pins only the hash contract, not equality.
        WonderlandServerInfo nullHost = new WonderlandServerInfo(null, 42);
        assertThat(nullHost.hashCode()).isEqualTo(new WonderlandServerInfo(null, 42).hashCode());
    }
}

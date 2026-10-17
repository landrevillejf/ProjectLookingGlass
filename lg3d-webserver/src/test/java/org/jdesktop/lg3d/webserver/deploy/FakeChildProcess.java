/*
 * A controllable ChildProcess stand-in for the JAR backend: liveness, pid and
 * buffered output are set by the test, and stop() is recorded, so fork/track/stop
 * can be proven without ever starting a real JVM.
 */
package org.jdesktop.lg3d.webserver.deploy;

public final class FakeChildProcess implements ChildProcess {

    private final long pid;
    private boolean alive;
    private String output;

    /** Set true once stop() has been called. */
    public boolean stopCalled;

    public FakeChildProcess(long pid, boolean alive, String output) {
        this.pid = pid;
        this.alive = alive;
        this.output = output;
    }

    public void setAlive(boolean value) {
        this.alive = value;
    }

    public void setOutput(String value) {
        this.output = value;
    }

    @Override
    public long pid() {
        return pid;
    }

    @Override
    public boolean isAlive() {
        return alive;
    }

    @Override
    public void stop() {
        stopCalled = true;
        alive = false;
    }

    @Override
    public String output() {
        return output;
    }
}

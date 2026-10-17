/*
 * A scriptable ChildProcessStarter stand-in: records each argv, returns a preset
 * ChildProcess (or a fresh live FakeChildProcess), and can be told to fail with an
 * IOException so the JAR backend's "could not start" path is exercised without
 * forking a real JVM.
 */
package org.jdesktop.lg3d.webserver.deploy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class FakeChildProcessStarter implements ChildProcessStarter {

    /** Every command passed to start(), in order. */
    public final List<List<String>> started = new ArrayList<>();

    private ChildProcess toReturn;
    private IOException failure;
    private long pidSeq = 4242L;

    public FakeChildProcessStarter toReturn(ChildProcess child) {
        this.toReturn = child;
        return this;
    }

    public FakeChildProcessStarter failWith(IOException exception) {
        this.failure = exception;
        return this;
    }

    public List<String> lastCommand() {
        return started.isEmpty() ? null : started.get(started.size() - 1);
    }

    @Override
    public ChildProcess start(List<String> command) throws IOException {
        started.add(List.copyOf(command));
        if (failure != null) {
            throw failure;
        }
        return toReturn != null ? toReturn : new FakeChildProcess(++pidSeq, true, "");
    }
}

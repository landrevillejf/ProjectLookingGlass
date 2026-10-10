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
package org.jdesktop.lg3d.apps.texteditor.ext;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The optional, permission-gated capability delegates the editor panel wires
 * behind an {@link EditorContext}. Every field may be null; the matching
 * {@link EditorContext} method is a silent no-op when its permission was not
 * granted or its delegate is null, so a headless test (or a host that does not
 * implement a surface) can leave a sink unset.
 *
 * <p>Build one with {@link #builder()} (or the legacy {@link #legacy} form).
 * This is a plumbing value object, not part of the extension-facing API.</p>
 */
public final class EditorSinks {

    final Consumer<String> showMessage;
    final Runnable openFile;
    final Runnable saveFile;
    final BiConsumer<String, String> showOutput;
    final Runnable clearOutput;
    final BiConsumer<String, List<Diagnostic>> reportDiagnostics;
    final Consumer<String> clearDiagnostics;
    final BiConsumer<String, List<StructureSymbol>> showStructure;
    final Consumer<String> appendDebugOutput;
    final Consumer<String> setDebugState;
    final Consumer<List<String>> showStack;
    final Consumer<List<String>> showLocals;
    final Consumer<List<String>> setBreakpoints;

    private EditorSinks(Builder b) {
        this.showMessage = b.showMessage;
        this.openFile = b.openFile;
        this.saveFile = b.saveFile;
        this.showOutput = b.showOutput;
        this.clearOutput = b.clearOutput;
        this.reportDiagnostics = b.reportDiagnostics;
        this.clearDiagnostics = b.clearDiagnostics;
        this.showStructure = b.showStructure;
        this.appendDebugOutput = b.appendDebugOutput;
        this.setDebugState = b.setDebugState;
        this.showStack = b.showStack;
        this.showLocals = b.showLocals;
        this.setBreakpoints = b.setBreakpoints;
    }

    /** @return a fresh builder with every sink unset. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The sink set implied by the legacy six constructors (status/open/save/output)
     * with no diagnostics or debug surface, so existing hosts keep compiling.
     */
    public static EditorSinks legacy(Consumer<String> showMessage, Runnable openFile,
                                     Runnable saveFile, BiConsumer<String, String> showOutput,
                                     Runnable clearOutput) {
        return builder()
                .showMessage(showMessage)
                .openFile(openFile)
                .saveFile(saveFile)
                .showOutput(showOutput)
                .clearOutput(clearOutput)
                .build();
    }

    /** Fluent builder for {@link EditorSinks}. */
    public static final class Builder {
        private Consumer<String> showMessage;
        private Runnable openFile;
        private Runnable saveFile;
        private BiConsumer<String, String> showOutput;
        private Runnable clearOutput;
        private BiConsumer<String, List<Diagnostic>> reportDiagnostics;
        private Consumer<String> clearDiagnostics;
        private BiConsumer<String, List<StructureSymbol>> showStructure;
        private Consumer<String> appendDebugOutput;
        private Consumer<String> setDebugState;
        private Consumer<List<String>> showStack;
        private Consumer<List<String>> showLocals;
        private Consumer<List<String>> setBreakpoints;

        public Builder showMessage(Consumer<String> v) { this.showMessage = v; return this; }
        public Builder openFile(Runnable v) { this.openFile = v; return this; }
        public Builder saveFile(Runnable v) { this.saveFile = v; return this; }
        public Builder showOutput(BiConsumer<String, String> v) { this.showOutput = v; return this; }
        public Builder clearOutput(Runnable v) { this.clearOutput = v; return this; }
        public Builder reportDiagnostics(BiConsumer<String, List<Diagnostic>> v) {
            this.reportDiagnostics = v; return this;
        }
        public Builder clearDiagnostics(Consumer<String> v) {
            this.clearDiagnostics = v; return this;
        }
        public Builder showStructure(BiConsumer<String, List<StructureSymbol>> v) {
            this.showStructure = v; return this;
        }
        public Builder appendDebugOutput(Consumer<String> v) {
            this.appendDebugOutput = v; return this;
        }
        public Builder setDebugState(Consumer<String> v) {
            this.setDebugState = v; return this;
        }
        public Builder showStack(Consumer<List<String>> v) {
            this.showStack = v; return this;
        }
        public Builder showLocals(Consumer<List<String>> v) {
            this.showLocals = v; return this;
        }
        public Builder setBreakpoints(Consumer<List<String>> v) {
            this.setBreakpoints = v; return this;
        }

        public EditorSinks build() {
            return new EditorSinks(this);
        }
    }
}

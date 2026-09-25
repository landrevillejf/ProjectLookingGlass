/**
 * Project Looking Glass
 *
 * Copyright (c) 2026, Jean-Francois Landreville - Gradle/JDK 21
 * modernization port and improvements. All Rights Reserved.
 *
 * Redistributions in source code form must reproduce the above
 * copyright and this condition.
 *
 * The contents of this file are subject to the GNU General Public
 * License, Version 2 (the "License"); you may not use this file
 * except in compliance with the License. A copy of the License is
 * available at http://www.opensource.org/licenses/gpl-license.php.
 */
package org.jdesktop.lg3d.displayserver.desktop2d;

import java.util.List;
import java.util.function.Predicate;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.ItemSpec;
import org.jdesktop.lg3d.displayserver.desktop2d.Desktop2DMenuConfig.MenuModel;

/**
 * Decides what a string typed into the Alt+F2 run dialog should do, without
 * touching Swing or spawning anything — the pure seam behind {@link RunDialog}.
 *
 * <p>Resolution mirrors the way a person uses a run box:</p>
 * <ol>
 *  <li>An exact (case-insensitive) match against a start-menu application name
 *      launches that application's {@link ItemSpec}, so typing "Calculator"
 *      behaves exactly like picking Calculator from the menu.</li>
 *  <li>Otherwise, a string that classifies as an external command
 *      ({@link Desktop2DAppRegistry.Kind#EXTERNAL}) whose executable is on the
 *      PATH runs as a child process, so typing "firefox" or "xterm -ls" works.</li>
 *  <li>Anything else — blank input, an unknown name, or an external command whose
 *      executable is missing — resolves to {@link Type#NOT_FOUND}.</li>
 * </ol>
 *
 * <p>The PATH lookup is injected as a {@link Predicate} so the whole decision
 * table can be exercised headless and deterministically; the no-argument
 * overload supplies the real {@link Desktop2DAppRegistry#isExternalAvailable}.</p>
 */
public final class RunResolver {

    /** What a resolved run-dialog entry should do. */
    public enum Type {
        /** Launch a start-menu application (an {@link ItemSpec}). */
        APP,
        /** Run an external command as a child process. */
        COMMAND,
        /** Nothing matched; the dialog shows "not found". */
        NOT_FOUND
    }

    /**
     * The outcome of resolving one input string. Exactly one of {@link #item()}
     * (for {@link Type#APP}) or {@link #command()} (for {@link Type#COMMAND}) is
     * meaningful; both are null for {@link Type#NOT_FOUND}.
     */
    public record Decision(Type type, ItemSpec item, String command) {

        /** A decision that launches {@code item}. */
        public static Decision app(ItemSpec item) {
            return new Decision(Type.APP, item, item.getCommand());
        }

        /** A decision that runs {@code command} externally. */
        public static Decision command(String command) {
            return new Decision(Type.COMMAND, null, command);
        }

        /** A decision that matches nothing. */
        public static Decision notFound() {
            return new Decision(Type.NOT_FOUND, null, null);
        }

        public boolean isApp() {
            return type == Type.APP;
        }

        public boolean isCommand() {
            return type == Type.COMMAND;
        }

        public boolean isNotFound() {
            return type == Type.NOT_FOUND;
        }
    }

    private RunResolver() {
        // no instances
    }

    /**
     * Resolves {@code input} against {@code model}, using the real PATH lookup
     * ({@link Desktop2DAppRegistry#isExternalAvailable}) to decide whether an
     * external command is runnable.
     */
    public static Decision resolve(String input, MenuModel model) {
        return resolve(input, model, Desktop2DAppRegistry::isExternalAvailable);
    }

    /**
     * Resolves {@code input} against {@code model}, using {@code externalAvailable}
     * to decide whether an external command's executable is present. The
     * injected predicate keeps the decision table testable without a PATH.
     */
    public static Decision resolve(String input, MenuModel model,
                            Predicate<String> externalAvailable) {
        if (input == null) {
            return Decision.notFound();
        }
        String text = input.trim();
        if (text.isEmpty()) {
            return Decision.notFound();
        }
        ItemSpec match = matchName(text, model);
        if (match != null) {
            return Decision.app(match);
        }
        boolean external = Desktop2DAppRegistry.classify(text)
                == Desktop2DAppRegistry.Kind.EXTERNAL;
        if (external && externalAvailable != null && externalAvailable.test(text)) {
            return Decision.command(text);
        }
        return Decision.notFound();
    }

    /** The first item whose name equals {@code text} case-insensitively, else null. */
    private static ItemSpec matchName(String text, MenuModel model) {
        if (model == null) {
            return null;
        }
        List<ItemSpec> items = model.getItems();
        if (items == null) {
            return null;
        }
        for (ItemSpec item : items) {
            if (item != null && text.equalsIgnoreCase(item.getName())) {
                return item;
            }
        }
        return null;
    }
}

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
package org.jdesktop.lg3d.mandela.rt;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.jdesktop.lg3d.mandela.values.Callable;
import org.jdesktop.lg3d.mandela.values.MandelaError;
import org.jdesktop.lg3d.mandela.values.MandelaList;
import org.jdesktop.lg3d.mandela.values.MandelaMap;
import org.jdesktop.lg3d.mandela.values.MandelaRange;
import org.jdesktop.lg3d.mandela.values.Operators;
import org.jdesktop.lg3d.mandela.values.Values;
import org.jdesktop.lg3d.mandela.vm.Builtins;
import org.jdesktop.lg3d.mandela.vm.Machine;

/**
 * The vocabulary every Mandela host shares: the methods on the built-in types,
 * the global functions, and the {@code std.*} modules.
 *
 * <p>The interpreter deliberately knows nothing about {@code join}, {@code upper}
 * or {@code sqrt}. It asks this class, through {@link Builtins}, whenever a member
 * is not a declared field or method, and a host is free to answer differently:
 * a Web Browser page installs a library whose {@code fs} module has no grants,
 * an Espresso panel one whose {@code println} goes to its Problems view. What
 * lives here is the desktop-shaped default.</p>
 *
 * <h2>Three kinds of name</h2>
 *
 * <ul>
 *   <li><b>properties</b> &mdash; {@code s.length}, {@code m.keys}. Read as
 *       values, never called. Cheap, because every member read that misses a
 *       declared field walks this table.</li>
 *   <li><b>methods</b> &mdash; {@code l.map(f)}. Resolved to a callable that
 *       already knows its receiver, so the machine only has to pass the
 *       arguments.</li>
 *   <li><b>modules</b> &mdash; {@code use std.json} installs one map under the
 *       short name, so a script writes {@code json.parse(text)} and the group
 *       stays discoverable without polluting the global table.</li>
 * </ul>
 *
 * <h2>Failures</h2>
 *
 * <p>Everything thrown here is a {@link MandelaError}, so script code can catch
 * it with {@code try} / {@code catch} and the kind name is stable:
 * {@code TypeError} for a value of the wrong shape, {@code ValueError} for a bad
 * argument or a missing name, {@code PermissionError} for a refused capability,
 * {@code IOError} for the filesystem. A Java exception escaping a library
 * function would leave the script with nothing to match on.</p>
 *
 * <p>Callback-taking methods ({@code map}, {@code filter}, {@code sort}) re-enter
 * the machine through {@link Machine#invoke}, which is the barrier path described
 * on {@link org.jdesktop.lg3d.mandela.vm.VM}: a script callback may itself call
 * {@code map}, and the nesting is bounded by the sandbox's depth budget.</p>
 */
public final class StandardLibrary implements Builtins {

    /** One member body, with the receiver and the running machine in hand. */
    private interface Fn {
        /**
         * @param machine the interpreter that is running the call
         * @param self    the receiver the member was read on
         * @param args    the evaluated arguments, in order
         * @return the value to hand back to the script
         */
        Object call(Machine machine, Object self, List<Object> args);
    }

    /** One property read. */
    private interface Property {
        /** @param self the receiver
         *  @return the value the name holds */
        Object read(Object self);
    }

    /** The member tables, filled once. */
    private final Map<String, Fn> stringMethods = new LinkedHashMap<>();
    private final Map<String, Fn> listMethods = new LinkedHashMap<>();
    private final Map<String, Fn> mapMethods = new LinkedHashMap<>();
    private final Map<String, Fn> rangeMethods = new LinkedHashMap<>();
    private final Map<String, Fn> numberMethods = new LinkedHashMap<>();
    private final Map<String, Property> stringProps = new LinkedHashMap<>();
    private final Map<String, Property> listProps = new LinkedHashMap<>();
    private final Map<String, Property> mapProps = new LinkedHashMap<>();
    private final Map<String, Property> rangeProps = new LinkedHashMap<>();

    /** The modules the host may hand out, by short name. */
    private final Map<String, MandelaMap> modules = new LinkedHashMap<>();

    /** Filesystem access, which only a host with a root can answer for. */
    private final Files files;

    /**
     * How {@code import "..."} is resolved, when the host offers one.
     *
     * <p>Null by default. A plain embedding has no notion of a file, and the
     * library refuses rather than guessing a path; {@link Runtime} installs the
     * desktop's loader when it builds the library for a real run.</p>
     */
    private Importer importer;

    /**
     * Builds the library over a host's file gateway.
     *
     * @param gateway how to reach the host's files, or null when the host has none
     *                (a browser page), in which case {@code std.fs} refuses
     */
    public StandardLibrary(Files gateway) {
        this.files = gateway;
        fillStringMethods();
        fillListMethods();
        fillMapMethods();
        fillRangeMethods();
        fillNumberMethods();
        fillModules();
    }

    /** The same library with no filesystem at all. */
    public StandardLibrary() {
        this(null);
    }

    // -- the Builtins contract ----------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>A map answers its own keys first, which is what makes a module object
     * ({@code json.parse}) and any record-shaped value read naturally; only a
     * missing key falls through to the methods a map has of its own.</p>
     */
    @Override
    public Object member(Machine machine, Object receiver, String name) {
        if (receiver instanceof MandelaMap map) {
            if (map.contains(name)) {
                return map.get(name);
            }
            Property property = mapProps.get(name);
            if (property != null) {
                return property.read(map);
            }
            return bound(mapMethods.get(name), name, machine, map);
        }
        if (receiver instanceof MandelaList list) {
            Property property = listProps.get(name);
            if (property != null) {
                return property.read(list);
            }
            return bound(listMethods.get(name), name, machine, list);
        }
        if (receiver instanceof String text) {
            Property property = stringProps.get(name);
            if (property != null) {
                return property.read(text);
            }
            return bound(stringMethods.get(name), name, machine, text);
        }
        if (receiver instanceof MandelaRange range) {
            Property property = rangeProps.get(name);
            if (property != null) {
                return property.read(range);
            }
            return bound(rangeMethods.get(name), name, machine, range);
        }
        if (receiver instanceof Long || receiver instanceof Double || receiver instanceof Integer) {
            // Numbers carry methods but no properties, and the receiver is boxed
            // here exactly as the machine holds it.
            return bound(numberMethods.get(name), name, machine, receiver);
        }
        if (receiver instanceof HostFunction host) {
            // Reflecting on a callable is how a script inspects what a host gave it.
            if ("name".equals(name)) {
                return host.name();
            }
            if ("arity".equals(name)) {
                return (long) host.arity();
            }
        }
        return null;
    }

    /** Wraps a table entry as a callable that already knows its receiver. */
    private static Object bound(Fn body, String name, Machine machine, Object self) {
        if (body == null) {
            return null;
        }
        return HostFunction.of(name, -1, args -> body.call(machine, self, args));
    }

    /** Loads a file module on the library's behalf. */
    @FunctionalInterface
    public interface Importer {
        /**
         * @param machine the interpreter that met the {@code import}
         * @param path    the literal path from the statement
         * @return the module's exported names
         */
        MandelaMap load(Machine machine, String path);
    }

    /**
     * Gives the library a way to answer {@code import}.
     *
     * @param importer the loader, or null to refuse again
     */
    public void setImporter(Importer importer) {
        this.importer = importer;
    }

    @Override
    public MandelaMap importModule(Machine machine, String path) {
        if (importer == null) {
            throw MandelaError.value("'" + path + "' cannot be imported by this host;"
                    + " only 'use std.<name>' is available");
        }
        return importer.load(machine, path);
    }

    @Override
    public MandelaMap stdlibNames(Machine machine, String module) {
        String shortName = module.startsWith("std.")
                ? module.substring("std.".length()) : module;
        MandelaMap found = modules.get(shortName);
        if (found == null) {
            found = dynamicModule(machine, shortName);
        }
        if (found == null) {
            throw MandelaError.value("there is no standard module 'std." + shortName
                    + "'; the host offers " + String.join(", ", moduleNames()));
        }
        MandelaMap installed = new MandelaMap(1);
        installed.put(shortName, found);
        return installed;
    }

    /**
     * Publishes the global functions a script may call without a {@code use}.
     *
     * <p>Kept small on purpose: {@code println} and the conversions are the whole
     * set, because every name here competes with a script's own declaration and
     * with a host's own bindings.</p>
     *
     * <p>The console functions close over the machine they are installed in,
     * which is what routes a page script's {@code println} to the browser console
     * and an Espresso panel's to its Problems view &mdash; never to
     * {@code System.out}.</p>
     *
     * @param machine the interpreter these names will run on
     * @return the names and their values, for a global table
     */
    public MandelaMap globals(Machine machine) {
        MandelaMap out = new MandelaMap(16);
        out.put("println", HostFunction.of("println", -1,
                args -> { machine.writeLine(join(args)); return null; }));
        out.put("print", HostFunction.of("print", -1,
                args -> { machine.write(join(args)); return null; }));
        out.put("eprintln", HostFunction.of("eprintln", -1,
                args -> { machine.writeError(join(args)); return null; }));
        out.put("str", HostFunction.of("str", 1, args -> Values.display(args.get(0))));
        out.put("int", HostFunction.of("int", 1, args -> whole(args.get(0), "int(...)")));
        out.put("double", HostFunction.of("double", 1, args -> real(args.get(0), "double(...)")));
        out.put("bool", HostFunction.of("bool", 1, args -> Values.truthy(args.get(0))));
        out.put("length", HostFunction.of("length", 1, args -> Operators.length(args.get(0))));
        out.put("typeOf", HostFunction.of("typeOf", 1, args -> Values.typeName(args.get(0))));
        out.put("min", HostFunction.of("min", -1, args -> pick(args, true)));
        out.put("max", HostFunction.of("max", -1, args -> pick(args, false)));
        out.put("abs", HostFunction.of("abs", 1, args -> abs(args.get(0))));
        out.put("error", HostFunction.of("error", -1, args -> {
            String message = args.isEmpty() ? "error" : Values.display(args.get(0));
            String kind = (args.size() > 1) ? Values.display(args.get(1)) : MandelaError.ERROR;
            throw MandelaError.of(kind, message);
        }));
        out.put("assert", HostFunction.of("assert", -1, args -> {
            if (args.isEmpty() || !Values.truthy(args.get(0))) {
                String what = (args.size() > 1) ? Values.display(args.get(1))
                        : "the condition in assert(...) did not hold";
                throw MandelaError.of("AssertError", what);
            }
            return null;
        }));
        out.put("keys", HostFunction.of("keys", 1, args -> {
            if (args.get(0) instanceof MandelaMap map) {
                return listOf(map.keys());
            }
            throw badShape("keys(...)", "map", args.get(0));
        }));
        out.put("valuesOf", HostFunction.of("valuesOf", 1, args -> {
            if (args.get(0) instanceof MandelaMap map) {
                return listOf(map.values());
            }
            throw badShape("valuesOf(...)", "map", args.get(0));
        }));
        return out;
    }

    /** @return the largest absolute value, keeping Int as Int */
    private static Object abs(Object value) {
        if (value instanceof Long whole) {
            return Math.abs(whole);
        }
        if (Values.isNum(value)) {
            return Math.abs(Values.asDouble(value));
        }
        throw MandelaError.type("abs(...) needs a number; got a "
                + Values.typeName(value));
    }

    /** Renders the arguments of a print call: one value bare, several spaced. */
    private static String join(List<Object> args) {
        if (args.size() == 1) {
            return Values.display(args.get(0));
        }
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                line.append(' ');
            }
            line.append(Values.display(args.get(i)));
        }
        return line.toString();
    }

    /**
     * Installs the library's globals and this machine's own bindings into a
     * global table, the one call a host makes to wire a fresh VM up.
     *
     * <p>The two hooks the compiler looks for by name go in here as well, because
     * they are what makes {@code use} and {@code import} work at all: a
     * {@code use} statement compiles to a call on {@code __use__}, which asks
     * this library for the module on the running machine. Passing null
     * {@code own} still installs them, so a host that does nothing else gets a
     * language that behaves the same as everyone else's.</p>
     *
     * @param machine the interpreter about to run
     * @param globals the live global table
     * @param own     the host's functions, installed last so a host may replace a
     *                name the library offers
     */
    public void install(Machine machine, Map<String, Object> globals,
                        Map<String, Object> own) {
        for (Map.Entry<String, Object> entry : globals(machine).entries().entrySet()) {
            globals.put(entry.getKey(), entry.getValue());
        }
        globals.put(org.jdesktop.lg3d.mandela.code.Compiler.USE_HOOK,
                HostFunction.of("__use__", 1, args -> {
                    machine.useModule(argText(args, 0, "use"));
                    return null;
                }));
        globals.put(org.jdesktop.lg3d.mandela.code.Compiler.IMPORT_HOOK,
                HostFunction.of("__import__", 1,
                        args -> machine.importModule(argText(args, 0, "import"))));
        if (own != null) {
            globals.putAll(own);
        }
    }

    // -- Str -----------------------------------------------------------------

    /**
     * The string vocabulary. Every method is literal, not pattern-based:
     * Mandela has no regular-expression engine in the language, and a script that
     * needs one asks its host, rather than getting a {@code replaceAll} whose
     * argument silently changes meaning when it contains a {@code .}.
     */
    private void fillStringMethods() {
        stringProps.put("length", value -> (long) ((String) value).length());

        stringMethods.put("upper", (m, self, args) -> text(self).toUpperCase(Locale.ROOT));
        stringMethods.put("lower", (m, self, args) -> text(self).toLowerCase(Locale.ROOT));
        stringMethods.put("trim", (m, self, args) -> text(self).trim());
        stringMethods.put("split", (m, self, args) -> splitText(text(self), arg(args, 0)));
        stringMethods.put("replace", (m, self, args) -> text(self).replace(
                argText(args, 0, "replace"), argText(args, 1, "replace")));
        stringMethods.put("replaceFirst", (m, self, args) -> {
            String source = text(self);
            String from = argText(args, 0, "replaceFirst");
            int at = source.indexOf(from);
            if (at < 0) {
                return source;
            }
            return source.substring(0, at) + argText(args, 1, "replaceFirst")
                    + source.substring(at + from.length());
        });
        stringMethods.put("contains", (m, self, args) -> text(self).contains(
                argText(args, 0, "contains")));
        stringMethods.put("startsWith", (m, self, args) -> text(self).startsWith(
                argText(args, 0, "startsWith")));
        stringMethods.put("endsWith", (m, self, args) -> text(self).endsWith(
                argText(args, 0, "endsWith")));
        stringMethods.put("indexOf", (m, self, args) -> (long) text(self).indexOf(
                argText(args, 0, "indexOf")));
        stringMethods.put("charAt", (m, self, args) -> {
            String source = text(self);
            long at = argWhole(args, 0, "charAt");
            if (at < 0 || at >= source.length()) {
                throw MandelaError.index("'" + source + "' has no character at " + at);
            }
            return source.substring((int) at, (int) at + 1);
        });
        stringMethods.put("substring", (m, self, args) -> {
            String source = text(self);
            int from = clampStart(argWhole(args, 0, "substring"), source.length());
            int to = (args.size() > 1)
                    ? clampEnd(argWhole(args, 1, "substring"), source.length())
                    : source.length();
            return (to <= from) ? "" : source.substring(from, to);
        });
        stringMethods.put("toInt", (m, self, args) -> whole(self, "toInt()"));
        stringMethods.put("toDouble", (m, self, args) -> real(self, "toDouble()"));
        stringMethods.put("repeat", (m, self, args) -> {
            String source = text(self);
            long count = argWhole(args, 0, "repeat");
            if (count < 0) {
                throw MandelaError.value("repeat(...) needs a count of 0 or more");
            }
            long total = (long) source.length() * count;
            if (total > MAX_TEXT) {
                throw MandelaError.limit("repeat(...) would build " + total
                        + " characters, over the " + MAX_TEXT + " character limit");
            }
            StringBuilder out = new StringBuilder((int) total);
            for (long i = 0; i < count; i++) {
                out.append(source);
            }
            return out.toString();
        });
        stringMethods.put("padStart", (m, self, args) -> pad(text(self), args, true));
        stringMethods.put("padEnd", (m, self, args) -> pad(text(self), args, false));
    }

    /** Splits on a literal separator, or on whitespace runs when called bare. */
    private static MandelaList splitText(String source, Object separator) {
        MandelaList out = new MandelaList(4);
        if (separator == null || Values.isAbsent(separator)) {
            StringBuilder token = new StringBuilder();
            for (int i = 0; i < source.length(); i++) {
                char c = source.charAt(i);
                if (Character.isWhitespace(c)) {
                    if (token.length() > 0) {
                        out.add(token.toString());
                        token.setLength(0);
                    }
                } else {
                    token.append(c);
                }
            }
            if (token.length() > 0) {
                out.add(token.toString());
            }
            return out;
        }
        String sep = text(separator);
        if (sep.isEmpty()) {
            // Splitting on "" is a character list, which is what a caller means.
            for (int i = 0; i < source.length(); i++) {
                out.add(source.substring(i, i + 1));
            }
            return out;
        }
        int at = 0;
        while (true) {
            int next = source.indexOf(sep, at);
            if (next < 0) {
                out.add(source.substring(at));
                return out;
            }
            out.add(source.substring(at, next));
            at = next + sep.length();
        }
    }

    private static String pad(String source, List<Object> args, boolean before) {
        long width = argWhole(args, 0, "pad");
        String fill = (args.size() > 1) ? text(args.get(1)) : " ";
        if (fill.isEmpty() || source.length() >= width) {
            return source;
        }
        StringBuilder out = new StringBuilder((int) Math.min(width, MAX_TEXT));
        long missing = width - source.length();
        for (long i = 0; i < missing; i++) {
            out.append(fill.charAt((int) (i % fill.length())));
        }
        return before ? out.append(source).toString() : source + out;
    }

    // -- List ----------------------------------------------------------------

    /**
     * The list vocabulary. Mutating methods return the list so a script can chain
     * them, and the non-mutating ones ({@code map}, {@code filter}, {@code sorted},
     * {@code slice}) always build a new list, never a view &mdash; a view would
     * keep the source alive and let two names disagree about one sequence.
     */
    private void fillListMethods() {
        listProps.put("length", value -> (long) ((MandelaList) value).size());
        listProps.put("size", value -> (long) ((MandelaList) value).size());
        listProps.put("isEmpty", value -> ((MandelaList) value).isEmpty());
        listProps.put("first", value -> {
            MandelaList list = (MandelaList) value;
            return list.isEmpty() ? null : list.get(0);
        });
        listProps.put("last", value -> {
            MandelaList list = (MandelaList) value;
            return list.isEmpty() ? null : list.get(list.size() - 1);
        });

        listMethods.put("push", (m, self, args) -> {
            MandelaList list = needList(self, "push");
            for (Object value : args) {
                list.add(value);
            }
            return list;
        });
        listMethods.put("add", (m, self, args) -> {
            needList(self, "add").add(args.isEmpty() ? null : args.get(0));
            return self;
        });
        listMethods.put("insert", (m, self, args) -> {
            MandelaList list = needList(self, "insert");
            list.insert(indexArg(argWhole(args, 0, "insert"), list.size() + 1),
                    arg(args, 1));
            return list;
        });
        listMethods.put("removeAt", (m, self, args) -> {
            MandelaList list = needList(self, "removeAt");
            int at = (int) argWhole(args, 0, "removeAt");
            if (at < 0 || at >= list.size()) {
                throw MandelaError.index("the list has " + list.size()
                        + " element(s); there is nothing at " + at);
            }
            return list.removeAt(at);
        });
        listMethods.put("pop", (m, self, args) -> {
            MandelaList list = needList(self, "pop");
            if (list.isEmpty()) {
                return null;
            }
            return list.removeAt(list.size() - 1);
        });
        listMethods.put("clear", (m, self, args) -> {
            needList(self, "clear").clear();
            return null;
        });
        listMethods.put("set", (m, self, args) -> {
            MandelaList list = needList(self, "set");
            list.set(indexArg(argWhole(args, 0, "set"), list.size()), arg(args, 1));
            return list;
        });
        listMethods.put("get", (m, self, args) -> {
            MandelaList list = needList(self, "get");
            long at = argWhole(args, 0, "get");
            if (at < 0 || at >= list.size()) {
                if (args.size() > 1) {
                    return args.get(1);
                }
                throw MandelaError.index("the list has " + list.size()
                        + " element(s); there is nothing at " + at);
            }
            return list.get((int) at);
        });
        listMethods.put("indexOf", (m, self, args) -> {
            MandelaList list = needList(self, "indexOf");
            Object wanted = arg(args, 0);
            for (int i = 0; i < list.size(); i++) {
                if (Values.equal(list.get(i), wanted)) {
                    return (long) i;
                }
            }
            return -1L;
        });
        listMethods.put("contains", (m, self, args) -> {
            MandelaList list = needList(self, "contains");
            Object wanted = arg(args, 0);
            for (Object value : list.items()) {
                if (Values.equal(value, wanted)) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        });
        listMethods.put("join", (m, self, args) -> {
            MandelaList list = needList(self, "join");
            String sep = (args.isEmpty()) ? "" : text(args.get(0));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(sep);
                }
                out.append(Values.display(list.get(i)));
            }
            return out.toString();
        });
        listMethods.put("map", (m, self, args) -> {
            MandelaList list = needList(self, "map");
            Callable callback = needCallable(arg(args, 0), "map");
            MandelaList out = new MandelaList(list.size());
            for (int i = 0; i < list.size(); i++) {
                out.add(invoke(m, callback, list.get(i), (long) i));
            }
            return out;
        });
        listMethods.put("filter", (m, self, args) -> {
            MandelaList list = needList(self, "filter");
            Callable callback = needCallable(arg(args, 0), "filter");
            MandelaList out = new MandelaList(list.size());
            for (int i = 0; i < list.size(); i++) {
                Object value = list.get(i);
                if (Values.truthy(invoke(m, callback, value, (long) i))) {
                    out.add(value);
                }
            }
            return out;
        });
        listMethods.put("reduce", (m, self, args) -> {
            MandelaList list = needList(self, "reduce");
            Callable callback = needCallable(arg(args, 0), "reduce");
            Object acc = (args.size() > 1) ? args.get(1) : (list.isEmpty() ? null : list.get(0));
            int from = (args.size() > 1) ? 0 : 1;
            for (int i = from; i < list.size(); i++) {
                acc = invoke(m, callback, acc, list.get(i), (long) i);
            }
            return acc;
        });
        listMethods.put("forEach", (m, self, args) -> {
            MandelaList list = needList(self, "forEach");
            Callable callback = needCallable(arg(args, 0), "forEach");
            for (int i = 0; i < list.size(); i++) {
                invoke(m, callback, list.get(i), (long) i);
            }
            return null;
        });
        listMethods.put("any", (m, self, args) -> testList(m, self, args, true));
        listMethods.put("all", (m, self, args) -> testList(m, self, args, false));
        listMethods.put("find", (m, self, args) -> {
            MandelaList list = needList(self, "find");
            Callable callback = needCallable(arg(args, 0), "find");
            for (int i = 0; i < list.size(); i++) {
                Object value = list.get(i);
                if (Values.truthy(invoke(m, callback, value, (long) i))) {
                    return value;
                }
            }
            return null;
        });
        listMethods.put("sort", (m, self, args) -> sortInto((MandelaList) self,
                comparatorFor(m, arg(args, 0))));
        listMethods.put("sortBy", (m, self, args) -> sortInto((MandelaList) self,
                keyComparator(m, arg(args, 0))));
        listMethods.put("sorted", (m, self, args) -> sortInto(needList(self, "sorted")
                .copyOf(), comparatorFor(m, arg(args, 0))));
        listMethods.put("sortedBy", (m, self, args) -> sortInto(needList(self, "sortedBy")
                .copyOf(), keyComparator(m, arg(args, 0))));
        listMethods.put("reversed", (m, self, args) -> {
            MandelaList list = needList(self, "reversed");
            MandelaList out = new MandelaList(list.size());
            for (int i = list.size() - 1; i >= 0; i--) {
                out.add(list.get(i));
            }
            return out;
        });
        listMethods.put("slice", (m, self, args) -> {
            MandelaList list = needList(self, "slice");
            int from = clampStart(argWhole(args, 0, "slice"), list.size());
            int to = (args.size() > 1)
                    ? clampEnd(argWhole(args, 1, "slice"), list.size()) : list.size();
            MandelaList out = new MandelaList(Math.max(0, to - from));
            for (int i = from; i < to; i++) {
                out.add(list.get(i));
            }
            return out;
        });
        listMethods.put("take", (m, self, args) -> {
            MandelaList list = needList(self, "take");
            long count = Math.max(0, argWhole(args, 0, "take"));
            MandelaList out = new MandelaList((int) Math.min(count, list.size()));
            for (int i = 0; i < count && i < list.size(); i++) {
                out.add(list.get(i));
            }
            return out;
        });
        listMethods.put("skip", (m, self, args) -> {
            MandelaList list = needList(self, "skip");
            long count = Math.max(0, argWhole(args, 0, "skip"));
            MandelaList out = new MandelaList(list.size());
            for (int i = (int) Math.min(count, list.size()); i < list.size(); i++) {
                out.add(list.get(i));
            }
            return out;
        });
        listMethods.put("sum", (m, self, args) -> {
            MandelaList list = needList(self, "sum");
            Object acc = 0L;
            for (Object value : list.items()) {
                acc = Operators.add(acc, value);
            }
            return acc;
        });
        listMethods.put("min", (m, self, args) -> pick(needList(self, "min").items(), true));
        listMethods.put("max", (m, self, args) -> pick(needList(self, "max").items(), false));
        listMethods.put("distinct", (m, self, args) -> {
            MandelaList list = needList(self, "distinct");
            MandelaList out = new MandelaList(list.size());
            for (Object value : list.items()) {
                boolean seen = false;
                for (Object held : out.items()) {
                    if (Values.equal(held, value)) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) {
                    out.add(value);
                }
            }
            return out;
        });
        listMethods.put("flatten", (m, self, args) -> {
            MandelaList list = needList(self, "flatten");
            MandelaList out = new MandelaList(list.size());
            for (Object value : list.items()) {
                if (value instanceof MandelaList inner) {
                    out.addAll(inner.copyOf());
                } else {
                    out.add(value);
                }
            }
            return out;
        });
    }

    // -- Map -----------------------------------------------------------------

    /**
     * The map vocabulary. A map's own keys win over these names when a script reads
     * {@code m.get} as a value (see {@link #member}), so a document that happens to
     * carry a {@code keys} field still reads the way the author wrote it &mdash;
     * the method forms are reached by calling, which is the common case.
     */
    private void fillMapMethods() {
        mapProps.put("length", value -> (long) ((MandelaMap) value).size());
        mapProps.put("size", value -> (long) ((MandelaMap) value).size());
        mapProps.put("isEmpty", value -> ((MandelaMap) value).isEmpty());
        mapProps.put("keys", value -> listOf(((MandelaMap) value).keys()));
        mapProps.put("values", value -> listOf(((MandelaMap) value).values()));

        mapMethods.put("get", (m, self, args) -> {
            MandelaMap map = (MandelaMap) self;
            String key = argText(args, 0, "get");
            if (map.contains(key)) {
                return map.get(key);
            }
            return (args.size() > 1) ? args.get(1) : null;
        });
        mapMethods.put("put", (m, self, args) -> {
            ((MandelaMap) self).put(argText(args, 0, "put"), arg(args, 1));
            return self;
        });
        mapMethods.put("set", (m, self, args) -> {
            ((MandelaMap) self).put(argText(args, 0, "set"), arg(args, 1));
            return self;
        });
        mapMethods.put("remove", (m, self, args) -> ((MandelaMap) self)
                .remove(argText(args, 0, "remove")));
        mapMethods.put("has", (m, self, args) -> ((MandelaMap) self)
                .contains(argText(args, 0, "has")));
        mapMethods.put("clear", (m, self, args) -> {
            ((MandelaMap) self).clear();
            return null;
        });
        mapMethods.put("merge", (m, self, args) -> {
            MandelaMap map = (MandelaMap) self;
            Object other = arg(args, 0);
            if (!(other instanceof MandelaMap added)) {
                throw badShape("merge(...)", "map", other);
            }
            map.putAll(added);
            return map;
        });
        mapMethods.put("copy", (m, self, args) -> ((MandelaMap) self).copyOf());
        mapMethods.put("forEach", (m, self, args) -> {
            MandelaMap map = (MandelaMap) self;
            Callable callback = needCallable(arg(args, 0), "forEach");
            for (Map.Entry<String, Object> entry : map.entries().entrySet()) {
                invoke(m, callback, entry.getKey(), entry.getValue());
            }
            return null;
        });
        mapMethods.put("mapValues", (m, self, args) -> {
            MandelaMap map = (MandelaMap) self;
            Callable callback = needCallable(arg(args, 0), "mapValues");
            MandelaMap out = new MandelaMap(map.size());
            for (Map.Entry<String, Object> entry : map.entries().entrySet()) {
                out.put(entry.getKey(), invoke(m, callback, entry.getValue(), entry.getKey()));
            }
            return out;
        });
        mapMethods.put("entries", (m, self, args) -> {
            MandelaMap map = (MandelaMap) self;
            MandelaList out = new MandelaList(map.size());
            for (Map.Entry<String, Object> entry : map.entries().entrySet()) {
                MandelaList pair = new MandelaList(2);
                pair.add(entry.getKey());
                pair.add(entry.getValue());
                out.add(pair);
            }
            return out;
        });
    }

    // -- Range ---------------------------------------------------------------

    private void fillRangeMethods() {
        rangeProps.put("length", value -> ((MandelaRange) value).size());
        rangeProps.put("first", value -> ((MandelaRange) value).from());
        rangeProps.put("last", value -> ((MandelaRange) value).last());
        rangeProps.put("step", value -> ((MandelaRange) value).step());
        rangeProps.put("isEmpty", value -> ((MandelaRange) value).isEmpty());

        rangeMethods.put("toList", (m, self, args) -> {
            MandelaRange range = (MandelaRange) self;
            long size = range.size();
            if (size > MAX_LIST) {
                throw MandelaError.limit("the range holds " + size + " values, over the "
                        + MAX_LIST + " element limit for toList()");
            }
            MandelaList out = new MandelaList((int) size);
            for (long value : iterableRange(range)) {
                out.add(value);
            }
            return out;
        });
        rangeMethods.put("contains", (m, self, args) -> ((MandelaRange) self)
                .contains(argWhole(args, 0, "contains")));
        rangeMethods.put("reversed", (m, self, args) -> {
            MandelaRange range = (MandelaRange) self;
            long size = range.size();
            if (size > MAX_LIST) {
                throw MandelaError.limit("the range holds " + size + " values, over the "
                        + MAX_LIST + " element limit for reversed()");
            }
            // The values the range produces, in the other order -- not the values
            // between its bounds, which is what a strided range must not collapse to.
            List<Long> values = iterableRange(range);
            MandelaList out = new MandelaList(values.size());
            for (int i = values.size() - 1; i >= 0; i--) {
                out.add(values.get(i));
            }
            return out;
        });
        rangeMethods.put("sum", (m, self, args) -> {
            // Closed form rather than a walk, so 0..1e12 is O(1) instead of a hang.
            MandelaRange range = (MandelaRange) self;
            long[] edges = edgesOf(range);
            long count = range.size();
            if (count == 0) {
                return 0L;
            }
            return doubledSum(edges[0], edges[1], count);
        });
        // A range is a list for reading purposes, so the sequence operations are the
        // list's own over a materialised copy rather than a second implementation
        // that could drift. Materialising is the cost, and the limit above is what
        // stops a script from paying it by accident on 0..1e12.
        for (String fromLists : List.of("map", "filter", "forEach", "any", "all", "find",
                "join", "min", "max", "distinct", "slice", "take", "skip", "sorted",
                "sortBy")) {
            rangeMethods.put(fromLists, (m, self, args) -> {
                Fn body = listMethods.get(fromLists);
                return body.call(m, materialise((MandelaRange) self, fromLists), args);
            });
        }
    }

    /** @return every value of a range as a list, bounded by {@link #MAX_LIST} */
    private static MandelaList materialise(MandelaRange range, String what) {
        long size = range.size();
        if (size > MAX_LIST) {
            throw MandelaError.limit("'" + what + "' over a range of " + size
                    + " values is over the " + MAX_LIST + " element limit;"
                    + " walk it with 'for' instead");
        }
        MandelaList out = new MandelaList((int) size);
        for (long value : iterableRange(range)) {
            out.add(value);
        }
        return out;
    }

    /** @return the inclusive bounds of a range as {first, last} */
    private static long[] edgesOf(MandelaRange range) {
        long first = range.from();
        long last = range.last();
        return new long[] { first, last };
    }

    /**
     * Walks a range as longs, the one place the exclusive flag and the stride are
     * read together. The value at position <i>i</i> is computed from the bounds
     * rather than accumulated, so a strided range cannot drift and an empty one
     * cannot loop.
     */
    private static List<Long> iterableRange(MandelaRange range) {
        long size = range.size();
        List<Long> values = new ArrayList<>((int) Math.min(size, 1024));
        for (long index = 0L; index < size; index++) {
            values.add(range.from() + range.step() * index);
        }
        return values;
    }

    /**
     * Sums an arithmetic run without iterating it, exactly while the terms fit a
     * long and in double only when the exact form would overflow.
     */
    private static Object doubledSum(long first, long last, long count) {
        try {
            return Math.multiplyExact(Math.addExact(first, last), count) / 2;
        } catch (ArithmeticException overflow) {
            return (double) count * ((double) first + (double) last) / 2.0;
        }
    }

    // -- Int / Double --------------------------------------------------------

    private void fillNumberMethods() {
        numberMethods.put("abs", (m, self, args) -> abs(self));
        numberMethods.put("sign", (m, self, args) -> {
            double value = Values.asDouble(self);
            return (long) (value < 0 ? -1 : (value > 0 ? 1 : 0));
        });
        numberMethods.put("floor", (m, self, args) -> Math.floor(Values.asDouble(self)));
        numberMethods.put("ceil", (m, self, args) -> Math.ceil(Values.asDouble(self)));
        numberMethods.put("round", (m, self, args) -> Math.round(Values.asDouble(self)));
        numberMethods.put("sqrt", (m, self, args) -> sqrt(self));
        numberMethods.put("cbrt", (m, self, args) -> Math.cbrt(Values.asDouble(self)));
        numberMethods.put("toInt", (m, self, args) -> whole(self, "toInt()"));
        numberMethods.put("toDouble", (m, self, args) -> real(self, "toDouble()"));
        numberMethods.put("isWhole", (m, self, args) -> Values.isInt(self));
    }

    // -- modules -------------------------------------------------------------

    /**
     * Builds the modules that need no host at all. {@code fs} and {@code env} are
     * deliberately missing: they answer per machine, because the grants that gate
     * them belong to the run and not to the library (see
     * {@link #dynamicModule}).
     */
    private void fillModules() {
        MandelaMap json = new MandelaMap(4);
        json.put("parse", HostFunction.of("json.parse", 1,
                args -> Json.parse(argText(args, 0, "json.parse"))));
        json.put("encode", HostFunction.of("json.encode", 1,
                args -> Json.encode(args.get(0))));
        json.put("stringify", HostFunction.of("json.stringify", 1,
                args -> Json.stringify(args.get(0))));
        json.put("isValid", HostFunction.of("json.isValid", 1,
                args -> Json.canParse(argText(args, 0, "json.isValid"))));
        modules.put("json", json);

        MandelaMap math = new MandelaMap(32);
        math.put("PI", Math.PI);
        math.put("E", Math.E);
        math.put("tau", Math.PI * 2);
        math.put("abs", HostFunction.of("math.abs", 1, args -> abs(args.get(0))));
        math.put("sign", HostFunction.of("math.sign", 1, args -> {
            double value = real(args.get(0), "math.sign");
            return (long) (value < 0 ? -1 : (value > 0 ? 1 : 0));
        }));
        math.put("floor", HostFunction.of("math.floor", 1,
                args -> Math.floor(real(args.get(0), "math.floor"))));
        math.put("ceil", HostFunction.of("math.ceil", 1,
                args -> Math.ceil(real(args.get(0), "math.ceil"))));
        math.put("round", HostFunction.of("math.round", 1,
                args -> Math.round(real(args.get(0), "math.round"))));
        math.put("sqrt", HostFunction.of("math.sqrt", 1, args -> sqrt(args.get(0))));
        math.put("cbrt", HostFunction.of("math.cbrt", 1,
                args -> Math.cbrt(real(args.get(0), "math.cbrt"))));
        math.put("pow", HostFunction.of("math.pow", 2,
                args -> Math.pow(real(args.get(0), "math.pow"),
                        real(args.get(1), "math.pow"))));
        math.put("exp", HostFunction.of("math.exp", 1,
                args -> Math.exp(real(args.get(0), "math.exp"))));
        math.put("log", HostFunction.of("math.log", 1,
                args -> Math.log(real(args.get(0), "math.log"))));
        math.put("log10", HostFunction.of("math.log10", 1,
                args -> Math.log10(real(args.get(0), "math.log10"))));
        math.put("log2", HostFunction.of("math.log2", 1,
                args -> Math.log(real(args.get(0), "math.log2")) / Math.log(2)));
        math.put("sin", HostFunction.of("math.sin", 1,
                args -> Math.sin(real(args.get(0), "math.sin"))));
        math.put("cos", HostFunction.of("math.cos", 1,
                args -> Math.cos(real(args.get(0), "math.cos"))));
        math.put("tan", HostFunction.of("math.tan", 1,
                args -> Math.tan(real(args.get(0), "math.tan"))));
        math.put("asin", HostFunction.of("math.asin", 1,
                args -> Math.asin(real(args.get(0), "math.asin"))));
        math.put("acos", HostFunction.of("math.acos", 1,
                args -> Math.acos(real(args.get(0), "math.acos"))));
        math.put("atan", HostFunction.of("math.atan", 1,
                args -> Math.atan(real(args.get(0), "math.atan"))));
        math.put("atan2", HostFunction.of("math.atan2", 2,
                args -> Math.atan2(real(args.get(0), "math.atan2"),
                        real(args.get(1), "math.atan2"))));
        math.put("hypot", HostFunction.of("math.hypot", 2,
                args -> Math.hypot(real(args.get(0), "math.hypot"),
                        real(args.get(1), "math.hypot"))));
        math.put("min", HostFunction.of("math.min", 2,
                args -> pick(args, true)));
        math.put("max", HostFunction.of("math.max", 2,
                args -> pick(args, false)));
        math.put("random", HostFunction.of("math.random", 0,
                args -> ThreadLocalRandom.current().nextDouble()));
        math.put("randomInt", HostFunction.of("math.randomInt", 2, args -> {
            long low = argWhole(args, 0, "math.randomInt");
            long high = argWhole(args, 1, "math.randomInt");
            if (high < low) {
                throw MandelaError.value("math.randomInt needs the second bound"
                        + " at or above the first; got " + low + " and " + high);
            }
            if (high == Long.MAX_VALUE) {
                throw MandelaError.limit("math.randomInt cannot include Long.MAX_VALUE");
            }
            return ThreadLocalRandom.current().nextLong(low, high + 1);
        }));
        modules.put("math", math);
    }

    /**
     * The time module. It is built per machine rather than once, because
     * {@code time.sleep} blocks the thread the host is running on: a page script
     * must not be able to freeze the browser's UI thread, so the call asserts the
     * {@link Capabilities.Grant#THREAD} grant of the run that asks for it.
     *
     * @param machine the interpreter meeting the {@code use}
     * @return the module
     */
    private MandelaMap timeModule(Machine machine) {
        MandelaMap time = new MandelaMap(8);
        time.put("now", HostFunction.of("time.now", 0, args -> System.currentTimeMillis()));
        time.put("nanoTime", HostFunction.of("time.nanoTime", 0, args -> System.nanoTime()));
        time.put("iso", HostFunction.of("time.iso", -1, args -> {
            long millis = args.isEmpty() ? System.currentTimeMillis()
                    : argWhole(args, 0, "time.iso");
            return Instant.ofEpochMilli(millis).toString();
        }));
        time.put("format", HostFunction.of("time.format", -1, args -> {
            long millis = args.isEmpty() ? System.currentTimeMillis()
                    : argWhole(args, 0, "time.format");
            String pattern = (args.size() > 1) ? text(args.get(1)) : "yyyy-MM-dd HH:mm:ss";
            DateTimeFormatter formatter;
            try {
                formatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
            } catch (RuntimeException badPattern) {
                throw MandelaError.value("'" + pattern + "' is not a time pattern: "
                        + badPattern.getMessage());
            }
            return formatter.format(Instant.ofEpochMilli(millis)
                    .atZone(ZoneId.systemDefault()));
        }));
        time.put("fields", HostFunction.of("time.fields", -1, args -> {
            long millis = args.isEmpty() ? System.currentTimeMillis()
                    : argWhole(args, 0, "time.fields");
            var zoned = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault());
            MandelaMap out = new MandelaMap(10);
            out.put("year", (long) zoned.getYear());
            out.put("month", (long) zoned.getMonthValue());
            out.put("day", (long) zoned.getDayOfMonth());
            out.put("hour", (long) zoned.getHour());
            out.put("minute", (long) zoned.getMinute());
            out.put("second", (long) zoned.getSecond());
            out.put("millis", (long) (zoned.getNano() / 1_000_000));
            out.put("dayOfWeek", (long) zoned.getDayOfWeek().getValue());
            out.put("zone", zoned.getZone().getId());
            return out;
        }));
        time.put("parse", HostFunction.of("time.parse", 1, args -> {
            String iso = argText(args, 0, "time.parse");
            try {
                return Instant.parse(iso).toEpochMilli();
            } catch (RuntimeException notAnInstant) {
                throw MandelaError.value("'" + iso + "' is not an ISO-8601 instant"
                        + " such as 2026-10-10T12:00:00Z");
            }
        }));
        time.put("sleep", HostFunction.of("time.sleep", 1, args -> {
            double seconds = real(args.get(0), "time.sleep");
            if (seconds < 0) {
                throw MandelaError.value("time.sleep needs 0 seconds or more");
            }
            machine.capabilities().require(Capabilities.Grant.THREAD, "time.sleep");
            try {
                Thread.sleep(Math.round(seconds * 1000));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw MandelaError.of("Interrupted", "time.sleep was interrupted");
            }
            return null;
        }));
        time.put("zone", HostFunction.of("time.zone", 0,
                args -> ZoneId.systemDefault().getId()));
        return time;
    }

    /** Names that answer per machine, because they read the run's capabilities. */
    private static final List<String> GATED_MODULES = List.of("fs", "env", "time");

    /**
     * Builds a module that has to know which machine is asking.
     *
     * @param machine the interpreter meeting the {@code use}
     * @param name    the short module name
     * @return the module, or null when this library has no such name
     */
    private MandelaMap dynamicModule(Machine machine, String name) {
        switch (name) {
            case "fs":
                return fsModule(machine);
            case "env":
                return envModule(machine);
            case "time":
                return timeModule(machine);
            default:
                return null;
        }
    }

    /**
     * The filesystem module, every entry of which asserts its grant before it
     * resolves a path. With no gateway the module still exists &mdash; so a script
     * can {@code use std.fs} and test it &mdash; but every call explains that the
     * host has no files.
     */
    private MandelaMap fsModule(Machine machine) {
        MandelaMap fs = new MandelaMap(16);
        fs.put("read", HostFunction.of("fs.read", 1,
                args -> gateway(machine).read(machine.capabilities(),
                        argText(args, 0, "fs.read"))));
        fs.put("lines", HostFunction.of("fs.lines", 1,
                args -> gateway(machine).readLines(machine.capabilities(),
                        argText(args, 0, "fs.lines"))));
        fs.put("write", HostFunction.of("fs.write", 2, args -> {
            gateway(machine).write(machine.capabilities(), argText(args, 0, "fs.write"),
                    argText(args, 1, "fs.write"));
            return null;
        }));
        fs.put("append", HostFunction.of("fs.append", 2, args -> {
            gateway(machine).append(machine.capabilities(), argText(args, 0, "fs.append"),
                    argText(args, 1, "fs.append"));
            return null;
        }));
        fs.put("exists", HostFunction.of("fs.exists", 1,
                args -> gateway(machine).exists(machine.capabilities(),
                        argText(args, 0, "fs.exists"))));
        fs.put("isDirectory", HostFunction.of("fs.isDirectory", 1,
                args -> gateway(machine).isDirectory(machine.capabilities(),
                        argText(args, 0, "fs.isDirectory"))));
        fs.put("list", HostFunction.of("fs.list", -1,
                args -> gateway(machine).list(machine.capabilities(),
                        args.isEmpty() ? "." : argText(args, 0, "fs.list"))));
        fs.put("mkdir", HostFunction.of("fs.mkdir", 1, args -> {
            gateway(machine).makeDirectory(machine.capabilities(),
                    argText(args, 0, "fs.mkdir"));
            return null;
        }));
        fs.put("delete", HostFunction.of("fs.delete", 1,
                args -> gateway(machine).delete(machine.capabilities(),
                        argText(args, 0, "fs.delete"))));
        fs.put("size", HostFunction.of("fs.size", 1,
                args -> gateway(machine).size(machine.capabilities(),
                        argText(args, 0, "fs.size"))));
        fs.put("modified", HostFunction.of("fs.modified", 1,
                args -> gateway(machine).modifiedAt(machine.capabilities(),
                        argText(args, 0, "fs.modified"))));
        fs.put("root", HostFunction.of("fs.root", 0, args -> {
            java.nio.file.Path root = machine.capabilities().fileRoot();
            return (root == null) ? null : root.toString();
        }));
        return fs;
    }

    /** The environment module, which is only ever meaningful with the ENV grant. */
    private MandelaMap envModule(Machine machine) {
        MandelaMap env = new MandelaMap(4);
        env.put("get", HostFunction.of("env.get", -1, args -> {
            machine.capabilities().require(Capabilities.Grant.ENV, "env.get");
            String name = argText(args, 0, "env.get");
            String value = System.getenv(name);
            if (value != null) {
                return value;
            }
            return (args.size() > 1) ? args.get(1) : null;
        }));
        env.put("has", HostFunction.of("env.has", 1, args -> {
            machine.capabilities().require(Capabilities.Grant.ENV, "env.has");
            return System.getenv(argText(args, 0, "env.has")) != null;
        }));
        env.put("names", HostFunction.of("env.names", 0, args -> {
            machine.capabilities().require(Capabilities.Grant.ENV, "env.names");
            return listOf(new ArrayList<>(System.getenv().keySet()));
        }));
        env.put("user", HostFunction.of("env.user", 0,
                args -> System.getProperty("user.name", "")));
        env.put("os", HostFunction.of("env.os", 0,
                args -> System.getProperty("os.name", "")));
        env.put("separator", java.io.File.separator);
        env.put("pathRoots", HostFunction.of("env.pathRoots", 0, args -> {
            machine.capabilities().require(Capabilities.Grant.ENV, "env.pathRoots");
            return listOf(java.nio.file.Path.of("").getFileSystem().getRootDirectories());
        }));
        return env;
    }

    private Files gateway(Machine machine) {
        if (files == null) {
            Capabilities caps = machine.capabilities();
            throw MandelaError.permission("this host (" + caps.label()
                    + ") has no filesystem for std.fs to use");
        }
        return files;
    }

    // -- module lookups ------------------------------------------------------

    /** @return the module names a {@code use} statement can ask for */
    public List<String> moduleNames() {
        List<String> names = new ArrayList<>(modules.keySet());
        names.addAll(GATED_MODULES);
        return names;
    }

    /**
     * The members one built-in type answers to, for a tool that lists them.
     *
     * <p>This is the same table {@link #member} resolves against, enumerated rather
     * than called, so an editor's completion popup cannot run a library function or
     * need a capability to fill itself. The order is the resolution order too:
     * properties before methods, because a property read shadows a method of the
     * same name.</p>
     *
     * <p>A {@code Bool} carries no members and an unknown type name answers empty
     * rather than guessing: {@code typeOf} is the authority on what a value is, and
     * a name invented here would be offered to the developer and then fail at
     * runtime.</p>
     *
     * @param typeName {@code Str}, {@code List}, {@code Map}, {@code Range}, or a
     *                 number under any of the names the language uses for one
     * @return the member names, empty when the type has none
     */
    public List<String> valueMemberNames(String typeName) {
        if (typeName == null) {
            return List.of();
        }
        return switch (typeName) {
            case "Str" -> union(stringProps.keySet(), stringMethods.keySet());
            case "List" -> union(listProps.keySet(), listMethods.keySet());
            case "Map" -> union(mapProps.keySet(), mapMethods.keySet());
            case "Range" -> union(rangeProps.keySet(), rangeMethods.keySet());
            case "Int", "Num", "Double" -> List.copyOf(numberMethods.keySet());
            default -> List.of();
        };
    }

    /** Two tables in lookup order, without a name appearing twice. */
    private static List<String> union(Set<String> props, Set<String> methods) {
        Set<String> names = new LinkedHashSet<>(props);
        names.addAll(methods);
        return List.copyOf(names);
    }

    // -- argument helpers ----------------------------------------------------

    /** @return the argument at {@code index}, or null when the call site omitted it */
    private static Object arg(List<Object> args, int index) {
        return (index < args.size()) ? args.get(index) : null;
    }

    private static String argText(List<Object> args, int index, String what) {
        Object value = arg(args, index);
        if (value == null || Values.isAbsent(value)) {
            throw MandelaError.value(what + " needs " + (index == 0 ? "an argument"
                    : "its " + (index + 1) + "th argument"));
        }
        return text(value);
    }

    private static long argWhole(List<Object> args, int index, String what) {
        Object value = arg(args, index);
        if (value == null || Values.isAbsent(value)) {
            throw MandelaError.value(what + " needs " + (index == 0 ? "an argument"
                    : "its " + (index + 1) + "th argument"));
        }
        return whole(value, what);
    }

    /** Clamps an index into {@code size}, counting from the end when negative. */
    private static int clampIndex(long at, int size) {
        long real = (at < 0) ? size + at : at;
        if (real < 0) {
            return 0;
        }
        return (int) Math.min(real, size);
    }

    private static int clampStart(long from, int size) {
        return clampIndex(from, size);
    }

    private static int clampEnd(long to, int size) {
        return clampIndex(to, size);
    }

    /** A strict index for the calls that must not silently clamp. */
    private static int indexArg(long at, int bound) {
        long real = (at < 0) ? bound + at : at;
        if (real < 0 || real >= bound) {
            throw MandelaError.index("the index " + at + " is out of range for "
                    + bound + " element(s)");
        }
        return (int) real;
    }

    /** Copies any iterable into a fresh MandelaList. */
    private static MandelaList listOf(Iterable<?> source) {
        MandelaList out = new MandelaList(8);
        for (Object value : source) {
            out.add(value);
        }
        return out;
    }

    /** Caps {@code repeat}/{@code join} output, so a bug cannot eat the heap. */
    private static final int MAX_TEXT = 1 << 24;

    /** Caps {@code toList}, for the same reason on the other side of the API. */
    private static final int MAX_LIST = 1 << 22;

    private static Object sqrt(Object value) {
        double argument = real(value, "sqrt");
        if (argument < 0) {
            throw MandelaError.value("sqrt needs a number of 0 or more; got " + argument);
        }
        return Math.sqrt(argument);
    }

    /** any/all with no callback fall back to the truthiness of each element. */
    private static Object testList(Machine machine, Object self, List<Object> args,
                                   boolean anyForm) {
        String what = anyForm ? "any" : "all";
        MandelaList list = needList(self, what);
        Object given = arg(args, 0);
        Callable callback = (given == null) ? null : needCallable(given, what);
        for (int i = 0; i < list.size(); i++) {
            Object value = list.get(i);
            boolean held = (callback == null) ? Values.truthy(value)
                    : Values.truthy(invoke(machine, callback, value, (long) i));
            if (anyForm && held) {
                return Boolean.TRUE;
            }
            if (!anyForm && !held) {
                return Boolean.FALSE;
            }
        }
        return anyForm ? Boolean.FALSE : Boolean.TRUE;
    }

    /**
     * The order {@code sort} uses: natural when called bare, otherwise the
     * callback is a comparator and its Int result is the answer.
     */
    private static Comparator<Object> comparatorFor(Machine machine, Object callback) {
        if (callback == null) {
            return Values::compare;
        }
        Callable body = needCallable(callback, "sort");
        return (left, right) -> Long.compare(
                whole(invoke(machine, body, left, right), "the sort callback"), 0L);
    }

    /**
     * The order {@code sortBy} uses. The key function is called inside the
     * comparison rather than once per element: it keeps the implementation honest
     * about a callback that returns a fresh value per call, and a list a script
     * sorts is a list a script can read.
     */
    private static Comparator<Object> keyComparator(Machine machine, Object callback) {
        Callable body = needCallable(callback, "sortBy");
        return (left, right) -> Values.compare(
                invoke(machine, body, left), invoke(machine, body, right));
    }

    /** Sorts in place and hands the list back, which is what a chain expects. */
    private static Object sortInto(MandelaList list, Comparator<Object> order) {
        List<Object> items = new ArrayList<>(list.items());
        items.sort(order);
        list.clear();
        for (Object value : items) {
            list.add(value);
        }
        return list;
    }

    // -- shared helpers ------------------------------------------------------

    private static MandelaError badShape(String what, String expected, Object value) {
        return MandelaError.type(what + " needs a " + expected + "; it got a "
                + Values.typeName(value));
    }

    private static String text(Object value) {
        if (value instanceof String text) {
            return text;
        }
        // Reading a member off a number is a script bug, but printing one is not,
        // so the coercion is only offered where the argument is obviously text.
        throw MandelaError.type("expected a Str; got a " + Values.typeName(value));
    }

    private static long whole(Object value, String what) {
        if (value instanceof Long || value instanceof Integer) {
            return Values.asLong(value);
        }
        if (value instanceof Double real) {
            if (real != Math.rint(real) || real.isInfinite() || real.isNaN()) {
                throw MandelaError.value("'" + what + "' needs a whole number; got " + real);
            }
            return real.longValue();
        }
        if (value instanceof String text) {
            Long parsed = Values.parseLong(text);
            if (parsed != null) {
                return parsed;
            }
        }
        throw MandelaError.type("'" + what + "' needs an Int; got a "
                + Values.typeName(value));
    }

    private static double real(Object value, String what) {
        if (Values.isNum(value)) {
            return Values.asDouble(value);
        }
        if (value instanceof String text) {
            Double parsed = Values.parseNumber(text);
            if (parsed != null) {
                return parsed;
            }
        }
        throw MandelaError.type("'" + what + "' needs a number; got a "
                + Values.typeName(value));
    }

    private static Callable needCallable(Object value, String what) {
        if (value instanceof Callable callable) {
            return callable;
        }
        throw MandelaError.type(what + " needs a function to call; got a "
                + Values.typeName(value));
    }

    private static MandelaList needList(Object value, String what) {
        if (value instanceof MandelaList list) {
            return list;
        }
        throw badShape(what, "list", value);
    }

    private static Object pick(List<Object> args, boolean smallest) {
        if (args.isEmpty()) {
            throw MandelaError.value("min(...) / max(...) need at least one value");
        }
        Object best = args.get(0);
        for (Object candidate : args) {
            int order = Values.compare(candidate, best);
            if (smallest ? order < 0 : order > 0) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Calls a script callback from the library, handing it what it can take.
     *
     * <p>{@code map} knows the index as well as the value, and a script that does
     * not care writes {@code { x -> x * 2 }}. The machine checks argument counts on
     * script-to-script calls &mdash; right, because a typo there is a bug &mdash;
     * but a builtin is not the script's caller in that sense, so the extra
     * arguments are dropped here instead of failing a call the author wrote on
     * purpose.</p>
     */
    private static Object invoke(Machine machine, Callable target, Object... args) {
        int arity = target.arity();
        if (arity >= 0 && !target.isVariadic() && arity < args.length) {
            Object[] trimmed = new Object[arity];
            System.arraycopy(args, 0, trimmed, 0, arity);
            return machine.invoke(target, null, trimmed);
        }
        return machine.invoke(target, null, args);
    }
}

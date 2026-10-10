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
package org.jdesktop.lg3d.mandela.values;

import java.util.List;

/**
 * An instance of a declared type.
 *
 * <p>Fields live in one {@link MandelaMap} keyed by name. That is slower than a
 * slot array and is the right trade for a scripting desktop: a script can add a
 * field to an object it did not declare, {@code str()} prints every field
 * whatever the class, the JSON encoder can serialise any instance without the
 * class cooperating, and the field set is exactly what an editor needs to offer
 * as completion.</p>
 *
 * <p>Equality depends on the declaration kind. A {@code type} is a value: two
 * {@code Point(1, 2)} instances are equal, which is what makes value semantics
 * worth having. A {@code class} is an identity: two {@code Window} objects with
 * the same bounds are still two windows. An {@code enum} constant is created
 * once, so identity equality already gives the singleton behaviour.</p>
 */
public final class MandelaInstance {

    private final MandelaClass owner;
    private final MandelaMap fields;

    /** Creates an instance of {@code owner} with room for its declared fields. */
    public MandelaInstance(MandelaClass owner) {
        this.owner = owner;
        this.fields = new MandelaMap(Math.max(8, owner.fieldNames().size() * 2));
    }

    /** Creates an instance pre-filled with {@code initial}. */
    public MandelaInstance(MandelaClass owner, MandelaMap initial) {
        this.owner = owner;
        this.fields = initial;
    }

    /** @return the declaration this value was made from */
    public MandelaClass owner() {
        return owner;
    }

    /** @return the type name used by {@code str()}, {@code is} and diagnostics */
    public String typeName() {
        return owner.name();
    }

    /**
     * @param name the field name
     * @return the value, or {@code null} when the field was never assigned
     */
    public Object get(String name) {
        return fields.get(name);
    }

    /** Assigns a field, creating it when the class did not declare it. */
    public void set(String name, Object value) {
        fields.put(name, value);
    }

    /** @return true when the field has been assigned at least once */
    public boolean has(String name) {
        return fields.contains(name);
    }

    /** @return the assigned field names, in assignment order */
    public List<String> fieldNames() {
        return fields.keys();
    }

    /** @return the live field map; the runtime uses it for iteration and serialisation */
    public MandelaMap fields() {
        return fields;
    }

    /** @return the number of assigned fields */
    public int size() {
        return fields.size();
    }

    /**
     * Structural equality for a {@code type}, identity for a {@code class} or an
     * {@code enum} constant.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MandelaInstance instance)) {
            return false;
        }
        if (owner.kind() == MandelaClass.Kind.TYPE) {
            return owner == instance.owner && fields.equals(instance.fields);
        }
        return false;
    }

    /** @return a hash consistent with {@link #equals(Object)} */
    @Override
    public int hashCode() {
        if (owner.kind() == MandelaClass.Kind.TYPE) {
            return System.identityHashCode(owner) * 31 + fields.hashCode();
        }
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return Values.display(this);
    }
}

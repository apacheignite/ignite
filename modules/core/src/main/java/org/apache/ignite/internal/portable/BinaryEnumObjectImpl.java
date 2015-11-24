/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.portable;

import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.binary.BinaryObjectException;
import org.apache.ignite.binary.BinaryType;
import org.apache.ignite.internal.util.typedef.internal.SB;
import org.jetbrains.annotations.Nullable;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

/**
 * Binary enum object.
 */
public class BinaryEnumObjectImpl implements BinaryObject, Externalizable {
    /** Context. */
    private PortableContext ctx;

    /** Type ID. */
    private int typeId;

    /** Raw data. */
    private String clsName;

    /** Ordinal. */
    private int ord;

    /**
     * {@link Externalizable} support.
     */
    public BinaryEnumObjectImpl() {
        // No-op.
    }

    /**
     * Constructor.
     *
     * @param ctx Context.
     * @param typeId Type ID.
     * @param clsName Class name.
     * @param ord Ordinal.
     */
    public BinaryEnumObjectImpl(PortableContext ctx, int typeId, @Nullable String clsName, int ord) {
        assert ctx != null;

        this.ctx = ctx;
        this.typeId = typeId;
        this.clsName = clsName;
        this.ord = ord;
    }

    /**
     * @return Class name.
     */
    @Nullable public String className() {
        return clsName;
    }

    /** {@inheritDoc} */
    @Override public int typeId() {
        return typeId;
    }

    /** {@inheritDoc} */
    @Override public BinaryType type() throws BinaryObjectException {
        return ctx.metadata(typeId());
    }

    /** {@inheritDoc} */
    @Override public <F> F field(String fieldName) throws BinaryObjectException {
        return null;
    }

    /** {@inheritDoc} */
    @Override public boolean hasField(String fieldName) {
        return false;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public <T> T deserialize() throws BinaryObjectException {
        Class cls = PortableUtils.resolveClass(ctx, typeId, clsName, null);

        return BinaryEnumCache.get(cls, ord);
    }

    /** {@inheritDoc} */
    @Override public BinaryObject clone() throws CloneNotSupportedException {
        return (BinaryObject)super.clone();
    }

    /** {@inheritDoc} */
    @Override public int enumOrdinal() throws BinaryObjectException {
        return ord;
    }

    /** {@inheritDoc} */
    @Override public int hashCode() {
        return 31 * typeId + ord;
    }

    /** {@inheritDoc} */
    @Override public boolean equals(Object obj) {
        if (obj != null && (obj instanceof BinaryEnumObjectImpl)) {
            BinaryEnumObjectImpl other = (BinaryEnumObjectImpl)obj;

            return typeId == other.typeId && ord == other.ord;
        }

        return false;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        // 1. Try deserializing the object.
        try {
            Object val = deserialize();

            return new SB().a(val).toString();
        }
        catch (Exception e) {
            // No-op.
        }

        // 2. Try getting meta.
        BinaryType type;

        try {
            type = type();
        }
        catch (Exception e) {
            type = null;
        }

        if (type != null) {
            return type.typeName() + "[ordinal=" + ord  + ']';
        }
        else {
            if (typeId == GridPortableMarshaller.UNREGISTERED_TYPE_ID)
                return "BinaryEnum[clsName=" + clsName + ", ordinal=" + ord + ']';
            else
                return "BinaryEnum[typeId=" + typeId + ", ordinal=" + ord + ']';
        }
    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject(ctx);

        out.writeInt(typeId);
        out.writeObject(clsName);
        out.writeInt(ord);
    }

    /** {@inheritDoc} */
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        ctx = (PortableContext)in.readObject();

        typeId = in.readInt();
        clsName = (String)in.readObject();
        ord = in.readInt();
    }
}
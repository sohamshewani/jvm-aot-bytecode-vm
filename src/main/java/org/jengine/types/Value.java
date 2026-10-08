package org.jengine.types;

import java.lang.invoke.VarHandle;
import java.util.Objects;

/**
 * High-performance 64-bit NaN-boxed tagged union representing dynamic runtime values 
 * for the jvm-aot-bytecode-vm.
 * 
 * <p>NaN Boxing Layout (IEEE 754-2008 double precision standard):
 * <ul>
 *   <li>Quiet NaN canonical mask: 0x7FF8000000000000L</li>
 *   <li>Tag Mask:                  0x0007000000000000L (Bits 48-50)</li>
 *   <li>Payload Mask:              0x0000FFFFFFFFFFFFL (Bits 0-47, 48-bit address/data space)</li>
 * </ul>
 * 
 * <p>Tag Definitions:
 * <ul>
 *   <li>0x0: TAG_DOUBLE  (Standard IEEE 754 double precision floating point, non-NaN values)</li>
 *   <li>0x1: TAG_INT     (32-bit signed integer stored in lower 32 bits of payload)</li>
 *   <li>0x2: TAG_LONG    (48-bit sign-extended long integer packed into payload)</li>
 *   <li>0x3: TAG_BOOL    (Boolean value stored in bit 0 of payload: 0=false, 1=true)</li>
 *   <li>0x4: TAG_NIL     (Null / Unit reference)</li>
 *   <li>0x5: TAG_OBJECT  (Heap object reference, 48-bit compressed oop / direct memory address)</li>
 * </ul>
 */
public final class Value {

    // IEEE 754 Double Quiet NaN base for tagged pointers and non-double primitives
    private static final long QNAN = 0x7FF8000000000000L;

    // Tag field bitmask and shift
    private static final long TAG_MASK   = 0x0007000000000000L;
    private static final int  TAG_SHIFT  = 48;

    // Payload bitmask (lower 48 bits)
    private static final long PAYLOAD_MASK = 0x0000FFFFFFFFFFFFL;

    // Type tags
    public static final long TAG_DOUBLE  = 0x0L;
    public static final long TAG_INT     = 0x1L;
    public static final long TAG_LONG    = 0x2L;
    public static final long TAG_BOOL    = 0x3L;
    public static final long TAG_NIL     = 0x4L;
    public static final long TAG_OBJECT  = 0x5L;

    // Canonical representation instances
    public static final Value NIL = new Value(QNAN | (TAG_NIL << TAG_SHIFT));
    public static final Value TRUE = new Value(QNAN | (TAG_BOOL << TAG_SHIFT) | 1L);
    public static final Value FALSE = new Value(QNAN | (TAG_BOOL << TAG_SHIFT) | 0L);

    // Raw 64-bit NaN-boxed representation
    private final long rawBits;

    /**
     * Private constructor for internal raw bit wrapping.
     * 
     * @param rawBits the 64-bit NaN-boxed representation
     */
    private Value(long rawBits) {
        this.rawBits = rawBits;
    }

    /**
     * Factory method to wrap raw 64-bit NaN-boxed bits directly.
     * 
     * @param rawBits the 64-bit NaN-boxed representation
     * @return a Value instance
     */
    public static Value fromRawBits(long rawBits) {
        return new Value(rawBits);
    }

    /**
     * Retrieves the underlying raw 64-bit NaN-boxed bit pattern.
     * 
     * @return raw bit representation
     */
    public long getRawBits() {
        return rawBits;
    }

    /**
     * Extracts the runtime tag of this Value.
     * 
     * @return tag identifier (0 to 5)
     */
    public int getTag() {
        if ((rawBits & QNAN) != QNAN) {
            return (int) TAG_DOUBLE;
        }
        return (int) ((rawBits & TAG_MASK) >>> TAG_SHIFT);
    }

    // =========================================================================
    // Factory Methods for Primitive and Object Creation
    // =========================================================================

    /**
     * Creates a Value from a standard 64-bit double precision float.
     * 
     * @param value the double value
     * @return NaN-boxed Value representation
     */
    public static Value valueOf(double value) {
        long bits = Double.doubleToRawLongBits(value);
        // If the value is a NaN, normalize it to prevent tag collision
        if ((bits & QNAN) == QNAN) {
            bits = QNAN; // Canonical NaN
        }
        return new Value(bits);
    }

    /**
     * Creates a Value from a 32-bit signed integer.
     * 
     * @param value the integer value
     * @return NaN-boxed Value representation
     */
    public static Value valueOf(int value) {
        long payload = ((long) value) & 0xFFFFFFFFL;
        return new Value(QNAN | (TAG_INT << TAG_SHIFT) | payload);
    }

    /**
     * Creates a Value from a 64-bit long integer. Values exceeding 48 bits 
     * will truncate or require boxing depending on application constraints; 
     * this implementation enforces a 48-bit sign-extension check.
     * 
     * @param value the long value
     * @return NaN-boxed Value representation
     */
    public static Value valueOf(long value) {
        if (value < -140737488355328L || value > 140737488355327L) {
            throw new IllegalArgumentException("Long value out of 48-bit NaN-boxed payload range: " + value);
        }
        long payload = value & PAYLOAD_MASK;
        return new Value(QNAN | (TAG_LONG << TAG_SHIFT) | payload);
    }

    /**
     * Creates a Value from a boolean condition.
     * 
     * @param value the boolean value
     * @return NaN-boxed Value representation
     */
    public static Value valueOf(boolean value) {
        return value ? TRUE : FALSE;
    }

    /**
     * Creates a Value wrapping a heap object reference address (compressed oop or direct pointer).
     * 
     * @param address 48-bit memory address or object reference handle
     * @return NaN-boxed Value representation
     */
    public static Value ofObject(long address) {
        if ((address & ~PAYLOAD_MASK) != 0) {
            throw new IllegalArgumentException("Object reference address exceeds 48-bit payload limit: 0x" + Long.toHexString(address));
        }
        return new Value(QNAN | (TAG_OBJECT << TAG_SHIFT) | address);
    }

    // =========================================================================
    // Type Check Predicates
    // =========================================================================

    public boolean isDouble() {
        return (rawBits & QNAN) != QNAN;
    }

    public boolean isInt() {
        return (rawBits & QNAN) == QNAN && (((rawBits & TAG_MASK) >>> TAG_SHIFT) == TAG_INT);
    }

    public boolean isLong() {
        return (rawBits & QNAN) == QNAN && (((rawBits & TAG_MASK) >>> TAG_SHIFT) == TAG_LONG);
    }

    public boolean isBoolean() {
        return (rawBits & QNAN) == QNAN && (((rawBits & TAG_MASK) >>> TAG_SHIFT) == TAG_BOOL);
    }

    public boolean isNil() {
        return rawBits == NIL.rawBits;
    }

    public boolean isObject() {
        return (rawBits & QNAN) == QNAN && (((rawBits & TAG_MASK) >>> TAG_SHIFT) == TAG_OBJECT);
    }

    public boolean isNumber() {
        return isDouble() || isInt() || isLong();
    }

    // =========================================================================
    // Unboxing and Value Extraction
    // =========================================================================

    public double asDouble() {
        if (isDouble()) {
            return Double.longBitsToDouble(rawBits);
        } else if (isInt()) {
            return (double) asInt();
        } else if (isLong()) {
            return (double) asLong();
        }
        throw new ClassCastException("Value cannot be coerced to double: tag=" + getTag());
    }

    public int asInt() {
        if (isInt()) {
            return (int) (rawBits & 0xFFFFFFFFL);
        } else if (isDouble()) {
            return (int) Double.longBitsToDouble(rawBits);
        } else if (isLong()) {
            return (int) asLong();
        }
        throw new ClassCastException("Value cannot be coerced to int: tag=" + getTag());
    }

    public long asLong() {
        if (isLong() || isInt()) {
            long payload = rawBits & PAYLOAD_MASK;
            // Sign extension from bit 47
            if ((payload & 0x0000800000000000L) != 0L) {
                payload |= 0xFFFF000000000000L;
            }
            return payload;
        } else if (isDouble()) {
            return (long) Double.longBitsToDouble(rawBits);
        }
        throw new ClassCastException("Value cannot be coerced to long: tag=" + getTag());
    }

    public boolean asBoolean() {
        if (isBoolean()) {
            return (rawBits & 1L) != 0L;
        }
        throw new ClassCastException("Value is not a boolean: tag=" + getTag());
    }

    public long asObjectAddress() {
        if (isObject()) {
            return rawBits & PAYLOAD_MASK;
        }
        throw new ClassCastException("Value is not an object reference: tag=" + getTag());
    }

    // =========================================================================
    // Object Overrides
    // =========================================================================

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        Value other = (Value) obj;
        // Handle IEEE 754 NaN bitwise equality equivalence or direct bit match
        if (this.isDouble() && other.isDouble()) {
            return Double.doubleToRawLongBits(this.asDouble()) == Double.doubleToRawLongBits(other.asDouble());
        }
        return this.rawBits == other.rawBits;
    }

    @Override
    public int hashCode() {
        if (isDouble()) {
            long v = Double.doubleToRawLongBits(asDouble());
            return Long.hashCode(v);
        }
        return Long.hashCode(rawBits);
    }

    @Override
    public String toString() {
        if (isDouble()) {
            return Double.toString(asDouble());
        } else if (isInt()) {
            return Integer.toString(asInt());
        } else if (isLong()) {
            return Long.toString(asLong());
        } else if (isBoolean()) {
            return Boolean.toString(asBoolean());
        } else if (isNil()) {
            return "nil";
        } else if (isObject()) {
            return "object@0x" + Long.toHexString(asObjectAddress());
        }
        return "unknown(raw=0x" + Long.toHexString(rawBits) + ")";
    }
}
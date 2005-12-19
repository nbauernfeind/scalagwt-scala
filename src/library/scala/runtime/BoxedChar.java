/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2002-2005, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */
package scala.runtime;

public class BoxedChar extends BoxedNumber
    implements java.io.Serializable
{

    private static final int MinHashed = 0;
    private static final int MaxHashed = 255;
    private static BoxedChar[] canonical = new BoxedChar[MaxHashed - MinHashed + 1];

    static {
	for (int i = MinHashed; i <= MaxHashed; i++)
	    canonical[i - MinHashed] = new BoxedChar((char)i);
    }

    public static BoxedChar box(char value) {
	if (MinHashed <= value && value <= MaxHashed) return canonical[value - MinHashed];
	else return new BoxedChar(value);
    }

    public final char value;

    private BoxedChar(char value) { this.value = value; }

    public byte byteValue() { return (byte)value; }
    public short shortValue() { return (short)value; }
    public char charValue() { return (char)value; }
    public int intValue() { return (int)value; }
    public long longValue() { return (long)value; }
    public float floatValue() { return (float)value; }
    public double doubleValue() { return (double)value; }

    public final boolean $eq$eq(java.lang.Object other) {
        return equals(other);
    }

    public final boolean $bang$eq(java.lang.Object other) {
        return !equals(other);
    }

    public boolean equals(java.lang.Object other) {
	return other instanceof BoxedNumber && value == ((BoxedNumber) other).charValue();
    }

    public int hashCode() {
	return value;
    }

    public String toString() {
	return String.valueOf(value);
    }
}
package unlucky.utility.client.util;

/**
 * Tags a mask colour with how wide the ESP composite should draw it.
 *
 * <p>The mask only carries colour, so the tag rides in the low three bits of blue: {@code 011}
 * means "add the extra width". Vanilla's team and glowing colours are built from 0x00, 0x55,
 * 0xAA and 0xFF, whose low bits are 000, 101, 010 and 111, so none of them reads as tagged.
 * Shifting blue by at most 7/255 is invisible.
 *
 * <p>A wide colour can also carry an id in the low three bits of red and green, so two wide
 * silhouettes of the same colour that touch on screen still read as two: the composite draws
 * a wide silhouette's edge wherever its exact colour ends, not only where the mask does.
 */
public final class EspColors {
	private static final int MASK = 0x7;
	private static final int WIDE = 0x3;

	private EspColors() {
	}

	/** The colour, tagged to be drawn wider by the composite's extra width. */
	public static int wide(int argb) {
		return (argb & ~MASK) | WIDE;
	}

	/** {@link #wide(int)}, told apart from its neighbours by one of 64 ids. */
	public static int wide(int argb, int id) {
		int red = (id >> 3) & MASK, green = id & MASK;
		return wide(argb & ~(MASK << 16) & ~(MASK << 8)) | red << 16 | green << 8;
	}

	/** The colour, nudged off the wide tag if it happened to carry it. */
	public static int plain(int argb) {
		return (argb & MASK) == WIDE ? argb ^ 1 : argb;
	}
}

package unlucky.utility.client.util;

import java.util.function.Supplier;

/**
 * Marks the one synthetic player render-state extraction used by Freecam's F5
 * spectator-head proxy.  The real local-player state must stay untouched so
 * the body remains rendered at its actual world position.
 */
public final class FreecamRenderProxy {
	private static final ThreadLocal<Integer> EXTRACTION_DEPTH = ThreadLocal.withInitial(() -> 0);

	private FreecamRenderProxy() {
	}

	public static boolean isExtracting() {
		return EXTRACTION_DEPTH.get() > 0;
	}

	public static <T> T extract(Supplier<T> action) {
		EXTRACTION_DEPTH.set(EXTRACTION_DEPTH.get() + 1);
		try {
			return action.get();
		} finally {
			int remaining = EXTRACTION_DEPTH.get() - 1;
			if (remaining == 0) {
				EXTRACTION_DEPTH.remove();
			} else {
				EXTRACTION_DEPTH.set(remaining);
			}
		}
	}
}

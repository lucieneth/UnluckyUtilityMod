package unlucky.utility.client.util;

/**
 * Shared one-shot timing for the chat open animations, so the message log (slides
 * in from the left) and the input bar (rises from the bottom) start together.
 *
 * <p>{@link #noteOpen} is called every frame from the message-log hook (which runs
 * even while unfocused) to catch the closed→open edge; {@link #entrance} returns a
 * 1→0 factor over ~220ms that both hooks scale their offset by. It's exactly 0 once
 * settled or closed, which keeps the resting chat pixel-perfect.
 */
public final class ChatAnim {
	private static final long DURATION_NANOS = 220_000_000L;

	private static boolean wasOpen;
	private static long openNanos = Long.MIN_VALUE;

	private ChatAnim() {
	}

	/** Feed the current focus state each frame; stamps the time on the opening edge. */
	public static void noteOpen(boolean open) {
		if (open != wasOpen) {
			wasOpen = open;
			if (open) {
				openNanos = System.nanoTime();
			}
		}
	}

	/** Entrance factor: 1 at the instant of opening, easing to 0; always 0 when closed/settled. */
	public static float entrance(boolean open) {
		if (!open) {
			return 0f;
		}
		long elapsed = System.nanoTime() - openNanos;
		if (elapsed < 0L || elapsed >= DURATION_NANOS) {
			return 0f;
		}
		float t = elapsed / (float) DURATION_NANOS;
		float ease = 1f - (float) Math.pow(1.0 - t, 3.0); // easeOutCubic
		return 1f - ease;
	}
}

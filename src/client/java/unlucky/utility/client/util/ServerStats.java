package unlucky.utility.client.util;

/**
 * Server tick-rate estimate. The server sends {@code ClientboundSetTimePacket}
 * periodically carrying the current game-tick; dividing the tick delta by the
 * real elapsed time between two packets yields TPS regardless of how often the
 * server sends them. Smoothed and clamped to [0, 20].
 */
public final class ServerStats {
	public static volatile float tps = 20.0f;

	private static long lastGameTime = -1;
	private static long lastNanos;

	private ServerStats() {
	}

	public static void onSetTime(long gameTime) {
		long now = System.nanoTime();
		if (lastGameTime >= 0) {
			long tickDelta = gameTime - lastGameTime;
			double seconds = (now - lastNanos) / 1.0e9;
			if (tickDelta > 0 && tickDelta < 400 && seconds > 0.05) {
				float inst = (float) Math.min(tickDelta / seconds, 20.0);
				tps += (inst - tps) * 0.5f;
			} else if (tickDelta < 0 || tickDelta >= 400) {
				tps = 20.0f; // world/server change — reset rather than spike
			}
		}
		lastGameTime = gameTime;
		lastNanos = now;
	}
}

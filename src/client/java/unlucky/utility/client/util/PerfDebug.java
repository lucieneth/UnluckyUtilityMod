package unlucky.utility.client.util;

import java.util.LinkedHashMap;
import java.util.Map;

import unlucky.utility.client.UnluckyClientMod;

/**
 * Frame/tick profiler behind a flag: run with {@code -Dunlucky.perfDebug=true}
 * (or {@code UNLUCKY_PERF_DEBUG=true} in the environment, which survives the
 * gradle daemon) to log rolling avg/max per section once a second. Generalizes
 * StorageESP's {@code -Dunlucky.espDebug} pattern to the whole client
 * (done.md Phase 10 Tier 0).
 *
 * <p>{@link #ENABLED} is a {@code static final} boolean, so call sites guarded
 * by it are stripped by the JIT when the flag is off — instrumentation costs
 * nothing in normal play. Main thread only (render and tick both run there);
 * no synchronization.
 */
public final class PerfDebug {
	public static final boolean ENABLED = Boolean.getBoolean("unlucky.perfDebug")
			|| "true".equalsIgnoreCase(System.getenv("UNLUCKY_PERF_DEBUG"));

	private static final Map<String, Stat> STATS = new LinkedHashMap<>();
	private static long lastLogMs;

	private static final class Stat {
		long totalNanos;
		long maxNanos;
		int samples;
	}

	private PerfDebug() {
	}

	/** Section start; pair with {@link #end}. Only call under an {@code ENABLED} guard. */
	public static long begin() {
		return System.nanoTime();
	}

	public static void end(String section, long startNanos) {
		long elapsed = System.nanoTime() - startNanos;
		Stat stat = STATS.computeIfAbsent(section, k -> new Stat());
		stat.totalNanos += elapsed;
		stat.maxNanos = Math.max(stat.maxNanos, elapsed);
		stat.samples++;
	}

	/** Logs and resets every section once a second. Safe to call every frame and tick. */
	public static void flushIfDue() {
		long now = System.currentTimeMillis();
		if (now - lastLogMs < 1000) {
			return;
		}
		lastLogMs = now;
		StringBuilder line = new StringBuilder("[Perf]");
		boolean any = false;
		for (Map.Entry<String, Stat> entry : STATS.entrySet()) {
			Stat stat = entry.getValue();
			if (stat.samples == 0) {
				continue;
			}
			any = true;
			line.append(String.format(" %s avg=%.3fms max=%.3fms n=%d |",
					entry.getKey(), stat.totalNanos / (double) stat.samples / 1_000_000.0,
					stat.maxNanos / 1_000_000.0, stat.samples));
			stat.totalNanos = 0;
			stat.maxNanos = 0;
			stat.samples = 0;
		}
		if (any) {
			UnluckyClientMod.LOGGER.info(line.toString());
		}
	}
}

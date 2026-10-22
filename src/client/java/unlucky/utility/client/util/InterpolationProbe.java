package unlucky.utility.client.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import unlucky.utility.client.UnluckyClientMod;

/**
 * Measures how far other players are drawn behind where the server has them.
 *
 * <p>26.3 gave every LivingEntity a SteppedInterpolationHandler: the server batches each
 * tick's position over the entity type's update interval and the client replays the
 * steps, so {@code position()} trails the path while {@code getInterpolation().target()}
 * already holds its end. That reading comes from bytecode; this is the in-game check
 * before any module starts aiming at the target instead.
 *
 * <p>Samples once per client tick for a fixed window, then prints per-player stats. The
 * "ticks behind" column divides the gap by how far the target itself moves per tick, so
 * it only means something while the player is walking.
 */
public final class InterpolationProbe {
	private static final double RANGE = 64.0;

	private static int ticksLeft;
	private static @Nullable Consumer<String> out;
	private static final Map<String, Stats> STATS = new LinkedHashMap<>();

	private static final class Stats {
		String handler = "?";
		int samples;
		int interpolating;
		double gapSum;
		double gapMax;
		double lagSum;
		int lagSamples;
		@Nullable Vec3 lastTarget;
	}

	/** {@code .lag [seconds]}: starts a window, or stops the running one early. */
	public static void command(String[] args, Consumer<String> sink) {
		if (out != null) {
			finish();
			return;
		}
		int seconds = 10;
		if (args.length > 1) {
			try {
				seconds = Math.max(1, Math.min(120, Integer.parseInt(args[1])));
			} catch (NumberFormatException e) {
				sink.accept("Usage: lag [seconds]");
				return;
			}
		}
		STATS.clear();
		ticksLeft = seconds * 20;
		out = sink;
		sink.accept("Sampling players within " + (int) RANGE + " blocks for " + seconds
				+ "s; run lag again to stop early");
	}

	public static void onTick() {
		if (out == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			finish();
			return;
		}
		for (AbstractClientPlayer player : mc.level.players()) {
			if (player == mc.player || player.distanceTo(mc.player) > RANGE) {
				continue;
			}
			InterpolationHandler handler = player.getInterpolation();
			// target() is null whenever nothing is in flight: the path has been replayed and
			// the drawn position already is the server's.
			PositionAndRotation end = handler.target();
			Vec3 target = end == null ? player.position() : end.position();
			double gap = player.position().distanceTo(target);

			Stats stats = STATS.computeIfAbsent(player.getGameProfile().name(), name -> new Stats());
			stats.handler = handler.getClass().getSimpleName();
			stats.samples++;
			if (handler.hasActiveInterpolation()) {
				stats.interpolating++;
			}
			stats.gapSum += gap;
			stats.gapMax = Math.max(stats.gapMax, gap);
			if (stats.lastTarget != null) {
				double speed = stats.lastTarget.distanceTo(target);
				// Below this the player is standing still and the ratio is noise.
				if (speed > 0.05) {
					stats.lagSum += gap / speed;
					stats.lagSamples++;
				}
			}
			stats.lastTarget = target;
		}
		if (--ticksLeft <= 0) {
			finish();
		}
	}

	private static void finish() {
		Consumer<String> sink = out;
		out = null;
		if (sink == null) {
			return;
		}
		if (STATS.isEmpty()) {
			sink.accept("No other players were in range");
			return;
		}
		sink.accept("player: avg gap / max gap (blocks), ticks behind while moving, % interpolating, handler");
		STATS.forEach((name, s) -> {
			String line = String.format(Locale.ROOT, "%s: %.3f / %.3f, %s, %d%%, %s",
					name, s.gapSum / s.samples, s.gapMax,
					s.lagSamples == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", s.lagSum / s.lagSamples),
					s.interpolating * 100 / s.samples, s.handler);
			sink.accept(line);
			UnluckyClientMod.LOGGER.info("[lag probe] {}", line);
		});
		STATS.clear();
	}

	private InterpolationProbe() {
	}
}

package unlucky.utility.client.module.modules.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.ActionSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render3D;

/**
 * Estimates the stronghold from the Eyes of Ender you throw.
 *
 * <p>Two throws and a ruler is the folklore method, and it is wrong in a specific way: the
 * eye's heading is quantised and jittered, so two nearly-parallel rays intersect a very long
 * way from where the stronghold actually is. Treating each throw as a noisy angular
 * measurement rather than an exact line is what fixes that, and it is why the default is
 * Probabilistic — the estimate it returns is the point that best explains <em>all</em> the
 * throws at once, not the crossing of the most recent two.
 *
 * <p>The estimator is a straightforward maximum-likelihood search, reimplemented from the
 * public description of the method: score a candidate by the Gaussian likelihood of every
 * observed angle given that candidate, then repeatedly shrink a grid around the best cell.
 * Twelve halvings take a 4000-block-wide region to sub-block resolution, so accuracy is set by
 * the throws, never by the search.
 *
 * <p>Sampling reads the eye's own velocity a couple of ticks after it spawns rather than the
 * player's yaw at the moment of the throw. The eye picks its heading itself, and the throw yaw
 * is only an approximation of it — the whole point of the delay is to let the entity tell us
 * where it decided to go.
 *
 * <p>Accuracy is a heuristic and is not advertised otherwise: pregenerated worlds, custom
 * world generation and servers that move or remove strongholds all defeat it. A found portal
 * frame therefore outranks any estimate.
 */
public class StrongholdFinder extends Module {
	private static final double SEED_SPAN = 4000.0;
	private static final int GRID = 21;
	private static final double PARALLEL_EPSILON = 1.0e-6;

	public final ModeSetting method = add(new ModeSetting("Method",
			"Probabilistic weighs every throw; Simple intersection crosses the last two rays",
			"Probabilistic", "Probabilistic", "Simple intersection"));
	public final BooleanSetting requireSame = add(new BooleanSetting("Require same stronghold",
			"Discard a throw that disagrees with the established estimate, instead of averaging "
					+ "two different strongholds into a point between them", true));
	public final NumberSetting sampleDelay = add(new NumberSetting("Sample delay",
			"Ticks to wait after an eye spawns before reading its heading", 2, 1, 20, 1));
	public final NumberSetting minimumSpeed = add(new NumberSetting("Minimum eye horizontal speed",
			"Ignore an eye that is not yet travelling", 0.02, 0.001, 1.0, 0.001));
	public final NumberSetting maximumAge = add(new NumberSetting("Maximum sample age",
			"Give up on an eye that never produced a usable heading", 20, 5, 100, 1));
	public final NumberSetting maximumSpawnDistance = add(new NumberSetting("Maximum eye spawn distance",
			"Only pair with eyes that appeared next to you, so another player's throws are ignored",
			8, 1, 32, 1));
	public final NumberSetting refinement = add(new NumberSetting("Refinement rounds",
			"Grid halvings per estimate; more is finer but costs a little more per throw",
			12, 4, 24, 1), () -> method.is("Probabilistic"));
	public final NumberSetting angularSigma = add(new NumberSetting("Angular sigma",
			"Assumed angular error of one throw, in degrees", 0.03, 0.005, 2.0, 0.005),
			() -> method.is("Probabilistic"));
	public final NumberSetting topCandidates = add(new NumberSetting("Show top candidates",
			"How many runner-up regions to draw", 3, 0, 10, 1));

	public final BooleanSetting renderRays = add(new BooleanSetting("Render rays",
			"Draw each recorded throw as a line", true));
	public final ColorSetting rayColor = add(new ColorSetting("Ray color",
			"Color of a recorded throw ray", 0x8060C0FF), renderRays::get);
	public final BooleanSetting renderEstimate = add(new BooleanSetting("Render best estimate",
			"Draw a marker at the current best estimate", true));
	public final ColorSetting estimateColor = add(new ColorSetting("Estimate color",
			"Color of the best-estimate marker", 0xFF40FF80), renderEstimate::get);
	public final BooleanSetting renderCandidates = add(new BooleanSetting("Render candidate regions",
			"Draw the runner-up regions as well", true));
	public final BooleanSetting announce = add(new BooleanSetting("Announce estimate",
			"Print the estimate in chat each time it changes", false));
	public final BooleanSetting detectFrames = add(new BooleanSetting("Detect portal frames",
			"Sweep nearby blocks for end portal frames, which outrank any estimate", true));
	public final NumberSetting frameRange = add(new NumberSetting("Frame scan range",
			"Horizontal radius of the portal-frame sweep", 32, 8, 64, 4), detectFrames::get);
	public final NumberSetting frameBudget = add(new NumberSetting("Frame scan budget",
			"Block positions examined per tick while sweeping", 4000, 500, 40000, 500),
			detectFrames::get);
	public final BooleanSetting resetOnWorld = add(new BooleanSetting("Reset on world change",
			"Forget throws when the world or dimension changes", true));
	public final ActionSetting clear = add(new ActionSetting("Clear throws",
			"Forget every recorded throw and start again", this::clearThrows));

	/** One usable throw: where the eye was when sampled, and the unit heading it took. */
	private record Observation(double x, double z, double dx, double dz) {
	}

	/** An eye seen but not yet sampled. */
	private record Pending(int firstTick, Vec3 spawn) {
	}

	private final List<Observation> observations = new ArrayList<>();
	private final Map<Integer, Pending> pending = new HashMap<>();
	private final List<Vec3> candidates = new ArrayList<>();
	private ClientLevel level;
	private Vec3 estimate;
	private BlockPos foundFrame;
	private long clientTick;
	private int frameCursor = -1;
	private BlockPos frameOrigin;

	public StrongholdFinder() {
		super("StrongholdFinder", "Estimates the stronghold from your Eye of Ender throws",
				Category.WORLD, ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected void onEnable() {
		level = mc().level;
	}

	@Override
	protected void onDisable() {
		pending.clear();
		candidates.clear();
		frameCursor = -1;
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null) {
			return;
		}
		if (mc().level != level) {
			level = mc().level;
			if (resetOnWorld.get()) {
				clearThrows();
			}
		}
		clientTick++;

		trackEyes();
		if (detectFrames.get()) {
			sweepForFrames();
		}
		draw();
	}

	/**
	 * Watches live eyes and converts each into one observation.
	 *
	 * <p>The spawn-distance gate is what makes this safe on a shared server: an eye that
	 * appeared somewhere other than in front of us is somebody else's throw, and folding it in
	 * would drag the estimate toward a stronghold we are not walking to.
	 */
	private void trackEyes() {
		double maxSpawn = maximumSpawnDistance.get();
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (!(entity instanceof EyeOfEnder eye)) {
				continue;
			}
			Pending seen = pending.get(eye.getId());
			if (seen == null) {
				if (eye.position().distanceTo(mc().player.position()) <= maxSpawn) {
					pending.put(eye.getId(), new Pending((int) clientTick, eye.position()));
				}
				continue;
			}
			long age = clientTick - seen.firstTick();
			if (age < sampleDelay.getInt()) {
				continue;
			}
			if (age > maximumAge.getInt()) {
				pending.remove(eye.getId());
				continue;
			}
			Vec3 velocity = eye.getDeltaMovement();
			double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
			if (speed < minimumSpeed.get()) {
				continue;
			}
			pending.remove(eye.getId());
			record(new Observation(eye.getX(), eye.getZ(), velocity.x / speed, velocity.z / speed));
		}
		pending.entrySet().removeIf(entry ->
				clientTick - entry.getValue().firstTick() > maximumAge.getInt());
	}

	/** Accepts an observation unless it contradicts the estimate we already trust. */
	private void record(Observation observation) {
		if (requireSame.get() && estimate != null && observations.size() >= 2
				&& residualDegrees(observation, estimate.x, estimate.z) > 5.0) {
			ChatUtil.info("StrongholdFinder: throw ignored — its heading disagrees with the "
					+ "current estimate. Use Clear throws if you have moved to a new stronghold.");
			return;
		}
		observations.add(observation);
		recompute();
	}

	private void recompute() {
		estimate = method.is("Simple intersection") ? intersectLatest() : maximumLikelihood();
		if (estimate != null && announce.get()) {
			ChatUtil.info(String.format("StrongholdFinder: estimate x %.0f z %.0f (%d throw%s)",
					estimate.x, estimate.z, observations.size(),
					observations.size() == 1 ? "" : "s"));
		}
	}

	/** Crossing of the two most recent rays, or null when they are effectively parallel. */
	private Vec3 intersectLatest() {
		if (observations.size() < 2) {
			return null;
		}
		Observation a = observations.get(observations.size() - 2);
		Observation b = observations.getLast();
		double denominator = a.dx() * b.dz() - a.dz() * b.dx();
		if (Math.abs(denominator) < PARALLEL_EPSILON) {
			return null;
		}
		double t = ((b.x() - a.x()) * b.dz() - (b.z() - a.z()) * b.dx()) / denominator;
		if (t <= 0.0) {
			return null; // the crossing is behind the throw
		}
		return new Vec3(a.x() + a.dx() * t, 0.0, a.z() + a.dz() * t);
	}

	/**
	 * The point that best explains every recorded heading.
	 *
	 * <p>Coarse-to-fine rather than a hypothesis cloud: each round evaluates a grid, keeps the
	 * best cell and halves the span around it. That reaches sub-block resolution in a fixed,
	 * predictable number of evaluations instead of trading accuracy against a sample count.
	 */
	private Vec3 maximumLikelihood() {
		if (observations.isEmpty()) {
			return null;
		}
		if (observations.size() == 1) {
			// One ray cannot fix a distance; show a point along it so there is something to walk toward.
			Observation only = observations.getFirst();
			return new Vec3(only.x() + only.dx() * 1000.0, 0.0, only.z() + only.dz() * 1000.0);
		}

		Vec3 seed = seedPoint();
		double centreX = seed.x;
		double centreZ = seed.z;
		double span = SEED_SPAN;
		candidates.clear();

		for (int round = 0; round < refinement.getInt(); round++) {
			double step = span / (GRID - 1);
			double bestScore = Double.NEGATIVE_INFINITY;
			double bestX = centreX;
			double bestZ = centreZ;
			List<Vec3> roundBest = new ArrayList<>();

			for (int ix = 0; ix < GRID; ix++) {
				for (int iz = 0; iz < GRID; iz++) {
					double x = centreX - span / 2.0 + ix * step;
					double z = centreZ - span / 2.0 + iz * step;
					double score = logLikelihood(x, z);
					if (score > bestScore) {
						bestScore = score;
						bestX = x;
						bestZ = z;
					}
					roundBest.add(new Vec3(x, score, z));
				}
			}
			centreX = bestX;
			centreZ = bestZ;
			span /= 2.0;

			if (round == refinement.getInt() - 1) {
				roundBest.sort((a, b) -> Double.compare(b.y, a.y));
				for (int i = 1; i <= Math.min(topCandidates.getInt(), roundBest.size() - 1); i++) {
					candidates.add(new Vec3(roundBest.get(i).x, 0.0, roundBest.get(i).z));
				}
			}
		}
		return new Vec3(centreX, 0.0, centreZ);
	}

	/** A starting guess: the best pairwise crossing, or a point far along the first ray. */
	private Vec3 seedPoint() {
		Vec3 best = null;
		for (int i = 0; i < observations.size(); i++) {
			for (int j = i + 1; j < observations.size(); j++) {
				Vec3 crossing = intersect(observations.get(i), observations.get(j));
				if (crossing != null && (best == null
						|| logLikelihood(crossing.x, crossing.z) > logLikelihood(best.x, best.z))) {
					best = crossing;
				}
			}
		}
		if (best != null) {
			return best;
		}
		Observation first = observations.getFirst();
		return new Vec3(first.x() + first.dx() * 1000.0, 0.0, first.z() + first.dz() * 1000.0);
	}

	private static Vec3 intersect(Observation a, Observation b) {
		double denominator = a.dx() * b.dz() - a.dz() * b.dx();
		if (Math.abs(denominator) < PARALLEL_EPSILON) {
			return null;
		}
		double t = ((b.x() - a.x()) * b.dz() - (b.z() - a.z()) * b.dx()) / denominator;
		return t <= 0.0 ? null : new Vec3(a.x() + a.dx() * t, 0.0, a.z() + a.dz() * t);
	}

	/** Gaussian log-likelihood of every observed angle, given a stronghold at (x, z). */
	private double logLikelihood(double x, double z) {
		double sigma = Math.max(1.0e-4, angularSigma.get());
		double total = 0.0;
		for (Observation observation : observations) {
			double residual = residualDegrees(observation, x, z);
			if (Double.isNaN(residual)) {
				return Double.NEGATIVE_INFINITY;
			}
			double normalised = residual / sigma;
			total -= normalised * normalised;
		}
		return total;
	}

	/** Absolute angle in degrees between an observation's heading and the direction to (x, z). */
	private static double residualDegrees(Observation observation, double x, double z) {
		double toX = x - observation.x();
		double toZ = z - observation.z();
		double length = Math.sqrt(toX * toX + toZ * toZ);
		if (length < 1.0e-6) {
			return Double.NaN;
		}
		double cross = observation.dx() * (toZ / length) - observation.dz() * (toX / length);
		double dot = observation.dx() * (toX / length) + observation.dz() * (toZ / length);
		return Math.toDegrees(Math.abs(Math.atan2(cross, dot)));
	}

	/**
	 * Budgeted sweep for an end portal frame.
	 *
	 * <p>A found frame ends the guessing, so this runs regardless of how confident the estimate
	 * is. It is deliberately incremental for the same reason LightOverlay's is: the volume is
	 * large, the target is rare, and a full sweep in one tick would be a visible stutter.
	 */
	private void sweepForFrames() {
		if (foundFrame != null) {
			return;
		}
		int radius = frameRange.getInt();
		int height = 48;
		int width = radius * 2 + 1;
		int total = width * width * height;
		if (frameCursor < 0) {
			frameOrigin = mc().player.blockPosition();
			frameCursor = 0;
		}
		int budget = Math.min(frameBudget.getInt(), total - frameCursor);
		for (int step = 0; step < budget; step++) {
			int index = frameCursor + step;
			int x = index % width;
			int z = index / width % width;
			int y = index / (width * width);
			BlockPos pos = frameOrigin.offset(x - radius, y - height + 8, z - radius);
			if (mc().level.getBlockState(pos).is(Blocks.END_PORTAL_FRAME)) {
				foundFrame = pos.immutable();
				ChatUtil.info("StrongholdFinder: end portal frame found at "
						+ pos.getX() + " " + pos.getY() + " " + pos.getZ());
				return;
			}
		}
		frameCursor += budget;
		if (frameCursor >= total) {
			frameCursor = -1; // sweep again from wherever the player now is
		}
	}

	private void clearThrows() {
		observations.clear();
		pending.clear();
		candidates.clear();
		estimate = null;
		foundFrame = null;
		frameCursor = -1;
	}

	private void draw() {
		double y = mc().player.getY();
		if (renderRays.get()) {
			int color = rayColor.get();
			for (Observation observation : observations) {
				Vec3 from = new Vec3(observation.x(), y, observation.z());
				double length = estimate == null ? 512.0
						: Math.hypot(estimate.x - observation.x(), estimate.z - observation.z());
				Render3D.line(from, from.add(observation.dx() * length, 0.0,
						observation.dz() * length), color, 1.5f, true);
			}
		}
		if (foundFrame != null) {
			Render3D.blockBox(foundFrame, estimateColor.get(), 2.5f,
					ColorUtil.withAlpha(estimateColor.get(), 60), true);
			Render3D.blockLabel("Portal frame", foundFrame, estimateColor.get(), 1.0f);
			return;
		}
		if (estimate == null) {
			return;
		}
		if (renderCandidates.get()) {
			int color = ColorUtil.withAlpha(estimateColor.get(), 70);
			for (Vec3 candidate : candidates) {
				Render3D.box(marker(candidate, y), color, 1.0f,
						ColorUtil.withAlpha(estimateColor.get(), 20), true);
			}
		}
		if (renderEstimate.get()) {
			BlockPos at = BlockPos.containing(estimate.x, y, estimate.z);
			Render3D.box(marker(estimate, y), estimateColor.get(), 2.5f,
					ColorUtil.withAlpha(estimateColor.get(), 50), true);
			Render3D.blockLabel(String.format("Stronghold ~%.0f blocks",
					Math.hypot(estimate.x - mc().player.getX(), estimate.z - mc().player.getZ())),
					at, estimateColor.get(), 1.0f);
		}
	}

	/** A tall thin column so the estimate stays visible from a distance and from above. */
	private static AABB marker(Vec3 at, double y) {
		return new AABB(at.x - 4.0, y - 12.0, at.z - 4.0, at.x + 4.0, y + 12.0, at.z + 4.0);
	}
}

package unlucky.utility.client.module.modules.combat;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.MovementActionCoordinator;
import unlucky.utility.client.util.ProjectilePathUtil;
import unlucky.utility.client.util.ProjectilePathUtil.ProjectileType;
import unlucky.utility.client.util.Render3D;

/**
 * Steps out of the way of a projectile that is already going to hit you.
 *
 * <p>The prediction is the shared one: {@link ProjectilePathUtil} advances each in-flight
 * projectile with the same constants Trajectories draws and BowAimbot solves against, so a
 * dodge cannot disagree with the line the player is looking at. Nothing here re-derives
 * gravity or drag.
 *
 * <p>A dodge is only worth making inside a window. Too early and the shooter simply re-aims;
 * too late and the move cannot outrun the arrow — so a threat counts only between the minimum
 * and maximum time-to-impact, and outside that band the module stays completely still. This is
 * also what stops it from reacting to the arrows sailing past on the other side of a fight.
 *
 * <p>Escape candidates are checked for somewhere to stand before they are checked for safety
 * from the arrow. Dodging into a lava pool, off a ledge or into the void is a strictly worse
 * outcome than the hit, which is why {@code AntiVoid} outranks this module in
 * {@link MovementActionCoordinator} and why a candidate with no floor is discarded before it
 * is ever scored.
 *
 * <p>Meteor's "Packet" mode is deliberately absent: it exists to defeat movement checks, and
 * this client keeps to vanilla movement semantics. Input and Velocity cover the real behavior.
 */
public class ArrowDodge extends Module {
	/** Roughly one tick of ordinary ground walking; the Input-mode step is scaled from this. */
	private static final double WALK_STEP = 0.215;
	private static final double SCAN_RADIUS = 64.0;
	private static final double STATIONARY = 1.0e-3;
	private static final int CANDIDATE_DIRECTIONS = 8;

	public final BooleanSetting arrows = add(new BooleanSetting("Arrows",
			"Dodge arrows and spectral arrows", true));
	public final BooleanSetting tridents = add(new BooleanSetting("Tridents",
			"Dodge thrown tridents", true));
	public final BooleanSetting potions = add(new BooleanSetting("Potions",
			"Dodge splash and lingering potions", false));
	public final BooleanSetting smallThrowables = add(new BooleanSetting("Snowballs/eggs",
			"Dodge snowballs and eggs", false));
	public final BooleanSetting otherProjectiles = add(new BooleanSetting("Other projectiles",
			"Dodge anything else that flies, using generic throwable physics", false));
	public final BooleanSetting ignoreOwn = add(new BooleanSetting("Ignore own projectiles",
			"Never dodge something you threw", true));

	public final NumberSetting predictionSteps = add(new NumberSetting("Prediction steps",
			"Ticks of flight simulated per projectile", 120, 20, 300, 10));
	public final NumberSetting dangerRadius = add(new NumberSetting("Danger radius",
			"Extra margin added around your hitbox when testing a path", 0.25, 0, 2, 0.05));
	public final NumberSetting minimumImpact = add(new NumberSetting("Minimum time to impact",
			"Ignore hits too close to avoid", 2, 0, 20, 1));
	public final NumberSetting maximumImpact = add(new NumberSetting("Maximum time to impact",
			"Ignore hits far enough away that the shot will be re-aimed anyway", 40, 5, 200, 5));

	public final ModeSetting mode = add(new ModeSetting("Mode",
			"Input steers with ordinary movement input; Velocity sets the escape velocity directly",
			"Input", "Input", "Velocity"));
	public final NumberSetting moveSpeed = add(new NumberSetting("Move speed",
			"Multiplier on the sidestep", 1.0, 0.2, 2.0, 0.05));
	public final BooleanSetting groundSafety = add(new BooleanSetting("Ground safety",
			"Never step somewhere with no floor under it", true));
	public final BooleanSetting avoidVoid = add(new BooleanSetting("Avoid void",
			"Never step toward an unsupported drop to the world floor", true));
	public final BooleanSetting avoidHazards = add(new BooleanSetting("Avoid lava/fire",
			"Never step into lava, fire or a campfire", true));
	public final BooleanSetting preferSmallest = add(new BooleanSetting("Prefer smallest movement",
			"Among safe escapes, take the one closest to standing still", true));

	public final BooleanSetting renderThreat = add(new BooleanSetting("Render threat path",
			"Draw the predicted path of anything currently counted as a threat", true));
	public final ColorSetting threatColor = add(new ColorSetting("Threat color",
			"Color of the predicted threat path", 0xC0FF4040), renderThreat::get);
	public final BooleanSetting renderDodge = add(new BooleanSetting("Render chosen dodge",
			"Draw a line toward the escape direction being taken", false));
	public final ColorSetting dodgeColor = add(new ColorSetting("Dodge color",
			"Color of the chosen escape line", 0xC040FF80), renderDodge::get);

	private final ProjectilePathUtil.ResultBuffer buffer = new ProjectilePathUtil.ResultBuffer();
	private final List<List<Vec3>> threats = new ArrayList<>();
	private Vec3 escape = Vec3.ZERO;

	public ArrowDodge() {
		super("ArrowDodge", "Sidesteps incoming projectiles that are predicted to hit you",
				Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onDisable() {
		MovementActionCoordinator.release(this);
		threats.clear();
		escape = Vec3.ZERO;
	}

	/**
	 * The move vector the dodge wants this tick, in the player-relative (left, forward) space
	 * {@code KeyboardInput} writes, or null when there is nothing to avoid.
	 *
	 * <p>Returning input rather than velocity is what keeps Input mode vanilla: the value goes
	 * through the ordinary travel code, so friction, collision, sprinting and slabs all behave
	 * exactly as they would if the player had held the key themselves.
	 */
	public Vec2 inputOverride() {
		if (!isEnabled() || !mode.is("Input") || escape.lengthSqr() < STATIONARY
				|| mc().player == null) {
			return null;
		}
		float yaw = mc().player.getYRot() * Mth.DEG_TO_RAD;
		float sin = Mth.sin(yaw);
		float cos = Mth.cos(yaw);
		// Inverse of Entity.getInputVector's rotation: world (x,z) back into (left, forward).
		float left = (float) (escape.x * cos + escape.z * sin);
		float forward = (float) (-escape.x * sin + escape.z * cos);
		return new Vec2(left, forward).normalized();
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		escape = Vec3.ZERO;
		threats.clear();
		if (player == null || mc().level == null || player.isSpectator()) {
			MovementActionCoordinator.release(this);
			return;
		}

		collectThreats(player);
		if (threats.isEmpty()) {
			MovementActionCoordinator.release(this);
			return;
		}
		drawThreats();

		escape = chooseEscape(player);
		if (escape.lengthSqr() < STATIONARY) {
			MovementActionCoordinator.release(this);
			return;
		}
		if (renderDodge.get()) {
			Vec3 from = player.position().add(0.0, 0.1, 0.0);
			Render3D.line(from, from.add(escape.scale(2.0)), dodgeColor.get(), 2.0f, true);
		}
		if (mode.is("Velocity")) {
			double step = WALK_STEP * moveSpeed.get();
			MovementActionCoordinator.request(this, MovementActionCoordinator.PRIORITY_DODGE,
					current -> new Vec3(escape.x * step, current.y, escape.z * step));
		}
		// Input mode needs no coordinator claim: it never writes velocity, it writes the input
		// the vanilla travel code is about to read.
	}

	/** Simulates every eligible projectile and keeps the ones that would land on us in time. */
	private void collectThreats(LocalPlayer player) {
		AABB danger = player.getBoundingBox().inflate(dangerRadius.get());
		int steps = predictionSteps.getInt();
		int minTick = minimumImpact.getInt();
		int maxTick = maximumImpact.getInt();

		for (Entity entity : mc().level.entitiesForRendering()) {
			if (!(entity instanceof Projectile projectile)) {
				continue;
			}
			ProjectileType type = profileFor(projectile);
			if (type == null || projectile.distanceToSqr(player) > SCAN_RADIUS * SCAN_RADIUS) {
				continue;
			}
			if (ignoreOwn.get() && projectile.getOwner() == player) {
				continue;
			}
			Vec3 velocity = projectile.getDeltaMovement();
			if (velocity.lengthSqr() < STATIONARY) {
				continue; // already stuck in a block or on the ground
			}

			ProjectilePathUtil.simulate(mc().level, projectile, projectile.position(), velocity,
					type, steps, false, null, buffer);
			int impact = impactTick(buffer.points(), danger);
			if (impact >= minTick && impact <= maxTick) {
				threats.add(List.copyOf(buffer.points()));
			}
		}
	}

	/**
	 * The first tick index whose flight segment enters {@code danger}, or -1.
	 *
	 * <p>Segment-based rather than point-based on purpose: an arrow covers up to three blocks
	 * per tick, so sampling only the endpoints misses a hit that happens between them.
	 */
	private static int impactTick(List<Vec3> points, AABB danger) {
		for (int i = 1; i < points.size(); i++) {
			if (danger.clip(points.get(i - 1), points.get(i)).isPresent()) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Picks a horizontal escape, or {@link Vec3#ZERO} when standing still is already fine.
	 *
	 * <p>Standing still is tested first and wins outright: a threat that turns out to miss
	 * after all should produce no movement, not a reflexive sidestep. Only once staying put is
	 * known to be a hit does the module look for somewhere to go.
	 */
	private Vec3 chooseEscape(LocalPlayer player) {
		double step = WALK_STEP * moveSpeed.get();
		if (earliestImpact(player, Vec3.ZERO, step) == Integer.MAX_VALUE) {
			return Vec3.ZERO;
		}

		Vec3 heading = player.getDeltaMovement();
		Vec3 facing = heading.horizontalDistanceSqr() > STATIONARY
				? new Vec3(heading.x, 0.0, heading.z).normalize()
				: null;
		Vec3 best = Vec3.ZERO;
		long bestImpact = Long.MIN_VALUE;
		double bestAlignment = Double.NEGATIVE_INFINITY;

		for (int i = 0; i < CANDIDATE_DIRECTIONS; i++) {
			double angle = i * (2.0 * Math.PI / CANDIDATE_DIRECTIONS);
			Vec3 direction = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
			if (!candidateSafe(player, direction, step)) {
				continue;
			}
			long impact = earliestImpact(player, direction, step);
			if (impact <= minimumImpact.getInt()) {
				continue; // does not buy enough time to be worth the move
			}
			// Later impact always wins. Among candidates that clear the threat outright they
			// all tie, and "prefer smallest movement" breaks that tie toward the direction the
			// player is already travelling, which is the least disruptive thing to do to them.
			double alignment = facing == null || !preferSmallest.get()
					? 0.0 : facing.dot(direction);
			if (impact > bestImpact || (impact == bestImpact && alignment > bestAlignment)) {
				bestImpact = impact;
				bestAlignment = alignment;
				best = direction;
			}
		}
		return best;
	}

	/**
	 * The earliest tick any threat would still land while moving along {@code direction},
	 * or {@link Integer#MAX_VALUE} when every threat misses.
	 *
	 * <p>The player is projected as continuing to move for the whole window, which is what
	 * lets a direction that merely delays the hit score below one that clears it entirely.
	 */
	private long earliestImpact(LocalPlayer player, Vec3 direction, double step) {
		AABB base = player.getBoundingBox().inflate(dangerRadius.get());
		long earliest = Integer.MAX_VALUE;
		for (List<Vec3> path : threats) {
			for (int tick = 1; tick < path.size(); tick++) {
				AABB moved = base.move(direction.x * step * tick, 0.0, direction.z * step * tick);
				if (moved.clip(path.get(tick - 1), path.get(tick)).isPresent()) {
					earliest = Math.min(earliest, tick);
					break;
				}
			}
		}
		return earliest;
	}

	/** Terrain veto: somewhere to stand, nothing lethal in it, and not a step into the void. */
	private boolean candidateSafe(LocalPlayer player, Vec3 direction, double step) {
		double distance = step * Math.max(1, minimumImpact.getInt());
		Vec3 offset = new Vec3(direction.x * distance, 0.0, direction.z * distance);
		AABB box = player.getBoundingBox().move(offset);
		if (!mc().level.noCollision(player, box)) {
			return false;
		}

		Vec3 target = player.position().add(offset);
		BlockPos feet = BlockPos.containing(target.x, box.minY + 0.1, target.z);
		if (avoidHazards.get() && (hazardous(feet) || hazardous(feet.below()))) {
			return false;
		}
		if (!groundSafety.get() && !avoidVoid.get()) {
			return true;
		}
		// One column probe answers both questions; they differ only in how far down they look.
		// Ground safety wants a floor within a survivable step, Avoid void wants any floor at all.
		int depth = avoidVoid.get() ? Math.max(8, feet.getY() - mc().level.getMinY()) : 4;
		for (int drop = 1; drop <= depth; drop++) {
			BlockPos probe = feet.below(drop);
			if (!mc().level.getBlockState(probe).getCollisionShape(mc().level, probe).isEmpty()) {
				return !groundSafety.get() || drop <= 4;
			}
		}
		return false;
	}

	private boolean hazardous(BlockPos pos) {
		FluidState fluid = mc().level.getFluidState(pos);
		if (fluid.is(FluidTags.LAVA)) {
			return true;
		}
		BlockState state = mc().level.getBlockState(pos);
		return state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES);
	}

	/** Maps a live projectile onto the shared physics profile that matches it. */
	private ProjectileType profileFor(Projectile projectile) {
		if (projectile instanceof ThrownTrident) {
			return tridents.get() ? ProjectileType.TRIDENT : null;
		}
		if (projectile instanceof AbstractArrow) {
			return arrows.get() ? ProjectileType.BOW_ARROW : null;
		}
		if (projectile instanceof AbstractThrownPotion) {
			return potions.get() ? ProjectileType.POTION : null;
		}
		if (projectile instanceof Snowball) {
			return smallThrowables.get() ? ProjectileType.SNOWBALL : null;
		}
		if (projectile instanceof ThrownEgg) {
			return smallThrowables.get() ? ProjectileType.EGG : null;
		}
		return otherProjectiles.get() ? ProjectileType.SNOWBALL : null;
	}

	private void drawThreats() {
		if (!renderThreat.get()) {
			return;
		}
		int color = threatColor.get();
		for (List<Vec3> path : threats) {
			for (int i = 1; i < path.size(); i++) {
				Render3D.line(path.get(i - 1), path.get(i), color, 1.5f, false);
			}
		}
	}
}

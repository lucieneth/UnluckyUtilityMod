package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;

/**
 * Walk on water.
 *
 * <p><b>Solid</b> mode is the strider's mechanic, and the strider needs
 * <em>three</em> things — miss any one and the fluid stays passable:
 * <ol>
 *   <li>{@code LivingEntity.canStandOnFluid(fluid)} → true;</li>
 *   <li>{@code LivingEntity.getLiquidCollisionShape()} → a <b>non-empty</b> shape.
 *       The base class returns {@code Shapes.empty()}; the strider returns a
 *       half-height column. This is the shape the fluid block collides with;</li>
 *   <li>{@code CollisionContext.isAbove} → your feet are already above that
 *       shape's top face.</li>
 * </ol>
 * Only source blocks at the top of their column ever collide, and saying yes to
 * (1) also switches off swim physics ({@code shouldTravelInFluid}) — so a
 * submerged player has neither collision nor swimming and would sink forever.
 * Hence the lift: while fluid stands over our feet we rise, and the moment they
 * reach the surface vanilla's collision holds us flat. See {@code LivingEntityMixin}.
 *
 * <p><b>Dolphin</b> mode grants no shape and no standing; it just rides the surface.
 *
 * <p>Both target <em>feet at the surface</em>, measured with
 * {@code getFluidHeight} (metres of fluid above the feet). Anything eye-relative
 * ({@code isUnderWater()}) settles you chest-deep instead.
 */
public class Jesus extends Module {
	/**
	 * The box the fluid collides against, matching a source block's rendered
	 * surface (8/9 of a block) so you stand exactly <em>on</em> the water rather
	 * than hovering a tenth of a block over it.
	 */
	public static final VoxelShape SURFACE_SHAPE = Shapes.box(0.0, 0.0, 0.0, 1.0, 8.0 / 9.0, 1.0);

	/** Air-physics gravity per tick; the lift has to beat it. */
	private static final double GRAVITY = 0.08;

	public final ModeSetting mode = add(new ModeSetting("Mode",
			"Solid: the surface becomes ground. Dolphin: ride the surface, bobbing",
			"Solid", "Solid", "Dolphin"));
	public final BooleanSetting lava = add(new BooleanSetting("Lava",
			"Walk on lava too (Solid mode) — you still burn", false));
	public final BooleanSetting sneakToSink = add(new BooleanSetting("Sneak to sink",
			"Hold sneak to drop through the surface", true));

	public Jesus() {
		super("Jesus", "Walk on water", Category.MOVEMENT);
	}

	/**
	 * Answers vanilla's canStandOnFluid for the local player. Callers must have
	 * checked {@link #isEnabled()} — this is reached from a mixin that runs
	 * regardless of module state.
	 */
	public boolean standsOn(FluidState fluid) {
		if (!solid()) {
			return false;
		}
		if (fluid.is(FluidTags.WATER)) {
			return true;
		}
		return lava.get() && fluid.is(FluidTags.LAVA);
	}

	/**
	 * Whether the player should currently get a fluid collision shape. Same gate as
	 * {@link #standsOn}, minus the fluid type — {@code getLiquidCollisionShape()}
	 * doesn't get told which fluid it's for.
	 */
	public boolean solid() {
		return isEnabled() && mode.is("Solid") && active();
	}

	@Override
	public void onTick() {
		if (!active()) {
			return;
		}
		LocalPlayer player = mc().player;
		double depth = submersion(player);
		if (depth <= 0.0) {
			return; // feet already clear of the surface
		}
		Vec3 velocity = player.getDeltaMovement();
		double lift;
		if (mode.is("Solid")) {
			// Only needed to climb out when submerged — once the feet reach the
			// surface the collision shape holds us, no bobbing. Travel subtracts
			// gravity next tick, so anything at or below GRAVITY would never rise.
			lift = Math.min(0.30, GRAVITY + 0.06 + depth * 0.12);
		} else {
			// Dolphin: swim physics still apply, so a gentle push is enough. Leaving
			// the water lets gravity dip us back in — that dip is the bob.
			lift = depth > 0.12 ? 0.10 : 0.02;
		}
		player.setDeltaMovement(velocity.x, Math.max(velocity.y, lift), velocity.z);
	}

	/** Metres of walkable fluid standing above the player's feet. */
	private double submersion(LocalPlayer player) {
		double depth = player.getFluidHeight(FluidTags.WATER);
		if (lava.get() && mode.is("Solid")) {
			depth = Math.max(depth, player.getFluidHeight(FluidTags.LAVA));
		}
		return depth;
	}

	/** Not while spectating, creative-flying, or deliberately sinking. */
	private boolean active() {
		LocalPlayer player = mc().player;
		if (player == null || player.isSpectator() || player.getAbilities().flying) {
			return false;
		}
		return !(sneakToSink.get() && player.isShiftKeyDown());
	}
}

package unlucky.utility.client.module.modules.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BlockListSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.HoleUtil;
import unlucky.utility.client.util.PlacementExecutor;

/**
 * Puts a block where your feet were, while your feet are somewhere else.
 *
 * <p>A burrow is a vanilla-valid jump plus a placement at the position you have just left. That
 * is the whole trick and it is entirely legitimate: the block goes down while the hitbox is
 * above it, and the server agrees because both halves are ordinary actions in an ordinary
 * order. What makes a burrow module dangerous is everything people bolt onto it — timers,
 * rubber-banding, fake lag, per-server profiles — and none of that is here. If the jump does not
 * clear the block honestly, no block is placed.
 *
 * <p><b>The trigger height is a real check, not a delay.</b> Placing before the hitbox has
 * actually cleared the block is a placement the server refuses, and refusing it repeatedly is
 * how a burrow module turns into a stream of rejected packets. Feet clearance is measured, and
 * measured against the block being placed into.
 *
 * <p>Automatic activation has a cooldown and cannot re-trigger while a placement is already in
 * flight — otherwise "enemy near" fires every tick an enemy is near, which is all of them.
 */
public class Burrow extends Module {
	/** Ticks between automatic triggers, so "enemy near" does not mean "every tick". */
	private static final int AUTO_COOLDOWN = 40;
	private static final double CENTRE_EPSILON = 0.12;
	private static final double CENTRE_STEP = 0.14;

	public final BlockListSetting blocks = add(new BlockListSetting("Blocks",
			"Placement preference order — right-click to pick",
			Set.of("minecraft:obsidian", "minecraft:ender_chest")));

	public final ModeSetting activation = add(new ModeSetting("Activation",
			"What starts a burrow", "Manual",
			"Manual", "Enemy near", "Crystal near", "Low health"));
	public final NumberSetting enemyRange = add(new NumberSetting("Enemy range",
			"Distance at which an enemy triggers a burrow", 5, 1, 10, 0.5),
			() -> activation.is("Enemy near"));
	public final NumberSetting crystalRange = add(new NumberSetting("Crystal range",
			"Distance at which a crystal triggers a burrow", 4, 1, 10, 0.5),
			() -> activation.is("Crystal near"));
	public final NumberSetting healthThreshold = add(new NumberSetting("Health threshold",
			"Health plus absorption at which a burrow triggers", 12, 1, 36, 1),
			() -> activation.is("Low health"));

	public final ModeSetting liftMode = add(new ModeSetting("Lift mode",
			"Jump uses the vanilla jump; Packet lift steps up in vanilla position increments",
			"Jump", "Jump", "Packet lift"));
	public final NumberSetting triggerHeight = add(new NumberSetting("Trigger height",
			"Feet clearance required before the block goes down", 1.00, 0.50, 1.20, 0.01));
	public final BooleanSetting onlyInHole = add(new BooleanSetting("Only in hole",
			"Require a HoleUtil-safe starting position", true));
	public final BooleanSetting centreFirst = add(new BooleanSetting("Center first",
			"Move to the middle of your block before jumping", true));

	public final ModeSetting rotation = add(new ModeSetting("Rotation",
			"How the placement is aimed", "Silent", "Off", "Silent", "Visible"));
	public final BooleanSetting autoSwitch = add(new BooleanSetting("Auto switch",
			"Select a listed block before placing", true));
	public final BooleanSetting swapBack = add(new BooleanSetting("Swap back",
			"Return to the slot held before the placement", true), autoSwitch::get);
	public final ModeSetting swing = add(new ModeSetting("Swing",
			"Hand swing after the placement", "Client", "Client", "Packet", "None"));
	public final BooleanSetting airPlace = add(new BooleanSetting("Air place",
			"Allow a direct target click when no support face exists", false));
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Ticks to wait after the trigger height is reached", 0, 0, 10, 1));
	public final BooleanSetting selfDisable = add(new BooleanSetting("Self disable",
			"Switch off after a success or a terminal failure", true));
	public final BooleanSetting render = add(new BooleanSetting("Render",
			"Show the planned and placed position", true));
	public final ColorSetting renderColor = add(new ColorSetting("Render color",
			"Colour of the planned/placed marker", 0xB0FF5C5C), render::get);

	private final PlacementExecutor executor = new PlacementExecutor(this);

	/** Where the block goes — the block the player was standing on top of when the jump began. */
	private BlockPos target;
	private boolean jumped;
	private int cooldown;
	private int waited;

	public Burrow() {
		super("Burrow", "Places a block at your feet during a vanilla-valid jump",
				Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		reset();
	}

	@Override
	protected void onDisable() {
		executor.reset();
		reset();
	}

	@Override
	protected void onPanic() {
		onDisable();
	}

	private void reset() {
		target = null;
		jumped = false;
		waited = 0;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		executor.renderPlaced(renderColor.get(), render.get());
		if (cooldown > 0) {
			cooldown--;
		}
		if (!executor.beginTick(placementOptions())) {
			return;
		}
		if (mc().gui.screen() != null || player.containerMenu != player.inventoryMenu) {
			executor.release();
			return;
		}

		if (target == null) {
			if (!shouldStart(player)) {
				executor.release();
				return;
			}
			if (onlyInHole.get() && HoleUtil.playerHole(new HoleUtil.Options(1, 2, false)) == null) {
				// Not in a hole worth burrowing out of. Refusing is the whole point of the
				// setting: a burrow in the open is a block on your head and nothing else.
				executor.release();
				return;
			}
			if (centreFirst.get() && centreOn(player)) {
				return; // still drifting; the block would land off-axis
			}
			target = player.blockPosition().immutable();
			jumped = false;
			waited = 0;
		}

		if (!jumped) {
			if (!lift(player)) {
				fail();
				return;
			}
			jumped = true;
			return;
		}

		// The measured check, not a delay: the hitbox has to have actually cleared the block.
		double clearance = player.getBoundingBox().minY - target.getY();
		if (clearance < triggerHeight.get()) {
			if (player.onGround() && clearance <= 0.0) {
				// Back on the ground without ever clearing it — the jump was blocked.
				fail();
			}
			return;
		}
		if (waited < delay.getInt()) {
			waited++;
			return;
		}
		if (!emptyOfEntities(target)) {
			return; // something is standing in it; wait rather than spend a packet
		}
		if (executor.place(target)) {
			if (selfDisable.get()) {
				setEnabled(false);
			}
			reset();
			cooldown = AUTO_COOLDOWN;
			return;
		}
		if (render.get()) {
			executor.renderPlanned(renderColor.get(), true, false);
		}
		if (player.onGround()) {
			// Landed again with nothing placed. Terminal for this attempt.
			fail();
		}
	}

	private void fail() {
		executor.release();
		reset();
		cooldown = AUTO_COOLDOWN;
		if (selfDisable.get()) {
			setEnabled(false);
		}
	}

	/**
	 * Whether an attempt may begin.
	 *
	 * <p>Manual means "the module being on is the request", which is why it pairs with self
	 * disable. The automatic modes are gated on the cooldown so a condition that stays true —
	 * and "an enemy is within five blocks" stays true for a whole fight — produces one attempt
	 * rather than one per tick.
	 */
	private boolean shouldStart(LocalPlayer player) {
		if (cooldown > 0) {
			return false;
		}
		return switch (activation.get()) {
			case "Enemy near" -> nearestEnemyWithin(player, enemyRange.get());
			case "Crystal near" -> crystalWithin(player, crystalRange.get());
			case "Low health" ->
					player.getHealth() + player.getAbsorptionAmount() <= healthThreshold.get();
			default -> true;
		};
	}

	private boolean nearestEnemyWithin(LocalPlayer player, double range) {
		double rangeSqr = range * range;
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (entity == player || !(entity instanceof LivingEntity living) || !living.isAlive()) {
				continue;
			}
			if (living instanceof net.minecraft.world.entity.player.Player other
					&& unlucky.utility.client.util.FriendManager.isFriend(other.getUUID())) {
				continue;
			}
			if (living.distanceToSqr(player) <= rangeSqr) {
				return true;
			}
		}
		return false;
	}

	private boolean crystalWithin(LocalPlayer player, double range) {
		double rangeSqr = range * range;
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (entity instanceof EndCrystal && entity.distanceToSqr(player) <= rangeSqr) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Gets the hitbox off the block.
	 *
	 * <p>Jump is vanilla's own jump. Packet lift steps the position up in increments vanilla
	 * itself produces during a jump, which is the same movement expressed as positions rather
	 * than as velocity — deliberately <em>not</em> a teleport, a timer, or a rubber-band.
	 *
	 * @return whether the lift could be started at all
	 */
	private boolean lift(LocalPlayer player) {
		if (!player.onGround()) {
			return false;
		}
		if (liftMode.is("Jump")) {
			player.jumpFromGround();
			return true;
		}
		Vec3 velocity = player.getDeltaMovement();
		player.setDeltaMovement(velocity.x, 0.42, velocity.z);
		return true;
	}

	/** Nudges toward the middle of the current block. Returns true while still off-centre. */
	private boolean centreOn(LocalPlayer player) {
		double targetX = Mth.floor(player.getX()) + 0.5;
		double targetZ = Mth.floor(player.getZ()) + 0.5;
		double dx = targetX - player.getX();
		double dz = targetZ - player.getZ();
		if (Math.abs(dx) <= CENTRE_EPSILON && Math.abs(dz) <= CENTRE_EPSILON) {
			return false;
		}
		Vec3 velocity = player.getDeltaMovement();
		player.setDeltaMovement(Mth.clamp(dx, -CENTRE_STEP, CENTRE_STEP), velocity.y,
				Mth.clamp(dz, -CENTRE_STEP, CENTRE_STEP));
		return true;
	}

	private boolean emptyOfEntities(BlockPos pos) {
		AABB box = new AABB(pos);
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (entity == mc().player) {
				continue; // we are above it; that is the point
			}
			if (entity.isAlive() && !entity.isSpectator() && entity.getBoundingBox().intersects(box)) {
				return false;
			}
		}
		return true;
	}

	private PlacementExecutor.Options placementOptions() {
		List<String> order = new ArrayList<>(blocks.get());
		return new PlacementExecutor.Options(
				new PlacementExecutor.Material() {
					@Override
					public boolean accepts(net.minecraft.world.item.BlockItem item, BlockPos pos) {
						if (!blocks.contains(item.getBlock())) {
							return false;
						}
						var state = item.getBlock().defaultBlockState();
						return !(item.getBlock() instanceof FallingBlock)
								&& state.isCollisionShapeFullBlock(mc().level, pos)
								&& state.canSurvive(mc().level, pos);
					}

					@Override
					public int rank(net.minecraft.world.item.BlockItem item) {
						int index = order.indexOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK
								.getKey(item.getBlock()).toString());
						return index < 0 ? order.size() : index;
					}
				},
				autoSwitch.get(), swapBack.get(),
				switch (rotation.get()) {
					case "Silent" -> PlacementExecutor.Rotate.SILENT;
					case "Visible" -> PlacementExecutor.Rotate.VISIBLE;
					default -> PlacementExecutor.Rotate.OFF;
				},
				180.0f, mc().player == null ? 0.0 : mc().player.blockInteractionRange(),
				airPlace.get(), false,
				switch (swing.get()) {
					case "Packet" -> PlacementExecutor.Swing.PACKET;
					case "None" -> PlacementExecutor.Swing.NONE;
					default -> PlacementExecutor.Swing.CLIENT;
				},
				1, 0, 0, true);
	}
}

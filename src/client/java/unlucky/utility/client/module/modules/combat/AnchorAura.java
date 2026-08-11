package unlucky.utility.client.module.modules.combat;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.player.AutoEat;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.ExplosionDamageUtil;
import unlucky.utility.client.util.InventoryActionCoordinator;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.RotationManager;
import unlucky.utility.client.util.TargetingUtil;

/**
 * Places, charges and detonates respawn anchors against a target.
 *
 * <p>Three server round trips rather than one, which is the whole difficulty. A crystal is a
 * place and a hit; an anchor is place, charge, detonate, and each step is only legal once the
 * server has acknowledged the previous one. So the module is an explicit state machine that
 * reads the <em>world's</em> block state to decide what to do next, never a local counter of
 * what it believes it has sent. A dropped packet therefore costs a retry, not a desync.
 *
 * <p>The dimension gate is not an optimisation. In the Nether an anchor is furniture: using it
 * sets your spawn instead of exploding, so running the detonate step there would quietly
 * rewrite the player's respawn point in the middle of a fight. 26.2 no longer exposes
 * {@code respawnAnchorWorks} on the client's {@code DimensionType} record, so the check is the
 * dimension key itself — correct for the vanilla three, and deliberately conservative for a
 * custom dimension whose anchor behavior the client cannot see.
 *
 * <p>Damage safety, targeting and rotation are the same shared pieces {@link CrystalAura} uses,
 * and the two never act in the same tick: this module stands down while that one is mid-action.
 */
public class AnchorAura extends Module {
	private static final long FADE_MS = 600L;

	public final NumberSetting targetRange = add(new NumberSetting("Target range",
			"Maximum distance to a valid target", 10, 1, 20, 0.5));
	public final ModeSetting priority = add(new ModeSetting("Priority",
			"Target ranking", "Lowest health", "Lowest health", "Closest", "Lowest armor"));
	public final NumberSetting minTargetDamage = add(new NumberSetting("Min target damage",
			"Never start a cycle for less than this much damage", 7, 0, 20, 0.5));
	public final NumberSetting maxSelfDamage = add(new NumberSetting("Max self damage",
			"Never detonate something predicted to hit you harder than this", 7, 0, 20, 0.5));
	public final BooleanSetting antiSuicide = add(new BooleanSetting("Anti-suicide",
			"Refuse anything the damage model says you would not survive", true));
	public final NumberSetting safetyMargin = add(new NumberSetting("Safety margin",
			"Health that must remain for a blast to count as survivable", 1, 0, 10, 0.5),
			antiSuicide::get);
	public final BooleanSetting predict = add(new BooleanSetting("Predict movement",
			"Aim at where the target will be rather than where it is", true));
	public final NumberSetting predictionTicks = add(new NumberSetting("Prediction ticks",
			"Ticks of target velocity to lead by", 2, 0, 10, 1), predict::get);
	public final BooleanSetting ignoreFriends = add(new BooleanSetting("Ignore friends",
			"Never target a friend", true));
	public final BooleanSetting fakePlayers = add(new BooleanSetting("Fake players",
			"Include client-side practice players, for testing", true));

	public final BooleanSetting place = add(new BooleanSetting("Place",
			"Place anchors", true));
	public final NumberSetting placeDelay = add(new NumberSetting("Place delay",
			"Quiet ticks after placing", 5, 0, 40, 1), place::get);
	public final NumberSetting placeRange = add(new NumberSetting("Place range",
			"Maximum distance from the eye to a placement", 4.0, 1, 6, 0.1), place::get);
	public final NumberSetting placeWallsRange = add(new NumberSetting("Walls range",
			"Maximum distance when the position is not directly visible", 4.0, 0, 6, 0.1),
			place::get);
	public final BooleanSetting airPlace = add(new BooleanSetting("Air place",
			"Allow a placement with no supporting face; many servers reject it", false),
			place::get);

	public final NumberSetting chargeDelay = add(new NumberSetting("Charge delay",
			"Quiet ticks after charging", 1, 0, 40, 1));
	public final BooleanSetting autoGlowstone = add(new BooleanSetting("Auto switch glowstone",
			"Select glowstone from the hotbar to charge", true));

	public final NumberSetting detonateDelay = add(new NumberSetting("Detonate delay",
			"Quiet ticks after detonating", 1, 0, 40, 1));
	public final NumberSetting detonateRange = add(new NumberSetting("Detonate range",
			"Maximum distance to an anchor we may set off", 4.5, 1, 8, 0.1));
	public final NumberSetting detonateWallsRange = add(new NumberSetting("Detonate walls range",
			"Maximum distance when the anchor is not directly visible", 4.5, 0, 8, 0.1));

	public final BooleanSetting autoSwitch = add(new BooleanSetting("Auto switch",
			"Select the anchor and the detonating item from the hotbar", true));
	public final BooleanSetting swapBack = add(new BooleanSetting("Swap back",
			"Return to the slot held before each step", true), autoSwitch::get);
	public final BooleanSetting rotate = add(new BooleanSetting("Rotate",
			"Aim through the shared rotation owner", true));
	public final ModeSetting rotation = add(new ModeSetting("Rotation",
			"Silent turns only the server/model; Visible also turns the camera", "Silent",
			"Silent", "Visible"), rotate::get);
	public final NumberSetting rotationSpeed = add(new NumberSetting("Rotation speed",
			"Maximum degrees turned per tick", 45, 1, 180, 1), rotate::get);

	public final BooleanSetting pauseUsing = add(new BooleanSetting("Pause while using item",
			"Stand down while you are eating, drinking or drawing a bow", true));
	public final BooleanSetting pauseMining = add(new BooleanSetting("Pause while mining",
			"Stand down while you are breaking a block", true));
	public final BooleanSetting pauseCrystal = add(new BooleanSetting("Pause while CrystalAura acts",
			"Never spend the same tick as CrystalAura", true));
	public final BooleanSetting pauseOnEat = addPauseOnEat();

	public final BooleanSetting renderBest = add(new BooleanSetting("Render best position",
			"Outline the position or anchor the cycle is working on", true));
	public final ColorSetting bestColor = add(new ColorSetting("Best color",
			"Color of the current stage marker", 0xC0FFA040), renderBest::get);
	public final BooleanSetting renderDamage = add(new BooleanSetting("Render damage text",
			"Label the marker with predicted target and self damage", true));
	public final BooleanSetting renderStage = add(new BooleanSetting("Render stage",
			"Label the marker with PLACE, CHARGE or DETONATE", true));
	public final BooleanSetting renderFade = add(new BooleanSetting("Render placed fade",
			"Fade out anchors that were just detonated", true));

	/** What the cycle is waiting to do next. Derived from the world, never assumed. */
	private enum Stage {
		PLACE,
		CHARGE,
		DETONATE
	}

	private record Candidate(BlockPos pos, Vec3 centre, float targetDamage, float selfDamage) {
	}

	private final java.util.Map<BlockPos, Long> fading = new java.util.LinkedHashMap<>();
	private BlockPos active;
	private Stage stage = Stage.PLACE;
	private int delayTicks;

	public AnchorAura() {
		super("AnchorAura", "Places, charges and detonates respawn anchors under a damage model",
				Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		reset();
	}

	@Override
	protected void onDisable() {
		InventoryActionCoordinator.release(this);
		reset();
	}

	private void reset() {
		active = null;
		stage = Stage.PLACE;
		delayTicks = 0;
		fading.clear();
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || mc().gameMode == null) {
			InventoryActionCoordinator.release(this);
			return;
		}
		drawFade();
		if (delayTicks > 0) {
			delayTicks--;
		}
		if (paused(player)) {
			InventoryActionCoordinator.release(this);
			return;
		}

		LivingEntity target = TargetingUtil.select(player, mc().level.entitiesForRendering(),
				new TargetingUtil.Filter().groups(true, true, false)
						.fakePlayers(fakePlayers.get()).ignoreFriends(ignoreFriends.get())
						.range(targetRange.get()).priority(targetPriority()));
		if (target == null) {
			active = null;
			stage = Stage.PLACE;
			InventoryActionCoordinator.release(this);
			return;
		}

		refreshStage();
		if (delayTicks > 0) {
			drawActive(target);
			return;
		}
		switch (stage) {
			case PLACE -> doPlace(player, target);
			case CHARGE -> doCharge(player);
			case DETONATE -> doDetonate(player, target);
		}
		drawActive(target);
	}

	/**
	 * In the Nether an anchor sets spawn instead of exploding, so the cycle must not run there.
	 * Everything else here is the ordinary shared pause set.
	 */
	private boolean paused(LocalPlayer player) {
		if (mc().level.dimension() == Level.NETHER) {
			return true;
		}
		if (pauseCrystal.get()
				&& UnluckyClient.INSTANCE.modules.get(CrystalAura.class).isActing()) {
			return true;
		}
		return mc().gui.screen() != null || player.isSpectator() || !player.isAlive()
				|| player.containerMenu != player.inventoryMenu
				|| AutoEat.pauses(pauseOnEat)
				|| (pauseUsing.get() && player.isUsingItem())
				|| (pauseMining.get() && mc().gameMode.isDestroying());
	}

	/**
	 * Re-derives the stage from the block that is actually there.
	 *
	 * <p>This is what makes the machine safe against lost packets: if the anchor never
	 * appeared we are back at PLACE, if it appeared but is uncharged we are at CHARGE, and a
	 * charged anchor is ready to DETONATE — regardless of what was sent last tick.
	 */
	private void refreshStage() {
		if (active == null) {
			stage = Stage.PLACE;
			return;
		}
		BlockState state = mc().level.getBlockState(active);
		if (!state.is(Blocks.RESPAWN_ANCHOR)) {
			active = null;
			stage = Stage.PLACE;
			return;
		}
		stage = state.getValue(RespawnAnchorBlock.CHARGE) > 0 ? Stage.DETONATE : Stage.CHARGE;
	}

	private void doPlace(LocalPlayer player, LivingEntity target) {
		if (!place.get()) {
			InventoryActionCoordinator.release(this);
			return;
		}
		Candidate best = bestCandidate(player, target);
		if (best == null) {
			InventoryActionCoordinator.release(this);
			return;
		}
		int slot = hotbarSlot(player, Items.RESPAWN_ANCHOR);
		if (slot < 0 || !autoSwitch.get() && player.getInventory().getSelectedSlot() != slot) {
			InventoryActionCoordinator.release(this);
			return;
		}
		BlockHitResult hit = supportClick(player, best.pos());
		if (hit == null || (rotate.get() && !aim(player, hit.getLocation()))) {
			return;
		}
		if (!useWith(player, slot, hit)) {
			return;
		}
		active = best.pos().immutable();
		stage = Stage.CHARGE;
		delayTicks = placeDelay.getInt();
	}

	private void doCharge(LocalPlayer player) {
		int slot = hotbarSlot(player, Items.GLOWSTONE);
		if (slot < 0 || (!autoGlowstone.get() && player.getInventory().getSelectedSlot() != slot)) {
			InventoryActionCoordinator.release(this);
			return;
		}
		Vec3 click = Vec3.atCenterOf(active);
		if (rotate.get() && !aim(player, click)) {
			return;
		}
		if (!useWith(player, slot, new BlockHitResult(click, Direction.UP, active, false))) {
			return;
		}
		delayTicks = chargeDelay.getInt();
	}

	/**
	 * Sets the anchor off, having first checked the blast against us with the same rules a
	 * placement used. Glowstone in hand would charge it again instead of detonating it, so the
	 * detonating click deliberately picks a slot that is not glowstone.
	 */
	private void doDetonate(LocalPlayer player, LivingEntity target) {
		Vec3 centre = Vec3.atCenterOf(active);
		Vec3 eye = player.getEyePosition();
		double allowed = visible(eye, centre, player)
				? detonateRange.get() : Math.min(detonateRange.get(), detonateWallsRange.get());
		if (eye.distanceTo(centre) > allowed) {
			InventoryActionCoordinator.release(this);
			return;
		}
		float selfDamage = ExplosionDamageUtil.self(centre, ExplosionDamageUtil.ANCHOR_RADIUS);
		if (!safe(centre, selfDamage)) {
			InventoryActionCoordinator.release(this);
			return;
		}
		int slot = nonGlowstoneSlot(player);
		if (slot < 0) {
			InventoryActionCoordinator.release(this);
			return;
		}
		if (rotate.get() && !aim(player, centre)) {
			return;
		}
		if (!useWith(player, slot, new BlockHitResult(centre, Direction.UP, active, false))) {
			return;
		}
		fading.put(active.immutable(), System.currentTimeMillis());
		active = null;
		stage = Stage.PLACE;
		delayTicks = detonateDelay.getInt();
	}

	/** Equips {@code slot}, sends one ordinary use click, and restores the hotbar. */
	private boolean useWith(LocalPlayer player, int slot, BlockHitResult hit) {
		if (!InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_COMBAT)
				|| !InventoryActionCoordinator.owns(this)) {
			return false;
		}
		int previous = player.getInventory().getSelectedSlot();
		if (slot != previous && !InventoryActionCoordinator.selectHotbar(this, slot)) {
			InventoryActionCoordinator.release(this);
			return false;
		}
		mc().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
		player.swing(InteractionHand.MAIN_HAND);
		if (slot != previous && swapBack.get()) {
			InventoryActionCoordinator.selectHotbar(this, previous);
		} else if (slot != previous) {
			InventoryActionCoordinator.keepHotbar(this);
		}
		InventoryActionCoordinator.release(this);
		return true;
	}

	/** Best legal, safe position for a new anchor, scored on target damage minus self damage. */
	private Candidate bestCandidate(LocalPlayer player, LivingEntity target) {
		double range = placeRange.get();
		int reach = Mth.ceil(range);
		BlockPos origin = player.blockPosition();
		Vec3 eye = player.getEyePosition();
		Vec3 lead = predict.get()
				? target.getDeltaMovement().scale(predictionTicks.get()) : Vec3.ZERO;
		List<Candidate> found = new ArrayList<>();

		for (int dx = -reach; dx <= reach; dx++) {
			for (int dy = -reach; dy <= reach; dy++) {
				for (int dz = -reach; dz <= reach; dz++) {
					BlockPos pos = origin.offset(dx, dy, dz);
					if (!mc().level.getBlockState(pos).canBeReplaced() || !clearOfEntities(pos)) {
						continue;
					}
					if (!airPlace.get() && supportClick(player, pos) == null) {
						continue;
					}
					Vec3 centre = Vec3.atCenterOf(pos);
					double allowed = visible(eye, centre, player)
							? range : Math.min(range, placeWallsRange.get());
					if (eye.distanceTo(centre) > allowed) {
						continue;
					}
					float targetDamage = ExplosionDamageUtil.afterProtection(target,
							ExplosionDamageUtil.raw(centre.subtract(lead),
									ExplosionDamageUtil.ANCHOR_RADIUS, target));
					float selfDamage = ExplosionDamageUtil.self(centre,
							ExplosionDamageUtil.ANCHOR_RADIUS);
					if (targetDamage < minTargetDamage.getFloat() || !safe(centre, selfDamage)) {
						continue;
					}
					found.add(new Candidate(pos.immutable(), centre, targetDamage, selfDamage));
				}
			}
		}

		Candidate best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (Candidate candidate : found) {
			double score = candidate.targetDamage() - candidate.selfDamage();
			if (score > bestScore) {
				bestScore = score;
				best = candidate;
			}
		}
		return best;
	}

	private boolean clearOfEntities(BlockPos pos) {
		AABB box = new AABB(pos);
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (entity.isAlive() && !entity.isSpectator() && entity.getBoundingBox().intersects(box)) {
				return false;
			}
		}
		return true;
	}

	/** A neighbouring solid face to click against, or null when only an air place would do. */
	private BlockHitResult supportClick(LocalPlayer player, BlockPos pos) {
		for (Direction direction : Direction.values()) {
			BlockPos neighbour = pos.relative(direction);
			if (mc().level.getBlockState(neighbour).canBeReplaced()) {
				continue;
			}
			Direction face = direction.getOpposite();
			Vec3 point = Vec3.atCenterOf(neighbour)
					.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
			if (player.getEyePosition().distanceTo(point) <= placeRange.get()) {
				return new BlockHitResult(point, face, neighbour, false);
			}
		}
		return airPlace.get()
				? new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos.below(), false) : null;
	}

	private boolean safe(Vec3 centre, float selfDamage) {
		if (selfDamage > maxSelfDamage.getFloat()) {
			return false;
		}
		return !antiSuicide.get() || ExplosionDamageUtil.selfSurvivable(centre,
				ExplosionDamageUtil.ANCHOR_RADIUS, safetyMargin.getFloat());
	}

	private static int hotbarSlot(LocalPlayer player, net.minecraft.world.item.Item item) {
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			if (player.getInventory().getItem(slot).is(item)) {
				return slot;
			}
		}
		return -1;
	}

	/** Any hotbar slot that will not recharge the anchor; the empty hand is a valid answer. */
	private static int nonGlowstoneSlot(LocalPlayer player) {
		int selected = player.getInventory().getSelectedSlot();
		if (!player.getInventory().getItem(selected).is(Items.GLOWSTONE)) {
			return selected;
		}
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			if (!player.getInventory().getItem(slot).is(Items.GLOWSTONE)) {
				return slot;
			}
		}
		return -1;
	}

	private boolean aim(LocalPlayer player, Vec3 point) {
		if (rotation.is("Silent")) {
			return RotationManager.face(point, rotationSpeed.getFloat(),
					RotationManager.PRIORITY_FUNCTIONAL);
		}
		Vec3 delta = point.subtract(player.getEyePosition());
		double horizontal = delta.horizontalDistance();
		float wantedYaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90.0f;
		float wantedPitch = (float) (-(Mth.atan2(delta.y, horizontal) * Mth.RAD_TO_DEG));
		float speed = rotationSpeed.getFloat();
		float yaw = player.getYRot()
				+ Mth.clamp(Mth.wrapDegrees(wantedYaw - player.getYRot()), -speed, speed);
		float pitch = Mth.clamp(player.getXRot()
				+ Mth.clamp(wantedPitch - player.getXRot(), -speed, speed), -90.0f, 90.0f);
		if (!RotationManager.rotateIfAllowed(yaw, pitch, RotationManager.PRIORITY_FUNCTIONAL)) {
			return false;
		}
		player.setYRot(yaw);
		player.setXRot(pitch);
		return Math.abs(Mth.wrapDegrees(wantedYaw - yaw)) < 1.0f
				&& Math.abs(wantedPitch - pitch) < 1.0f;
	}

	private boolean visible(Vec3 eye, Vec3 point, LocalPlayer player) {
		return mc().level.clip(new ClipContext(eye, point, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
	}

	private TargetingUtil.Priority targetPriority() {
		return priority.is("Closest") ? TargetingUtil.Priority.CLOSEST
				: priority.is("Lowest armor") ? TargetingUtil.Priority.LOWEST_ARMOR
				: TargetingUtil.Priority.LOWEST_HEALTH;
	}

	private void drawActive(LivingEntity target) {
		if (active == null || !renderBest.get()) {
			return;
		}
		int color = bestColor.get();
		Render3D.blockBox(active, color, 2.0f,
				ColorUtil.withAlpha(color, Math.max(24, bestColor.alpha() / 4)), true);
		if (renderStage.get()) {
			Render3D.blockLabel(stage.name(), active, color, 1.0f);
		}
		if (renderDamage.get()) {
			Vec3 centre = Vec3.atCenterOf(active);
			Render3D.blockLabel(String.format("%.1f / -%.1f",
					ExplosionDamageUtil.damage(centre, ExplosionDamageUtil.ANCHOR_RADIUS, target),
					ExplosionDamageUtil.self(centre, ExplosionDamageUtil.ANCHOR_RADIUS)),
					active.above(), color, 1.0f);
		}
	}

	private void drawFade() {
		long now = System.currentTimeMillis();
		fading.entrySet().removeIf(entry -> now - entry.getValue() >= FADE_MS);
		if (!renderFade.get()) {
			return;
		}
		for (var entry : fading.entrySet()) {
			double life = 1.0 - (double) (now - entry.getValue()) / FADE_MS;
			int alpha = (int) (bestColor.alpha() * Mth.clamp(life, 0.0, 1.0));
			Render3D.blockBox(entry.getKey(), ColorUtil.withAlpha(bestColor.get(), alpha), 1.5f,
					ColorUtil.withAlpha(bestColor.get(), Math.max(0, alpha / 4)), true);
		}
	}
}

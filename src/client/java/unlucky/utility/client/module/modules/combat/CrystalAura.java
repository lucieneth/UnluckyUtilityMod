package unlucky.utility.client.module.modules.combat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.player.AutoEat;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.CombatUtil;
import unlucky.utility.client.util.ExplosionDamageUtil;
import unlucky.utility.client.util.InventoryActionCoordinator;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.RotationManager;
import unlucky.utility.client.util.TargetingUtil;

/**
 * Places and detonates end crystals against a target.
 *
 * <p>Every decision here is a damage comparison, so the damage model is the module. Candidate
 * positions are scored by what {@link ExplosionDamageUtil} says the blast would do to the
 * target minus what it would do to us, and a position that fails the self-damage or survival
 * test is discarded before anything is sent. Anti-suicide is checked against remaining health
 * rather than the max-damage slider on purpose: the slider is a preference, being alive is not.
 *
 * <p>Placement legality is vanilla's, not an approximation of it. A crystal needs an obsidian
 * or bedrock base, an empty block above it, and no entity standing in the two-block volume it
 * would occupy — the same three tests {@code EndCrystalItem} makes — so a position this module
 * offers is one the server will actually accept. The 1.12 rule (two clear blocks above) is kept
 * as an option because servers pinned to that behavior still exist.
 *
 * <p>The candidate sweep is bounded by place range and nothing else; there is no world scan.
 * Exposure sampling is the expensive part of the damage model, so the sweep is a cube of side
 * {@code 2 * range} evaluated once per tick and the results are reused by the renderer rather
 * than recomputed for it.
 *
 * @see AnchorAura which pauses while this module is acting, so the two never spend the same tick
 */
public class CrystalAura extends Module {
	private static final long FADE_MS = 600L;

	public final NumberSetting targetRange = add(new NumberSetting("Target range",
			"Maximum distance to a valid target", 10, 1, 20, 0.5));
	public final ModeSetting priority = add(new ModeSetting("Priority",
			"Target ranking", "Lowest health", "Lowest health", "Closest", "Lowest armor"));
	public final BooleanSetting predict = add(new BooleanSetting("Predict movement",
			"Aim at where the target will be rather than where it is", true));
	public final NumberSetting predictionTicks = add(new NumberSetting("Prediction ticks",
			"Ticks of target velocity to lead by", 2, 0, 10, 1), predict::get);
	public final BooleanSetting ignoreFriends = add(new BooleanSetting("Ignore friends",
			"Never target a friend", true));
	public final BooleanSetting ignoreNaked = add(new BooleanSetting("Ignore naked players",
			"Skip players wearing no armor at all", false));
	public final BooleanSetting fakePlayers = add(new BooleanSetting("Fake players",
			"Include client-side practice players, for testing", true));

	public final NumberSetting minTargetDamage = add(new NumberSetting("Min target damage",
			"Never place for less than this much damage", 6, 0, 20, 0.5));
	public final NumberSetting maxSelfDamage = add(new NumberSetting("Max self damage",
			"Never place or break something predicted to hit you harder than this", 6, 0, 20, 0.5));
	public final BooleanSetting antiSuicide = add(new BooleanSetting("Anti-suicide",
			"Refuse anything the damage model says you would not survive", true));
	public final NumberSetting safetyMargin = add(new NumberSetting("Safety margin",
			"Health that must remain for a blast to count as survivable", 1, 0, 10, 0.5),
			antiSuicide::get);
	public final NumberSetting selfWeight = add(new NumberSetting("Self-damage weight",
			"How heavily self damage counts against a candidate's score", 1.0, 0, 4, 0.1));

	public final BooleanSetting rotate = add(new BooleanSetting("Rotate",
			"Aim through the shared rotation owner", true));
	public final ModeSetting rotation = add(new ModeSetting("Rotation",
			"Silent turns only the server/model; Visible also turns the camera", "Silent",
			"Silent", "Visible"), rotate::get);
	public final NumberSetting maxYaw = add(new NumberSetting("Max yaw step",
			"Degrees of yaw turned per tick", 45, 1, 180, 1), rotate::get);
	public final NumberSetting maxPitch = add(new NumberSetting("Max pitch step",
			"Degrees of pitch turned per tick", 45, 1, 180, 1), rotate::get);

	public final BooleanSetting place = add(new BooleanSetting("Place",
			"Place crystals", true));
	public final NumberSetting placeDelay = add(new NumberSetting("Place delay",
			"Quiet ticks after a placement", 0, 0, 20, 1), place::get);
	public final NumberSetting placeRange = add(new NumberSetting("Place range",
			"Maximum distance from the eye to a placement", 4.5, 1, 6, 0.1), place::get);
	public final NumberSetting placeWallsRange = add(new NumberSetting("Walls range",
			"Maximum distance when the position is not directly visible", 3.0, 0, 6, 0.1),
			place::get);
	public final ModeSetting placementRules = add(new ModeSetting("Placement rules",
			"Modern needs one clear block above; 1.12 needs two", "Modern", "Modern", "1.12"),
			place::get);

	public final BooleanSetting breakCrystals = add(new BooleanSetting("Break",
			"Detonate crystals", true));
	public final NumberSetting breakDelay = add(new NumberSetting("Break delay",
			"Quiet ticks after an attack", 0, 0, 20, 1), breakCrystals::get);
	public final NumberSetting breakRange = add(new NumberSetting("Break range",
			"Maximum distance to a crystal we may hit", 4.5, 1, 8, 0.1), breakCrystals::get);
	public final NumberSetting breakWallsRange = add(new NumberSetting("Break walls range",
			"Maximum distance when the crystal is not directly visible", 3.0, 0, 8, 0.1),
			breakCrystals::get);
	public final NumberSetting minimumAge = add(new NumberSetting("Minimum crystal age",
			"Ticks a crystal must have existed before it may be hit", 0, 0, 40, 1),
			breakCrystals::get);
	public final BooleanSetting onlyOwn = add(new BooleanSetting("Only own crystals",
			"Never detonate a crystal this module did not place", false), breakCrystals::get);
	public final NumberSetting maxAttempts = add(new NumberSetting("Max attempts per crystal",
			"Stop re-hitting a crystal the server is not removing", 2, 1, 10, 1),
			breakCrystals::get);

	public final ModeSetting autoSwitch = add(new ModeSetting("Auto switch",
			"Select a crystal from the hotbar before placing", "Normal", "Off", "Normal"));
	public final BooleanSetting swapBack = add(new BooleanSetting("Swap back",
			"Return to the slot held before each placement", true),
			() -> !autoSwitch.is("Off"));
	public final BooleanSetting antiWeakness = add(new BooleanSetting("Anti weakness",
			"Do not attack with Weakness unless the held item can still break a crystal", true));
	public final BooleanSetting notWhileEating = add(new BooleanSetting("Do not switch while eating",
			"Leave the hotbar alone while an item is being used", true));
	public final BooleanSetting pauseOnEat = addPauseOnEat();

	public final BooleanSetting facePlace = add(new BooleanSetting("Face place",
			"Ignore the minimum damage once the target is nearly finished", true));
	public final NumberSetting faceHealth = add(new NumberSetting("Face place health",
			"Target health at or below which face place applies", 8, 1, 20, 0.5), facePlace::get);
	public final NumberSetting faceArmor = add(new NumberSetting("Face place armor durability",
			"Armor durability percent at or below which face place applies", 10, 0, 100, 1),
			facePlace::get);
	public final BooleanSetting faceMissingArmor = add(new BooleanSetting("Face place on missing armor",
			"Apply face place when the target is missing a piece", true), facePlace::get);

	public final BooleanSetting pauseUsing = add(new BooleanSetting("Pause while using item",
			"Stand down while you are eating, drinking or drawing a bow", true));
	public final BooleanSetting pauseMining = add(new BooleanSetting("Pause while mining",
			"Stand down while you are breaking a block", true));
	public final NumberSetting pauseHealth = add(new NumberSetting("Pause below health",
			"Stop entirely under this much effective health", 4, 0, 20, 0.5));

	public final BooleanSetting renderCandidates = add(new BooleanSetting("Render candidate positions",
			"Outline every position that passed the safety tests", false));
	public final BooleanSetting renderBest = add(new BooleanSetting("Render best position",
			"Outline the position about to be used", true));
	public final ColorSetting bestColor = add(new ColorSetting("Best color",
			"Color of the chosen position", 0xC040C0FF), renderBest::get);
	public final BooleanSetting renderDamage = add(new BooleanSetting("Render damage text",
			"Label the chosen position with predicted target and self damage", true));
	public final BooleanSetting renderPlaced = add(new BooleanSetting("Render placed crystal",
			"Fade out positions a crystal was just placed on", true));

	/** A legal position with its two damage numbers already computed. */
	public record Candidate(BlockPos base, Vec3 centre, float targetDamage, float selfDamage) {
		public double score(double weight) {
			return targetDamage - selfDamage * weight;
		}
	}

	private final List<Candidate> candidates = new ArrayList<>();
	private final Set<BlockPos> ourCrystals = new HashSet<>();
	private final java.util.Map<Integer, Integer> attempts = new java.util.HashMap<>();
	private final java.util.Map<BlockPos, Long> placedFade = new java.util.LinkedHashMap<>();
	private int placeTimer;
	private int breakTimer;
	private boolean acting;

	public CrystalAura() {
		super("CrystalAura", "Places and detonates end crystals under a damage model",
				Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
	}

	/** Whether this module took an action this tick — AnchorAura's cooperative pause. */
	public boolean isActing() {
		return isEnabled() && acting;
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
		candidates.clear();
		ourCrystals.clear();
		attempts.clear();
		placedFade.clear();
		placeTimer = 0;
		breakTimer = 0;
		acting = false;
	}

	@Override
	public void onTick() {
		acting = false;
		candidates.clear();
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || mc().gameMode == null) {
			InventoryActionCoordinator.release(this);
			return;
		}
		drawPlaced();
		if (placeTimer > 0) {
			placeTimer--;
		}
		if (breakTimer > 0) {
			breakTimer--;
		}
		if (paused(player)) {
			InventoryActionCoordinator.release(this);
			return;
		}

		LivingEntity target = selectTarget(player);
		if (target == null) {
			InventoryActionCoordinator.release(this);
			return;
		}

		// Break first: a crystal already in the world is damage that is one click away, and
		// placing another before detonating it would only crowd the position.
		if (breakCrystals.get() && breakTimer <= 0 && tryBreak(player, target)) {
			acting = true;
			breakTimer = breakDelay.getInt();
			return;
		}

		if (!place.get() || placeTimer > 0) {
			InventoryActionCoordinator.release(this);
			return;
		}
		collectCandidates(player, target);
		Candidate best = bestCandidate();
		if (best == null) {
			InventoryActionCoordinator.release(this);
			return;
		}
		drawCandidates(best);
		if (tryPlace(player, best)) {
			acting = true;
			placeTimer = placeDelay.getInt();
		} else {
			InventoryActionCoordinator.release(this);
		}
	}

	private boolean paused(LocalPlayer player) {
		return mc().gui.screen() != null || player.isSpectator() || !player.isAlive()
				|| player.containerMenu != player.inventoryMenu
				|| AutoEat.pauses(pauseOnEat)
				|| (pauseUsing.get() && player.isUsingItem())
				|| (pauseMining.get() && mc().gameMode.isDestroying())
				|| ExplosionDamageUtil.effectiveHealth(player) < pauseHealth.getFloat();
	}

	private LivingEntity selectTarget(LocalPlayer player) {
		return TargetingUtil.select(player, mc().level.entitiesForRendering(),
				new TargetingUtil.Filter().groups(true, true, false)
						.fakePlayers(fakePlayers.get())
						.ignoreFriends(ignoreFriends.get())
						.range(targetRange.get())
						.priority(targetPriority())
						.extra(candidate -> !ignoreNaked.get() || armorPieces(candidate) > 0));
	}

	private TargetingUtil.Priority targetPriority() {
		return priority.is("Closest") ? TargetingUtil.Priority.CLOSEST
				: priority.is("Lowest armor") ? TargetingUtil.Priority.LOWEST_ARMOR
				: TargetingUtil.Priority.LOWEST_HEALTH;
	}

	/**
	 * Sweeps the cube around the player for legal, safe positions.
	 *
	 * <p>Bounded by place range in every axis, so the cost is fixed by a setting the player can
	 * see rather than by how much world happens to be loaded.
	 */
	private void collectCandidates(LocalPlayer player, LivingEntity target) {
		double range = placeRange.get();
		int reach = Mth.ceil(range);
		BlockPos origin = player.blockPosition();
		Vec3 eye = player.getEyePosition();
		Vec3 lead = predict.get()
				? target.getDeltaMovement().scale(predictionTicks.get()) : Vec3.ZERO;
		boolean allowFace = facePlace.get() && faceApplies(target);

		for (int dx = -reach; dx <= reach; dx++) {
			for (int dy = -reach; dy <= reach; dy++) {
				for (int dz = -reach; dz <= reach; dz++) {
					BlockPos base = origin.offset(dx, dy, dz);
					if (!placeable(base)) {
						continue;
					}
					Vec3 centre = new Vec3(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
					double distance = eye.distanceTo(centre);
					double allowed = visible(eye, centre, player)
							? range : Math.min(range, placeWallsRange.get());
					if (distance > allowed) {
						continue;
					}

					// Prediction shifts the measured blast origin by the target's displacement.
					// That is exact for the distance term and an approximation for the exposure
					// rays, which is the right trade: distance dominates the damage curve.
					float targetDamage = ExplosionDamageUtil.afterProtection(target,
							ExplosionDamageUtil.raw(centre.subtract(lead),
									ExplosionDamageUtil.CRYSTAL_RADIUS, target));
					float selfDamage = ExplosionDamageUtil.self(centre,
							ExplosionDamageUtil.CRYSTAL_RADIUS);
					if (!safe(centre, selfDamage)) {
						continue;
					}
					if (targetDamage < minTargetDamage.getFloat() && !allowFace) {
						continue;
					}
					candidates.add(new Candidate(base.immutable(), centre, targetDamage, selfDamage));
				}
			}
		}
	}

	/** Vanilla's three placement tests, plus the optional 1.12 headroom rule. */
	private boolean placeable(BlockPos base) {
		if (!mc().level.getBlockState(base).is(Blocks.OBSIDIAN)
				&& !mc().level.getBlockState(base).is(Blocks.BEDROCK)) {
			return false;
		}
		BlockPos above = base.above();
		if (!mc().level.getBlockState(above).isAir()) {
			return false;
		}
		if (placementRules.is("1.12") && !mc().level.getBlockState(above.above()).isAir()) {
			return false;
		}
		AABB occupied = new AABB(above.getX(), above.getY(), above.getZ(),
				above.getX() + 1.0, above.getY() + 2.0, above.getZ() + 1.0);
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (!(entity instanceof EndCrystal) && entity.isAlive()
					&& entity.getBoundingBox().intersects(occupied)) {
				return false;
			}
		}
		return true;
	}

	private boolean safe(Vec3 centre, float selfDamage) {
		if (selfDamage > maxSelfDamage.getFloat()) {
			return false;
		}
		return !antiSuicide.get() || ExplosionDamageUtil.selfSurvivable(centre,
				ExplosionDamageUtil.CRYSTAL_RADIUS, safetyMargin.getFloat());
	}

	private Candidate bestCandidate() {
		Candidate best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (Candidate candidate : candidates) {
			double score = candidate.score(selfWeight.get());
			if (score > bestScore) {
				bestScore = score;
				best = candidate;
			}
		}
		return best;
	}

	/** True when the target is finished enough that the minimum-damage gate stops helping. */
	private boolean faceApplies(LivingEntity target) {
		if (ExplosionDamageUtil.effectiveHealth(target) <= faceHealth.getFloat()) {
			return true;
		}
		int pieces = 0;
		for (EquipmentSlot slot : new EquipmentSlot[] { EquipmentSlot.HEAD, EquipmentSlot.CHEST,
				EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
			ItemStack armor = target.getItemBySlot(slot);
			if (armor.isEmpty()) {
				continue;
			}
			pieces++;
			if (armor.isDamageableItem() && armor.getMaxDamage() > 0) {
				double remaining = 100.0 * (armor.getMaxDamage() - armor.getDamageValue())
						/ armor.getMaxDamage();
				if (remaining <= faceArmor.get()) {
					return true;
				}
			}
		}
		return faceMissingArmor.get() && pieces < 4;
	}

	private static int armorPieces(LivingEntity entity) {
		int pieces = 0;
		for (EquipmentSlot slot : new EquipmentSlot[] { EquipmentSlot.HEAD, EquipmentSlot.CHEST,
				EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
			if (!entity.getItemBySlot(slot).isEmpty()) {
				pieces++;
			}
		}
		return pieces;
	}

	/**
	 * Detonates the best legal crystal.
	 *
	 * <p>Scored the same way a placement is, because a crystal someone else placed is only
	 * worth hitting if the blast still favours us. The attempt counter exists because a server
	 * that has already removed a crystal the client still shows would otherwise attract an
	 * attack packet every tick for as long as the ghost entity lived.
	 */
	private boolean tryBreak(LocalPlayer player, LivingEntity target) {
		Vec3 eye = player.getEyePosition();
		EndCrystal best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (!(entity instanceof EndCrystal crystal) || !crystal.isAlive()) {
				continue;
			}
			if (crystal.time < minimumAge.getInt()) {
				continue;
			}
			if (onlyOwn.get() && !ourCrystals.contains(crystal.blockPosition())) {
				continue;
			}
			if (attempts.getOrDefault(crystal.getId(), 0) >= maxAttempts.getInt()) {
				continue;
			}
			Vec3 centre = crystal.position();
			double allowed = visible(eye, centre, player)
					? breakRange.get() : Math.min(breakRange.get(), breakWallsRange.get());
			if (eye.distanceTo(centre) > allowed) {
				continue;
			}
			float selfDamage = ExplosionDamageUtil.self(centre, ExplosionDamageUtil.CRYSTAL_RADIUS);
			if (!safe(centre, selfDamage)) {
				continue;
			}
			float targetDamage = ExplosionDamageUtil.damage(centre,
					ExplosionDamageUtil.CRYSTAL_RADIUS, target);
			double score = targetDamage - selfDamage * selfWeight.get();
			if (score > bestScore) {
				bestScore = score;
				best = crystal;
			}
		}
		if (best == null) {
			return false;
		}
		if (antiWeakness.get() && player.hasEffect(MobEffects.WEAKNESS)
				&& player.getMainHandItem().isEmpty()) {
			return false; // a bare fist under Weakness does not remove a crystal
		}
		if (rotate.get() && !aim(player, best.getBoundingBox().getCenter())) {
			return false;
		}
		attempts.merge(best.getId(), 1, Integer::sum);
		CombatUtil.attack(best);
		return true;
	}

	/** Equips a crystal, aims at the base's top face and sends one ordinary use click. */
	private boolean tryPlace(LocalPlayer player, Candidate candidate) {
		if (notWhileEating.get() && player.isUsingItem()) {
			return false;
		}
		InteractionHand hand = player.getOffhandItem().is(Items.END_CRYSTAL)
				? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		int slot = -1;
		if (hand == InteractionHand.MAIN_HAND && !player.getMainHandItem().is(Items.END_CRYSTAL)) {
			if (autoSwitch.is("Off")) {
				return false;
			}
			slot = hotbarCrystal(player);
			if (slot < 0) {
				return false;
			}
		}

		Vec3 click = new Vec3(candidate.base().getX() + 0.5, candidate.base().getY() + 1.0,
				candidate.base().getZ() + 0.5);
		if (rotate.get() && !aim(player, click)) {
			return false;
		}
		if (!InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_COMBAT)
				|| !InventoryActionCoordinator.owns(this)) {
			return false;
		}

		int previous = player.getInventory().getSelectedSlot();
		if (slot >= 0 && !InventoryActionCoordinator.selectHotbar(this, slot)) {
			InventoryActionCoordinator.release(this);
			return false;
		}
		mc().gameMode.useItemOn(player, hand,
				new BlockHitResult(click, Direction.UP, candidate.base(), false));
		player.swing(hand);
		ourCrystals.add(candidate.base().above().immutable());
		placedFade.put(candidate.base().immutable(), System.currentTimeMillis());

		if (slot >= 0 && swapBack.get()) {
			InventoryActionCoordinator.selectHotbar(this, previous);
		} else if (slot >= 0) {
			InventoryActionCoordinator.keepHotbar(this);
		}
		InventoryActionCoordinator.release(this);
		return true;
	}

	private static int hotbarCrystal(LocalPlayer player) {
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			if (player.getInventory().getItem(slot).is(Items.END_CRYSTAL)) {
				return slot;
			}
		}
		return -1;
	}

	/** Turns toward {@code point}; returns whether we own the rotation and are on target. */
	private boolean aim(LocalPlayer player, Vec3 point) {
		if (rotation.is("Silent")) {
			return RotationManager.face(point, Math.min(maxYaw.getFloat(), maxPitch.getFloat()),
					RotationManager.PRIORITY_FUNCTIONAL);
		}
		Vec3 delta = point.subtract(player.getEyePosition());
		double horizontal = delta.horizontalDistance();
		float wantedYaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90.0f;
		float wantedPitch = (float) (-(Mth.atan2(delta.y, horizontal) * Mth.RAD_TO_DEG));
		float yaw = player.getYRot() + Mth.clamp(Mth.wrapDegrees(wantedYaw - player.getYRot()),
				-maxYaw.getFloat(), maxYaw.getFloat());
		float pitch = Mth.clamp(player.getXRot()
				+ Mth.clamp(wantedPitch - player.getXRot(), -maxPitch.getFloat(),
						maxPitch.getFloat()), -90.0f, 90.0f);
		if (!RotationManager.rotateIfAllowed(yaw, pitch, RotationManager.PRIORITY_FUNCTIONAL)) {
			return false;
		}
		player.setYRot(yaw);
		player.setXRot(pitch);
		return Math.abs(Mth.wrapDegrees(wantedYaw - yaw)) < 1.0f
				&& Math.abs(wantedPitch - pitch) < 1.0f;
	}

	private boolean visible(Vec3 eye, Vec3 point, LocalPlayer player) {
		return mc().level.clip(new net.minecraft.world.level.ClipContext(eye, point,
				net.minecraft.world.level.ClipContext.Block.COLLIDER,
				net.minecraft.world.level.ClipContext.Fluid.NONE, player))
				.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
	}

	private void drawCandidates(Candidate best) {
		if (renderCandidates.get()) {
			int faint = ColorUtil.withAlpha(bestColor.get(), 40);
			for (Candidate candidate : candidates) {
				if (candidate != best) {
					Render3D.blockBox(candidate.base().above(), faint, 1.0f,
							ColorUtil.withAlpha(bestColor.get(), 12), true);
				}
			}
		}
		if (renderBest.get()) {
			Render3D.blockBox(best.base().above(), bestColor.get(), 2.0f,
					ColorUtil.withAlpha(bestColor.get(), Math.max(24, bestColor.alpha() / 4)), true);
		}
		if (renderDamage.get()) {
			Render3D.blockLabel(String.format("%.1f / -%.1f", best.targetDamage(), best.selfDamage()),
					best.base().above(), bestColor.get(), 1.0f);
		}
	}

	private void drawPlaced() {
		long now = System.currentTimeMillis();
		placedFade.entrySet().removeIf(entry -> now - entry.getValue() >= FADE_MS);
		attempts.keySet().removeIf(id -> mc().level.getEntity(id) == null);
		ourCrystals.removeIf(pos -> mc().level.getBlockState(pos.below()).isAir());
		if (!renderPlaced.get()) {
			return;
		}
		for (var entry : placedFade.entrySet()) {
			double life = 1.0 - (double) (now - entry.getValue()) / FADE_MS;
			int alpha = (int) (bestColor.alpha() * Mth.clamp(life, 0.0, 1.0));
			Render3D.blockBox(entry.getKey().above(),
					ColorUtil.withAlpha(bestColor.get(), alpha), 1.5f,
					ColorUtil.withAlpha(bestColor.get(), Math.max(0, alpha / 4)), true);
		}
	}
}

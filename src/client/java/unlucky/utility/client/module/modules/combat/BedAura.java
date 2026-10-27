package unlucky.utility.client.module.modules.combat;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
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
import unlucky.utility.client.util.SwingUtil;
import unlucky.utility.client.util.TargetingUtil;

/**
 * Places beds against a target and sets them off, where beds explode.
 *
 * <p>The gate is the bed rule itself, read from the environment attributes at the bed's own
 * position, not a dimension key: in the Overworld a bed click is a sleep attempt (or a spawn
 * change), and a datapack can make any dimension either way. Where the rule says
 * {@code destroy_on_use}, a click removes both halves and explodes at the centre of the head
 * with power 5 — the damage model scores exactly that point.
 *
 * <p><b>The bed's facing is the player's yaw.</b> Vanilla places the foot where you click and the
 * head one block along your horizontal facing, so a candidate is a head position plus a
 * direction, and placing it means turning to that direction first. The turn is requested a tick
 * before the click — the spoofed rotation rides the end-of-tick movement packet, and the server
 * reads the facing from the rotation it already has — and the click goes out only if the same
 * candidate is still the best next tick.
 *
 * <p>Two stages, re-derived from the world each tick like {@link AnchorAura}: no bed at the active
 * head means place, a bed there means detonate. A lost packet is a retry, not a desync.
 */
public class BedAura extends Module {
	private static final long FADE_MS = 600L;
	private static final Direction[] HORIZONTAL = {
			Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
	};

	public final NumberSetting targetRange = add(new NumberSetting("Target range",
			"Maximum distance to a valid target", 10, 1, 20, 0.5));
	public final ModeSetting priority = add(new ModeSetting("Priority",
			"Target ranking", "Lowest health", "Lowest health", "Closest", "Lowest armor"));
	public final NumberSetting minTargetDamage = add(new NumberSetting("Min target damage",
			"Never place for less than this much damage", 7, 0, 20, 0.5));
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

	public final NumberSetting placeDelay = add(new NumberSetting("Place delay",
			"Quiet ticks after placing", 2, 0, 40, 1));
	public final NumberSetting placeRange = add(new NumberSetting("Place range",
			"Maximum distance from the eye to either half of the bed", 4.5, 1, 6, 0.1));
	public final NumberSetting placeWallsRange = add(new NumberSetting("Walls range",
			"Maximum distance when the head is not directly visible", 3.5, 0, 6, 0.1));
	public final NumberSetting detonateDelay = add(new NumberSetting("Detonate delay",
			"Quiet ticks after detonating", 1, 0, 40, 1));
	public final BooleanSetting autoSwitch = add(new BooleanSetting("Auto switch",
			"Select a bed from the hotbar to place", true));
	public final BooleanSetting swapBack = add(new BooleanSetting("Swap back",
			"Return to the slot held before each placement", true), autoSwitch::get);
	public final ModeSetting rotation = add(new ModeSetting("Rotation",
			"Silent turns only the server/model; Visible also turns the camera. Beds always need the turn",
			"Silent", "Silent", "Visible"));

	public final BooleanSetting pauseUsing = add(new BooleanSetting("Pause while using item",
			"Stand down while you are eating, drinking or drawing a bow", true));
	public final BooleanSetting pauseMining = add(new BooleanSetting("Pause while mining",
			"Stand down while you are breaking a block", true));
	public final BooleanSetting pauseCrystal = add(new BooleanSetting("Pause while CrystalAura acts",
			"Never spend the same tick as CrystalAura", true));
	public final BooleanSetting pauseOnEat = addPauseOnEat();

	public final BooleanSetting renderBest = add(new BooleanSetting("Render best position",
			"Outline the bed the cycle is working on", true));
	public final ColorSetting bestColor = add(new ColorSetting("Best color",
			"Color of the bed marker", 0xC0FF4060), renderBest::get);
	public final BooleanSetting renderDamage = add(new BooleanSetting("Render damage text",
			"Label the marker with predicted target and self damage", true));
	public final BooleanSetting renderFade = add(new BooleanSetting("Render placed fade",
			"Fade out beds that were just detonated", true));

	/** Head is where the blast is; facing points from the foot to the head. */
	private record Candidate(BlockPos head, Direction facing, BlockHitResult click,
			float targetDamage, float selfDamage) {
		BlockPos foot() {
			return head.relative(facing.getOpposite());
		}
	}

	private final Map<BlockPos, Long> fading = new LinkedHashMap<>();
	private Candidate active;
	/** The candidate we turned towards last tick; it is only placed if it is still the pick. */
	private Candidate aimed;
	private int delayTicks;

	public BedAura() {
		super("BedAura", "Places and detonates beds where they explode, under a damage model",
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
		aimed = null;
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
			aimed = null;
			InventoryActionCoordinator.release(this);
			return;
		}

		LivingEntity target = TargetingUtil.select(player, mc().level.entitiesForRendering(),
				new TargetingUtil.Filter().groups(true, true, false)
						.fakePlayers(fakePlayers.get()).ignoreFriends(ignoreFriends.get())
						.range(targetRange.get()).priority(targetPriority()));
		if (target == null) {
			active = null;
			aimed = null;
			return;
		}

		if (active != null && !(mc().level.getBlockState(active.head()).getBlock() instanceof BedBlock)) {
			active = null; // gone, or never arrived: back to placing
		}
		if (delayTicks <= 0) {
			if (active != null) {
				detonate(player);
			} else {
				place(player, target);
			}
		}
		drawActive(target);
	}

	/** Whether a bed is down and waiting to be set off. AnchorAura holds off until it is. */
	public boolean inCycle() {
		return isEnabled() && active != null;
	}

	private boolean paused(LocalPlayer player) {
		if (!bedsExplode(player.blockPosition())) {
			return true;
		}
		if (pauseCrystal.get()
				&& UnluckyClient.INSTANCE.modules.get(CrystalAura.class).isActing()) {
			return true;
		}
		// AnchorAura ticks first; while it has an anchor going, that cycle is finished first.
		if (UnluckyClient.INSTANCE.modules.get(AnchorAura.class).inCycle()) {
			return true;
		}
		return mc().gui.screen() != null || player.isSpectator() || !player.isAlive()
				|| player.containerMenu != player.inventoryMenu
				|| AutoEat.pauses(pauseOnEat)
				|| (pauseUsing.get() && player.isUsingItem())
				|| (pauseMining.get() && mc().gameMode.isDestroying());
	}

	/** Whether a bed used at {@code pos} blows up instead of letting you sleep. */
	private boolean bedsExplode(BlockPos pos) {
		return mc().level.environmentAttributes()
				.getValue(EnvironmentAttributes.BED_RULE, pos).destroyOnUse();
	}

	private void place(LocalPlayer player, LivingEntity target) {
		Candidate best = bestCandidate(player, target);
		if (best == null) {
			aimed = null;
			return;
		}
		int slot = bedSlot(player);
		if (slot < 0 || (!autoSwitch.get() && slot != player.getInventory().getSelectedSlot())) {
			aimed = null;
			return;
		}

		float yaw = best.facing().toYRot();
		float pitch = pitchTo(player, best.click().getLocation());
		if (!RotationManager.rotateIfAllowed(yaw, pitch, RotationManager.PRIORITY_FUNCTIONAL)) {
			aimed = null;
			return;
		}
		if (rotation.is("Visible")) {
			player.setYRot(yaw);
			player.setXRot(pitch);
		}
		boolean ready = aimed != null && aimed.head().equals(best.head())
				&& aimed.facing() == best.facing();
		aimed = best;
		if (!ready) {
			return; // the turn goes out this tick; the click follows next tick
		}

		if (!InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_COMBAT)) {
			return;
		}
		int previous = player.getInventory().getSelectedSlot();
		if (slot != previous && !InventoryActionCoordinator.selectHotbar(this, slot)) {
			InventoryActionCoordinator.release(this);
			return;
		}
		// The client predicts the bed from its own yaw, which is not the spoofed one; lend it
		// the yaw the server will use so prediction and server agree on the bed's facing.
		float realYaw = player.getYRot();
		player.setYRot(yaw);
		try {
			mc().gameMode.useItemOn(player, InteractionHand.MAIN_HAND, best.click());
		} finally {
			player.setYRot(realYaw);
		}
		SwingUtil.attack(player, InteractionHand.MAIN_HAND);
		if (slot != previous && !swapBack.get()) {
			InventoryActionCoordinator.keepHotbar(this);
		}
		InventoryActionCoordinator.release(this);

		active = best;
		aimed = null;
		delayTicks = placeDelay.getInt();
	}

	/**
	 * Clicks the bed. Block use comes before the held item for a player who is not sneaking,
	 * so whatever is in hand is fine — but a sneaking click would place that item instead.
	 */
	private void detonate(LocalPlayer player) {
		if (player.isShiftKeyDown()) {
			return;
		}
		BlockPos head = active.head();
		Vec3 centre = Vec3.atCenterOf(head);
		Vec3 eye = player.getEyePosition();
		if (eye.distanceTo(centre) > placeRange.get() + 0.5) {
			active = null;
			return;
		}
		if (!safe(centre, ExplosionDamageUtil.self(centre, ExplosionDamageUtil.BED_RADIUS))) {
			return;
		}
		Vec3 click = new Vec3(centre.x, head.getY() + 0.5, centre.z);
		RotationManager.rotateIfAllowed(yawTo(player, click), pitchTo(player, click),
				RotationManager.PRIORITY_FUNCTIONAL);
		mc().gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
				new BlockHitResult(click, Direction.UP, head, false));
		SwingUtil.attack(player, InteractionHand.MAIN_HAND);
		fading.put(head.immutable(), System.currentTimeMillis());
		active = null;
		delayTicks = detonateDelay.getInt();
	}

	/** Best legal, safe bed, scored on target damage minus self damage. */
	private Candidate bestCandidate(LocalPlayer player, LivingEntity target) {
		double range = placeRange.get();
		int reach = Mth.ceil(range);
		BlockPos origin = player.blockPosition();
		Vec3 eye = player.getEyePosition();
		Vec3 lead = predict.get()
				? target.getDeltaMovement().scale(predictionTicks.get()) : Vec3.ZERO;

		Candidate best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (int dx = -reach; dx <= reach; dx++) {
			for (int dy = -reach; dy <= reach; dy++) {
				for (int dz = -reach; dz <= reach; dz++) {
					BlockPos head = origin.offset(dx, dy, dz);
					Vec3 centre = Vec3.atCenterOf(head);
					if (!mc().level.getBlockState(head).canBeReplaced()
							|| eye.distanceTo(centre) > range) {
						continue;
					}
					double allowed = visible(eye, centre, player)
							? range : Math.min(range, placeWallsRange.get());
					if (eye.distanceTo(centre) > allowed) {
						continue;
					}
					float targetDamage = ExplosionDamageUtil.afterProtection(target,
							ExplosionDamageUtil.raw(centre.subtract(lead),
									ExplosionDamageUtil.BED_RADIUS, target));
					if (targetDamage < minTargetDamage.getFloat()) {
						continue;
					}
					float selfDamage = ExplosionDamageUtil.self(centre, ExplosionDamageUtil.BED_RADIUS);
					if (!safe(centre, selfDamage)) {
						continue;
					}
					double score = targetDamage - selfDamage;
					if (score <= bestScore || !bedsExplode(head)) {
						continue;
					}
					for (Direction facing : HORIZONTAL) {
						BlockPos foot = head.relative(facing.getOpposite());
						if (!mc().level.getBlockState(foot).canBeReplaced() || !clearOfEntities(foot)) {
							continue;
						}
						BlockHitResult click = supportClick(player, foot, head);
						if (click == null) {
							continue;
						}
						best = new Candidate(head.immutable(), facing, click, targetDamage, selfDamage);
						bestScore = score;
						break;
					}
				}
			}
		}
		return best;
	}

	/**
	 * A solid face to click so the foot lands on {@code foot}. Below first, since a floor is
	 * the face least likely to be a container that would open instead.
	 */
	private BlockHitResult supportClick(LocalPlayer player, BlockPos foot, BlockPos head) {
		for (Direction direction : Direction.values()) {
			BlockPos neighbour = foot.relative(direction);
			if (neighbour.equals(head) || mc().level.getBlockState(neighbour).canBeReplaced()) {
				continue;
			}
			Direction face = direction.getOpposite();
			Vec3 point = Vec3.atCenterOf(neighbour)
					.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
			if (player.getEyePosition().distanceTo(point) <= placeRange.get()) {
				return new BlockHitResult(point, face, neighbour, false);
			}
		}
		return null;
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

	private boolean safe(Vec3 centre, float selfDamage) {
		if (selfDamage > maxSelfDamage.getFloat()) {
			return false;
		}
		return !antiSuicide.get() || ExplosionDamageUtil.selfSurvivable(centre,
				ExplosionDamageUtil.BED_RADIUS, safetyMargin.getFloat());
	}

	private static int bedSlot(LocalPlayer player) {
		Inventory inventory = player.getInventory();
		if (isBed(inventory.getItem(inventory.getSelectedSlot()))) {
			return inventory.getSelectedSlot();
		}
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			if (isBed(inventory.getItem(slot))) {
				return slot;
			}
		}
		return -1;
	}

	/** A real bed, not any {@code AbstractBedBlock}: other bed-likes follow their own rule. */
	private static boolean isBed(ItemStack stack) {
		return stack.getItem() instanceof BlockItem item && item.getBlock() instanceof BedBlock;
	}

	private static float yawTo(LocalPlayer player, Vec3 point) {
		Vec3 delta = point.subtract(player.getEyePosition());
		return (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90.0f;
	}

	private static float pitchTo(LocalPlayer player, Vec3 point) {
		Vec3 delta = point.subtract(player.getEyePosition());
		return Mth.clamp((float) (-(Mth.atan2(delta.y, delta.horizontalDistance()) * Mth.RAD_TO_DEG)),
				-90.0f, 90.0f);
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
		Candidate shown = active != null ? active : aimed;
		if (shown == null || !renderBest.get()) {
			return;
		}
		int color = bestColor.get();
		int fill = ColorUtil.withAlpha(color, Math.max(24, bestColor.alpha() / 4));
		Render3D.blockBox(shown.head(), color, 2.0f, fill, true);
		Render3D.blockBox(shown.foot(), color, 1.0f, fill, true);
		if (renderDamage.get()) {
			Vec3 centre = Vec3.atCenterOf(shown.head());
			Render3D.blockLabel(String.format("%.1f / -%.1f",
					ExplosionDamageUtil.damage(centre, ExplosionDamageUtil.BED_RADIUS, target),
					ExplosionDamageUtil.self(centre, ExplosionDamageUtil.BED_RADIUS)),
					shown.head().above(), color, 1.0f);
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

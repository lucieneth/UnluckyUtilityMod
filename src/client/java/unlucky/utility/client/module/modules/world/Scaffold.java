package unlucky.utility.client.module.modules.world;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.player.AutoEat;
import unlucky.utility.client.settings.BlockListSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.InventoryActionCoordinator;
import unlucky.utility.client.util.MoveUtil;
import unlucky.utility.client.util.PlacementSolver;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.RotationManager;

/**
 * Builds the floor while the player bridges, towers, or walks down one block at a time.
 *
 * <p>Descent is deliberately not "place below, then push down". A block directly below the
 * player is an obstacle, not a landing. Descend first places an anchor under the current
 * platform, then a lower platform one block out from that anchor. Sneak's vanilla edge lock is
 * released only after the offset platform is present, so the downward move always has a known
 * landing and never drives the player into the block just placed.
 *
 * <p>Placement is vanilla all the way down: {@link PlacementSolver} chooses a legal support
 * click, {@link RotationManager} owns the server rotation, and
 * {@link InventoryActionCoordinator} owns the hotbar for the one tick in which the click is
 * sent. None of those claims is held while merely waiting at an edge.
 */
public class Scaffold extends Module {
	private static final long FADE_MS = 700L;
	private static final double CENTRE_EPSILON = 0.10;
	private static final double DESCEND_NUDGE = 0.10;

	public final ModeSetting mode = add(new ModeSetting("Mode",
			"Smart bridges normally, towers while jump is held, and descends while sneak is held",
			"Smart", "Smart", "Bridge", "Tower", "Descend"));
	public final ModeSetting blockMode = add(new ModeSetting("Blocks",
			"Any full block, or use the list as a whitelist or blacklist", "Any full block",
			"Any full block", "Whitelist", "Blacklist"));
	public final BlockListSetting blocks = add(new BlockListSetting("Block list",
			"Blocks allowed by the selected whitelist/blacklist mode", Set.of()),
			() -> !blockMode.is("Any full block"));
	public final BooleanSetting autoSwitch = add(new BooleanSetting("Auto switch",
			"Select a suitable block from the inventory before placing", true));
	public final BooleanSetting swapBack = add(new BooleanSetting("Swap back",
			"Return to the slot/item held before each placement", false), autoSwitch::get);
	public final BooleanSetting rotate = add(new BooleanSetting("Rotate",
			"Aim the placement through the shared rotation owner", true));
	public final ModeSetting rotation = add(new ModeSetting("Rotation",
			"Silent turns only the server/model; Visible also turns the camera", "Silent",
			"Silent", "Visible"), rotate::get);
	public final NumberSetting rotationSpeed = add(new NumberSetting("Rotation speed",
			"Maximum degrees turned toward a support click per tick", 45, 1, 180, 1), rotate::get);
	public final NumberSetting placeDelay = add(new NumberSetting("Place delay",
			"Quiet ticks after a successful placement", 0, 0, 20, 1));
	public final NumberSetting blocksPerTick = add(new NumberSetting("Blocks per tick",
			"Maximum placement clicks in one tick", 1, 1, 5, 1));
	public final NumberSetting placeRange = add(new NumberSetting("Place range",
			"Maximum distance from the eye to the support click", 4.5, 1, 6, 0.1));
	public final NumberSetting aheadDistance = add(new NumberSetting("Ahead distance",
			"How far ahead of the next feet position Bridge plans", 0.5, 0, 2, 0.1));
	public final BooleanSetting airPlace = add(new BooleanSetting("Air place",
			"Offer a direct target click when no real support face exists; many servers reject it",
			false));
	public final BooleanSetting onlyMoving = add(new BooleanSetting("Only while moving",
			"Bridge only while horizontal movement input is present", true));
	public final BooleanSetting onlyUseHeld = add(new BooleanSetting("Only while use held",
			"Do nothing unless the normal place/use key is held", false));
	public final BooleanSetting swing = add(new BooleanSetting("Swing",
			"Show the normal main-hand swing after a placement", true));
	public final BooleanSetting pauseOnEat = addPauseOnEat();

	public final BooleanSetting safeWalk = add(new BooleanSetting("SafeWalk",
			"Use vanilla edge backoff while bridging so movement waits for the floor", true));
	public final NumberSetting expand = add(new NumberSetting("Expand",
			"Extra bridge blocks planned in the movement direction", 0, 0, 3, 1));
	public final BooleanSetting predictedBelow = add(new BooleanSetting("Place below predicted",
			"Plan below the next feet position instead of only the current one", true));

	public final BooleanSetting jumpTower = add(new BooleanSetting("Jump activates tower",
			"In Smart mode, holding jump selects Tower", true));
	public final BooleanSetting fastTower = add(new BooleanSetting("Fast tower",
			"Apply a controlled upward velocity while a block is known beneath the player", true));
	public final NumberSetting towerSpeed = add(new NumberSetting("Tower speed",
			"Upward velocity used by Fast tower", 0.42, 0.1, 1, 0.01), fastTower::get);
	public final BooleanSetting towerMoving = add(new BooleanSetting("Allow tower while moving",
			"Tower without requiring zero horizontal input", true));
	public final BooleanSetting centreTower = add(new BooleanSetting("Center before tower",
			"Move toward the middle of the supporting block before rising", false));

	public final BooleanSetting sneakDescend = add(new BooleanSetting("Sneak activates descend",
			"In Smart mode, holding sneak at an edge selects Descend", true));
	public final NumberSetting descendSpeed = add(new NumberSetting("Descend speed",
			"Controlled downward velocity toward the known lower platform", 0.20, 0.05, 0.8, 0.01));
	public final BooleanSetting validLower = add(new BooleanSetting("Require valid lower placement",
			"Never start down until the lower anchor and offset platform can be built", true));
	public final BooleanSetting edgeSafeDescend = add(new BooleanSetting("Edge-safe descend",
			"Keep vanilla edge protection until the offset lower platform exists", true));
	public final BooleanSetting pauseNoSupport = add(new BooleanSetting("Pause without support",
			"Stop at the edge when PlacementSolver cannot find a support click", true));

	public final BooleanSetting plannedRender = add(new BooleanSetting("Planned block",
			"Render the next block Scaffold is trying to place", true));
	public final ColorSetting plannedColor = add(new ColorSetting("Planned color",
			"Outline/fill color for the planned block", 0xB05CD6FF), plannedRender::get);
	public final BooleanSetting placedFade = add(new BooleanSetting("Placed fade",
			"Fade recently clicked blocks out", true));
	public final ColorSetting placedColor = add(new ColorSetting("Placed color",
			"Color of the placed-block fade", 0xA055FF88), placedFade::get);
	public final BooleanSetting supportFace = add(new BooleanSetting("Support click face",
			"Draw the face and point the placement packet will click", false));

	private record BlockChoice(ItemStack stack, BlockItem item, int inventorySlot) {
	}

	private ClientLevel level;
	private int delayTicks;
	private BlockPos planned;
	private BlockHitResult plannedHit;
	private final Map<BlockPos, Long> placed = new LinkedHashMap<>();

	/** Descend's two placements and the one-block-out landing. */
	private BlockPos descendAnchor;
	private BlockPos descendPlatform;
	private Direction descendDirection;
	private boolean descendReady;
	private boolean syntheticVertical;

	public Scaffold() {
		super("Scaffold", "Builds a bridge, tower, or safe one-block descent under you",
				Category.WORLD, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		resetState(false);
	}

	@Override
	protected void onDisable() {
		resetState(true);
	}

	@Override
	protected void onPanic() {
		// Ordinary disable is already the urgent path: release the hotbar and stop synthetic Y.
		resetState(true);
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || mc().gameMode == null) {
			resetState(false);
			return;
		}
		if (mc().level != level) {
			level = mc().level;
			resetWork();
		}

		planned = null;
		plannedHit = null;
		syntheticVertical = false;
		drawPlaced();

		if (mc().gui.screen() != null || player.isSpectator() || player.getAbilities().flying
				|| player.isFallFlying() || AutoEat.pauses(pauseOnEat)
				|| player.containerMenu != player.inventoryMenu
				|| (onlyUseHeld.get() && !mc().options.keyUse.isDown())) {
			InventoryActionCoordinator.release(this);
			clearDescend();
			return;
		}

		if (delayTicks > 0) {
			delayTicks--;
		}

		switch (activeMode()) {
			case "Tower" -> tower(player);
			case "Descend" -> descend(player);
			default -> bridge(player);
		}

		drawPlanned();
	}

	private void bridge(LocalPlayer player) {
		clearDescend();
		Vec3 direction = MoveUtil.inputDirection(player);
		if (direction.lengthSqr() < 1.0e-6 && onlyMoving.get()) {
			InventoryActionCoordinator.release(this);
			return;
		}

		Set<BlockPos> targets = new LinkedHashSet<>();
		double baseX = player.getX();
		double baseZ = player.getZ();
		if (predictedBelow.get()) {
			Vec3 velocity = player.getDeltaMovement();
			baseX += velocity.x + direction.x * aheadDistance.get();
			baseZ += velocity.z + direction.z * aheadDistance.get();
		}
		int floorY = Mth.floor(player.getBoundingBox().minY - 0.05);
		for (int extra = 0; extra <= expand.getInt(); extra++) {
			double reach = direction.lengthSqr() < 1.0e-6 ? 0.0 : extra;
			targets.add(new BlockPos(Mth.floor(baseX + direction.x * reach), floorY,
					Mth.floor(baseZ + direction.z * reach)));
		}

		int sent = 0;
		for (BlockPos target : targets) {
			if (!replaceable(target)) {
				continue;
			}
			if (sent >= blocksPerTick.getInt() || delayTicks > 0) {
				if (planned == null) {
					planned = target.immutable();
				}
				break;
			}
			if (place(target)) {
				sent++;
				delayTicks = placeDelay.getInt();
			}
		}
		if (sent == 0) {
			InventoryActionCoordinator.release(this);
		}
	}

	private void tower(LocalPlayer player) {
		clearDescend();
		if (!mc().options.keyJump.isDown()
				|| (!towerMoving.get() && MoveUtil.hasInput(player))) {
			InventoryActionCoordinator.release(this);
			return;
		}
		BlockPos below = blockBelowFeet(player);
		if (centreTower.get() && !centred(player, below)) {
			moveTowardCentre(player, below);
			return;
		}

		boolean supportKnown = fullSupport(below);
		boolean placedNow = false;
		if (!supportKnown && replaceable(below) && delayTicks <= 0) {
			placedNow = place(below);
			if (placedNow) {
				delayTicks = placeDelay.getInt();
			}
		} else if (!supportKnown) {
			planned = below.immutable();
		}

		if (fastTower.get() && (supportKnown || placedNow)) {
			Vec3 velocity = player.getDeltaMovement();
			player.setDeltaMovement(velocity.x, Math.max(velocity.y, towerSpeed.get()), velocity.z);
			syntheticVertical = true;
		}
		if (!placedNow) {
			InventoryActionCoordinator.release(this);
		}
	}

	private void descend(LocalPlayer player) {
		if (!mc().options.keyShift.isDown()) {
			clearDescend();
			InventoryActionCoordinator.release(this);
			return;
		}
		Vec3 movement = MoveUtil.inputDirection(player);
		if (movement.lengthSqr() < 1.0e-6) {
			InventoryActionCoordinator.release(this);
			return;
		}

		if (descendPlatform != null && landedOn(player, descendPlatform)) {
			clearDescend();
			return; // one level per tick-cycle; plan the next step from stable ground
		}
		if (descendPlatform == null) {
			if (!player.onGround()) {
				return;
			}
			BlockPos current = blockBelowFeet(player);
			Direction direction = dominantDirection(movement);
			// Descend is a stair down at an edge, not a way to tunnel through a same-level floor.
			if (edgeSafeDescend.get() && fullSupport(current.relative(direction))) {
				return;
			}
			descendDirection = direction;
			descendAnchor = current.below().immutable();
			descendPlatform = descendAnchor.relative(direction).immutable();
		}

		if (fullSupport(descendPlatform)) {
			descendReady = true;
			applyDescend(player);
			InventoryActionCoordinator.release(this);
			return;
		}

		descendReady = false;
		BlockPos next = fullSupport(descendAnchor) ? descendPlatform : descendAnchor;
		if (!replaceable(next)) {
			if (validLower.get() || pauseNoSupport.get()) {
				planned = next.immutable();
			}
			return;
		}
		if (delayTicks <= 0 && place(next)) {
			delayTicks = placeDelay.getInt();
		} else if (planned == null) {
			planned = next.immutable();
		}
	}

	/** Controlled down-and-out movement toward the already-existing lower platform. */
	private void applyDescend(LocalPlayer player) {
		Vec3 velocity = player.getDeltaMovement();
		double vx = velocity.x;
		double vz = velocity.z;
		double along = vx * descendDirection.getStepX() + vz * descendDirection.getStepZ();
		if (along < DESCEND_NUDGE) {
			vx += descendDirection.getStepX() * (DESCEND_NUDGE - along);
			vz += descendDirection.getStepZ() * (DESCEND_NUDGE - along);
		}
		player.setDeltaMovement(vx, Math.min(velocity.y, -descendSpeed.get()), vz);
		syntheticVertical = true;
	}

	/**
	 * Called from the existing Player mixin's one edge-policy hook.
	 *
	 * @return -1 for vanilla, 0 to allow walking off this edge, 1 to force vanilla SafeWalk
	 */
	public int groundSurfaceOverride(LocalPlayer player) {
		if (!isEnabled() || player != mc().player || mc().gui.screen() != null
				|| player.containerMenu != player.inventoryMenu || AutoEat.pauses(pauseOnEat)
				|| (onlyUseHeld.get() && !mc().options.keyUse.isDown())) {
			return -1;
		}
		String active = activeMode();
		if (active.equals("Descend") && mc().options.keyShift.isDown()) {
			return descendReady ? 0 : (edgeSafeDescend.get() || pauseNoSupport.get() ? 1 : -1);
		}
		if (active.equals("Bridge") && safeWalk.get()) {
			return 1;
		}
		return -1;
	}

	private String activeMode() {
		if (!mode.is("Smart")) {
			return mode.get();
		}
		if (jumpTower.get() && mc().options.keyJump.isDown()) {
			return "Tower";
		}
		if (sneakDescend.get() && mc().options.keyShift.isDown()) {
			return "Descend";
		}
		return "Bridge";
	}

	/** Solves, aims, equips and sends one ordinary block-use click. */
	private boolean place(BlockPos target) {
		planned = target.immutable();
		BlockChoice choice = chooseBlock(target);
		if (choice == null) {
			return false;
		}
		BlockState wanted = choice.item().getBlock().defaultBlockState();
		PlacementSolver.Solution solution = PlacementSolver.solve(target, wanted, choice.stack(),
				new PlacementSolver.Options(placeRange.get(), airPlace.get(), false, true, rotate.get()));
		if (solution == null) {
			return false;
		}
		plannedHit = solution.hit();
		if (!aim(solution)) {
			return false;
		}
		if (!InventoryActionCoordinator.acquire(this, InventoryActionCoordinator.PRIORITY_PLACEMENT)
				|| !InventoryActionCoordinator.owns(this)) {
			return false;
		}

		int swappedMainSlot = equip(choice);
		if (swappedMainSlot == Integer.MIN_VALUE || !InventoryActionCoordinator.owns(this)) {
			InventoryActionCoordinator.release(this);
			return false;
		}
		mc().gameMode.useItemOn(mc().player, InteractionHand.MAIN_HAND, solution.hit());
		if (swing.get()) {
			mc().player.swing(InteractionHand.MAIN_HAND);
		}
		placed.put(target.immutable(), System.currentTimeMillis());

		if (swapBack.get() && swappedMainSlot >= 0 && InventoryActionCoordinator.owns(this)) {
			InventoryActionCoordinator.swapToHotbar(this, mc().player.inventoryMenu,
					swappedMainSlot, mc().player.getInventory().getSelectedSlot());
		}
		if (!swapBack.get()) {
			InventoryActionCoordinator.keepHotbar(this);
		}
		InventoryActionCoordinator.release(this);
		return true;
	}

	private boolean aim(PlacementSolver.Solution solution) {
		if (!rotate.get()) {
			return true;
		}
		Vec3 point = PlacementSolver.lookPoint(mc().player.getEyePosition(), solution.yaw(),
				solution.pitch(), 4.0);
		if (rotation.is("Silent")) {
			return RotationManager.face(point, rotationSpeed.getFloat(),
					RotationManager.PRIORITY_PLACEMENT);
		}

		float currentYaw = mc().player.getYRot();
		float currentPitch = mc().player.getXRot();
		float nextYaw = approach(currentYaw, solution.yaw(), rotationSpeed.getFloat());
		float nextPitch = approach(currentPitch, solution.pitch(), rotationSpeed.getFloat());
		if (!RotationManager.rotateIfAllowed(nextYaw, nextPitch,
				RotationManager.PRIORITY_PLACEMENT)) {
			return false;
		}
		mc().player.setYRot(nextYaw);
		mc().player.setXRot(Mth.clamp(nextPitch, -90.0f, 90.0f));
		return Math.abs(Mth.wrapDegrees(solution.yaw() - nextYaw)) < 1.0f
				&& Math.abs(solution.pitch() - nextPitch) < 1.0f;
	}

	private static float approach(float from, float to, float speed) {
		return from + Mth.clamp(Mth.wrapDegrees(to - from), -speed, speed);
	}

	/**
	 * Gets the chosen stack into the hand.
	 *
	 * @return main-inventory menu slot to swap back, -1 for a hotbar selection, or MIN_VALUE
	 *         on failure
	 */
	private int equip(BlockChoice choice) {
		Inventory inventory = mc().player.getInventory();
		int selected = inventory.getSelectedSlot();
		if (choice.inventorySlot() == selected) {
			return -1;
		}
		if (!autoSwitch.get()) {
			return Integer.MIN_VALUE;
		}
		if (choice.inventorySlot() < Inventory.SELECTION_SIZE) {
			return InventoryActionCoordinator.selectHotbar(this, choice.inventorySlot())
					? -1 : Integer.MIN_VALUE;
		}
		return InventoryActionCoordinator.swapToHotbar(this, mc().player.inventoryMenu,
				choice.inventorySlot(), selected) ? choice.inventorySlot() : Integer.MIN_VALUE;
	}

	private BlockChoice chooseBlock(BlockPos target) {
		Inventory inventory = mc().player.getInventory();
		int selected = inventory.getSelectedSlot();
		BlockChoice held = choice(inventory.getItem(selected), selected, target);
		if (held != null) {
			return held;
		}
		if (!autoSwitch.get()) {
			return null;
		}
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			if (slot == selected) {
				continue;
			}
			BlockChoice choice = choice(inventory.getItem(slot), slot, target);
			if (choice != null) {
				return choice;
			}
		}
		for (int slot = Inventory.SELECTION_SIZE; slot < Inventory.INVENTORY_SIZE; slot++) {
			BlockChoice choice = choice(inventory.getItem(slot), slot, target);
			if (choice != null) {
				return choice;
			}
		}
		return null;
	}

	private BlockChoice choice(ItemStack stack, int slot, BlockPos target) {
		if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) {
			return null;
		}
		BlockState state = item.getBlock().defaultBlockState();
		boolean listed = blocks.contains(item.getBlock());
		if (blockMode.is("Whitelist") ? !listed : blockMode.is("Blacklist") && listed) {
			return null;
		}
		// A scaffold block is a floor. Falling blocks and block entities may look full in a
		// picker but can disappear or open a menu, so they are never automatic material.
		if (item.getBlock() instanceof FallingBlock || state.hasBlockEntity()
				|| !state.isCollisionShapeFullBlock(mc().level, target)
				|| !state.canSurvive(mc().level, target)) {
			return null;
		}
		return new BlockChoice(stack, item, slot);
	}

	private boolean replaceable(BlockPos pos) {
		return mc().level.getBlockState(pos).canBeReplaced();
	}

	private boolean fullSupport(BlockPos pos) {
		BlockState state = mc().level.getBlockState(pos);
		return !state.canBeReplaced() && state.isCollisionShapeFullBlock(mc().level, pos);
	}

	private static BlockPos blockBelowFeet(LocalPlayer player) {
		return new BlockPos(Mth.floor(player.getX()), Mth.floor(player.getBoundingBox().minY - 0.05),
				Mth.floor(player.getZ()));
	}

	private static Direction dominantDirection(Vec3 direction) {
		if (Math.abs(direction.x) > Math.abs(direction.z)) {
			return direction.x > 0 ? Direction.EAST : Direction.WEST;
		}
		return direction.z > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	private static boolean landedOn(LocalPlayer player, BlockPos platform) {
		return player.onGround() && blockBelowFeet(player).equals(platform);
	}

	private static boolean centred(LocalPlayer player, BlockPos support) {
		return Math.abs(player.getX() - (support.getX() + 0.5)) <= CENTRE_EPSILON
				&& Math.abs(player.getZ() - (support.getZ() + 0.5)) <= CENTRE_EPSILON;
	}

	private static void moveTowardCentre(LocalPlayer player, BlockPos support) {
		Vec3 velocity = player.getDeltaMovement();
		double dx = Mth.clamp(support.getX() + 0.5 - player.getX(), -0.12, 0.12);
		double dz = Mth.clamp(support.getZ() + 0.5 - player.getZ(), -0.12, 0.12);
		player.setDeltaMovement(dx, velocity.y, dz);
	}

	private void drawPlanned() {
		if (planned == null) {
			return;
		}
		if (plannedRender.get()) {
			int color = plannedColor.get();
			Render3D.blockBox(planned, color, 2.0f,
					ColorUtil.withAlpha(color, Math.max(24, plannedColor.alpha() / 4)), true);
		}
		if (supportFace.get() && plannedHit != null) {
			Vec3 hit = plannedHit.getLocation();
			Direction face = plannedHit.getDirection();
			Render3D.line(hit.subtract(face.getStepX() * 0.16, face.getStepY() * 0.16,
					face.getStepZ() * 0.16), hit.add(face.getStepX() * 0.16,
					face.getStepY() * 0.16, face.getStepZ() * 0.16), plannedColor.get(), 2.0f, true);
		}
	}

	private void drawPlaced() {
		long now = System.currentTimeMillis();
		placed.entrySet().removeIf(entry -> now - entry.getValue() >= FADE_MS);
		if (!placedFade.get()) {
			return;
		}
		for (Map.Entry<BlockPos, Long> entry : placed.entrySet()) {
			double life = 1.0 - (double) (now - entry.getValue()) / FADE_MS;
			int base = placedColor.get();
			int alpha = (int) (placedColor.alpha() * Mth.clamp(life, 0.0, 1.0));
			int color = ColorUtil.withAlpha(base, alpha);
			Render3D.blockBox(entry.getKey(), color, 1.5f,
					ColorUtil.withAlpha(base, Math.max(0, alpha / 4)), true);
		}
	}

	private void resetState(boolean stopMovement) {
		InventoryActionCoordinator.release(this);
		if (stopMovement && syntheticVertical && mc().player != null) {
			Vec3 velocity = mc().player.getDeltaMovement();
			mc().player.setDeltaMovement(velocity.x, 0.0, velocity.z);
		}
		level = mc().level;
		resetWork();
	}

	private void resetWork() {
		delayTicks = 0;
		planned = null;
		plannedHit = null;
		placed.clear();
		clearDescend();
		syntheticVertical = false;
	}

	private void clearDescend() {
		descendAnchor = null;
		descendPlatform = null;
		descendDirection = null;
		descendReady = false;
	}
}

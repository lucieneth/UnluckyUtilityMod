package unlucky.utility.client.module.modules.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
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
import unlucky.utility.client.util.CombatUtil;
import unlucky.utility.client.util.ExplosionDamageUtil;
import unlucky.utility.client.util.InventoryActionCoordinator;
import unlucky.utility.client.util.PlacementSolver;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.RotationManager;

/**
 * Walls the four cardinal squares at foot level so a crystal cannot be placed against you.
 *
 * <p>The ring is the whole idea: an end crystal needs a block face to sit on and a clear
 * two-block space above it, so denying the four squares your hitbox actually touches is worth
 * far more than denying the diagonals, which cannot reach you at full damage anyway. That is
 * why this places four blocks and not eight.
 *
 * <p>Everything here is ordinary vanilla interaction — {@link PlacementSolver} finds a legal
 * support click, {@link RotationManager} owns the server rotation and
 * {@link InventoryActionCoordinator} owns the hotbar for the tick a click is sent. There is no
 * reach extension and no synthetic placement: a ring square with no adjacent face to click
 * against is simply reported as unplaceable rather than air-placed, unless the player asks.
 *
 * <p>The safety rules cut the other way from most combat modules. Breaking a crystal that is
 * blocking a ring square is the module <em>damaging itself on purpose</em>, so it happens only
 * when {@link ExplosionDamageUtil} says the blast leaves the player alive with the configured
 * margin — and with anti-suicide on, never at all if the number says otherwise, whatever the
 * max-damage slider says.
 */
public class Surround extends Module {
	private static final long FADE_MS = 700L;
	private static final double CENTRE_EPSILON = 0.12;
	private static final double CENTRE_STEP = 0.14;

	private static final Direction[] RING = {
			Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
	};

	public final BlockListSetting blocks = add(new BlockListSetting("Blocks",
			"Blocks Surround is allowed to use, in inventory order — right-click to pick",
			Set.of("minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:respawn_anchor",
					"minecraft:ender_chest")));
	public final NumberSetting blocksPerTick = add(new NumberSetting("Blocks per tick",
			"Maximum placement clicks in one tick", 2, 1, 8, 1));
	public final NumberSetting placeDelay = add(new NumberSetting("Delay",
			"Quiet ticks after a successful placement", 0, 0, 20, 1));
	public final ModeSetting centre = add(new ModeSetting("Center",
			"Move to the middle of your block so the ring sits evenly around you",
			"On activate", "Never", "On activate", "If incomplete", "Always"));
	public final BooleanSetting floorSupport = add(new BooleanSetting("Floor/support",
			"Place the block underneath a ring square when it has no face to click against", true));
	public final BooleanSetting doubleHeight = add(new BooleanSetting("Double height",
			"Also wall the ring at head level", false));
	public final BooleanSetting onlyGround = add(new BooleanSetting("Only on ground",
			"Do nothing while airborne, where the ring would be built at the wrong height", true));
	public final BooleanSetting airPlace = add(new BooleanSetting("Air place",
			"Offer a direct target click when no real support face exists; many servers reject it",
			false));
	public final BooleanSetting rotate = add(new BooleanSetting("Rotate",
			"Aim each placement through the shared rotation owner", true));
	public final NumberSetting rotationSpeed = add(new NumberSetting("Rotation speed",
			"Maximum degrees turned toward a support click per tick", 45, 1, 180, 1), rotate::get);
	public final BooleanSetting swing = add(new BooleanSetting("Swing",
			"Show the normal main-hand swing after a placement", true));
	public final BooleanSetting autoSwitch = add(new BooleanSetting("Auto switch",
			"Select a listed block from the inventory before placing", true));
	public final BooleanSetting swapBack = add(new BooleanSetting("Swap back",
			"Return to the slot held before each placement", true), autoSwitch::get);
	public final BooleanSetting pauseOnEat = addPauseOnEat();

	public final BooleanSetting breakCrystals = add(new BooleanSetting("Break blocking crystals",
			"Attack an end crystal that is sitting in a ring square, if the blast is survivable",
			true));
	public final NumberSetting maxSelfDamage = add(new NumberSetting("Max self damage",
			"Never break a crystal predicted to hit you harder than this", 6, 0, 20, 0.5),
			breakCrystals::get);
	public final BooleanSetting antiSuicide = add(new BooleanSetting("Anti-suicide",
			"Refuse any break the damage model says you would not survive, whatever the slider says",
			true), breakCrystals::get);
	public final NumberSetting safetyMargin = add(new NumberSetting("Safety margin",
			"Health that must remain after a break for it to count as survivable", 1, 0, 10, 0.5),
			() -> breakCrystals.get() && antiSuicide.get());

	public final BooleanSetting disableOnY = add(new BooleanSetting("Disable on Y change",
			"Switch off when you leave the level the ring was built for", true));
	public final BooleanSetting disableComplete = add(new BooleanSetting("Disable when complete",
			"Switch off as soon as the ring has no missing squares left", false));
	public final BooleanSetting disableOnDeath = add(new BooleanSetting("Disable on death",
			"Switch off when you die", true));

	public final BooleanSetting renderPlanned = add(new BooleanSetting("Render planned blocks",
			"Outline the ring squares still waiting on a block", true));
	public final ColorSetting plannedColor = add(new ColorSetting("Planned color",
			"Outline/fill color for a planned ring square", 0xB0FF5C5C), renderPlanned::get);
	public final BooleanSetting placedFade = add(new BooleanSetting("Render placed fade",
			"Fade recently placed ring blocks out", true));
	public final ColorSetting placedColor = add(new ColorSetting("Placed color",
			"Color of the placed-block fade", 0xA055FF88), placedFade::get);

	private record BlockChoice(ItemStack stack, BlockItem item, int inventorySlot) {
	}

	private int delayTicks;
	private Integer anchorY;
	private boolean centredOnce;
	private final List<BlockPos> planned = new ArrayList<>(8);
	private final Map<BlockPos, Long> placed = new LinkedHashMap<>();

	public Surround() {
		super("Surround", "Walls the four squares around your feet against crystals",
				Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		delayTicks = 0;
		centredOnce = false;
		planned.clear();
		placed.clear();
		anchorY = mc().player == null ? null : Mth.floor(mc().player.getBoundingBox().minY);
	}

	@Override
	protected void onDisable() {
		InventoryActionCoordinator.release(this);
		planned.clear();
		placed.clear();
		anchorY = null;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || mc().gameMode == null) {
			InventoryActionCoordinator.release(this);
			planned.clear();
			return;
		}

		planned.clear();
		drawPlaced();

		if (disableOnDeath.get() && !player.isAlive()) {
			setEnabled(false);
			return;
		}
		if (mc().gui.screen() != null || player.isSpectator() || AutoEat.pauses(pauseOnEat)
				|| player.containerMenu != player.inventoryMenu
				|| (onlyGround.get() && !player.onGround())) {
			InventoryActionCoordinator.release(this);
			return;
		}

		int feetY = Mth.floor(player.getBoundingBox().minY);
		if (anchorY == null) {
			anchorY = feetY;
		} else if (disableOnY.get() && feetY != anchorY) {
			setEnabled(false);
			return;
		}

		if (delayTicks > 0) {
			delayTicks--;
		}

		List<BlockPos> targets = ringTargets(player, feetY);
		boolean complete = targets.isEmpty();
		if (wantsCentring(complete) && centreOn(player)) {
			// Still drifting toward the middle: placing now would build the ring off-axis.
			planned.addAll(targets);
			drawPlanned();
			return;
		}
		centredOnce = true;

		if (complete) {
			InventoryActionCoordinator.release(this);
			if (disableComplete.get()) {
				setEnabled(false);
			}
			return;
		}

		int sent = 0;
		for (BlockPos target : targets) {
			if (sent >= blocksPerTick.getInt() || delayTicks > 0) {
				planned.add(target);
				continue;
			}
			if (breakCrystals.get() && breakBlocking(target)) {
				sent++;
				continue;
			}
			if (place(target)) {
				sent++;
				delayTicks = placeDelay.getInt();
			} else {
				planned.add(target);
			}
		}
		if (sent == 0) {
			InventoryActionCoordinator.release(this);
		}
		drawPlanned();
	}

	/**
	 * The ring squares that still need a block, nearest first.
	 *
	 * <p>A square whose support is missing contributes that support instead of itself when
	 * Floor/support is on: a ring block hanging over air has nothing to click against, so the
	 * block under it has to exist before the ring block can. Ordering by distance keeps the
	 * per-tick budget spent on the squares nearest the player, which are the ones a crystal
	 * would use first.
	 */
	private List<BlockPos> ringTargets(LocalPlayer player, int feetY) {
		BlockPos feet = new BlockPos(Mth.floor(player.getX()), feetY, Mth.floor(player.getZ()));
		List<BlockPos> targets = new ArrayList<>(8);
		for (Direction direction : RING) {
			collect(targets, feet.relative(direction));
			if (doubleHeight.get()) {
				collect(targets, feet.relative(direction).above());
			}
		}
		Vec3 eye = player.getEyePosition();
		targets.sort((a, b) -> Double.compare(
				eye.distanceToSqr(Vec3.atCenterOf(a)), eye.distanceToSqr(Vec3.atCenterOf(b))));
		return targets;
	}

	private void collect(List<BlockPos> targets, BlockPos pos) {
		if (!needsBlock(pos)) {
			return;
		}
		if (floorSupport.get() && needsBlock(pos.below()) && !hasAdjacentFace(pos)) {
			BlockPos support = pos.below().immutable();
			if (!targets.contains(support)) {
				targets.add(support);
			}
		}
		targets.add(pos.immutable());
	}

	/** Whether a placement click here would have any existing face to click against. */
	private boolean hasAdjacentFace(BlockPos pos) {
		for (Direction direction : Direction.values()) {
			if (!mc().level.getBlockState(pos.relative(direction)).canBeReplaced()) {
				return true;
			}
		}
		return false;
	}

	private boolean needsBlock(BlockPos pos) {
		return mc().level.getBlockState(pos).canBeReplaced();
	}

	private boolean wantsCentring(boolean complete) {
		if (centre.is("Never")) {
			return false;
		}
		if (centre.is("Always")) {
			return true;
		}
		if (centre.is("If incomplete")) {
			return !complete;
		}
		return !centredOnce;
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

	/**
	 * Attacks a crystal occupying {@code target}, when the damage model allows it.
	 *
	 * <p>Deliberately conservative about what "occupying" means: only a crystal whose box
	 * actually overlaps the square being placed into blocks that placement, and only that
	 * crystal is worth the blast. Anti-suicide is checked against the survival margin rather
	 * than the damage slider, so a low-health player cannot slide the max-damage setting up
	 * and kill themselves with it.
	 */
	private boolean breakBlocking(BlockPos target) {
		AABB square = new AABB(target);
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (!(entity instanceof EndCrystal crystal) || !crystal.getBoundingBox().intersects(square)) {
				continue;
			}
			Vec3 centre = crystal.position();
			if (ExplosionDamageUtil.self(centre, ExplosionDamageUtil.CRYSTAL_RADIUS)
					> maxSelfDamage.getFloat()) {
				return false;
			}
			if (antiSuicide.get() && !ExplosionDamageUtil.selfSurvivable(centre,
					ExplosionDamageUtil.CRYSTAL_RADIUS, safetyMargin.getFloat())) {
				return false;
			}
			CombatUtil.attack(crystal);
			return true;
		}
		return false;
	}

	/** Solves, aims, equips and sends one ordinary block-use click. */
	private boolean place(BlockPos target) {
		if (!emptyOfEntities(target)) {
			return false;
		}
		BlockChoice choice = chooseBlock(target);
		if (choice == null) {
			return false;
		}
		BlockState wanted = choice.item().getBlock().defaultBlockState();
		PlacementSolver.Solution solution = PlacementSolver.solve(target, wanted, choice.stack(),
				new PlacementSolver.Options(mc().player.blockInteractionRange(), airPlace.get(),
						false, true, rotate.get()));
		if (solution == null || !aim(solution)) {
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

	/**
	 * Whether a block may legally appear here.
	 *
	 * <p>The player's own box is the case that matters: the ring is built at foot level and a
	 * player standing near an edge overlaps the neighbouring square, so an unchecked placement
	 * would be rejected by the server and retried every tick for as long as they stood there.
	 */
	private boolean emptyOfEntities(BlockPos pos) {
		AABB box = new AABB(pos);
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (entity instanceof EndCrystal) {
				continue; // handled by breakBlocking, which knows whether the blast is survivable
			}
			if (entity.isAlive() && !entity.isSpectator() && entity.getBoundingBox().intersects(box)) {
				return false;
			}
		}
		return true;
	}

	private boolean aim(PlacementSolver.Solution solution) {
		if (!rotate.get()) {
			return true;
		}
		Vec3 point = PlacementSolver.lookPoint(mc().player.getEyePosition(), solution.yaw(),
				solution.pitch(), 4.0);
		return RotationManager.face(point, rotationSpeed.getFloat(),
				RotationManager.PRIORITY_PLACEMENT);
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
		if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)
				|| !blocks.contains(item.getBlock())) {
			return null;
		}
		BlockState state = item.getBlock().defaultBlockState();
		// A ring block has to be a wall the moment it lands: gravity blocks fall out of the
		// ring and non-full shapes leave the crystal space they were meant to deny.
		if (item.getBlock() instanceof FallingBlock
				|| !state.isCollisionShapeFullBlock(mc().level, target)
				|| !state.canSurvive(mc().level, target)) {
			return null;
		}
		return new BlockChoice(stack, item, slot);
	}

	private void drawPlanned() {
		if (!renderPlanned.get()) {
			return;
		}
		int color = plannedColor.get();
		for (BlockPos pos : planned) {
			Render3D.blockBox(pos, color, 2.0f,
					ColorUtil.withAlpha(color, Math.max(24, plannedColor.alpha() / 4)), true);
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
			Render3D.blockBox(entry.getKey(), ColorUtil.withAlpha(base, alpha), 1.5f,
					ColorUtil.withAlpha(base, Math.max(0, alpha / 4)), true);
		}
	}
}

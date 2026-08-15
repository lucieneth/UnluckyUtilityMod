package unlucky.utility.client.module.modules.combat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
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
import unlucky.utility.client.util.CombatUtil;
import unlucky.utility.client.util.ExplosionDamageUtil;
import unlucky.utility.client.util.InventoryActionCoordinator;
import unlucky.utility.client.util.PlacementExecutor;
import unlucky.utility.client.util.PlacementSolver;
import unlucky.utility.client.util.RotationManager;

/**
 * Walls the four cardinal squares at foot level so a crystal cannot be placed against you.
 *
 * <p>The ring is the whole idea: an end crystal needs a block face to sit on and a clear
 * two-block space above it, so denying the four squares your hitbox actually touches is worth
 * far more than denying the diagonals, which cannot reach you at full damage anyway. That is
 * why this places four blocks and not eight.
 *
 * <p>Everything here is ordinary vanilla interaction, and none of it is this module's own:
 * {@link PlacementExecutor} runs the click, {@link PlacementSolver} under it finds a legal
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

	/**
	 * The shared placement flow — solve, aim, lease, equip, click, swap back, release.
	 *
	 * <p>This module and Scaffold were the two working implementations of it, which is why the
	 * extraction started from them; five more were going to need the same steps in the same
	 * order with the same unwinding on each failure.
	 */
	private final PlacementExecutor executor = new PlacementExecutor(this);

	private Integer anchorY;
	private boolean centredOnce;

	public Surround() {
		super("Surround", "Walls the four squares around your feet against crystals",
				Category.COMBAT, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onEnable() {
		centredOnce = false;
		executor.reset();
		anchorY = mc().player == null ? null : Mth.floor(mc().player.getBoundingBox().minY);
	}

	@Override
	protected void onDisable() {
		executor.reset();
		anchorY = null;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		// Every tick, drawn or not: the fade map has to be pruned regardless.
		executor.renderPlaced(placedColor.get(), placedFade.get());

		if (!executor.beginTick(placementOptions())) {
			return;
		}
		if (disableOnDeath.get() && !player.isAlive()) {
			setEnabled(false);
			return;
		}
		if (mc().gui.screen() != null || player.containerMenu != player.inventoryMenu
				|| (onlyGround.get() && !player.onGround())) {
			executor.release();
			return;
		}

		int feetY = Mth.floor(player.getBoundingBox().minY);
		if (anchorY == null) {
			anchorY = feetY;
		} else if (disableOnY.get() && feetY != anchorY) {
			setEnabled(false);
			return;
		}

		List<BlockPos> targets = ringTargets(player, feetY);
		boolean complete = targets.isEmpty();
		if (wantsCentring(complete) && centreOn(player)) {
			// Still drifting toward the middle: placing now would build the ring off-axis.
			targets.forEach(executor::plan);
			drawPlanned();
			return;
		}
		centredOnce = true;

		if (complete) {
			executor.release();
			if (disableComplete.get()) {
				setEnabled(false);
			}
			return;
		}

		boolean acted = false;
		for (BlockPos target : targets) {
			if (!emptyOfEntities(target)) {
				// Nothing may be placed into an occupied square, and the player's own box is
				// the case that matters: standing near an edge overlaps a neighbouring one, and
				// an unchecked click there is rejected by the server and retried every tick.
				executor.plan(target);
				continue;
			}
			if (executor.canAct() && breakCrystals.get() && breakBlocking(target)) {
				// Charged against the budget: it is the action taken *instead* of placing here.
				executor.spend();
				acted = true;
				continue;
			}
			acted |= executor.place(target);
		}
		if (!acted) {
			executor.release();
		}
		drawPlanned();
	}

	/**
	 * This tick's placement policy. A ring block has to be a wall the moment it lands: gravity
	 * blocks fall out of the ring and non-full shapes leave the crystal space they were meant
	 * to deny.
	 */
	private PlacementExecutor.Options placementOptions() {
		return new PlacementExecutor.Options(
				(item, target) -> blocks.contains(item.getBlock())
						&& !(item.getBlock() instanceof FallingBlock)
						&& item.getBlock().defaultBlockState().isCollisionShapeFullBlock(mc().level, target)
						&& item.getBlock().defaultBlockState().canSurvive(mc().level, target),
				autoSwitch.get(), swapBack.get(),
				rotate.get() ? PlacementExecutor.Rotate.SILENT : PlacementExecutor.Rotate.OFF,
				rotationSpeed.getFloat(),
				mc().player == null ? 0.0 : mc().player.blockInteractionRange(),
				airPlace.get(), false,
				swing.get() ? PlacementExecutor.Swing.CLIENT : PlacementExecutor.Swing.NONE,
				blocksPerTick.getInt(), placeDelay.getInt(), 0, pauseOnEat.get());
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

	private void drawPlanned() {
		executor.renderPlanned(plannedColor.get(), renderPlanned.get(), false);
	}
}

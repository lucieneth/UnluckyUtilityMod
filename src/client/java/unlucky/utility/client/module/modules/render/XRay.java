package unlucky.utility.client.module.modules.render;

import java.util.Set;

import net.minecraft.world.level.block.state.BlockState;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BlockListSetting;
import unlucky.utility.client.settings.BooleanSetting;

/**
 * Classic XRay: every block not on the list simply isn't rendered, and section
 * occlusion is opened up so you can see straight through the world. Chunks
 * rebuild on toggle and whenever the block list changes.
 */
public class XRay extends Module {
	public static final Set<String> PRESET_ORES = Set.of(
			"minecraft:coal_ore", "minecraft:deepslate_coal_ore",
			"minecraft:iron_ore", "minecraft:deepslate_iron_ore",
			"minecraft:copper_ore", "minecraft:deepslate_copper_ore",
			"minecraft:gold_ore", "minecraft:deepslate_gold_ore", "minecraft:nether_gold_ore",
			"minecraft:redstone_ore", "minecraft:deepslate_redstone_ore",
			"minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
			"minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
			"minecraft:emerald_ore", "minecraft:deepslate_emerald_ore",
			"minecraft:nether_quartz_ore", "minecraft:ancient_debris");

	public static final Set<String> PRESET_STORAGE = Set.of(
			"minecraft:chest", "minecraft:trapped_chest", "minecraft:ender_chest",
			"minecraft:barrel", "minecraft:hopper", "minecraft:furnace",
			"minecraft:blast_furnace", "minecraft:smoker", "minecraft:dispenser",
			"minecraft:dropper", "minecraft:shulker_box");

	public static final Set<String> PRESET_VALUABLES = Set.of(
			"minecraft:diamond_block", "minecraft:emerald_block", "minecraft:gold_block",
			"minecraft:iron_block", "minecraft:netherite_block", "minecraft:ancient_debris",
			"minecraft:spawner", "minecraft:enchanting_table", "minecraft:beacon",
			"minecraft:shulker_box", "minecraft:amethyst_cluster", "minecraft:budding_amethyst");

	public final BlockListSetting blocks = add(new BlockListSetting("Blocks", "Blocks that stay visible", PRESET_ORES));
	public final BooleanSetting fluids = add(new BooleanSetting("Fluids", "Keep water and lava visible", true));
	public final BooleanSetting fullbright = add(new BooleanSetting("Fullbright", "Listed blocks at flat full brightness, no shading", false));
	public final unlucky.utility.client.settings.NumberSetting distance = add(new unlucky.utility.client.settings.NumberSetting(
			"Distance", "XRay radius in chunks; sections beyond render normally", 8, 2, 32, 1));

	// chunk compilation runs on worker threads thousands of times per section,
	// so the hot path reads plain volatile snapshots instead of settings
	private static volatile boolean active;
	private static volatile boolean showFluids = true;
	private static volatile boolean fullbrightOn;
	private static volatile int rangeBlocks = 8 * 16;
	private static volatile Set<net.minecraft.world.level.block.Block> visibleBlocks = Set.of();
	// set per compiling section (worker thread): is this section inside range?
	private static final ThreadLocal<Boolean> SECTION_IN_RANGE = ThreadLocal.withInitial(() -> false);
	// camera position, sampled on the main thread — section compilation runs on
	// worker threads and must never touch live render state directly
	private static volatile double camX, camY, camZ;

	public XRay() {
		super("XRay", "See only the blocks that matter", Category.RENDER);
	}

	/** Fast path for the chunk compiler (worker threads). */
	public static boolean hides(BlockState state) {
		return active && SECTION_IN_RANGE.get() && !visibleBlocks.contains(state.getBlock());
	}

	public static boolean hidesFluids() {
		return active && SECTION_IN_RANGE.get() && !showFluids;
	}

	public static boolean active() {
		return active && SECTION_IN_RANGE.get();
	}

	/**
	 * Just the module switch, no range gate. For hooks that run outside the
	 * vanilla section compiler (Sodium's meshing threads never call
	 * {@link #beginSection}, so the ThreadLocal is permanently false there —
	 * that gate silently disabled the whole first Sodium integration).
	 */
	public static boolean enabled() {
		return active;
	}

	/** Sodium path: per-block equivalents of active()/hides() — the range test
	 *  runs against the block position instead of the per-section ThreadLocal. */
	public static boolean activeAt(net.minecraft.core.BlockPos pos) {
		return active && inRange(pos);
	}

	public static boolean hidesAt(BlockState state, net.minecraft.core.BlockPos pos) {
		return active && inRange(pos) && !visibleBlocks.contains(state.getBlock());
	}

	private static boolean inRange(net.minecraft.core.BlockPos pos) {
		return inRange(pos.getX(), pos.getY(), pos.getZ());
	}

	private static boolean inRange(int x, int y, int z) {
		double dx = x + 0.5 - camX;
		double dy = y + 0.5 - camY;
		double dz = z + 0.5 - camZ;
		return dx * dx + dy * dy + dz * dz <= (double) rangeBlocks * rangeBlocks;
	}

	/**
	 * Sodium fullbright gate, per-position: like {@link #fullbrightActive} but
	 * without the vanilla-only section ThreadLocal (never set on Sodium's mesh
	 * threads). Mirrors the vanilla semantics — every in-range block meshed
	 * gets full light coords, and since hidden blocks aren't meshed only the
	 * visible ores' samples are actually forced.
	 */
	public static boolean fullbrightAt(int x, int y, int z) {
		return active && fullbrightOn && inRange(x, y, z);
	}

	/** True while meshing an in-range section with fullbright on (worker thread). */
	public static boolean fullbrightActive() {
		return active && fullbrightOn && SECTION_IN_RANGE.get();
	}

	/**
	 * Called by the section compiler mixin (worker thread) at the start of each
	 * section. Distance is computed against the main-thread camera snapshot — no
	 * live render state is touched here.
	 */
	public static void beginSection(double centerX, double centerY, double centerZ) {
		if (!active) {
			SECTION_IN_RANGE.set(false);
			return;
		}
		double dx = centerX - camX;
		double dy = centerY - camY;
		double dz = centerZ - camZ;
		SECTION_IN_RANGE.set(dx * dx + dy * dy + dz * dz <= (double) rangeBlocks * rangeBlocks);
	}

	public static void endSection() {
		SECTION_IN_RANGE.set(false);
	}

	/** Re-snapshot the settings and rebuild all chunk geometry. */
	public static void refresh() {
		XRay xray = UnluckyClient.INSTANCE.modules.get(XRay.class);
		Set<net.minecraft.world.level.block.Block> resolved = new java.util.HashSet<>();
		for (String id : xray.blocks.get()) {
			net.minecraft.resources.Identifier parsed = net.minecraft.resources.Identifier.tryParse(id);
			if (parsed != null) {
				net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(parsed).ifPresent(resolved::add);
			}
		}
		visibleBlocks = Set.copyOf(resolved);
		showFluids = xray.fluids.get();
		fullbrightOn = xray.fullbright.get();
		rangeBlocks = xray.distance.getInt() * 16;
		active = xray.isEnabled();
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.level != null) {
			// the safe full-rebuild path: flags geometry invalid, consumed by
			// the extractor next frame (resetLevelRenderData tears down viewArea)
			mc.levelExtractor.allChanged();
		}
	}

	@Override
	public void onTick() {
		// snapshot the camera on the main thread for the worker-side range check.
		// newly loaded/rebuilt sections test against this live position, so the
		// range cap tracks the player without forcing full rebuilds (which would
		// flash the whole screen every time you cross a chunk boundary)
		net.minecraft.world.phys.Vec3 cam = mc().gameRenderer.mainCamera().position();
		camX = cam.x;
		camY = cam.y;
		camZ = cam.z;
		// only rebuild when a setting actually changes
		if (showFluids != fluids.get() || fullbrightOn != fullbright.get() || rangeBlocks != distance.getInt() * 16) {
			refresh();
		}
	}

	@Override
	protected void onEnable() {
		refresh();
	}

	@Override
	protected void onDisable() {
		refresh();
	}
}

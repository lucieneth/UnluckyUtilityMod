package unlucky.utility.client.module.modules.render;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.PerfDebug;
import unlucky.utility.client.util.Render3D;

/**
 * Marks the ground a hostile mob could spawn on.
 *
 * <p>Two states rather than one, because "unsafe" is a question about time as well as light.
 * A spot with no block light and no sky light is spawnable right now and will still be
 * spawnable at noon; a spot with no block light but open sky is only spawnable after dusk.
 * Collapsing those into a single marker is what makes other overlays tell you your lit base
 * is dangerous, so they are drawn and toggled separately here.
 *
 * <p>The spawn test itself is vanilla's — {@link SpawnPlacementTypes#ON_GROUND} against a
 * zombie — rather than a hand-written "solid block with two air above" rule. That covers
 * slabs, stairs, leaves, powder snow and every other surface whose spawnability is not
 * obvious from its shape, and it stays right when the rules change.
 *
 * <p>Nothing is scanned per frame. The volume is swept incrementally under a per-tick budget
 * into a cached marker list, and the cache is dropped only when the player has moved far
 * enough to matter or the refresh interval expires — a full rescan of a 17x9x17 volume every
 * frame is thousands of block lookups for a picture that changes when you place one torch.
 */
public class LightOverlay extends Module {
	private static final double MOVE_INVALIDATE = 2.0;
	private static final double INSET = 0.02;

	public final NumberSetting horizontalRange = add(new NumberSetting("Horizontal range",
			"Blocks scanned either side of you", 8, 2, 32, 1));
	public final NumberSetting verticalRange = add(new NumberSetting("Vertical range",
			"Blocks scanned above and below you", 4, 1, 16, 1));
	public final NumberSetting threshold = add(new NumberSetting("Spawn light threshold",
			"Block light at or below which a hostile mob can spawn", 0, 0, 15, 1));
	public final BooleanSetting showDangerous = add(new BooleanSetting("Show always-dangerous",
			"Mark spots that are spawnable regardless of the time of day", true));
	public final ColorSetting dangerousColor = add(new ColorSetting("Dangerous color",
			"Color for always-spawnable spots", 0xFFFF4040), showDangerous::get);
	public final BooleanSetting showPotential = add(new BooleanSetting("Show night/potential",
			"Mark spots that only become spawnable once the sky darkens", true));
	public final ColorSetting potentialColor = add(new ColorSetting("Potential color",
			"Color for spots that are only dangerous at night", 0xFFFFC040), showPotential::get);
	public final BooleanSetting throughWalls = add(new BooleanSetting("Through walls",
			"Draw markers that are behind terrain", false));
	public final ModeSetting marker = add(new ModeSetting("Marker",
			"Shape drawn on each unsafe surface", "Cross", "Cross", "Face", "Small box"));
	public final NumberSetting scanBudget = add(new NumberSetting("Scan budget",
			"Block positions examined per tick", 1000, 100, 20000, 100));
	public final NumberSetting refreshInterval = add(new NumberSetting("Refresh interval",
			"Ticks before the cached markers are rebuilt", 10, 1, 200, 1));

	/** A finished marker: the surface position and whether it is unsafe right now. */
	private record Marker(BlockPos pos, boolean dangerous) {
	}

	private final List<Marker> markers = new ArrayList<>();
	private final List<Marker> building = new ArrayList<>();
	private BlockPos scanOrigin;
	private BlockPos cacheOrigin;
	private int cursor = -1;
	private int sinceRefresh;

	public LightOverlay() {
		super("LightOverlay", "Marks ground where hostile mobs can spawn", Category.RENDER,
				ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected void onEnable() {
		invalidate();
	}

	@Override
	protected void onDisable() {
		markers.clear();
		building.clear();
		cursor = -1;
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null) {
			onDisable();
			return;
		}
		BlockPos here = mc().player.blockPosition();
		sinceRefresh++;
		boolean moved = cacheOrigin == null
				|| here.distSqr(cacheOrigin) > MOVE_INVALIDATE * MOVE_INVALIDATE;
		if (cursor < 0 && (moved || sinceRefresh >= refreshInterval.getInt())) {
			invalidate();
		}
		if (cursor >= 0) {
			advanceScan();
		}
		render();
	}

	/** Restarts the sweep. The previous markers keep drawing until the new pass finishes. */
	private void invalidate() {
		scanOrigin = mc().player == null ? null : mc().player.blockPosition();
		building.clear();
		cursor = scanOrigin == null ? -1 : 0;
		sinceRefresh = 0;
	}

	/**
	 * Consumes up to one tick's budget of the volume sweep.
	 *
	 * <p>The volume is walked as one flat index rather than three nested loops so the sweep
	 * can stop mid-column and resume exactly where it left off next tick; that is what keeps
	 * a 32-block range from being a stutter instead of a slower refresh.
	 */
	private void advanceScan() {
		long start = PerfDebug.begin();
		int horizontal = horizontalRange.getInt();
		int vertical = verticalRange.getInt();
		int width = horizontal * 2 + 1;
		int height = vertical * 2 + 1;
		int total = width * width * height;
		int budget = Math.min(scanBudget.getInt(), total - cursor);

		for (int step = 0; step < budget; step++) {
			int index = cursor + step;
			int x = index % width;
			int z = index / width % width;
			int y = index / (width * width);
			BlockPos pos = scanOrigin.offset(x - horizontal, y - vertical, z - horizontal);
			classify(pos);
		}
		cursor += budget;

		if (cursor >= total) {
			markers.clear();
			markers.addAll(building);
			building.clear();
			cacheOrigin = scanOrigin;
			cursor = -1;
		}
		PerfDebug.end("LightOverlay.scan", start);
	}

	/** Adds {@code pos} to the pass being built when a hostile mob could stand there. */
	private void classify(BlockPos pos) {
		if (!SpawnPlacementTypes.ON_GROUND.isSpawnPositionOk(mc().level, pos, EntityTypes.ZOMBIE)) {
			return;
		}
		int limit = threshold.getInt();
		if (mc().level.getBrightness(LightLayer.BLOCK, pos) > limit) {
			return; // lit well enough that the time of day cannot make it worse
		}
		boolean dangerous = mc().level.getBrightness(LightLayer.SKY, pos) <= limit;
		if (dangerous ? showDangerous.get() : showPotential.get()) {
			building.add(new Marker(pos.immutable(), dangerous));
		}
	}

	/**
	 * Draws the cached markers.
	 *
	 * <p>Only ever iterates a list a previous sweep already finished, so the per-frame cost is
	 * one pass over the markers and no world lookups at all.
	 */
	private void render() {
		if (markers.isEmpty()) {
			return;
		}
		long start = PerfDebug.begin();
		boolean ignoreDepth = throughWalls.get();
		for (Marker entry : markers) {
			ColorSetting source = entry.dangerous() ? dangerousColor : potentialColor;
			draw(entry.pos(), source.get(), Math.max(24, source.alpha() / 3), ignoreDepth);
		}
		PerfDebug.end("LightOverlay.render", start);
	}

	private void draw(BlockPos pos, int color, int fillAlpha, boolean ignoreDepth) {
		double x = pos.getX();
		double y = pos.getY() + INSET;
		double z = pos.getZ();
		int fill = ColorUtil.withAlpha(color, fillAlpha);
		switch (marker.get()) {
			case "Face" -> Render3D.box(new AABB(x + INSET, y, z + INSET,
					x + 1 - INSET, y, z + 1 - INSET), color, 1.5f, fill, ignoreDepth);
			case "Small box" -> Render3D.box(new AABB(x + 0.3, y, z + 0.3,
					x + 0.7, y + 0.4, z + 0.7), color, 1.5f, fill, ignoreDepth);
			default -> {
				Render3D.line(new Vec3(x + INSET, y, z + INSET),
						new Vec3(x + 1 - INSET, y, z + 1 - INSET), color, 1.5f, ignoreDepth);
				Render3D.line(new Vec3(x + 1 - INSET, y, z + INSET),
						new Vec3(x + INSET, y, z + 1 - INSET), color, 1.5f, ignoreDepth);
			}
		}
	}
}

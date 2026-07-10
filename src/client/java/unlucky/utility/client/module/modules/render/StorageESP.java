package unlucky.utility.client.module.modules.render;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import unlucky.utility.client.UnluckyClientMod;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.WorldScan;

/**
 * Shader-style boxes on storage blocks, through walls. Boxes match the actual
 * block shape, every container type has its own color, and everything fades
 * out as you get close.
 */
public class StorageESP extends Module {
	public final NumberSetting range = add(new NumberSetting("Range", "Scan radius", 64, 16, 128, 8));
	public final BooleanSetting throughWalls = add(new BooleanSetting("Through walls", "Show boxes behind blocks", true));
	public final BooleanSetting occlusion = add(new BooleanSetting("Occlusion cull", "Hide boxes hidden behind other ESP boxes (less clutter)", true));
	public final BooleanSetting chests = add(new BooleanSetting("Chests", "Regular chests", true));
	public final ColorSetting chestColor = add(new ColorSetting("Chest color", "Chest box color", 0xFFE8A33D));
	public final BooleanSetting trappedChests = add(new BooleanSetting("Trapped chests", "Trapped chests", true));
	public final ColorSetting trappedColor = add(new ColorSetting("Trapped color", "Trapped chest box color", 0xFFE85C5C));
	public final BooleanSetting enderChests = add(new BooleanSetting("Ender chests", "Ender chests", true));
	public final ColorSetting enderColor = add(new ColorSetting("Ender color", "Ender chest box color", 0xFFB65CFF));
	public final BooleanSetting shulkers = add(new BooleanSetting("Shulkers", "Shulker boxes", true));
	public final ColorSetting shulkerColor = add(new ColorSetting("Shulker color", "Fallback for uncolored shulkers", 0xFFFF7ED8));
	public final BooleanSetting shulkerDye = add(new BooleanSetting("Shulker dye", "Color shulkers by their dye", true));
	public final BooleanSetting barrels = add(new BooleanSetting("Barrels", "Barrels", true));
	public final ColorSetting barrelColor = add(new ColorSetting("Barrel color", "Barrel box color", 0xFFC98F55));
	public final BooleanSetting hoppers = add(new BooleanSetting("Hoppers", "Hoppers", false));
	public final ColorSetting hopperColor = add(new ColorSetting("Hopper color", "Hopper box color", 0xFFAAAAB4));
	public final BooleanSetting furnaces = add(new BooleanSetting("Furnaces", "Furnaces, smokers, blast furnaces", false));
	public final ColorSetting furnaceColor = add(new ColorSetting("Furnace color", "Furnace box color", 0xFFFF8A5C));
	public final BooleanSetting minecarts = add(new BooleanSetting("Minecarts", "Chest/furnace/hopper minecarts", true));
	public final ColorSetting minecartColor = add(new ColorSetting("Minecart color", "Container minecart color", 0xFF9CE8A3));
	public final BooleanSetting fill = add(new BooleanSetting("Fill", "Translucent fill", true));
	public final NumberSetting fillOpacity = add(new NumberSetting("Fill opacity", "Alpha of the fill", 45, 10, 160, 5));
	public final NumberSetting dimDistance = add(new NumberSetting("Dim distance", "Fade out when closer than this (0 = off)", 5, 0, 16, 1));

	private record Target(AABB box, int color) {
	}

	private final List<Target> cached = new ArrayList<>();
	// reused across ticks so occlusion-on doesn't reallocate a backing array every
	// tick; only ever populated when "Occlusion cull" is on (stays List.of() when off,
	// which several call sites rely on as a zero-cost fast path)
	private final List<AABB> occluderScratch = new ArrayList<>();
	// per-target/per-entity subset of occluderScratch that could actually affect its
	// sightlines — see fillRelevant(). Reused across targets within a tick.
	private final List<AABB> relevantScratch = new ArrayList<>();
	private int ticksUntilScan;

	// Phase 3: cached clipped geometry per target (parallel to `cached`, indices
	// line up 1:1 — always rebuilt together on rescan, see tick()). Re-emitted
	// every tick cheaply; only recomputed when something that could change the
	// clipped shape actually changes: the target list itself (rescan), the camera
	// moving (Render3D's face/edge clipping samples relative to the CAMERA, not
	// the player eye — see the eye/camera split below), or the occlusion-cull
	// toggle flipping (changes whether an occluder list is even passed in).
	private final List<Render3D.BoxGeom> geomCache = new ArrayList<>();
	private Vec3 lastGeomCamera;
	private boolean lastCull;
	private static final double GEOM_INVALIDATE_DIST_SQ = 0.2 * 0.2;

	// Phase 0 instrumentation: run with -Dunlucky.espDebug=true to log rolling
	// avg/max tick cost once a second. No cost at all when the flag is off.
	private static final boolean DEBUG = Boolean.getBoolean("unlucky.espDebug");
	private long debugAccumNanos;
	private long debugMaxNanos;
	private int debugSamples;
	private long debugLastLogMs;

	public StorageESP() {
		super("StorageESP", "Highlights storage blocks", Category.RENDER);
	}

	@Override
	protected void onEnable() {
		cached.clear();
		ticksUntilScan = 0;
		geomCache.clear();
		lastGeomCamera = null;
	}

	@Override
	public void onTick() {
		if (!DEBUG) {
			tick();
			return;
		}
		long start = System.nanoTime();
		tick();
		long elapsed = System.nanoTime() - start;
		debugAccumNanos += elapsed;
		debugMaxNanos = Math.max(debugMaxNanos, elapsed);
		debugSamples++;
		long now = System.currentTimeMillis();
		if (now - debugLastLogMs >= 1000) {
			double avgMs = (debugAccumNanos / (double) debugSamples) / 1_000_000.0;
			double maxMs = debugMaxNanos / 1_000_000.0;
			UnluckyClientMod.LOGGER.info("[StorageESP] targets={} avg={}ms max={}ms over {} ticks",
					cached.size(), String.format("%.3f", avgMs), String.format("%.3f", maxMs), debugSamples);
			debugAccumNanos = 0;
			debugMaxNanos = 0;
			debugSamples = 0;
			debugLastLogMs = now;
		}
	}

	private void tick() {
		if (mc().level == null || mc().player == null) {
			return;
		}
		boolean rescanned = false;
		if (--ticksUntilScan <= 0) {
			ticksUntilScan = 10;
			rescan();
			rescanned = true;
		}

		// two distinct eye points, intentionally not unified: occluded()'s cull
		// decision uses the player's eye (matches its existing behavior), while
		// Render3D's face/edge clipping samples relative to the CAMERA (matches
		// what Render3D.box() itself has always used internally) — same point in
		// normal play, can differ in Freecam.
		Vec3 eye = mc().player.getEyePosition();
		Vec3 camera = mc().gameRenderer.mainCamera().position();
		double dim = dimDistance.get();
		boolean cull = occlusion.get();
		List<AABB> occluders = List.of();
		if (cull) {
			occluderScratch.clear();
			for (Target target : cached) {
				occluderScratch.add(target.box);
			}
			occluders = occluderScratch;
		}

		// gizmos live one tick, re-emit the geometry cache every tick; only redo the
		// expensive clipping when the target list changed, the camera moved enough
		// to change what's visible, or occlusion got toggled (changes whether an
		// occluder list is even passed in)
		boolean rebuildGeom = rescanned || lastGeomCamera == null || cull != lastCull
				|| camera.distanceToSqr(lastGeomCamera) > GEOM_INVALIDATE_DIST_SQ;
		if (rebuildGeom) {
			while (geomCache.size() < cached.size()) {
				geomCache.add(new Render3D.BoxGeom());
			}
			lastGeomCamera = camera;
			lastCull = cull;
		}

		for (int i = 0; i < cached.size(); i++) {
			Target target = cached.get(i);
			List<AABB> relevant = cull ? fillRelevant(target.box, eye) : occluders;
			if (cull && Render3D.occluded(target.box, eye, relevant)) {
				continue;
			}
			float alpha = 1.0f;
			if (dim > 0) {
				double distance = Math.sqrt(target.box.getCenter().distanceToSqr(eye));
				alpha = (float) Math.clamp(distance / dim, 0.0, 1.0);
			}
			if (alpha < 0.04f) {
				continue;
			}
			int outline = ColorUtil.multiplyAlpha(target.color | 0xFF000000, alpha);
			int fillArgb = fill.get()
					? ColorUtil.withAlpha(target.color, (int) (fillOpacity.getInt() * alpha))
					: 0;
			Render3D.BoxGeom geom = geomCache.get(i);
			if (rebuildGeom) {
				// clipping samples relative to the camera, so the occluder prefilter
				// (a safe over-approximation for WHICHEVER eye it's built from) must
				// be built from the camera here, not the player eye used above
				List<AABB> geomRelevant = cull ? fillRelevant(target.box, camera) : occluders;
				Render3D.computeGeometry(target.box, camera, geomRelevant, geom);
			}
			Render3D.emitGeometry(geom, target.box, outline, 2.0f, fillArgb, throughWalls.get());
		}

		// container minecarts are entities — render them live so they track movement
		if (minecarts.get()) {
			int fillArgb = fill.get() ? ColorUtil.withAlpha(minecartColor.get(), fillOpacity.getInt()) : 0;
			for (net.minecraft.world.entity.Entity entity : mc().level.entitiesForRendering()) {
				if (entity instanceof net.minecraft.world.entity.vehicle.minecart.MinecartChest
						|| entity instanceof net.minecraft.world.entity.vehicle.minecart.MinecartHopper
						|| entity instanceof net.minecraft.world.entity.vehicle.minecart.MinecartFurnace) {
					if (entity.distanceToSqr(mc().player) <= range.get() * range.get()) {
						AABB cartBox = entity.getBoundingBox();
						List<AABB> relevant = cull ? fillRelevant(cartBox, eye) : occluders;
						if (!(cull && Render3D.occluded(cartBox, eye, relevant))) {
							Render3D.box(cartBox, minecartColor.get() | 0xFF000000, 2.0f, fillArgb, throughWalls.get(), relevant);
						}
					}
				}
			}
		}
	}

	/** How far past the eye-target span an occluder must be to be provably irrelevant. */
	private static final double SPAN_MARGIN = 0.25;

	/**
	 * Narrows {@link #occluderScratch} down to the boxes that could actually affect
	 * a sightline to {@code target}: anything that doesn't overlap the region
	 * spanned by the eye and the target (padded for the small offsets the various
	 * samplers in Render3D use — corner/edge lerps, face-normal nudges, all well
	 * under {@link #SPAN_MARGIN}) provably cannot sit on any ray between them. This
	 * is a safe over-approximation — it never drops a box that could matter, only
	 * ones that geometrically can't — so it changes nothing about the result, just
	 * how many boxes {@code Render3D} has to test per sample point. Turns the O(n)
	 * inner loop of every occlusion sample into O(k) for the typical handful of
	 * nearby boxes, instead of every box in range.
	 */
	private List<AABB> fillRelevant(AABB target, Vec3 eye) {
		double minX = Math.min(eye.x, target.minX) - SPAN_MARGIN, maxX = Math.max(eye.x, target.maxX) + SPAN_MARGIN;
		double minY = Math.min(eye.y, target.minY) - SPAN_MARGIN, maxY = Math.max(eye.y, target.maxY) + SPAN_MARGIN;
		double minZ = Math.min(eye.z, target.minZ) - SPAN_MARGIN, maxZ = Math.max(eye.z, target.maxZ) + SPAN_MARGIN;
		relevantScratch.clear();
		for (AABB box : occluderScratch) {
			if (box.maxX >= minX && box.minX <= maxX
					&& box.maxY >= minY && box.minY <= maxY
					&& box.maxZ >= minZ && box.minZ <= maxZ) {
				relevantScratch.add(box);
			}
		}
		return relevantScratch;
	}

	private void rescan() {
		cached.clear();
		for (BlockEntity blockEntity : WorldScan.blockEntitiesAround(range.get())) {
			int color = colorFor(blockEntity);
			if (color == 0) {
				continue;
			}
			BlockPos pos = blockEntity.getBlockPos();
			// match the actual block outline (chests are smaller than a full block)
			BlockState state = mc().level.getBlockState(pos);
			VoxelShape shape = state.getShape(mc().level, pos);
			AABB box = shape.isEmpty()
					? new AABB(pos).deflate(0.03)
					: shape.bounds().move(pos);
			cached.add(new Target(box, color));
		}
		weldNeighbors();
	}

	/**
	 * Stretches near-touching boxes (chest insets leave 1/16 gaps) to meet at
	 * the midpoint, so rows render as one connected shape instead of showing
	 * slivers of side face in the cracks.
	 *
	 * Deliberately O(n^2): an earlier attempt replaced this with an O(n) pass that
	 * only looked up each target's direct axis-neighbors (block-adjacent positions),
	 * on the assumption that weld() only ever succeeds for genuinely adjacent boxes.
	 * That assumption is false — a box whose bound already grew from an earlier weld
	 * (e.g. its east neighbor) can end up overlapping a THIRD, diagonally-positioned
	 * box that was never actually block-adjacent, and weld() has no way to tell the
	 * difference (it only sees current bounds, not original block positions). A
	 * verification harness (random "storage room" scenes, see plan.md Phase 5)
	 * caught this producing real, non-trivial differences from the original output,
	 * so the O(n) rewrite was reverted in favor of staying provably identical. This
	 * only runs once per 10-tick rescan (not per-frame), so it's a minor cost next
	 * to the occlusion-sampling work Phases 1-3 address.
	 */
	private void weldNeighbors() {
		for (int i = 0; i < cached.size(); i++) {
			for (int j = i + 1; j < cached.size(); j++) {
				AABB a = cached.get(i).box;
				AABB b = cached.get(j).box;
				AABB[] welded = weld(a, b);
				if (welded != null) {
					cached.set(i, new Target(welded[0], cached.get(i).color));
					cached.set(j, new Target(welded[1], cached.get(j).color));
				}
			}
		}
	}

	private static final double WELD_GAP = 0.2;

	/** Welds along one axis when the boxes overlap on the other two; null if not close. */
	private static AABB[] weld(AABB a, AABB b) {
		boolean overlapX = a.minX < b.maxX && b.minX < a.maxX;
		boolean overlapY = a.minY < b.maxY && b.minY < a.maxY;
		boolean overlapZ = a.minZ < b.maxZ && b.minZ < a.maxZ;
		if (overlapY && overlapZ) {
			if (b.minX - a.maxX > 0 && b.minX - a.maxX <= WELD_GAP) {
				double mid = (a.maxX + b.minX) / 2;
				return new AABB[] {new AABB(a.minX, a.minY, a.minZ, mid, a.maxY, a.maxZ),
						new AABB(mid, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)};
			}
			if (a.minX - b.maxX > 0 && a.minX - b.maxX <= WELD_GAP) {
				double mid = (b.maxX + a.minX) / 2;
				return new AABB[] {new AABB(mid, a.minY, a.minZ, a.maxX, a.maxY, a.maxZ),
						new AABB(b.minX, b.minY, b.minZ, mid, b.maxY, b.maxZ)};
			}
		}
		if (overlapX && overlapZ) {
			if (b.minY - a.maxY > 0 && b.minY - a.maxY <= WELD_GAP) {
				double mid = (a.maxY + b.minY) / 2;
				return new AABB[] {new AABB(a.minX, a.minY, a.minZ, a.maxX, mid, a.maxZ),
						new AABB(b.minX, mid, b.minZ, b.maxX, b.maxY, b.maxZ)};
			}
			if (a.minY - b.maxY > 0 && a.minY - b.maxY <= WELD_GAP) {
				double mid = (b.maxY + a.minY) / 2;
				return new AABB[] {new AABB(a.minX, mid, a.minZ, a.maxX, a.maxY, a.maxZ),
						new AABB(b.minX, b.minY, b.minZ, b.maxX, mid, b.maxZ)};
			}
		}
		if (overlapX && overlapY) {
			if (b.minZ - a.maxZ > 0 && b.minZ - a.maxZ <= WELD_GAP) {
				double mid = (a.maxZ + b.minZ) / 2;
				return new AABB[] {new AABB(a.minX, a.minY, a.minZ, a.maxX, a.maxY, mid),
						new AABB(b.minX, b.minY, mid, b.maxX, b.maxY, b.maxZ)};
			}
			if (a.minZ - b.maxZ > 0 && a.minZ - b.maxZ <= WELD_GAP) {
				double mid = (b.maxZ + a.minZ) / 2;
				return new AABB[] {new AABB(a.minX, a.minY, mid, a.maxX, a.maxY, a.maxZ),
						new AABB(b.minX, b.minY, b.minZ, b.maxX, b.maxY, mid)};
			}
		}
		return null;
	}

	private int colorFor(BlockEntity blockEntity) {
		// trapped chests extend ChestBlockEntity, so test them first
		if (blockEntity instanceof TrappedChestBlockEntity) {
			return trappedChests.get() ? trappedColor.get() : 0;
		}
		if (blockEntity instanceof ShulkerBoxBlockEntity shulker) {
			if (!shulkers.get()) {
				return 0;
			}
			if (shulkerDye.get() && shulker.getColor() != null) {
				return shulker.getColor().getTextureDiffuseColor() | 0xFF000000;
			}
			return shulkerColor.get();
		}
		return switch (blockEntity) {
			case ChestBlockEntity ignored -> chests.get() ? chestColor.get() : 0;
			case EnderChestBlockEntity ignored -> enderChests.get() ? enderColor.get() : 0;
			case BarrelBlockEntity ignored -> barrels.get() ? barrelColor.get() : 0;
			case HopperBlockEntity ignored -> hoppers.get() ? hopperColor.get() : 0;
			case AbstractFurnaceBlockEntity ignored -> furnaces.get() ? furnaceColor.get() : 0;
			default -> 0;
		};
	}
}

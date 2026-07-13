package unlucky.utility.client.module.modules.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BlockListSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render3D;

/**
 * Finds any block you pick and boxes it through walls. Built on the ESP stack:
 * a time-sliced chunk-section scan on the tick thread (bounded per tick so a big
 * radius never stalls the game — {@link LevelChunkSection#maybeHas} rejects
 * sections without a target block before touching individual states), with the
 * matched boxes cached and re-emitted every tick like TreasureESP.
 *
 * <p>Everything here runs on the client tick thread reading loaded client chunks,
 * which is main-thread safe — unlike the section <em>compiler</em> (see
 * ARCHITECTURE.md §6), no worker thread and no live render state is touched.
 */
public class Search extends Module {
	private static final Set<String> PRESET = Set.of(
			"minecraft:diamond_ore", "minecraft:deepslate_diamond_ore", "minecraft:ancient_debris");

	public final BlockListSetting blocks = add(new BlockListSetting("Blocks", "Blocks to find — right-click to pick", PRESET));
	public final NumberSetting range = add(new NumberSetting("Range", "Scan radius in chunks", 4, 1, 12, 1));
	public final NumberSetting maxResults = add(new NumberSetting("Max results", "Cap on boxes drawn (protects FPS)", 500, 50, 4000, 50));
	public final BooleanSetting throughWalls = add(new BooleanSetting("Through walls", "Show boxes behind blocks", true));
	public final ColorSetting color = add(new ColorSetting("Color", "Box color", 0xFF5CD6FF));
	public final BooleanSetting fill = add(new BooleanSetting("Fill", "Translucent fill", true));
	public final NumberSetting fillOpacity = add(new NumberSetting("Fill opacity", "Alpha of the fill", 40, 10, 160, 5));
	public final BooleanSetting tracers = add(new BooleanSetting("Tracers", "Line from the camera to each result", false));
	public final ColorSetting tracerColor = add(new ColorSetting("Tracer color", "Tracer line color", 0xFF5CD6FF));
	public final BooleanSetting occlusion = add(new BooleanSetting("Occlusion cull", "Hide boxes hidden behind other result boxes (less clutter)", true));

	// resolved target blocks; rebuilt only when the picker list changes
	private final Set<Block> targets = new HashSet<>();
	private Set<String> lastList;
	private final Predicate<BlockState> matches = state -> targets.contains(state.getBlock());

	// published results (rendered) and the parallel boxes; `building` is the in-progress scan
	private final List<BlockPos> results = new ArrayList<>();
	private final List<AABB> boxes = new ArrayList<>();
	private final List<BlockPos> building = new ArrayList<>();
	private final Deque<Long> pending = new ArrayDeque<>(); // packed ChunkPos still to scan this cycle

	// occlusion scratch (see StorageESP: the prefilter keeps the cull O(k), not O(n))
	private final List<AABB> occluderScratch = new ArrayList<>();
	private final List<AABB> relevantScratch = new ArrayList<>();

	private Level lastLevel;

	// per-tick scan budget: a hard chunk cap plus a wall-clock cap, whichever hits first
	private static final int MAX_CHUNKS_PER_TICK = 16;
	private static final long TICK_BUDGET_NANOS = 1_500_000L;

	public Search() {
		super("Search", "Find any block and see it through walls", Category.WORLD);
	}

	@Override
	protected void onEnable() {
		reset();
	}

	@Override
	protected void onDisable() {
		reset();
	}

	private void reset() {
		results.clear();
		boxes.clear();
		building.clear();
		pending.clear();
		lastList = null;
	}

	@Override
	public void onTick() {
		if (mc().level == null || mc().player == null) {
			return;
		}
		if (mc().level != lastLevel) {
			lastLevel = mc().level;
			reset();
		}
		resolveTargets();
		scanStep();
		render();
	}

	/** Re-resolve the block id list into Block instances when it changes. */
	private void resolveTargets() {
		Set<String> current = blocks.get();
		if (current.equals(lastList)) {
			return;
		}
		lastList = new HashSet<>(current);
		targets.clear();
		for (String id : current) {
			Identifier parsed = Identifier.tryParse(id);
			if (parsed != null) {
				BuiltInRegistries.BLOCK.getOptional(parsed).ifPresent(targets::add);
			}
		}
		pending.clear(); // restart the scan against the new target set
		building.clear();
	}

	/** Scans a bounded slice of the chunk ring each tick, publishing when a full pass finishes. */
	private void scanStep() {
		if (targets.isEmpty()) {
			results.clear();
			boxes.clear();
			return;
		}
		if (pending.isEmpty()) {
			refillRing();
		}
		int cap = maxResults.getInt();
		int processed = 0;
		long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
		while (!pending.isEmpty() && processed < MAX_CHUNKS_PER_TICK && System.nanoTime() < deadline) {
			long packed = pending.poll();
			scanChunk(ChunkPos.getX(packed), ChunkPos.getZ(packed), cap);
			processed++;
			if (building.size() >= cap) {
				pending.clear(); // hit the cap — end this pass early
			}
		}
		if (pending.isEmpty()) {
			publish();
		}
	}

	/**
	 * Queue every chunk within range of the player's current chunk for the next
	 * pass, <b>nearest first</b>. Ordering matters: when a dense block hits the
	 * {@code Max results} cap mid-pass we stop scanning, so the cap must keep the
	 * closest matches (a blob around you) instead of whichever corner we happened
	 * to poll first.
	 */
	private void refillRing() {
		building.clear();
		int r = range.getInt();
		ChunkPos center = mc().player.chunkPosition();
		int cx = center.x();
		int cz = center.z();
		List<int[]> ring = new ArrayList<>();
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				ring.add(new int[]{dx, dz});
			}
		}
		ring.sort(java.util.Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
		for (int[] o : ring) {
			pending.add(ChunkPos.pack(cx + o[0], cz + o[1]));
		}
	}

	private void scanChunk(int cx, int cz, int cap) {
		LevelChunk chunk = mc().level.getChunkSource().getChunkNow(cx, cz);
		if (chunk == null) {
			return;
		}
		LevelChunkSection[] sections = chunk.getSections();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section == null || section.hasOnlyAir() || !section.maybeHas(matches)) {
				continue;
			}
			int baseX = cx << 4;
			int baseZ = cz << 4;
			int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
			for (int ly = 0; ly < 16; ly++) {
				for (int lz = 0; lz < 16; lz++) {
					for (int lx = 0; lx < 16; lx++) {
						if (targets.contains(section.getBlockState(lx, ly, lz).getBlock())) {
							building.add(new BlockPos(baseX + lx, baseY + ly, baseZ + lz));
							if (building.size() >= cap) {
								return;
							}
						}
					}
				}
			}
		}
	}

	/** Swap the finished scan into the rendered set and rebuild the box list. */
	private void publish() {
		results.clear();
		results.addAll(building);
		boxes.clear();
		for (BlockPos pos : results) {
			VoxelShape shape = mc().level.getBlockState(pos).getShape(mc().level, pos);
			boxes.add(shape.isEmpty() ? new AABB(pos).deflate(0.01) : shape.bounds().move(pos).inflate(0.002));
		}
	}

	private void render() {
		if (results.isEmpty()) {
			return;
		}
		int outline = color.get() | 0xFF000000;
		int fillArgb = fill.get() ? ColorUtil.withAlpha(color.get(), fillOpacity.getInt()) : 0;
		boolean cull = occlusion.get() && boxes.size() > 1;
		boolean through = throughWalls.get();
		Vec3 eye = mc().player.getEyePosition();
		Vec3 camera = mc().gameRenderer.mainCamera().position();
		if (cull) {
			occluderScratch.clear();
			occluderScratch.addAll(boxes);
		}
		for (int i = 0; i < boxes.size(); i++) {
			AABB box = boxes.get(i);
			List<AABB> relevant = cull ? fillRelevant(box, eye) : List.of();
			if (cull && Render3D.occluded(box, eye, relevant)) {
				continue;
			}
			Render3D.box(box, outline, 2.0f, fillArgb, through, relevant);
			if (tracers.get()) {
				Render3D.line(camera, box.getCenter(), tracerColor.get() | 0xFF000000, 1.0f, through);
			}
		}
	}

	/** How far past the eye→target span an occluder must be to be provably irrelevant. */
	private static final double SPAN_MARGIN = 0.25;

	/** Narrows the occluder set to boxes that could actually sit on a sightline to {@code target}. */
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
}

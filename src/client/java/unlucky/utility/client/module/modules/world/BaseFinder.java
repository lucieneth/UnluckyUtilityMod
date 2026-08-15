package unlucky.utility.client.module.modules.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BlockListSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.BaseSignatures;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.PerfDebug;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.WorldRecordStore;

/**
 * Flags chunks that somebody has built in, from the chunk data as it arrives.
 *
 * <p>Everything here is a judgement about evidence. One crafter is a base; one
 * furnace is a witch hut. So the block signatures come in seven tiers, each with
 * its own threshold — see {@link BaseSignatures}, where the lists live — and a
 * chunk is flagged when any single tier clears its own bar. On top of those sit
 * the detectors that are not about counting at all: a sign somebody wrote on, a
 * portal, a spawner, anything standing above the clouds or down on the bedrock.
 *
 * <p><b>The scan is queued, not immediate.</b> A full chunk is up to ninety-eight
 * thousand block states, and chunks arrive in bursts — flying an elytra down a
 * highway would hand us a hundred at once. Trouser Streak scans each one inside
 * the packet handler; we take the position, put it in a queue, and spend a
 * budget of sections per tick, which is the same shape as LightOverlay's sweep
 * and the reason a highway trip stays smooth.
 *
 * <p>Finds are recorded in a {@link WorldRecordStore} keyed by server, dimension
 * and chunk — the same store StashFinder uses, so a base found on one session is
 * still on the map on the next one.
 *
 * <p>Reference: Trouser Streak's BaseFinder.
 */
public class BaseFinder extends Module {
	private static final WorldRecordStore STORE = new WorldRecordStore("bases");

	/**
	 * Terrain that carries no information, rejected before anything else looks at
	 * it. Straight from the original, and it is most of a chunk by volume.
	 */
	private static final Set<net.minecraft.world.level.block.Block> IGNORED = Set.of(
			Blocks.AIR, Blocks.CAVE_AIR, Blocks.STONE, Blocks.DEEPSLATE, Blocks.DIRT,
			Blocks.GRASS_BLOCK, Blocks.WATER, Blocks.SAND, Blocks.GRAVEL, Blocks.BEDROCK,
			Blocks.NETHERRACK, Blocks.LAVA);

	public final BooleanSetting chatFeedback = add(new BooleanSetting("Chat feedback",
			"Announce each base as it is found", true));
	public final BooleanSetting showCoords = add(new BooleanSetting("Show coordinates",
			"Include the chunk's coordinates in the message", true));
	public final NumberSetting minY = add(new NumberSetting("Min Y",
			"Ignore everything below this height", -64, -64, 320, 8));
	public final NumberSetting maxY = add(new NumberSetting("Max Y",
			"Ignore everything above this height", 320, -64, 320, 8));
	public final NumberSetting sectionsPerTick = add(new NumberSetting("Sections per tick",
			"Chunk sections scanned per tick — the whole cost of the module", 24, 4, 256, 4));

	public final BooleanSetting writtenSigns = add(new BooleanSetting("Written signs",
			"A sign with anything written on it is somebody's", true));
	public final BooleanSetting portals = add(new BooleanSetting("Portals",
			"Flag nether portals", true));
	public final BooleanSetting spawners = add(new BooleanSetting("Spawners",
			"Flag spawners — a dungeon somebody may have claimed", false));
	public final BooleanSetting skyBuild = add(new BooleanSetting("Skybuilds",
			"Flag anything built above the height below", true));
	public final NumberSetting skyBuildY = add(new NumberSetting("Skybuild height",
			"Height above which any placed block is a build", 200, 64, 320, 8), skyBuild::get);
	public final BooleanSetting deepBuild = add(new BooleanSetting("Deep builds",
			"Flag anything built below the height below", true));
	public final NumberSetting deepBuildY = add(new NumberSetting("Deep build height",
			"Height under which any placed block is a build", -50, -64, 64, 2), deepBuild::get);

	public final BooleanSetting persist = add(new BooleanSetting("Remember",
			"Keep finds between sessions, per server and dimension", true));
	public final ColorSetting color = add(new ColorSetting("Color",
			"Colour of a flagged chunk", ColorUtil.argb(255, 255, 80, 80)));
	public final BooleanSetting throughWalls = add(new BooleanSetting("Through walls",
			"Draw flagged chunks that are behind terrain", true));
	public final NumberSetting renderRange = add(new NumberSetting("Render range",
			"How far away a flagged chunk is still drawn, in chunks", 16, 2, 64, 2));

	/** Per-tier enable/list/threshold, built in a loop rather than seven times over. */
	private final List<BooleanSetting> tierEnabled = new ArrayList<>();
	private final List<BlockListSetting> tierBlocks = new ArrayList<>();
	private final List<NumberSetting> tierThreshold = new ArrayList<>();

	/** The tier lists as blocks rather than ids — see {@link #resolveTiers}. */
	private final List<Set<net.minecraft.world.level.block.Block>> resolved = new ArrayList<>();
	private int sinceResolve = Integer.MAX_VALUE;

	private final Deque<ChunkPos> pending = new ArrayDeque<>();
	private final Set<Long> queued = new HashSet<>();
	/** Chunks flagged this session, drawn without a store lookup per frame. */
	private final Set<Long> flagged = new HashSet<>();

	public BaseFinder() {
		super("BaseFinder", "Flags chunks somebody has built in", Category.WORLD,
				ServerVisibility.CLIENT_ONLY);
		for (int i = 0; i < BaseSignatures.TIERS.size(); i++) {
			String name = BaseSignatures.TIER_NAMES[i];
			BooleanSetting enabled = add(new BooleanSetting(name,
					"Use the \"" + name + "\" signature", true));
			tierEnabled.add(enabled);
			tierBlocks.add(add(new BlockListSetting(name + " blocks",
					"Blocks in this signature — right-click to edit",
					BaseSignatures.TIERS.get(i)), enabled::get));
			tierThreshold.add(add(new NumberSetting(name + " threshold",
					"How many of them a chunk needs", BaseSignatures.DEFAULT_THRESHOLDS[i], 1, 100, 1),
					enabled::get));
		}
	}

	@Override
	protected void onEnable() {
		pending.clear();
		queued.clear();
		flagged.clear();
		resolveTiers();
		loadKnown();
	}

	@Override
	protected void onDisable() {
		pending.clear();
		queued.clear();
		flagged.clear();
		if (persist.get()) {
			STORE.saveDirty();
		}
	}

	/** ClientPacketListenerMixin's chunk-arrival handler. Queue only — see the class doc. */
	public void onChunkLoaded(ClientboundLevelChunkWithLightPacket packet) {
		ChunkPos pos = new ChunkPos(packet.getX(), packet.getZ());
		long key = ChunkPos.pack(pos.x(), pos.z());
		if (flagged.contains(key) || !queued.add(key)) {
			return; // already known, or already waiting
		}
		pending.add(pos);
	}

	@Override
	public void onTick() {
		if (mc().level == null || mc().player == null) {
			return;
		}
		if (++sinceResolve >= 100) {
			resolveTiers();
		}
		drainQueue();
		if (persist.get()) {
			STORE.saveDirty();
		}
		render();
	}

	/**
	 * Spends this tick's budget on the queue.
	 *
	 * <p>The budget is counted in sections rather than chunks because that is where
	 * the cost actually is: a chunk over open ocean is two sections of work and a
	 * chunk under a mountain is twenty.
	 */
	private void drainQueue() {
		long start = PerfDebug.begin();
		int budget = sectionsPerTick.getInt();
		while (budget > 0 && !pending.isEmpty()) {
			ChunkPos pos = pending.poll();
			queued.remove(ChunkPos.pack(pos.x(), pos.z()));
			LevelChunk chunk = mc().level.getChunk(pos.x(), pos.z());
			if (chunk == null) {
				continue; // unloaded again while it waited
			}
			budget -= scan(chunk, pos);
		}
		PerfDebug.end("tick.BaseFinder.scan", start);
	}

	/**
	 * Scans one chunk whole.
	 *
	 * @return how many non-empty sections it cost, for the caller's budget
	 */
	private int scan(LevelChunk chunk, ChunkPos pos) {
		int floor = Math.max(minY.getInt(), mc().level.getMinY());
		int ceiling = Math.min(maxY.getInt(), mc().level.getMaxY());
		int[] counts = new int[BaseSignatures.TIERS.size()];
		String reason = signReason(chunk);
		int worked = 0;

		LevelChunkSection[] sections = chunk.getSections();
		int sectionY = mc().level.getMinY();
		for (LevelChunkSection section : sections) {
			if (section == null || section.hasOnlyAir() || sectionY + 15 < floor || sectionY > ceiling) {
				sectionY += 16;
				continue;
			}
			worked++;
			for (int x = 0; x < 16 && reason == null; x++) {
				for (int y = 0; y < 16 && reason == null; y++) {
					int worldY = sectionY + y;
					if (worldY < floor || worldY > ceiling) {
						continue;
					}
					for (int z = 0; z < 16; z++) {
						BlockState state = section.getBlockState(x, y, z);
						var block = state.getBlock();
						if (IGNORED.contains(block)) {
							continue;
						}
						String instant = instantReason(block, worldY);
						if (instant != null) {
							reason = instant;
							break;
						}
						countTiers(block, counts);
					}
				}
			}
			sectionY += 16;
		}

		if (reason == null) {
			reason = tierReason(counts);
		}
		if (reason != null) {
			flag(pos, reason, counts);
		}
		return Math.max(worked, 1);
	}

	/** The detectors that need one block and no counting. */
	private String instantReason(net.minecraft.world.level.block.Block block, int worldY) {
		if (portals.get() && block == Blocks.NETHER_PORTAL) {
			return "portal";
		}
		if (spawners.get() && block == Blocks.SPAWNER) {
			return "spawner";
		}
		if (skyBuild.get() && worldY >= skyBuildY.getInt()) {
			return "skybuild at y" + worldY;
		}
		if (deepBuild.get() && worldY <= deepBuildY.getInt()) {
			return "deep build at y" + worldY;
		}
		return null;
	}

	private void countTiers(net.minecraft.world.level.block.Block block, int[] counts) {
		for (int i = 0; i < counts.length; i++) {
			if (tierEnabled.get(i).get() && resolved.get(i).contains(block)) {
				counts[i]++;
			}
		}
	}

	/**
	 * Turns the id lists into block sets.
	 *
	 * <p>Worth the bookkeeping: the alternative is asking the registry for a block's
	 * id and building that string once per block state, and a chunk is up to
	 * ninety-eight thousand of those. Refreshed on a timer because the only thing
	 * that changes a list is somebody editing it in the ClickGUI.
	 */
	private void resolveTiers() {
		resolved.clear();
		for (BlockListSetting setting : tierBlocks) {
			Set<net.minecraft.world.level.block.Block> blocks = new HashSet<>();
			for (String id : setting.get()) {
				BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.Identifier.parse(id))
						.ifPresent(blocks::add);
			}
			resolved.add(blocks);
		}
		sinceResolve = 0;
	}

	private String tierReason(int[] counts) {
		for (int i = 0; i < counts.length; i++) {
			if (tierEnabled.get(i).get() && counts[i] >= tierThreshold.get(i).getInt()) {
				return BaseSignatures.TIER_NAMES[i].toLowerCase(java.util.Locale.ROOT)
						+ " ×" + counts[i];
			}
		}
		return null;
	}

	/**
	 * A sign with anything on it.
	 *
	 * <p>Read off the block entities rather than the block states — the block only
	 * says a sign is there, and an unwritten sign is worldgen furniture in an
	 * igloo or a village.
	 */
	private String signReason(LevelChunk chunk) {
		if (!writtenSigns.get()) {
			return null;
		}
		for (BlockEntity entity : chunk.getBlockEntities().values()) {
			if (!(entity instanceof SignBlockEntity sign)) {
				continue;
			}
			if (hasText(sign) ) {
				return "written sign";
			}
		}
		return null;
	}

	private boolean hasText(SignBlockEntity sign) {
		for (var side : List.of(sign.getFrontText(), sign.getBackText())) {
			for (var line : side.getMessages(false)) {
				if (!line.getString().isBlank()) {
					return true;
				}
			}
		}
		return false;
	}

	private void flag(ChunkPos pos, String reason, int[] counts) {
		long key = ChunkPos.pack(pos.x(), pos.z());
		if (!flagged.add(key)) {
			return;
		}
		if (persist.get()) {
			String serverId = WorldRecordStore.currentServerId();
			String dimension = WorldRecordStore.currentDimension();
			if (serverId != null && dimension != null) {
				Map<String, String> details = new LinkedHashMap<>();
				details.put("reason", reason);
				STORE.put(serverId, new WorldRecordStore.Record(dimension, pos.x(), pos.z(),
						total(counts), System.currentTimeMillis(), details));
			}
		}
		if (chatFeedback.get()) {
			ChatUtil.info("Base found (" + reason + ")" + (showCoords.get()
					? " at " + pos.getMiddleBlockX() + ", " + pos.getMiddleBlockZ() : ""));
		}
	}

	private int total(int[] counts) {
		int sum = 0;
		for (int count : counts) {
			sum += count;
		}
		return sum;
	}

	/** Pulls this server and dimension's remembered chunks back onto the map. */
	private void loadKnown() {
		String serverId = WorldRecordStore.currentServerId();
		String dimension = WorldRecordStore.currentDimension();
		if (serverId == null || dimension == null) {
			return;
		}
		for (WorldRecordStore.Record record : STORE.records(serverId, dimension)) {
			flagged.add(ChunkPos.pack(record.chunkX(), record.chunkZ()));
		}
	}

	private void render() {
		if (flagged.isEmpty()) {
			return;
		}
		int line = color.get();
		int fill = ColorUtil.withAlpha(line, 40);
		int range = renderRange.getInt();
		ChunkPos here = ChunkPos.containing(mc().player.blockPosition());
		int floor = Math.max(minY.getInt(), mc().level.getMinY());
		int ceiling = Math.min(maxY.getInt(), mc().level.getMaxY());

		for (long key : flagged) {
			ChunkPos pos = ChunkPos.unpack(key);
			if (Math.abs(pos.x() - here.x()) > range || Math.abs(pos.z() - here.z()) > range) {
				continue;
			}
			BlockPos min = pos.getBlockAt(0, floor, 0);
			Render3D.box(new AABB(min.getX(), floor, min.getZ(),
					min.getX() + 16.0, ceiling, min.getZ() + 16.0),
					line, 1.5f, fill, throughWalls.get());
		}
	}
}

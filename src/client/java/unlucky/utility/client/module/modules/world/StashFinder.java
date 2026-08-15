package unlucky.utility.client.module.modules.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.UnluckyClientMod;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.ActionSetting;
import unlucky.utility.client.settings.BlockListSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.ItemUtil;
import unlucky.utility.client.util.PingSound;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.WorldRecordStore;

/**
 * Remembers where the storage is.
 *
 * <p><b>Recorded on arrival, never searched for.</b> The client is already told about every
 * container in a chunk when the chunk loads — that is what draws them — so finding stashes costs
 * one pass over data that was going to be parsed anyway. A module that instead swept loaded
 * chunks looking for chests would do orders of magnitude more work for exactly the same answer,
 * and would find nothing the arrival path did not already have.
 *
 * <p><b>Records are per world and stay that way.</b> Chunk coordinates mean nothing across a
 * dimension or a server, so {@link WorldRecordStore} keys by server identity, dimension and
 * chunk, and a singleplayer save is as separate from a server as two servers are from each
 * other. Nothing is uploaded and nothing is combined. Exports are files the player asked for.
 *
 * <p>Notifications fire on a <em>meaningful</em> change only: a chunk that was already recorded
 * and has not grown says nothing. Walking past your own base is the common case, and a module
 * that pinged every time you did would be turned off within a day.
 */
public class StashFinder extends Module {
	/** How often the minecart pass runs. Containers on rails move; the chunk pass cannot see them. */
	private static final int MINECART_INTERVAL = 40;

	public final BooleanSetting chests = add(new BooleanSetting("Chests", "Count chests", true));
	public final BooleanSetting trappedChests = add(new BooleanSetting("Trapped chests",
			"Count trapped chests", true));
	public final BooleanSetting barrels = add(new BooleanSetting("Barrels", "Count barrels", true));
	public final BooleanSetting shulkers = add(new BooleanSetting("Shulker boxes",
			"Count shulker boxes", true));
	public final BooleanSetting enderChests = add(new BooleanSetting("Ender chests",
			"Count ender chests", false));
	public final BooleanSetting furnaces = add(new BooleanSetting("Furnaces",
			"Count furnaces", false));
	public final BooleanSetting blastFurnaces = add(new BooleanSetting("Blast furnaces",
			"Count blast furnaces", false));
	public final BooleanSetting smokers = add(new BooleanSetting("Smokers", "Count smokers", false));
	public final BooleanSetting hoppers = add(new BooleanSetting("Hoppers", "Count hoppers", false));
	public final BooleanSetting dispensers = add(new BooleanSetting("Dispensers",
			"Count dispensers", false));
	public final BooleanSetting droppers = add(new BooleanSetting("Droppers",
			"Count droppers", false));
	public final BooleanSetting minecarts = add(new BooleanSetting("Storage minecarts",
			"Count chest and hopper minecarts", false));

	public final NumberSetting minimumContainers = add(new NumberSetting("Minimum containers",
			"Enabled containers a cluster needs before it is recorded", 4, 1, 64, 1));
	public final NumberSetting minimumShulkers = add(new NumberSetting("Minimum shulkers",
			"Optional separate shulker requirement", 0, 0, 64, 1));
	public final NumberSetting clusterRadius = add(new NumberSetting("Cluster radius",
			"Chunks merged either side before the thresholds are applied", 0, 0, 2, 1));
	public final NumberSetting minimumSpawnDistance = add(new NumberSetting("Minimum spawn distance",
			"Ignore records closer than this to world spawn", 0, 0, 10000, 50));
	public final BlockListSetting supportBlacklist = add(new BlockListSetting("Support blacklist",
			"Ignore clusters mostly standing on these blocks — right-click to pick", java.util.Set.of()));
	public final BooleanSetting updateKnown = add(new BooleanSetting("Update known records",
			"Refresh counts and last-seen time instead of adding a duplicate", true));

	public final BooleanSetting notifications = add(new BooleanSetting("Notifications",
			"Tell me about new records and ones that grew", true));
	public final ModeSetting notificationMode = add(new ModeSetting("Notification mode",
			"Where the notice appears", "Chat", "Chat", "Toast", "Both"), notifications::get);
	public final ModeSetting sound = add(new ModeSetting("Sound",
			"Sound played with a notification", "Pling", "Off", "Pling", "Level-up"),
			notifications::get);

	public final BooleanSetting persist = add(new BooleanSetting("Persist records",
			"Save records across sessions", true));

	public final ModeSetting renderMode = add(new ModeSetting("Render mode",
			"Nearest draws only the closest record; Recorded draws every one in range",
			"Recorded", "Off", "Nearest", "Recorded"));
	public final NumberSetting maxRenderDistance = add(new NumberSetting("Maximum render distance",
			"Cull records further than this", 2000, 64, 10000, 64), () -> !renderMode.is("Off"));
	public final NumberSetting hideNearDistance = add(new NumberSetting("Hide near distance",
			"Hide records this close, so there is no marker around your feet", 16, 0, 128, 1),
			() -> !renderMode.is("Off"));
	public final BooleanSetting tracer = add(new BooleanSetting("Tracer",
			"Draw a line to each rendered record", true), () -> !renderMode.is("Off"));
	public final BooleanSetting chunkColumn = add(new BooleanSetting("Chunk column",
			"Draw the qualifying chunk as a column", true), () -> !renderMode.is("Off"));
	public final BooleanSetting label = add(new BooleanSetting("Label",
			"Show count, distance, dimension and age", true), () -> !renderMode.is("Off"));
	public final ColorSetting markerColor = add(new ColorSetting("Marker color",
			"Shared tracer and column colour", 0xFFB05CFF), () -> !renderMode.is("Off"));

	public final ActionSetting exportJson = add(new ActionSetting("Export JSON",
			"Write this server's records to a JSON file", () -> export(true)));
	public final ActionSetting exportCsv = add(new ActionSetting("Export CSV",
			"Write this server's records to a CSV file", () -> export(false)));
	public final ActionSetting clearDimension = add(new ActionSetting("Clear current dimension",
			"Forget every record in the dimension you are in", () -> confirm(false)));
	public final ActionSetting clearServer = add(new ActionSetting("Clear current server",
			"Forget every record on this server", () -> confirm(true)));

	/**
	 * The records themselves. Static because the store outlives any one enable — records found
	 * this session should not vanish because the module was toggled off to look at something.
	 */
	private static final WorldRecordStore STORE = new WorldRecordStore("stashes");

	/**
	 * Per-chunk container counts for the world we are in, so a cluster can be evaluated against
	 * its neighbours without re-reading them. Rebuilt from the store on world change.
	 */
	private final Map<Long, Counts> counts = new HashMap<>();

	private int sinceMinecartPass;
	/** Both clear actions ask twice; this is the first ask. */
	private long clearArmedAt;
	private boolean clearArmedServer;

	/** What one chunk holds, split so the shulker threshold can be applied separately. */
	private record Counts(int total, int shulkers, int blacklistedSupport, int supported) {
		Counts plus(Counts other) {
			return new Counts(total + other.total, shulkers + other.shulkers,
					blacklistedSupport + other.blacklistedSupport, supported + other.supported);
		}
	}

	public StashFinder() {
		super("StashFinder", "Records chunks that are full of storage", Category.WORLD,
				ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected void onDisable() {
		counts.clear();
		STORE.saveDirty();
	}

	/**
	 * A chunk arrived.
	 *
	 * <p>Called from {@code ClientPacketListenerMixin}'s existing chunk-load path rather than a
	 * second injection into the same method — NewChunks already owns that hook and two handlers
	 * at one site have no ordering contract with each other.
	 */
	public void onChunkLoaded(ClientboundLevelChunkWithLightPacket packet) {
		if (mc().level == null) {
			return;
		}
		LevelChunk chunk = mc().level.getChunk(packet.getX(), packet.getZ());
		if (chunk == null) {
			return;
		}
		counts.put(ChunkPos.pack(packet.getX(), packet.getZ()), count(chunk));
		evaluate(new ChunkPos(packet.getX(), packet.getZ()));
	}

	@Override
	public void onTick() {
		if (mc().level == null || mc().player == null) {
			return;
		}
		if (minecarts.get() && ++sinceMinecartPass >= MINECART_INTERVAL) {
			sinceMinecartPass = 0;
			minecartPass();
		}
		if (persist.get()) {
			STORE.saveDirty();
		}
		render();
	}

	// ---- counting ----------------------------------------------------------

	/** Counts the enabled container types in one chunk, and what they are standing on. */
	private Counts count(LevelChunk chunk) {
		int total = 0;
		int shulkerCount = 0;
		int blacklisted = 0;
		int supported = 0;
		for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
			if (!wanted(entry.getValue())) {
				continue;
			}
			total++;
			if (entry.getValue() instanceof ShulkerBoxBlockEntity) {
				shulkerCount++;
			}
			supported++;
			if (supportBlacklist.contains(
					chunk.getBlockState(entry.getKey().below()).getBlock())) {
				blacklisted++;
			}
		}
		return new Counts(total, shulkerCount, blacklisted, supported);
	}

	/**
	 * Whether this block entity is a container type the player asked to count.
	 *
	 * <p>Trapped chests are tested before plain ones because they extend {@code ChestBlockEntity}
	 * — the same ordering trap Shader's classifier documents.
	 */
	private boolean wanted(BlockEntity blockEntity) {
		if (blockEntity instanceof TrappedChestBlockEntity) {
			return trappedChests.get();
		}
		return switch (blockEntity) {
			case ChestBlockEntity ignored -> chests.get();
			case BarrelBlockEntity ignored -> barrels.get();
			case ShulkerBoxBlockEntity ignored -> shulkers.get();
			case EnderChestBlockEntity ignored -> enderChests.get();
			case BlastFurnaceBlockEntity ignored -> blastFurnaces.get();
			case SmokerBlockEntity ignored -> smokers.get();
			case FurnaceBlockEntity ignored -> furnaces.get();
			case HopperBlockEntity ignored -> hoppers.get();
			case DropperBlockEntity ignored -> droppers.get();
			case DispenserBlockEntity ignored -> dispensers.get();
			default -> false;
		};
	}

	/**
	 * Adds storage minecarts to the chunks they are standing in.
	 *
	 * <p>Separate from the chunk pass and on a slow timer because a container on rails is an
	 * entity, not chunk data: it may arrive long after its chunk did, and it may leave. Counting
	 * it at chunk-load time would be counting something that is not there yet.
	 */
	private void minecartPass() {
		Map<Long, Integer> carts = new HashMap<>();
		for (Entity entity : mc().level.entitiesForRendering()) {
			if (entity instanceof AbstractMinecartContainer) {
				carts.merge(ChunkPos.pack(entity.chunkPosition().x(), entity.chunkPosition().z()), 1, Integer::sum);
			}
		}
		for (Map.Entry<Long, Integer> entry : carts.entrySet()) {
			Counts base = counts.getOrDefault(entry.getKey(), new Counts(0, 0, 0, 0));
			counts.put(entry.getKey(), base.plus(new Counts(entry.getValue(), 0, 0, 0)));
			evaluate(ChunkPos.unpack(entry.getKey()));
		}
	}

	// ---- recording ---------------------------------------------------------

	/**
	 * Decides whether a chunk (with its cluster neighbours) is worth recording, and records it.
	 *
	 * <p>The cluster is summed at evaluation time rather than stored merged, so changing the
	 * radius re-answers the question from data already held instead of needing every chunk
	 * revisited.
	 */
	private void evaluate(ChunkPos pos) {
		String serverId = WorldRecordStore.currentServerId();
		String dimension = WorldRecordStore.currentDimension();
		if (serverId == null || dimension == null) {
			return;
		}
		int radius = clusterRadius.getInt();
		Counts sum = new Counts(0, 0, 0, 0);
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				Counts neighbour = counts.get(ChunkPos.pack(pos.x() + dx, pos.z() + dz));
				if (neighbour != null) {
					sum = sum.plus(neighbour);
				}
			}
		}
		if (sum.total() < minimumContainers.getInt() || sum.shulkers() < minimumShulkers.getInt()) {
			return;
		}
		// "Primarily supported by" — a majority, not a single match. One chest on deepslate in a
		// stash built on stone brick is not a natural formation.
		if (sum.supported() > 0 && sum.blacklistedSupport() * 2 > sum.supported()) {
			return;
		}
		if (tooCloseToSpawn(pos)) {
			return;
		}

		WorldRecordStore.Record existing = STORE.get(serverId, dimension, pos.x(), pos.z());
		if (existing != null && !updateKnown.get()) {
			return;
		}
		Map<String, String> details = new LinkedHashMap<>();
		details.put("shulkers", String.valueOf(sum.shulkers()));
		WorldRecordStore.Record record = new WorldRecordStore.Record(dimension, pos.x(), pos.z(),
				sum.total(), System.currentTimeMillis(), details);
		boolean meaningful = STORE.put(serverId, record);
		if (meaningful && notifications.get()) {
			notifyFound(record);
		}
	}

	/** Whether this chunk is inside the "ignore near spawn" ring. */
	private boolean tooCloseToSpawn(ChunkPos pos) {
		int minimum = minimumSpawnDistance.getInt();
		if (minimum <= 0 || mc().level == null) {
			return false;
		}
		BlockPos spawn = mc().level.getRespawnData().pos();
		double dx = pos.getMiddleBlockX() - spawn.getX();
		double dz = pos.getMiddleBlockZ() - spawn.getZ();
		return dx * dx + dz * dz < (double) minimum * minimum;
	}

	private void notifyFound(WorldRecordStore.Record record) {
		String title = "StashFinder";
		String body = record.count() + " containers at chunk " + record.chunkX() + ", " + record.chunkZ()
				+ " (" + record.chunkX() * 16 + ", " + record.chunkZ() * 16 + ")";
		if (!notificationMode.is("Toast")) {
			ChatUtil.info(title + ": " + body);
		}
		if (!notificationMode.is("Chat")) {
			UnluckyClient.INSTANCE.notifications.add(title, body,
					ItemUtil.icon(net.minecraft.world.item.Items.CHEST));
		}
		switch (sound.get()) {
			case "Pling" -> PingSound.play("Pling", 1.0f);
			case "Level-up" -> PingSound.play("Orb", 1.2f);
			default -> { }
		}
	}

	// ---- rendering ---------------------------------------------------------

	private void render() {
		if (renderMode.is("Off")) {
			return;
		}
		List<WorldRecordStore.Record> visible = inRange();
		if (visible.isEmpty()) {
			return;
		}
		if (renderMode.is("Nearest")) {
			visible = List.of(visible.get(0));
		}
		int color = markerColor.get();
		Vec3 eye = mc().player.getEyePosition();
		for (WorldRecordStore.Record record : visible) {
			double x = record.chunkX() * 16 + 8;
			double z = record.chunkZ() * 16 + 8;
			if (chunkColumn.get()) {
				Render3D.box(new net.minecraft.world.phys.AABB(x - 8, mc().level.getMinY(), z - 8,
						x + 8, mc().level.getMaxY(), z + 8),
						color, 1.5f, ColorUtil.withAlpha(color, 20), true);
			}
			if (tracer.get()) {
				Render3D.line(eye, new Vec3(x, mc().player.getY(), z), color, 1.5f, true);
			}
			if (label.get()) {
				Render3D.blockLabel(describe(record), BlockPos.containing(x, mc().player.getY() + 1, z),
						color, 1.0f);
			}
		}
	}

	/**
	 * Records within the render distance, nearest first.
	 *
	 * <p>Read from the store rather than a render-time scan of the world, which is the whole
	 * point of recording them: the picture costs a walk of a list that is usually a few dozen
	 * long, not a search.
	 */
	private List<WorldRecordStore.Record> inRange() {
		String serverId = WorldRecordStore.currentServerId();
		String dimension = WorldRecordStore.currentDimension();
		if (serverId == null || dimension == null) {
			return List.of();
		}
		double max = maxRenderDistance.get();
		double min = hideNearDistance.get();
		List<WorldRecordStore.Record> out = new ArrayList<>();
		for (WorldRecordStore.Record record : STORE.records(serverId, dimension)) {
			double distance = distanceTo(record);
			if (distance >= min && distance <= max) {
				out.add(record);
			}
		}
		out.sort(Comparator.comparingDouble(this::distanceTo));
		return out;
	}

	private double distanceTo(WorldRecordStore.Record record) {
		double dx = record.chunkX() * 16 + 8 - mc().player.getX();
		double dz = record.chunkZ() * 16 + 8 - mc().player.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	private String describe(WorldRecordStore.Record record) {
		long ageMinutes = (System.currentTimeMillis() - record.lastSeen()) / 60_000L;
		return record.count() + "x  " + (int) distanceTo(record) + "m  " + ageMinutes + "m ago";
	}

	// ---- commands and actions ----------------------------------------------

	/** {@code .stashes list} — every record in the current dimension, nearest first. */
	public void list(Consumer<String> out) {
		List<WorldRecordStore.Record> records = inRangeIgnoringSettings();
		if (records.isEmpty()) {
			out.accept("No stash records for this dimension.");
			return;
		}
		for (WorldRecordStore.Record record : records) {
			out.accept(record.count() + " containers at " + record.chunkX() * 16 + ", "
					+ record.chunkZ() * 16 + " (" + (int) distanceTo(record) + "m)");
		}
	}

	/** {@code .stashes nearest} — the closest one, or a plain answer that there is none. */
	public void nearest(Consumer<String> out) {
		List<WorldRecordStore.Record> records = inRangeIgnoringSettings();
		out.accept(records.isEmpty() ? "No stash records for this dimension."
				: "Nearest: " + records.get(0).count() + " containers at "
						+ records.get(0).chunkX() * 16 + ", " + records.get(0).chunkZ() * 16
						+ " (" + (int) distanceTo(records.get(0)) + "m)");
	}

	/** Removes the record for the chunk the player is standing in. */
	public void removeHere(Consumer<String> out) {
		String serverId = WorldRecordStore.currentServerId();
		String dimension = WorldRecordStore.currentDimension();
		if (serverId == null || dimension == null) {
			out.accept("No world.");
			return;
		}
		ChunkPos pos = mc().player.chunkPosition();
		out.accept(STORE.remove(serverId, dimension, pos.x(), pos.z())
				? "Forgot the record for this chunk."
				: "No record for this chunk.");
		STORE.saveDirty();
	}

	/** Distance-unfiltered listing, for the commands — a read-out should show everything. */
	private List<WorldRecordStore.Record> inRangeIgnoringSettings() {
		String serverId = WorldRecordStore.currentServerId();
		String dimension = WorldRecordStore.currentDimension();
		if (serverId == null || dimension == null || mc().player == null) {
			return List.of();
		}
		List<WorldRecordStore.Record> out = new ArrayList<>(STORE.records(serverId, dimension));
		out.sort(Comparator.comparingDouble(this::distanceTo));
		return out;
	}

	/**
	 * Both clear actions ask twice.
	 *
	 * <p>A single click that deletes an afternoon of exploring is the wrong shape for a button
	 * that sits in a settings panel next to the colour pickers.
	 */
	private void confirm(boolean server) {
		long now = System.currentTimeMillis();
		if (clearArmedAt == 0 || now - clearArmedAt > 5_000L || clearArmedServer != server) {
			clearArmedAt = now;
			clearArmedServer = server;
			ChatUtil.info("Click again within five seconds to clear "
					+ (server ? "every record on this server." : "this dimension's records."));
			return;
		}
		clearArmedAt = 0;
		String serverId = WorldRecordStore.currentServerId();
		if (serverId == null) {
			ChatUtil.info("No world.");
			return;
		}
		int removed = server ? STORE.clearServer(serverId)
				: STORE.clearDimension(serverId, WorldRecordStore.currentDimension());
		STORE.saveDirty();
		counts.clear();
		ChatUtil.info("Cleared " + removed + " stash records.");
	}

	/**
	 * Writes the current server and dimension's records to a file.
	 *
	 * <p>Negative coordinates are written as plain signed integers in both formats, which is the
	 * one thing an export of chunk positions has to get right and the one thing a naive CSV
	 * writer gets wrong by quoting them.
	 */
	private void export(boolean json) {
		String serverId = WorldRecordStore.currentServerId();
		String dimension = WorldRecordStore.currentDimension();
		if (serverId == null || dimension == null) {
			ChatUtil.info("Join a world first.");
			return;
		}
		List<WorldRecordStore.Record> records = STORE.records(serverId, dimension);
		if (records.isEmpty()) {
			ChatUtil.info("Nothing to export for this dimension.");
			return;
		}
		Path directory = FabricLoader.getInstance().getGameDir().resolve("unlucky-exports");
		Path file = directory.resolve("stashes-" + serverId + "-"
				+ dimension.replace(':', '_').replace('/', '_') + (json ? ".json" : ".csv"));
		StringBuilder text = new StringBuilder();
		if (json) {
			text.append("[\n");
			for (int i = 0; i < records.size(); i++) {
				WorldRecordStore.Record record = records.get(i);
				text.append("  {\"dimension\":\"").append(record.dimension())
						.append("\",\"chunkX\":").append(record.chunkX())
						.append(",\"chunkZ\":").append(record.chunkZ())
						.append(",\"blockX\":").append(record.chunkX() * 16)
						.append(",\"blockZ\":").append(record.chunkZ() * 16)
						.append(",\"count\":").append(record.count())
						.append(",\"lastSeen\":").append(record.lastSeen())
						.append('}').append(i + 1 < records.size() ? "," : "").append('\n');
			}
			text.append("]\n");
		} else {
			text.append("dimension,chunkX,chunkZ,blockX,blockZ,count,lastSeen\n");
			for (WorldRecordStore.Record record : records) {
				text.append(record.dimension()).append(',').append(record.chunkX()).append(',')
						.append(record.chunkZ()).append(',').append(record.chunkX() * 16).append(',')
						.append(record.chunkZ() * 16).append(',').append(record.count()).append(',')
						.append(record.lastSeen()).append('\n');
			}
		}
		try {
			Files.createDirectories(directory);
			Files.writeString(file, text.toString());
			ChatUtil.info("Exported " + records.size() + " records to " + file.getFileName());
		} catch (IOException e) {
			UnluckyClientMod.LOGGER.error("Failed to export stash records", e);
			ChatUtil.info("Export failed — see the log.");
		}
	}
}

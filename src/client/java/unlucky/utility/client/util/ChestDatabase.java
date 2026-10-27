package unlucky.utility.client.util;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import unlucky.utility.client.UnluckyClientMod;

/**
 * ChestManager's record of what is in which chest, one file per server and dimension.
 *
 * <p><b>One entry per container, not per block.</b> A double chest is stored once, under the
 * lower of its two halves, with the other half beside it, so tagging either half finds the
 * same entry and its 54 slots are never counted twice. Whether two chests form a double is
 * read from the blocks themselves ({@link #partner}), never guessed from adjacency: two single
 * chests side by side, or facing different ways, stay two entries.
 *
 * <p><b>Nested contents are kept.</b> A shulker box in a chest arrives with its 27 slots
 * already inside the open-container packet, and a bundle with its own list, so both are
 * recorded one level down without ever opening anything. That is what makes a full double
 * chest of full shulkers (54 × 27 = 1,458 stacks) cost no more to scan than an empty one.
 *
 * <p><b>On disk</b> it is compact JSON, gzipped, written off the game thread and moved into
 * place atomically, as {@link WorldRecordStore} does: a crash mid-save leaves the last good
 * file. A full double chest of full shulkers comes to a few kilobytes.
 */
public final class ChestDatabase {
	private static final Gson GSON = new Gson();
	/** Bumped when the on-disk shape changes; an older file is skipped rather than guessed at. */
	private static final int FORMAT = 1;
	/** Ticks a change waits before it is written, so a burst of scans is one write. */
	private static final int SAVE_DELAY = 40;
	/** How far into nested containers we look: chest → shulker → bundle. */
	private static final int MAX_DEPTH = 2;

	private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "unlucky-chest-db");
		thread.setDaemon(true);
		return thread;
	});

	/**
	 * One stack, where it sat, and what was inside it.
	 *
	 * @param id    item id, with the {@code minecraft:} namespace dropped to keep the file small
	 * @param name  the custom name, or null — a shulker labelled "Obsidian" is searchable by it
	 * @param ench  enchantment ids (plain and stored), or null
	 * @param inner a shulker's or bundle's contents, or null
	 */
	public record Stored(int slot, String id, int count, String name, List<String> ench, List<Stored> inner) {
	}

	/**
	 * One tracked container. {@code other} is the second half of a double chest, or null.
	 * {@code facing} is the chest's or barrel's direction (lower case), so the ChestManager
	 * screen can draw it the right way round while it is unloaded; null in older files.
	 */
	public record Entry(BlockPos pos, BlockPos other, String block, long scanned, int size, List<Stored> items,
			String facing) {
		public boolean isDouble() {
			return other != null;
		}

		public int usedSlots() {
			return items.size();
		}
	}

	private record EntryDto(int x, int y, int z, Integer ox, Integer oy, Integer oz, String block, long scanned,
			int size, List<Stored> items, String facing) {
	}

	private record FileDto(int format, List<EntryDto> chests) {
	}

	/** Keyed by the canonical half's {@code asLong}. */
	private static final Map<Long, Entry> entries = new LinkedHashMap<>();
	/** Either half's {@code asLong} → the canonical key. */
	private static final Map<Long, Long> halves = new HashMap<>();
	// the Chests panel reads names from its worker thread too
	private static final Map<Item, String> itemNames = new java.util.concurrent.ConcurrentHashMap<>();

	private static String server;
	private static String dimension;
	private static boolean dirty;
	private static int saveIn;
	/** Bumped on every change, so callers can tell their cached search is stale. */
	private static int revision;
	/** One other dimension's chests, read-only, for the GUI's dimension switch. */
	private static String otherKey;
	private static List<Entry> otherEntries = List.of();

	static {
		Runtime.getRuntime().addShutdownHook(new Thread(ChestDatabase::flushNow, "unlucky-chest-db-save"));
	}

	private ChestDatabase() {
	}

	// ---- identity ------------------------------------------------------------------

	/**
	 * Follows the player between servers and dimensions: writes out what is pending for the
	 * old world, then loads the new one. Called every tick by ChestManager and before every
	 * command, so neither ever reads another world's chests.
	 */
	public static void sync() {
		String nowServer = WorldRecordStore.currentServerId();
		String nowDimension = WorldRecordStore.currentDimension();
		if (java.util.Objects.equals(nowServer, server) && java.util.Objects.equals(nowDimension, dimension)) {
			return;
		}
		flush();
		if (server != null && dimension != null) {
			// what was live a moment ago is the freshest copy of that file; the write may not have landed
			otherKey = server + "/" + safe(dimension);
			otherEntries = List.copyOf(entries.values());
		}
		entries.clear();
		halves.clear();
		server = nowServer;
		dimension = nowDimension;
		revision++;
		if (server != null && dimension != null) {
			load();
		}
	}

	/** Whether a world is loaded to record into. */
	public static boolean ready() {
		return server != null && dimension != null;
	}

	public static int revision() {
		return revision;
	}

	/** The current dimension as its file name, or null. */
	public static String currentDimension() {
		return dimension == null ? null : safe(dimension);
	}

	/**
	 * Every dimension this world has a record for, as file names: the vanilla three first, then
	 * the rest by name. The current dimension is always listed, recorded or not.
	 */
	public static List<String> dimensions() {
		List<String> found = new ArrayList<>();
		if (server == null) {
			return found;
		}
		Path dir = file(server, "x").getParent();
		if (Files.isDirectory(dir)) {
			try (var files = Files.list(dir)) {
				files.map(path -> path.getFileName().toString())
						.filter(name -> name.endsWith(SUFFIX))
						.map(name -> name.substring(0, name.length() - SUFFIX.length()))
						.forEach(found::add);
			} catch (IOException e) {
				UnluckyClientMod.LOGGER.warn("Couldn't list chest records in {}", dir, e);
			}
		}
		String current = currentDimension();
		if (current != null && !found.contains(current)) {
			found.add(current);
		}
		List<String> vanilla = List.of("minecraft_overworld", "minecraft_the_nether", "minecraft_the_end");
		found.sort(java.util.Comparator.<String>comparingInt(name -> {
			int index = vanilla.indexOf(name);
			return index < 0 ? vanilla.size() : index;
		}).thenComparing(name -> name));
		return found;
	}

	/**
	 * The chests recorded in another dimension of this world, read-only. The current dimension
	 * answers with the live set; any other is read from its file once and kept until the view
	 * moves on, so flicking between tabs doesn't re-read the disk every frame.
	 */
	public static List<Entry> entriesIn(String safeDimension) {
		if (server == null || safeDimension == null) {
			return List.of();
		}
		if (safeDimension.equals(currentDimension())) {
			return List.copyOf(entries.values());
		}
		String key = server + "/" + safeDimension;
		if (!key.equals(otherKey)) {
			otherKey = key;
			otherEntries = read(file(server, safeDimension));
		}
		return otherEntries;
	}

	// ---- reading -------------------------------------------------------------------

	public static Collection<Entry> all() {
		return entries.values();
	}

	public static int size() {
		return entries.size();
	}

	/** The entry covering this block, through either half of a double chest. */
	public static Entry at(BlockPos pos) {
		Long key = halves.get(pos.asLong());
		return key == null ? null : entries.get(key);
	}

	// ---- writing -------------------------------------------------------------------

	public static void put(Entry entry) {
		// A chest that has since become a double (or stopped being one) is recorded under a
		// different key now; whatever covered either half before is replaced, not kept.
		remove(entry.pos());
		if (entry.other() != null) {
			remove(entry.other());
		}
		long key = entry.pos().asLong();
		entries.put(key, entry);
		halves.put(key, key);
		if (entry.other() != null) {
			halves.put(entry.other().asLong(), key);
		}
		changed();
	}

	/** Forgets the entry covering this block. Returns it, or null when nothing did. */
	public static Entry remove(BlockPos pos) {
		Long key = halves.get(pos.asLong());
		if (key == null) {
			return null;
		}
		Entry removed = entries.remove(key);
		if (removed != null) {
			halves.remove(removed.pos().asLong());
			if (removed.other() != null) {
				halves.remove(removed.other().asLong());
			}
			changed();
		}
		return removed;
	}

	public static int clear() {
		int count = entries.size();
		entries.clear();
		halves.clear();
		changed();
		return count;
	}

	private static void changed() {
		dirty = true;
		saveIn = SAVE_DELAY;
		revision++;
	}

	/** Counts down to the delayed save. Called once a tick. */
	public static void tick() {
		if (dirty && --saveIn <= 0) {
			flush();
		}
	}

	// ---- blocks --------------------------------------------------------------------

	/** Chests (trapped and copper too) and barrels: what ChestManager tracks. */
	public static boolean trackable(BlockState state) {
		return state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock;
	}

	/**
	 * The other half of a double chest, or null for anything that is not one.
	 *
	 * <p>Both halves have to agree. The neighbour the type property points at must be the same
	 * block, with the opposite chest type, pointing straight back — so a single chest beside
	 * another, a chest beside a trapped chest, or a half-updated pair mid-placement all read as
	 * singles, which is what the server will actually open them as.
	 */
	public static BlockPos partner(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
			return null;
		}
		BlockPos other = ChestBlock.getConnectedBlockPos(pos, state);
		BlockState otherState = level.getBlockState(other);
		if (otherState.getBlock() != state.getBlock()
				|| otherState.getValue(ChestBlock.TYPE) != state.getValue(ChestBlock.TYPE).getOpposite()
				|| !ChestBlock.getConnectedBlockPos(other, otherState).equals(pos)) {
			return null;
		}
		return other.immutable();
	}

	/** The half a container is stored under: itself, or the lower of a double chest's two. */
	public static BlockPos canonical(BlockPos pos, BlockPos other) {
		if (other == null) {
			return pos.immutable();
		}
		return pos.compareTo(other) <= 0 ? pos.immutable() : other.immutable();
	}

	// ---- recording -----------------------------------------------------------------

	/** One stack as stored, with its nested contents, or null for an empty slot. */
	public static Stored record(int slot, ItemStack stack) {
		return record(slot, stack, 0);
	}

	private static Stored record(int slot, ItemStack stack, int depth) {
		if (stack.isEmpty()) {
			return null;
		}
		String name = stack.has(DataComponents.CUSTOM_NAME) ? stack.getHoverName().getString() : null;
		List<String> ench = enchantments(stack);
		List<Stored> inner = null;
		if (depth < MAX_DEPTH) {
			inner = nested(stack, depth + 1);
		}
		return new Stored(slot, shortId(stack.getItem()), stack.getCount(), name, ench, inner);
	}

	private static List<Stored> nested(ItemStack stack, int depth) {
		List<ItemStack> contents = contents(stack);
		if (contents.isEmpty()) {
			return null;
		}
		List<Stored> out = new ArrayList<>();
		for (int i = 0; i < contents.size(); i++) {
			Stored stored = record(i, contents.get(i), depth);
			if (stored != null) {
				out.add(stored);
			}
		}
		return out.isEmpty() ? null : out;
	}

	/** A shulker's slots or a bundle's list; empty for anything else. */
	public static List<ItemStack> contents(ItemStack stack) {
		ItemContainerContents container = stack.get(DataComponents.CONTAINER);
		if (container != null) {
			return container.itemCopies().toList();
		}
		BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
		if (bundle != null) {
			return bundle.itemCopies().toList();
		}
		return List.of();
	}

	private static List<String> enchantments(ItemStack stack) {
		List<String> out = null;
		for (ItemEnchantments enchantments : new ItemEnchantments[] {
				stack.get(DataComponents.ENCHANTMENTS), stack.get(DataComponents.STORED_ENCHANTMENTS)}) {
			if (enchantments == null || enchantments.isEmpty()) {
				continue;
			}
			for (var entry : enchantments.entrySet()) {
				var key = entry.getKey().unwrapKey();
				if (key.isPresent()) {
					if (out == null) {
						out = new ArrayList<>();
					}
					out.add(shortId(key.get().identifier()));
				}
			}
		}
		return out;
	}

	public static String shortId(Item item) {
		return shortId(BuiltInRegistries.ITEM.getKey(item));
	}

	private static String shortId(Identifier id) {
		return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
	}

	/** The item a stored id names, or null when this game has no such item. */
	public static Item item(String id) {
		Identifier parsed = Identifier.tryParse(id);
		if (parsed == null) {
			return null;
		}
		return BuiltInRegistries.ITEM.getOptional(parsed).orElse(null);
	}

	/** An item's display name in the game's language, lowercased; cached. */
	public static String itemName(Item item) {
		return itemNames.computeIfAbsent(item,
				i -> i.getDefaultInstance().getHoverName().getString().toLowerCase(Locale.ROOT));
	}

	// ---- searching -----------------------------------------------------------------

	/**
	 * What the player typed, matched against item ids, display names, custom names and
	 * enchantments. "totem", "Totem of Undying", "totem_of" and "mending" all work.
	 */
	public static final class Query {
		private final String text;
		private final String asId;
		private final String raw;
		private final boolean exact;

		public Query(String raw) {
			this(raw, false);
		}

		private Query(String raw, boolean exact) {
			this.raw = raw.trim();
			this.text = this.raw.toLowerCase(Locale.ROOT);
			this.asId = text.replace(' ', '_');
			this.exact = exact;
		}

		/** One item and nothing else, by its short id: what picking a result in the Chests panel shows. */
		public static Query exact(String id) {
			return new Query(id, true);
		}

		public boolean isExact() {
			return exact;
		}

		public String raw() {
			return raw;
		}

		public boolean isEmpty() {
			return text.isEmpty();
		}

		/** Whether a stored stack itself (not its contents) matches. */
		public boolean matches(Stored stored) {
			if (exact) {
				return stored.id().equals(raw);
			}
			if (stored.id().contains(asId) || (stored.name() != null && stored.name().toLowerCase(Locale.ROOT).contains(text))) {
				return true;
			}
			Item item = item(stored.id());
			if (item != null && itemName(item).contains(text)) {
				return true;
			}
			return anyEnchant(stored.ench());
		}

		/** Whether a live stack itself (not its contents) matches. */
		public boolean matches(ItemStack stack) {
			if (stack.isEmpty()) {
				return false;
			}
			if (exact) {
				return shortId(stack.getItem()).equals(raw);
			}
			if (shortId(stack.getItem()).contains(asId) || itemName(stack.getItem()).contains(text)) {
				return true;
			}
			if (stack.has(DataComponents.CUSTOM_NAME)
					&& stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(text)) {
				return true;
			}
			return anyEnchant(enchantments(stack));
		}

		/** A live stack, or anything inside it down to bundle depth. */
		public boolean matchesDeep(ItemStack stack) {
			return matchesDeep(stack, 0);
		}

		private boolean matchesDeep(ItemStack stack, int depth) {
			if (matches(stack)) {
				return true;
			}
			if (depth >= MAX_DEPTH) {
				return false;
			}
			for (ItemStack inner : contents(stack)) {
				if (!inner.isEmpty() && matchesDeep(inner, depth + 1)) {
					return true;
				}
			}
			return false;
		}

		private boolean anyEnchant(List<String> ench) {
			if (ench == null) {
				return false;
			}
			for (String id : ench) {
				if (id.contains(asId)) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * How much of a query one container holds.
	 *
	 * @param loose    matching items lying in the container's own slots
	 * @param nested   matching items inside shulkers or bundles in it
	 * @param boxes    how many of its slots hold a box with a match inside
	 * @param top      id of the matching item with the largest count, for the label
	 * @param slots    the container's own slots that match or hold a match
	 */
	public record Tally(int loose, int nested, int boxes, String top, List<Integer> slots) {
		public int total() {
			return loose + nested;
		}
	}

	/** Null when nothing in the entry matches. */
	public static Tally tally(Entry entry, Query query) {
		int[] counts = new int[3];
		Map<String, Integer> byId = new HashMap<>();
		List<Integer> slots = new ArrayList<>();
		for (Stored stored : entry.items()) {
			boolean hit = false;
			if (query.matches(stored)) {
				counts[0] += stored.count();
				byId.merge(stored.id(), stored.count(), Integer::sum);
				hit = true;
			}
			int inside = countNested(stored.inner(), query, byId);
			if (inside > 0) {
				counts[1] += inside;
				counts[2]++;
				hit = true;
			}
			if (hit) {
				slots.add(stored.slot());
			}
		}
		if (slots.isEmpty()) {
			return null;
		}
		String top = byId.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
		return new Tally(counts[0], counts[1], counts[2], top, slots);
	}

	private static int countNested(List<Stored> inner, Query query, Map<String, Integer> byId) {
		if (inner == null) {
			return 0;
		}
		int total = 0;
		for (Stored stored : inner) {
			if (query.matches(stored)) {
				total += stored.count();
				byId.merge(stored.id(), stored.count(), Integer::sum);
			}
			total += countNested(stored.inner(), query, byId);
		}
		return total;
	}

	/** Every item id in this dimension's chests, nested ones included — for tab completion. */
	public static List<String> knownIds() {
		java.util.TreeSet<String> ids = new java.util.TreeSet<>();
		for (Entry entry : entries.values()) {
			collectIds(entry.items(), ids);
		}
		return new ArrayList<>(ids);
	}

	private static void collectIds(List<Stored> items, java.util.Set<String> ids) {
		if (items == null) {
			return;
		}
		for (Stored stored : items) {
			ids.add(stored.id());
			collectIds(stored.inner(), ids);
		}
	}

	// ---- disk ----------------------------------------------------------------------

	private static final String SUFFIX = ".json.gz";

	private static String safe(String dimensionId) {
		return dimensionId.replace(':', '_').replace('/', '_');
	}

	private static Path file(String serverId, String dimensionId) {
		return FabricLoader.getInstance().getConfigDir().resolve("unlucky").resolve("chests")
				.resolve(serverId).resolve(safe(dimensionId) + SUFFIX);
	}

	private static void load() {
		List<Entry> loaded = read(file(server, dimension));
		for (Entry entry : loaded) {
			long key = entry.pos().asLong();
			entries.put(key, entry);
			halves.put(key, key);
			if (entry.other() != null) {
				halves.put(entry.other().asLong(), key);
			}
		}
		if (!loaded.isEmpty()) {
			revision++;
		}
	}

	private static List<Entry> read(Path path) {
		if (!Files.isRegularFile(path)) {
			return List.of();
		}
		try (Reader reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(path)),
				StandardCharsets.UTF_8)) {
			FileDto dto = GSON.fromJson(reader, FileDto.class);
			if (dto == null || dto.format() != FORMAT || dto.chests() == null) {
				return List.of();
			}
			List<Entry> out = new ArrayList<>(dto.chests().size());
			for (EntryDto e : dto.chests()) {
				BlockPos pos = new BlockPos(e.x(), e.y(), e.z());
				BlockPos other = e.ox() == null ? null : new BlockPos(e.ox(), e.oy(), e.oz());
				out.add(new Entry(pos, other, e.block(), e.scanned(), e.size(),
						e.items() == null ? List.of() : e.items(), e.facing()));
			}
			return out;
		} catch (IOException | RuntimeException e) {
			UnluckyClientMod.LOGGER.warn("Couldn't read chest database {}", path, e);
			return List.of();
		}
	}

	/** Serialises on this thread, writes on the writer thread. */
	public static void flush() {
		if (!dirty || server == null || dimension == null) {
			dirty = false;
			return;
		}
		dirty = false;
		Path path = file(server, dimension);
		String json = serialise();
		WRITER.execute(() -> write(path, json));
	}

	private static void flushNow() {
		WRITER.shutdown();
		try {
			WRITER.awaitTermination(3, TimeUnit.SECONDS);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
		if (dirty && server != null && dimension != null) {
			dirty = false;
			write(file(server, dimension), serialise());
		}
	}

	private static String serialise() {
		List<EntryDto> out = new ArrayList<>(entries.size());
		for (Entry e : entries.values()) {
			BlockPos o = e.other();
			out.add(new EntryDto(e.pos().getX(), e.pos().getY(), e.pos().getZ(),
					o == null ? null : o.getX(), o == null ? null : o.getY(), o == null ? null : o.getZ(),
					e.block(), e.scanned(), e.size(), e.items(), e.facing()));
		}
		return GSON.toJson(new FileDto(FORMAT, out));
	}

	private static void write(Path path, String json) {
		try {
			Files.createDirectories(path.getParent());
			Path temp = path.resolveSibling(path.getFileName() + ".tmp");
			try (Writer writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(temp)),
					StandardCharsets.UTF_8)) {
				writer.write(json);
			}
			Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			UnluckyClientMod.LOGGER.warn("Couldn't save chest database {}", path, e);
		}
	}

	/** A chest's or barrel's facing, lower case, or null for anything without one. */
	public static String facing(BlockState state) {
		if (state.hasProperty(ChestBlock.FACING)) {
			return state.getValue(ChestBlock.FACING).getSerializedName();
		}
		if (state.hasProperty(BarrelBlock.FACING)) {
			return state.getValue(BarrelBlock.FACING).getSerializedName();
		}
		return null;
	}

	/** Registry key used in the {@code block} field, for the list read-out. */
	public static String blockId(BlockState state) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		return id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
	}
}

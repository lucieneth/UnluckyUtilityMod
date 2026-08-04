package unlucky.utility.client.util;

import java.util.Set;
import java.util.TreeSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;


/**
 * The block categories the XRay/Search presets are built from, <b>asked of the registry
 * rather than written down</b>.
 *
 * <p>A hand-written id list is wrong the moment the game ships a block nobody anticipated,
 * and it is wrong silently: the preset still works, it just quietly stops covering
 * something. The old lists had already drifted — {@code PRESET_STORAGE} named
 * {@code minecraft:shulker_box} and therefore covered exactly one of the seventeen
 * shulker boxes in the game.
 *
 * <p>These are <em>presets</em>, not answers. Since the block picker became the whole
 * registry with a search box, a category that misses one block costs a search rather than
 * a release, which is what makes derivation the right trade here — the rules below are
 * allowed to be approximate as long as they are never empty.
 *
 * <p><b>Not tags.</b> Block tags would be the obvious mechanism and they are the wrong one
 * here: tags are datapack state synced from the server, so they are unbound on the title
 * screen and the {@code c:} conventional tags exist only if the <em>server</em> runs Fabric
 * API — which an anarchy server does not. Everything below reads only the static registry,
 * so it answers the same in the main menu as it does on 2b2t.
 *
 * <p>Computed once and cached: registry contents cannot change after mod init.
 */
public final class BlockGroups {
	/**
	 * The one ore that no rule finds.
	 *
	 * <p>It does not end in {@code _ore} and drops no experience, so it matches neither
	 * signal. There is no shape to appeal to — "the block netherite comes from" is a fact
	 * about the recipe tree, not about the block — so it is named, and resolved through the
	 * registry so a version that drops it leaves the set correct instead of carrying a dead
	 * id.
	 */
	private static final String ANCIENT_DEBRIS = "minecraft:ancient_debris";

	/**
	 * Curated on purpose, and the one list here that should stay that way.
	 *
	 * <p>"Valuable" is a judgement about what is worth flying across a world for, not a
	 * property a block has; there is nothing in the registry to ask. Deriving it would mean
	 * inventing a rule that encodes this list anyway and then breaks differently. Dyed
	 * variants are still expanded from the registry below, which is the part that actually
	 * went stale.
	 */
	private static final String[] VALUABLES = {
			"minecraft:diamond_block", "minecraft:emerald_block", "minecraft:gold_block",
			"minecraft:iron_block", "minecraft:netherite_block", "minecraft:ancient_debris",
			"minecraft:spawner", "minecraft:enchanting_table", "minecraft:beacon",
			"minecraft:amethyst_cluster", "minecraft:budding_amethyst"};

	/**
	 * Its block entity is not a {@link Container}, so the container rule cannot see it —
	 * an ender chest is a window onto an inventory that lives on the player.
	 */
	private static final String ENDER_CHEST = "minecraft:ender_chest";

	private static Set<String> ores;
	private static Set<String> storage;
	private static Set<String> valuables;
	/** Whether {@link #storage()} has run its probe for real, rather than declining to. */
	private static boolean storageProbed;

	private BlockGroups() {
	}

	/**
	 * Every ore.
	 *
	 * <p>The rule is the {@code _ore} suffix — Mojang's naming for every ore in the game,
	 * deepslate and nether variants included, for as long as there have been ores, and the
	 * convention essentially every mod follows too. In 26.2 it reproduces the hand-written
	 * list it replaced exactly.
	 *
	 * <p><b>Not {@code DropExperienceBlock}</b>, which was the obvious widener and is a trap:
	 * it is a behaviour, not a category, and {@code SculkBlock} extends it. That put sculk in
	 * the Ores preset and therefore in XRay's default visible set, which means an ancient
	 * city stays opaque while you are X-raying it. The trade is deliberate — a modded ore
	 * that names itself something else is one search away in a picker that lists the whole
	 * registry, whereas a false positive quietly degrades the feature for everyone and looks
	 * like a rendering bug rather than a bad list.
	 */
	public static Set<String> ores() {
		if (ores == null) {
			Set<String> ids = new TreeSet<>();
			for (Block block : BuiltInRegistries.BLOCK) {
				Identifier key = BuiltInRegistries.BLOCK.getKey(block);
				if (key != null && key.getPath().endsWith("_ore")) {
					ids.add(key.toString());
				}
			}
			addIfPresent(ids, ANCIENT_DEBRIS);
			ores = Set.copyOf(ids);
		}
		return ores;
	}

	/**
	 * Everything that holds items — asked of the block, by building its block entity and
	 * seeing whether it is a {@link Container}.
	 *
	 * <p>Wider than the list it replaces, and meant to be: it picks up all seventeen shulker
	 * boxes and all eight copper chests rather than one and none, and every modded chest for
	 * free. It also picks up lecterns, jukeboxes and decorated pots, which hold exactly one
	 * item each and are containers by the only definition available here. The tab is a filter
	 * over the full catalog, so being generous costs a longer list and nothing else.
	 *
	 * <p><b>Empty until a world is loaded, deliberately.</b> Unlike {@link #ores()} and
	 * {@link #valuables()}, which only read names, this one has to <em>construct</em> a block
	 * entity — and {@code VaultBlockEntity} builds an {@link net.minecraft.world.item.ItemStack}
	 * in a static initialiser, which throws "Components not bound yet" before a world has
	 * synced its registries. Catching that is not enough: a failed static initialiser marks
	 * the class erroneous for the life of the JVM, so probing early would leave the vault
	 * permanently unclassifiable and the answer quietly wrong for the rest of the session.
	 * Refusing to answer is the honest failure; the picker says so, and the All tab still
	 * lists every block from the main menu.
	 */
	public static Set<String> storage() {
		boolean bound = ItemUtil.componentsBound();
		if (storage == null || (bound && !storageProbed)) {
			if (!bound) {
				return Set.of();
			}
			Set<String> ids = new TreeSet<>();
			for (Block block : BuiltInRegistries.BLOCK) {
				Identifier key = BuiltInRegistries.BLOCK.getKey(block);
				if (key != null && holdsItems(block)) {
					ids.add(key.toString());
				}
			}
			addIfPresent(ids, ENDER_CHEST);
			storage = Set.copyOf(ids);
			storageProbed = true;
		}
		return storage;
	}

	/** {@link #VALUABLES}, with every dyed shulker box the registry knows about. */
	public static Set<String> valuables() {
		if (valuables == null) {
			Set<String> ids = new TreeSet<>();
			for (String id : VALUABLES) {
				addIfPresent(ids, id);
			}
			for (Block block : BuiltInRegistries.BLOCK) {
				Identifier key = BuiltInRegistries.BLOCK.getKey(block);
				if (key != null && key.getPath().endsWith("shulker_box")) {
					ids.add(key.toString());
				}
			}
			valuables = Set.copyOf(ids);
		}
		return valuables;
	}

	/**
	 * Does this block's own block entity hold items?
	 *
	 * <p>{@code newBlockEntity} only constructs — it is handed a position and a state and no
	 * level — but it is arbitrary code, third-party for modded blocks, so anything it throws
	 * is treated as "not classifiable" rather than allowed to take the catalog down with it.
	 * {@link Throwable} and not {@link Exception}: the failure actually seen here was an
	 * {@code ExceptionInInitializerError}, which an {@code Exception} catch walks straight
	 * past. {@link #storage()} keeps that from happening in the first place; this is the net.
	 */
	private static boolean holdsItems(Block block) {
		if (!(block instanceof EntityBlock entityBlock)) {
			return false;
		}
		try {
			return entityBlock.newBlockEntity(BlockPos.ZERO, block.defaultBlockState()) instanceof Container;
		} catch (Throwable t) {
			return false;
		}
	}

	/** Adds a named id only if the running version still has it. */
	private static void addIfPresent(Set<String> ids, String id) {
		if (BuiltInRegistries.BLOCK.containsKey(Identifier.parse(id))) {
			ids.add(id);
		}
	}
}

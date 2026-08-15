package unlucky.utility.client.util;

import java.util.List;
import java.util.Set;

/**
 * BaseFinder's block signatures, in seven tiers of suspicion.
 *
 * <p>These are Trouser Streak's lists, extracted from its source rather than
 * retyped, and every id was checked against 26.2's own block registry — the
 * version it was written for folded the dyed variants differently, so a
 * transcription by hand would have quietly lost a third of tier one.
 *
 * <p>The tiers are a judgement about how much evidence a block is worth, not a
 * fact the registry could answer: a crafter is a base on its own, a furnace is
 * not. That is why they are written down here and derived in {@link BlockGroups}
 * — the same split that file already documents.
 */
public final class BaseSignatures {
	/** Blocks that essentially never occur in natural terrain — one is enough. */
	public static final Set<String> NEVER_NATURAL = Set.of(
			"minecraft:crafter", "minecraft:spruce_sapling", "minecraft:oak_sapling",
			"minecraft:birch_sapling", "minecraft:jungle_sapling", "minecraft:cherry_sapling",
			"minecraft:bamboo_sapling", "minecraft:cherry_button", "minecraft:cherry_door",
			"minecraft:cherry_fence", "minecraft:cherry_fence_gate", "minecraft:cherry_planks",
			"minecraft:cherry_pressure_plate", "minecraft:cherry_stairs", "minecraft:cherry_wood",
			"minecraft:cherry_trapdoor", "minecraft:cherry_slab", "minecraft:mangrove_planks",
			"minecraft:mangrove_button", "minecraft:mangrove_door", "minecraft:mangrove_fence",
			"minecraft:mangrove_fence_gate", "minecraft:mangrove_stairs", "minecraft:mangrove_slab",
			"minecraft:mangrove_trapdoor", "minecraft:birch_door", "minecraft:birch_fence_gate",
			"minecraft:birch_button", "minecraft:acacia_button", "minecraft:dark_oak_button",
			"minecraft:polished_blackstone_button", "minecraft:spruce_button", "minecraft:bamboo_block",
			"minecraft:bamboo_button", "minecraft:bamboo_door", "minecraft:bamboo_fence",
			"minecraft:bamboo_fence_gate", "minecraft:bamboo_mosaic", "minecraft:bamboo_mosaic_slab",
			"minecraft:bamboo_mosaic_stairs", "minecraft:bamboo_planks", "minecraft:bamboo_pressure_plate",
			"minecraft:bamboo_slab", "minecraft:bamboo_stairs", "minecraft:bamboo_trapdoor",
			"minecraft:chiseled_bookshelf", "minecraft:black_concrete", "minecraft:blue_concrete",
			"minecraft:cyan_concrete", "minecraft:brown_concrete", "minecraft:orange_concrete",
			"minecraft:magenta_concrete", "minecraft:light_blue_concrete", "minecraft:yellow_concrete",
			"minecraft:lime_concrete", "minecraft:pink_concrete", "minecraft:gray_concrete",
			"minecraft:light_gray_concrete", "minecraft:purple_concrete", "minecraft:green_concrete",
			"minecraft:black_concrete_powder", "minecraft:blue_concrete_powder", "minecraft:cyan_concrete_powder",
			"minecraft:brown_concrete_powder", "minecraft:white_concrete_powder", "minecraft:orange_concrete_powder",
			"minecraft:magenta_concrete_powder", "minecraft:light_blue_concrete_powder", "minecraft:yellow_concrete_powder",
			"minecraft:lime_concrete_powder", "minecraft:pink_concrete_powder", "minecraft:gray_concrete_powder",
			"minecraft:light_gray_concrete_powder", "minecraft:purple_concrete_powder", "minecraft:green_concrete_powder",
			"minecraft:red_concrete_powder", "minecraft:purple_terracotta", "minecraft:magenta_terracotta",
			"minecraft:pink_terracotta", "minecraft:magenta_glazed_terracotta", "minecraft:pink_glazed_terracotta",
			"minecraft:gray_glazed_terracotta", "minecraft:blue_glazed_terracotta", "minecraft:brown_glazed_terracotta",
			"minecraft:green_glazed_terracotta", "minecraft:oxidized_copper", "minecraft:cut_copper",
			"minecraft:exposed_cut_copper", "minecraft:weathered_cut_copper", "minecraft:cut_copper_slab",
			"minecraft:cut_copper_stairs", "minecraft:exposed_cut_copper_slab", "minecraft:exposed_cut_copper_stairs",
			"minecraft:weathered_cut_copper_slab", "minecraft:weathered_cut_copper_stairs", "minecraft:oxidized_cut_copper_slab",
			"minecraft:oxidized_cut_copper_stairs", "minecraft:copper_bulb", "minecraft:exposed_copper_bulb",
			"minecraft:weathered_copper_bulb", "minecraft:oxidized_copper_bulb", "minecraft:chiseled_copper",
			"minecraft:exposed_chiseled_copper", "minecraft:weathered_chiseled_copper", "minecraft:oxidized_chiseled_copper",
			"minecraft:copper_door", "minecraft:exposed_copper_door", "minecraft:weathered_copper_door",
			"minecraft:oxidized_copper_door", "minecraft:copper_grate", "minecraft:exposed_copper_grate",
			"minecraft:weathered_copper_grate", "minecraft:oxidized_copper_grate", "minecraft:copper_trapdoor",
			"minecraft:exposed_copper_trapdoor", "minecraft:weathered_copper_trapdoor", "minecraft:waxed_exposed_copper",
			"minecraft:waxed_weathered_copper", "minecraft:waxed_exposed_cut_copper", "minecraft:waxed_weathered_cut_copper",
			"minecraft:waxed_exposed_cut_copper_slab", "minecraft:waxed_exposed_cut_copper_stairs", "minecraft:waxed_weathered_cut_copper_slab",
			"minecraft:waxed_weathered_cut_copper_stairs", "minecraft:waxed_exposed_chiseled_copper", "minecraft:waxed_weathered_chiseled_copper",
			"minecraft:waxed_exposed_copper_door", "minecraft:waxed_weathered_copper_door", "minecraft:waxed_exposed_copper_grate",
			"minecraft:waxed_weathered_copper_grate", "minecraft:waxed_copper_trapdoor", "minecraft:waxed_exposed_copper_trapdoor",
			"minecraft:waxed_weathered_copper_trapdoor", "minecraft:soul_torch", "minecraft:soul_wall_torch",
			"minecraft:potted_mangrove_propagule", "minecraft:potted_cherry_sapling", "minecraft:potted_fern",
			"minecraft:potted_acacia_sapling", "minecraft:potted_warped_fungus", "minecraft:potted_warped_roots",
			"minecraft:potted_crimson_fungus", "minecraft:potted_crimson_roots", "minecraft:potted_oak_sapling",
			"minecraft:potted_wither_rose", "minecraft:wither_rose", "minecraft:cake",
			"minecraft:candle_cake", "minecraft:blue_candle_cake", "minecraft:black_candle_cake",
			"minecraft:brown_candle_cake", "minecraft:cyan_candle_cake", "minecraft:gray_candle_cake",
			"minecraft:green_candle_cake", "minecraft:light_blue_candle_cake", "minecraft:light_gray_candle_cake",
			"minecraft:lime_candle_cake", "minecraft:magenta_candle_cake", "minecraft:orange_candle_cake",
			"minecraft:pink_candle_cake", "minecraft:purple_candle_cake", "minecraft:red_candle_cake",
			"minecraft:white_candle_cake", "minecraft:yellow_candle_cake", "minecraft:blue_candle",
			"minecraft:black_candle", "minecraft:brown_candle", "minecraft:cyan_candle",
			"minecraft:gray_candle", "minecraft:green_candle", "minecraft:light_blue_candle",
			"minecraft:light_gray_candle", "minecraft:lime_candle", "minecraft:magenta_candle",
			"minecraft:orange_candle", "minecraft:pink_candle", "minecraft:purple_candle",
			"minecraft:yellow_candle", "minecraft:smooth_red_sandstone", "minecraft:chiseled_red_sandstone",
			"minecraft:cut_red_sandstone", "minecraft:smooth_red_sandstone_slab", "minecraft:smooth_red_sandstone_stairs",
			"minecraft:cut_red_sandstone_slab", "minecraft:red_sandstone_slab", "minecraft:red_sandstone_stairs",
			"minecraft:red_sandstone_wall", "minecraft:andesite_stairs", "minecraft:andesite_slab",
			"minecraft:andesite_wall", "minecraft:polished_andesite_slab", "minecraft:polished_andesite_stairs",
			"minecraft:polished_granite_slab", "minecraft:polished_granite_stairs", "minecraft:polished_diorite_slab",
			"minecraft:polished_diorite_stairs", "minecraft:tuff_slab", "minecraft:tuff_stairs",
			"minecraft:tuff_wall", "minecraft:tuff_brick_slab", "minecraft:tuff_brick_stairs",
			"minecraft:tuff_brick_wall", "minecraft:cracked_nether_bricks", "minecraft:chiseled_nether_bricks",
			"minecraft:red_nether_bricks", "minecraft:nether_brick_slab", "minecraft:nether_brick_wall",
			"minecraft:red_nether_brick_slab", "minecraft:red_nether_brick_stairs", "minecraft:red_nether_brick_wall",
			"minecraft:orange_stained_glass", "minecraft:light_blue_stained_glass", "minecraft:yellow_stained_glass",
			"minecraft:lime_stained_glass", "minecraft:pink_stained_glass", "minecraft:cyan_stained_glass",
			"minecraft:purple_stained_glass", "minecraft:blue_stained_glass", "minecraft:green_stained_glass",
			"minecraft:red_stained_glass", "minecraft:crimson_pressure_plate", "minecraft:crimson_button",
			"minecraft:crimson_door", "minecraft:crimson_fence", "minecraft:crimson_fence_gate",
			"minecraft:crimson_planks", "minecraft:crimson_sign", "minecraft:crimson_wall_sign",
			"minecraft:crimson_slab", "minecraft:crimson_stairs", "minecraft:crimson_trapdoor",
			"minecraft:warped_pressure_plate", "minecraft:warped_button", "minecraft:warped_door",
			"minecraft:warped_fence", "minecraft:warped_fence_gate", "minecraft:warped_planks",
			"minecraft:warped_sign", "minecraft:warped_wall_sign", "minecraft:warped_slab",
			"minecraft:warped_stairs", "minecraft:warped_trapdoor", "minecraft:scaffolding",
			"minecraft:cherry_sign", "minecraft:cherry_wall_sign", "minecraft:oak_sign",
			"minecraft:spruce_sign", "minecraft:acacia_sign", "minecraft:acacia_wall_sign",
			"minecraft:birch_sign", "minecraft:birch_wall_sign", "minecraft:dark_oak_sign",
			"minecraft:dark_oak_wall_sign", "minecraft:jungle_sign", "minecraft:jungle_wall_sign",
			"minecraft:mangrove_sign", "minecraft:mangrove_wall_sign", "minecraft:slime_block",
			"minecraft:sponge", "minecraft:tinted_glass", "minecraft:acacia_hanging_sign",
			"minecraft:acacia_wall_hanging_sign", "minecraft:bamboo_hanging_sign", "minecraft:bamboo_wall_hanging_sign",
			"minecraft:birch_hanging_sign", "minecraft:birch_wall_hanging_sign", "minecraft:cherry_hanging_sign",
			"minecraft:cherry_wall_hanging_sign", "minecraft:crimson_hanging_sign", "minecraft:crimson_wall_hanging_sign",
			"minecraft:dark_oak_hanging_sign", "minecraft:dark_oak_wall_hanging_sign", "minecraft:jungle_hanging_sign",
			"minecraft:jungle_wall_hanging_sign", "minecraft:mangrove_hanging_sign", "minecraft:mangrove_wall_hanging_sign",
			"minecraft:oak_hanging_sign", "minecraft:oak_wall_hanging_sign", "minecraft:spruce_hanging_sign",
			"minecraft:spruce_wall_hanging_sign", "minecraft:warped_hanging_sign", "minecraft:warped_wall_hanging_sign",
			"minecraft:chiseled_quartz_block", "minecraft:quartz_pillar", "minecraft:quartz_bricks",
			"minecraft:quartz_stairs", "minecraft:ochre_froglight", "minecraft:pearlescent_froglight",
			"minecraft:verdant_froglight", "minecraft:petrified_oak_slab", "minecraft:stripped_bamboo_block",
			"minecraft:stripped_cherry_log", "minecraft:stripped_cherry_wood", "minecraft:stripped_acacia_wood",
			"minecraft:birch_wood", "minecraft:stripped_birch_log", "minecraft:stripped_birch_wood",
			"minecraft:crimson_hyphae", "minecraft:stripped_crimson_hyphae", "minecraft:stripped_crimson_stem",
			"minecraft:dark_oak_wood", "minecraft:stripped_dark_oak_log", "minecraft:stripped_dark_oak_wood",
			"minecraft:stripped_jungle_log", "minecraft:stripped_jungle_wood", "minecraft:stripped_mangrove_log",
			"minecraft:stripped_mangrove_wood", "minecraft:warped_hyphae", "minecraft:stripped_warped_hyphae",
			"minecraft:stripped_warped_stem", "minecraft:shulker_box", "minecraft:black_shulker_box",
			"minecraft:blue_shulker_box", "minecraft:brown_shulker_box", "minecraft:cyan_shulker_box",
			"minecraft:gray_shulker_box", "minecraft:green_shulker_box", "minecraft:light_blue_shulker_box",
			"minecraft:light_gray_shulker_box", "minecraft:lime_shulker_box", "minecraft:magenta_shulker_box",
			"minecraft:orange_shulker_box", "minecraft:pink_shulker_box", "minecraft:purple_shulker_box",
			"minecraft:red_shulker_box", "minecraft:white_shulker_box", "minecraft:yellow_shulker_box",
			"minecraft:lava_cauldron", "minecraft:powder_snow_cauldron", "minecraft:activator_rail",
			"minecraft:beacon", "minecraft:beehive", "minecraft:repeating_command_block",
			"minecraft:command_block", "minecraft:chain_command_block", "minecraft:emerald_block",
			"minecraft:iron_block", "minecraft:netherite_block", "minecraft:raw_gold_block",
			"minecraft:conduit", "minecraft:daylight_detector", "minecraft:detector_rail",
			"minecraft:dried_kelp_block", "minecraft:dropper", "minecraft:enchanting_table",
			"minecraft:piglin_head", "minecraft:piglin_wall_head", "minecraft:creeper_head",
			"minecraft:creeper_wall_head", "minecraft:dragon_wall_head", "minecraft:dragon_head",
			"minecraft:player_head", "minecraft:player_wall_head", "minecraft:zombie_head",
			"minecraft:zombie_wall_head", "minecraft:skeleton_wall_skull", "minecraft:wither_skeleton_skull",
			"minecraft:wither_skeleton_wall_skull", "minecraft:heavy_core", "minecraft:honey_block",
			"minecraft:honeycomb_block", "minecraft:jukebox", "minecraft:lightning_rod",
			"minecraft:lodestone", "minecraft:observer", "minecraft:powered_rail",
			"minecraft:heavy_weighted_pressure_plate", "minecraft:light_weighted_pressure_plate", "minecraft:polished_blackstone_pressure_plate",
			"minecraft:birch_pressure_plate", "minecraft:jungle_pressure_plate", "minecraft:dark_oak_pressure_plate",
			"minecraft:mangrove_pressure_plate", "minecraft:respawn_anchor", "minecraft:calibrated_sculk_sensor",
			"minecraft:sniffer_egg", "minecraft:resin_block", "minecraft:resin_bricks",
			"minecraft:resin_brick_slab", "minecraft:resin_brick_wall", "minecraft:resin_brick_stairs",
			"minecraft:chiseled_resin_bricks", "minecraft:potted_closed_eyeblossom", "minecraft:potted_open_eyeblossom",
			"minecraft:potted_pale_oak_sapling", "minecraft:pale_oak_sapling", "minecraft:pale_oak_button",
			"minecraft:pale_oak_door", "minecraft:pale_oak_fence", "minecraft:pale_oak_fence_gate",
			"minecraft:pale_oak_planks", "minecraft:pale_oak_pressure_plate", "minecraft:pale_oak_hanging_sign",
			"minecraft:pale_oak_sign", "minecraft:pale_oak_wall_sign", "minecraft:pale_oak_wall_hanging_sign",
			"minecraft:pale_oak_slab", "minecraft:pale_oak_stairs", "minecraft:pale_oak_trapdoor",
			"minecraft:pale_oak_wood", "minecraft:stripped_pale_oak_wood", "minecraft:copper_bars",
			"minecraft:copper_chain", "minecraft:copper_lantern", "minecraft:copper_chest",
			"minecraft:exposed_copper_chest", "minecraft:oxidized_copper_chest", "minecraft:weathered_copper_chest",
			"minecraft:waxed_copper_chest", "minecraft:waxed_exposed_copper_chest", "minecraft:waxed_oxidized_copper_chest",
			"minecraft:waxed_weathered_copper_chest", "minecraft:copper_golem_statue", "minecraft:exposed_copper_golem_statue",
			"minecraft:weathered_copper_golem_statue", "minecraft:oxidized_copper_golem_statue", "minecraft:waxed_copper_golem_statue",
			"minecraft:waxed_exposed_copper_golem_statue", "minecraft:waxed_weathered_copper_golem_statue", "minecraft:waxed_oxidized_copper_golem_statue",
			"minecraft:copper_torch", "minecraft:copper_wall_torch", "minecraft:oak_shelf",
			"minecraft:dark_oak_shelf", "minecraft:pale_oak_shelf", "minecraft:acacia_shelf",
			"minecraft:bamboo_shelf", "minecraft:birch_shelf", "minecraft:cherry_shelf",
			"minecraft:crimson_shelf", "minecraft:jungle_shelf", "minecraft:mangrove_shelf",
			"minecraft:spruce_shelf", "minecraft:warped_shelf", "minecraft:potted_azalea_bush");

	/** Blocks that do occur naturally, but not in a heap: several together is a floor. */
	public static final Set<String> SPARSE_BUILD = Set.of(
			"minecraft:spruce_wall_sign", "minecraft:polished_diorite", "minecraft:note_block",
			"minecraft:mangrove_wood", "minecraft:weathered_copper");

	/** The workstations a lived-in base keeps. */
	public static final Set<String> WORKSTATIONS = Set.of(
			"minecraft:crafting_table", "minecraft:brewing_stand", "minecraft:ender_chest",
			"minecraft:smooth_quartz", "minecraft:redstone_block", "minecraft:diamond_block",
			"minecraft:brown_stained_glass");

	/** Signs of somebody securing a place rather than passing through. */
	public static final Set<String> SECURED = Set.of(
			"minecraft:oak_wall_sign", "minecraft:trapped_chest", "minecraft:iron_trapdoor",
			"minecraft:lapis_block");

	/** Furnishing: quartz, furnaces, beds. Common enough to need a real pile of it. */
	public static final Set<String> FURNISHED = Set.of(
			"minecraft:quartz_block", "minecraft:furnace", "minecraft:black_bed",
			"minecraft:gray_bed", "minecraft:light_blue_bed", "minecraft:light_gray_bed",
			"minecraft:pink_bed", "minecraft:red_bed", "minecraft:white_bed",
			"minecraft:yellow_bed", "minecraft:orange_bed", "minecraft:blue_bed",
			"minecraft:cyan_bed", "minecraft:green_bed", "minecraft:lime_bed",
			"minecraft:purple_bed", "minecraft:magenta_bed", "minecraft:brown_bed",
			"minecraft:white_concrete");

	/** Redstone that somebody wired, which needs volume before it means anything. */
	public static final Set<String> REDSTONE = Set.of(
			"minecraft:redstone_torch", "minecraft:hopper");

	/** Yours. Empty by design. */
	public static final Set<String> CUSTOM = Set.of();

	/** The tiers in order, so the module can build its settings in a loop. */
	public static final List<Set<String>> TIERS = List.of(
			NEVER_NATURAL, SPARSE_BUILD, WORKSTATIONS, SECURED, FURNISHED, REDSTONE, CUSTOM);

	/** How many of a tier's blocks a chunk needs before it counts, by default. */
	public static final int[] DEFAULT_THRESHOLDS = {1, 6, 4, 2, 12, 12, 1};

	/** Display names for the tiers, used to label the settings. */
	public static final String[] TIER_NAMES = {
			"Never natural", "Sparse build", "Workstations",
			"Secured", "Furnished", "Redstone", "Custom"};

	private BaseSignatures() {
	}
}

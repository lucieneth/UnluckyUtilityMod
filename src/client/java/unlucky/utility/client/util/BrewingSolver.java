package unlucky.utility.client.util;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import unlucky.utility.client.UnluckyClientMod;

/**
 * Works out how to brew a thing - by <b>reading</b> the game's recipes, never by
 * knowing them.
 *
 * <p><b>What changed in 26.3.</b> Brewing used to be code: {@code PotionBrewing.mix()}
 * was public, was the same method the stand itself called, and could simply be asked
 * "bottle plus reagent, what comes out?". 26.3 deleted {@code PotionBrewing} and made
 * brewing data-driven - {@code minecraft:brewing} recipes in the data pack - and the
 * client is not sent them. {@code RecipeAccess} on the client carries two membership
 * sets ({@code brewing_input}, {@code brewing_reagent}) and no outputs at all, so
 * there is no longer an oracle to ask at runtime.
 *
 * <p><b>What we do instead.</b> The recipes still ship, as JSON, inside the game's own
 * built-in data pack, and the client mounts that pack. So the table below is read out
 * of {@code data/minecraft/recipe/brewing/} rather than restated from memory: no
 * hardcoded potion graph, and a version bump that adds a potion is picked up without
 * anyone editing this file.
 *
 * <p><b>The one thing this gives up.</b> It is the <em>vanilla</em> recipe set, not the
 * connected server's. Under 26.2 a datapack or server-side mod that added a brew was
 * picked up for free, because {@code mix()} answered for whatever was loaded. A server
 * with custom brews will now be missing them here. Vanilla brewing - which is all of
 * it on an anarchy server - is unaffected.
 *
 * <p>The search itself is unchanged: breadth-first from a water bottle, every
 * reachable bottle mapped to the shortest chain that reaches it, computed once.
 */
public final class BrewingSolver {
	/** What's in a bottle: which container item, holding which potion. */
	public record State(Item container, Holder<Potion> potion) {
		public ItemStack stack() {
			return PotionContents.createItemStack(container, potion);
		}
	}

	/** One brew: put {@code reagent} in the stand and the bottle becomes {@code result}. */
	public record Step(Item reagent, State result) {
	}

	/** Where every chain starts: a plain water bottle. */
	public static final State WATER_BOTTLE = new State(Items.POTION, Potions.WATER);

	private static Table cachedTable;
	private static Map<State, List<Step>> cached;

	private BrewingSolver() {
	}

	/**
	 * Every bottle brewable from water, mapped to the shortest chain of brews that
	 * gets there. The water bottle itself maps to an empty chain.
	 */
	public static Map<State, List<Step>> solve() {
		if (cached != null) {
			return cached;
		}
		Table table = table();
		Map<State, List<Step>> paths = new LinkedHashMap<>();
		paths.put(WATER_BOTTLE, List.of());
		Deque<State> queue = new ArrayDeque<>();
		queue.add(WATER_BOTTLE);
		// breadth-first, so the first chain we find to a bottle is the shortest one
		while (!queue.isEmpty()) {
			State from = queue.poll();
			for (Item reagent : table.reagents()) {
				State to = table.mix(reagent, from);
				if (to == null || to.equals(from) || paths.containsKey(to)) {
					continue;
				}
				List<Step> path = new ArrayList<>(paths.get(from));
				path.add(new Step(reagent, to));
				paths.put(to, List.copyOf(path));
				queue.add(to);
			}
		}
		cached = Map.copyOf(paths);
		return cached;
	}

	private static Table table() {
		if (cachedTable == null) {
			cachedTable = Table.load();
		}
		return cachedTable;
	}

	/**
	 * The brewing recipes, read once out of the built-in data pack.
	 *
	 * <p>Keyed the way the search asks: a bottle plus a reagent. Only recipes whose
	 * input and output are both potions are kept - that is every
	 * {@code minecraft:brewing} recipe vanilla ships, and the guard means a future
	 * non-potion brew is skipped rather than failing the load.
	 */
	private record Table(Map<Key, State> mixes, List<Item> reagents) {
		private record Key(State from, Item reagent) {
		}

		private State mix(Item reagent, State from) {
			return mixes.get(new Key(from, reagent));
		}

		private static Table load() {
			Map<Key, State> mixes = new HashMap<>();
			// Reagents that change the bottle (gunpowder, dragon's breath) must go last,
			// and that ordering is load-bearing rather than tidy. Ties in a breadth-first
			// search are broken by insertion order, and "splash it first, then brew the
			// splash water bottle" is exactly as short as "brew it, then splash it" - so
			// without this the search picks the first and every chain starts with
			// gunpowder. Real, working vanilla, but it means an ordinary Awkward Potion
			// is not on the chain to a Splash Strength, so inventory stock cannot be
			// reused. Sinking container mixes to the end finds the conventional chain.
			Set<Item> potionReagents = new LinkedHashSet<>();
			Set<Item> containerReagents = new LinkedHashSet<>();
			Minecraft mc = Minecraft.getInstance();
			if (mc.getVanillaPackResources() == null) {
				UnluckyClientMod.LOGGER.error("No vanilla pack to read brewing recipes from");
				return new Table(Map.of(), List.of());
			}
			mc.getVanillaPackResources().fullResources().listResources(PackType.SERVER_DATA, "minecraft",
					"recipe/brewing", (location, supplier) -> {
						if (!location.getPath().endsWith(".json")) {
							return;
						}
						try (InputStream in = supplier.get()) {
							JsonObject json = JsonParser
									.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
									.getAsJsonObject();
							Item reagent = item(json.getAsJsonObject("reagent"), "item");
							State from = bottle(json.getAsJsonObject("input"));
							State to = bottle(json.getAsJsonObject("output"));
							if (reagent == null || from == null || to == null) {
								return;
							}
							mixes.put(new Key(from, reagent), to);
							if (from.container() == to.container()) {
								potionReagents.add(reagent);
							} else {
								containerReagents.add(reagent);
							}
						} catch (Exception e) {
							UnluckyClientMod.LOGGER.warn("Skipping brewing recipe {}", location, e);
						}
					});
			// A reagent can appear on both sides (gunpowder only changes the bottle, but
			// a modded one need not); the potion mix is the conventional step, so it
			// keeps the earlier position.
			containerReagents.removeAll(potionReagents);
			List<Item> ordered = new ArrayList<>(potionReagents);
			ordered.addAll(containerReagents);
			UnluckyClientMod.LOGGER.info("Loaded {} brewing recipes from the vanilla pack", mixes.size());
			return new Table(Map.copyOf(mixes), List.copyOf(ordered));
		}

		/**
		 * Reads one side of a recipe into a bottle.
		 *
		 * <p>The two sides are not spelled the same: an input names its potion inline
		 * under {@code potion_contents}, while an output is an item stack template and
		 * names it under {@code components}. Both are handled here so the caller does
		 * not have to know which side it is holding.
		 */
		private static State bottle(JsonObject side) {
			if (side == null) {
				return null;
			}
			Item container = item(side, side.has("id") ? "id" : "item");
			if (container == null) {
				return null;
			}
			JsonElement potionId = null;
			if (side.has("potion_contents")) {
				potionId = side.getAsJsonObject("potion_contents").get("potions");
			} else if (side.has("components")) {
				JsonObject components = side.getAsJsonObject("components");
				if (components.has("minecraft:potion_contents")) {
					potionId = components.getAsJsonObject("minecraft:potion_contents").get("potion");
				}
			}
			if (potionId == null || !potionId.isJsonPrimitive()) {
				return null;
			}
			Identifier id = Identifier.tryParse(potionId.getAsString());
			if (id == null) {
				return null;
			}
			return BuiltInRegistries.POTION.get(id)
					.map(holder -> new State(container, (Holder<Potion>) holder)).orElse(null);
		}

		private static Item item(JsonObject owner, String field) {
			if (owner == null || !owner.has(field)) {
				return null;
			}
			Identifier id = Identifier.tryParse(owner.get(field).getAsString());
			return id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
		}
	}

	/** Reads a brewed stack back into a State, or null if it isn't a potion at all. */
	private static State stateOf(ItemStack stack) {
		if (stack.isEmpty()) {
			return null;
		}
		Optional<Holder<Potion>> potion = stack
				.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).potion();
		return potion.map(holder -> new State(stack.getItem(), holder)).orElse(null);
	}

	/**
	 * A stable id for a bottle, for config and for queue lookups:
	 * {@code minecraft:splash_potion|minecraft:strong_strength}. Registry ids, so it
	 * survives a rename of anything we show on screen.
	 */
	public static String key(State state) {
		return BuiltInRegistries.ITEM.getKey(state.container()) + "|"
				+ BuiltInRegistries.POTION.getKey(state.potion().value());
	}

	/** Reads {@link #key} back, or null if either half no longer exists. */
	public static State fromKey(String key) {
		String[] parts = key.split("\\|");
		if (parts.length != 2) {
			return null;
		}
		Identifier containerId = Identifier.tryParse(parts[0]);
		Identifier potionId = Identifier.tryParse(parts[1]);
		if (containerId == null || potionId == null) {
			return null;
		}
		Item container = BuiltInRegistries.ITEM.getOptional(containerId).orElse(null);
		Holder<Potion> potion = BuiltInRegistries.POTION.get(potionId).map(holder -> (Holder<Potion>) holder)
				.orElse(null);
		return container == null || potion == null ? null : new State(container, potion);
	}

	/** "Splash Strong Strength" — what a person reads. */
	public static String label(State state) {
		String container = containerLabel(state.container());
		String potion = potionLabel(state.potion());
		return container.equals("Normal") ? potion : container + " " + potion;
	}

	/**
	 * "Strong Strength" — from the registry <b>key</b>, not the display name.
	 * Display names collide: {@code strength} and {@code strong_strength} both render
	 * as "Potion of Strength" (the II lives in the tooltip, not the name), so picking
	 * by display name can't tell them apart. Keys can.
	 */
	public static String potionLabel(Holder<Potion> potion) {
		return prettify(BuiltInRegistries.POTION.getKey(potion.value()).getPath());
	}

	/** "Normal" / "Splash" / "Lingering", from the container item's key. */
	public static String containerLabel(Item container) {
		String path = BuiltInRegistries.ITEM.getKey(container).getPath();
		if (path.equals("potion")) {
			return "Normal";
		}
		return prettify(path.replace("_potion", ""));
	}

	/** {@code strong_strength} -> {@code Strong Strength}. */
	private static String prettify(String path) {
		StringBuilder out = new StringBuilder(path.length());
		for (String word : path.split("_")) {
			if (word.isEmpty()) {
				continue;
			}
			if (!out.isEmpty()) {
				out.append(' ');
			}
			out.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
		}
		return out.toString();
	}
}

package unlucky.utility.client.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;

/**
 * Works out how to brew a thing — by <b>asking</b> vanilla, never by knowing.
 *
 * <p>The obvious build is to read {@code PotionBrewing}'s mix lists and model the
 * rules: a potion mix keeps the bottle and swaps the potion, a container mix keeps
 * the potion and swaps the bottle, container mixes are tried first... Every one of
 * those is a fact about {@code mix()} that we'd be restating from memory, and a
 * restatement can be wrong (or go stale in 27.1) while still compiling.
 *
 * <p>So we don't restate it. {@code PotionBrewing.mix(reagent, input)} is public and
 * is <b>the same method the brewing stand itself calls</b> — we hand it a bottle and
 * a reagent and read back what came out. It is the oracle, so our chains cannot
 * disagree with the stand: no hardcoded recipes, no private-field access, and
 * datapack or mod mixes are picked up for free without knowing they exist.
 *
 * <p>The cost is a brute-force sweep — every reachable bottle crossed with every
 * reagent — but the search is tiny (a few thousand {@code mix()} calls) and runs
 * once per {@code PotionBrewing} instance, not per tick.
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

	// PotionBrewing is rebuilt per world (feature flags, datapacks), so the cache
	// keys on the instance itself — a new one invalidates by simply not matching.
	private static PotionBrewing cachedFor;
	private static Map<State, List<Step>> cached;

	private BrewingSolver() {
	}

	/**
	 * Every bottle brewable from water, mapped to the shortest chain of brews that
	 * gets there. The water bottle itself maps to an empty chain.
	 */
	public static Map<State, List<Step>> solve(PotionBrewing brewing) {
		if (brewing == cachedFor) {
			return cached;
		}
		List<Item> reagents = reagents(brewing);
		Map<State, List<Step>> paths = new LinkedHashMap<>();
		paths.put(WATER_BOTTLE, List.of());
		Deque<State> queue = new ArrayDeque<>();
		queue.add(WATER_BOTTLE);
		// breadth-first, so the first chain we find to a bottle is the shortest one
		while (!queue.isEmpty()) {
			State from = queue.poll();
			ItemStack input = from.stack();
			for (Item reagent : reagents) {
				State to = stateOf(brewing.mix(new ItemStack(reagent), input.copy()));
				// mix() hands back the input untouched when nothing matches, so "no
				// recipe" and "recipe to itself" both land here as from.equals(to)
				if (to == null || to.equals(from) || paths.containsKey(to)) {
					continue;
				}
				List<Step> path = new ArrayList<>(paths.get(from));
				path.add(new Step(reagent, to));
				paths.put(to, List.copyOf(path));
				queue.add(to);
			}
		}
		cachedFor = brewing;
		cached = Map.copyOf(paths);
		return cached;
	}

	/**
	 * Every item the stand accepts as a reagent. {@code isIngredient} is public and
	 * covers both mix kinds, so this needs no knowledge of what brewing is — the
	 * registry sweep is the price of not hardcoding a list.
	 *
	 * <p>Reagents that change the <b>bottle</b> (gunpowder, dragon's breath) go last,
	 * and that ordering is load-bearing rather than tidy. Ties in a breadth-first
	 * search are broken by insertion order, and "splash it first, then brew the splash
	 * water bottle" is exactly as short as "brew it, then splash it" — so without this
	 * the search picks the first one and every chain starts with gunpowder. That's
	 * real, working vanilla, but it means an ordinary Awkward Potion isn't on the
	 * chain to a Splash Strength, so the stock in your inventory can't be reused.
	 * Sinking container mixes to the end makes the search find the conventional
	 * chain, where it can.
	 */
	private static List<Item> reagents(PotionBrewing brewing) {
		List<Item> potionReagents = new ArrayList<>();
		List<Item> containerReagents = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) {
			ItemStack stack = new ItemStack(item);
			if (brewing.isContainerIngredient(stack)) {
				containerReagents.add(item);
			} else if (brewing.isIngredient(stack)) {
				potionReagents.add(item);
			}
		}
		potionReagents.addAll(containerReagents);
		return potionReagents;
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

package unlucky.utility.client.settings;

import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * A selectable set of items, stored as registry ids (e.g. "minecraft:rotten_flesh").
 * The {@code filter} narrows what the picker offers — AutoEat only wants food,
 * a tool preference only wants tools — so one popup serves every use.
 */
public class ItemListSetting extends Setting<Set<String>> {
	private final Predicate<Item> filter;

	public ItemListSetting(String name, String description, Predicate<Item> filter, Set<String> defaultValue) {
		super(name, description, new TreeSet<>(defaultValue));
		this.filter = filter;
	}

	public ItemListSetting(String name, String description, Predicate<Item> filter) {
		this(name, description, filter, Set.of());
	}

	/** Which items the picker lists. */
	public Predicate<Item> filter() {
		return filter;
	}

	public boolean contains(Item item) {
		return get().contains(id(item));
	}

	public void toggle(Item item) {
		String id = id(item);
		if (!get().remove(id)) {
			get().add(id);
		}
	}

	public void setAll(Set<String> ids) {
		get().clear();
		get().addAll(ids);
	}

	public void clear() {
		get().clear();
	}

	public static String id(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}
}

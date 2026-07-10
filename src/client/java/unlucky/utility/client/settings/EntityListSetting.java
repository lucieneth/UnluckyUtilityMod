package unlucky.utility.client.settings;

import java.util.Set;
import java.util.TreeSet;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;

/**
 * A selectable set of mobs. Stored inverted — the ids in the set are the
 * DESELECTED ones — so the default (empty set) targets everything and mobs
 * added in future versions are allowed automatically.
 */
public class EntityListSetting extends Setting<Set<String>> {
	public EntityListSetting(String name, String description) {
		super(name, description, new TreeSet<>());
	}

	public boolean allows(EntityType<?> type) {
		return !get().contains(id(type));
	}

	public void toggle(EntityType<?> type) {
		String id = id(type);
		if (!get().remove(id)) {
			get().add(id);
		}
	}

	/** Empties the deselected set — everything allowed. */
	public void allowAll() {
		get().clear();
	}

	public void setAll(Set<String> ids) {
		get().clear();
		get().addAll(ids);
	}

	private static String id(EntityType<?> type) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
	}
}

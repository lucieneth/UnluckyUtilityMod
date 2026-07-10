package unlucky.utility.client.settings;

import java.util.Set;
import java.util.TreeSet;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/** A selectable set of blocks, stored as registry ids (e.g. "minecraft:diamond_ore"). */
public class BlockListSetting extends Setting<Set<String>> {
	public BlockListSetting(String name, String description, Set<String> defaultValue) {
		super(name, description, new TreeSet<>(defaultValue));
	}

	public boolean contains(Block block) {
		return get().contains(BuiltInRegistries.BLOCK.getKey(block).toString());
	}

	public void toggle(Block block) {
		String id = BuiltInRegistries.BLOCK.getKey(block).toString();
		if (!get().remove(id)) {
			get().add(id);
		}
	}

	public void setAll(Set<String> ids) {
		get().clear();
		get().addAll(ids);
	}
}

package unlucky.utility.client.util;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * Your saved creative hotbars, read back off disk.
 *
 * <p>Ctrl+1..9 in the creative inventory writes {@code hotbar.nbt} in the game
 * directory, and it keeps the whole stack — every data component, including ones
 * only a command could have produced. Vanilla stores each slot with
 * {@code ItemStack.OPTIONAL_CODEC} and {@code Hotbar.load} hands them back as real
 * {@link ItemStack}s, so nothing here parses anything by hand.
 *
 * <p>{@link #encode} runs the same codec in the other direction. That matters more
 * than it looks: a stack that came out of this file goes back in through the same
 * serialisation, so it is a round trip rather than a reconstruction, and components
 * nobody wrote special handling for survive anyway.
 */
public final class HotbarVault {
	/** {@code HotbarManager.NUM_HOTBAR_GROUPS}. */
	public static final int GROUPS = 9;

	/** A saved stack and the hotbar slot it was saved in. */
	public record Entry(int slot, ItemStack stack) {
	}

	private HotbarVault() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	/** True once a world is joined — the codec needs the server's registries. */
	public static boolean ready() {
		return mc().getConnection() != null;
	}

	/**
	 * Every non-empty slot of saved hotbar {@code group} (1-based, as the UI counts).
	 * Empty when the group was never saved, so callers can treat "nothing there" and
	 * "not in a world" as one case.
	 */
	public static List<Entry> read(int group) {
		List<Entry> out = new ArrayList<>();
		if (!ready() || group < 1 || group > GROUPS) {
			return out;
		}
		HolderLookup.Provider registries = mc().getConnection().registryAccess();
		List<ItemStack> stacks = mc().getHotbarManager().get(group - 1).load(registries);
		for (int slot = 0; slot < stacks.size(); slot++) {
			ItemStack stack = stacks.get(slot);
			if (!stack.isEmpty()) {
				out.add(new Entry(slot, stack));
			}
		}
		return out;
	}

	/** All nine groups, for the picker — index 0 is hotbar 1. */
	public static List<List<Entry>> readAll() {
		List<List<Entry>> out = new ArrayList<>(GROUPS);
		for (int group = 1; group <= GROUPS; group++) {
			out.add(read(group));
		}
		return out;
	}

	/** How many items hotbar {@code group} would produce. */
	public static int count(int group) {
		return read(group).size();
	}

	/** A stack as SNBT, or null when the codec refuses it. */
	public static String encode(ItemStack stack) {
		if (!ready()) {
			return null;
		}
		try {
			HolderLookup.Provider registries = mc().getConnection().registryAccess();
			Tag tag = ItemStack.CODEC
					.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack)
					.getOrThrow();
			return tag.toString();
		} catch (RuntimeException e) {
			return null;
		}
	}
}

package unlucky.utility.client.settings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;

/**
 * A block pattern around someone's feet, the shape Surround, SelfTrap and AutoTrap build.
 *
 * <p>Cells are offsets from the feet block: {@code y 0} is the feet, {@code y 1} the head, and
 * the editor allows {@value #REACH} out to each side, one below and three above. The feet and
 * head cells themselves are where the player stands, so they can never be part of it.
 *
 * <p>Stored as {@code x,y,z} strings in the order they were added, like the brew queue: config
 * holds plain text, and a module that cares about order (none does yet — they all go nearest
 * first) would get the order the cells were clicked in.
 */
public class PatternSetting extends Setting<List<String>> {
	public static final int REACH = 2;
	public static final int MIN_Y = -1;
	public static final int MAX_Y = 3;

	/** The editor's preset buttons, in the order they are shown. */
	public static final Map<String, List<String>> PRESETS = presets();

	public PatternSetting(String name, String description, String preset) {
		super(name, description, new ArrayList<>(PRESETS.getOrDefault(preset, List.of())));
	}

	public static String key(int x, int y, int z) {
		return x + "," + y + "," + z;
	}

	public static boolean allowed(int x, int y, int z) {
		return Math.abs(x) <= REACH && Math.abs(z) <= REACH && y >= MIN_Y && y <= MAX_Y
				&& !(x == 0 && z == 0 && (y == 0 || y == 1));
	}

	public boolean has(int x, int y, int z) {
		return get().contains(key(x, y, z));
	}

	public void toggle(int x, int y, int z) {
		if (!get().remove(key(x, y, z)) && allowed(x, y, z)) {
			get().add(key(x, y, z));
		}
	}

	public void setAll(Collection<String> entries) {
		get().clear();
		for (String entry : entries) {
			int[] cell = parse(entry);
			if (cell != null && allowed(cell[0], cell[1], cell[2]) && !get().contains(entry)) {
				get().add(key(cell[0], cell[1], cell[2]));
			}
		}
	}

	public void preset(String name) {
		setAll(PRESETS.getOrDefault(name, List.of()));
	}

	/** The preset this pattern is exactly, or null when it has been edited away from all of them. */
	public String matchingPreset() {
		for (Map.Entry<String, List<String>> preset : PRESETS.entrySet()) {
			if (preset.getValue().size() == get().size() && get().containsAll(preset.getValue())) {
				return preset.getKey();
			}
		}
		return null;
	}

	/** Every cell as an {x, y, z} offset. */
	public List<int[]> cells() {
		List<int[]> cells = new ArrayList<>(get().size());
		for (String entry : get()) {
			int[] cell = parse(entry);
			if (cell != null) {
				cells.add(cell);
			}
		}
		return cells;
	}

	/** The pattern laid around {@code feet}, in pattern order. */
	public List<BlockPos> around(BlockPos feet) {
		List<BlockPos> positions = new ArrayList<>(get().size());
		for (int[] cell : cells()) {
			positions.add(feet.offset(cell[0], cell[1], cell[2]));
		}
		return positions;
	}

	private static int[] parse(String entry) {
		String[] parts = entry.split(",");
		if (parts.length != 3) {
			return null;
		}
		try {
			return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
					Integer.parseInt(parts[2].trim())};
		} catch (NumberFormatException e) {
			return null; // hand-edited config; drop the cell rather than crash
		}
	}

	private static Map<String, List<String>> presets() {
		List<String> feetRing = ring(0, 1), headRing = ring(1, 1);
		List<String> top = List.of(key(0, 2, 0));
		Map<String, List<String>> presets = new LinkedHashMap<>();
		presets.put("Ring", feetRing);
		presets.put("Double", join(feetRing, headRing));
		presets.put("Anti-city", join(feetRing, ring(0, 2)));
		presets.put("Top", top);
		presets.put("Face", join(headRing, top));
		presets.put("Full", join(feetRing, headRing, top));
		presets.put("Clear", List.of());
		return presets;
	}

	/** The four cells {@code distance} out from the middle at height {@code y}. */
	private static List<String> ring(int y, int distance) {
		return List.of(key(0, y, -distance), key(distance, y, 0), key(0, y, distance), key(-distance, y, 0));
	}

	@SafeVarargs
	private static List<String> join(List<String>... parts) {
		List<String> joined = new ArrayList<>();
		for (List<String> part : parts) {
			joined.addAll(part);
		}
		return List.copyOf(joined);
	}
}

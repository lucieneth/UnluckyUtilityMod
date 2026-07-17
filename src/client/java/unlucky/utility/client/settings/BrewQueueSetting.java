package unlucky.utility.client.settings;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered brew list: "1 Strength, then 10 Night Vision, then 5 Invisibility".
 *
 * <p>A {@code List}, not a {@code Set} like the other list settings, because both
 * things they throw away matter here: order (the queue is worked front to back) and
 * duplicates-as-counts (you want ten of that one).
 *
 * <p>Entries are stored as {@code container|potion|count} — the first two halves are
 * {@link unlucky.utility.client.util.BrewingSolver#key} exactly, so an entry is just
 * a key with a count glued on and config holds plain registry ids rather than
 * anything we render.
 */
public class BrewQueueSetting extends Setting<List<String>> {
	public BrewQueueSetting(String name, String description) {
		super(name, description, new ArrayList<>());
	}

	/** The bottle keys in queue order, counts stripped. */
	public List<String> keys() {
		List<String> keys = new ArrayList<>(get().size());
		for (String entry : get()) {
			keys.add(keyOf(entry));
		}
		return keys;
	}

	public int countOf(String key) {
		for (String entry : get()) {
			if (keyOf(entry).equals(key)) {
				return countIn(entry);
			}
		}
		return 0;
	}

	/**
	 * Sets how many of {@code key} to brew; 0 drops it. New keys go on the end, so
	 * adjusting a count never reshuffles the queue under you.
	 */
	public void setCount(String key, int count) {
		List<String> entries = get();
		for (int i = 0; i < entries.size(); i++) {
			if (keyOf(entries.get(i)).equals(key)) {
				if (count <= 0) {
					entries.remove(i);
				} else {
					entries.set(i, key + "|" + count);
				}
				return;
			}
		}
		if (count > 0) {
			entries.add(key + "|" + count);
		}
	}

	public void add(String key, int delta) {
		setCount(key, Math.clamp(countOf(key) + delta, 0, 64));
	}

	public void setAll(List<String> entries) {
		get().clear();
		get().addAll(entries);
	}

	public void clear() {
		get().clear();
	}

	/** Total bottles across the whole queue — what the ClickGUI row summarises. */
	public int total() {
		int total = 0;
		for (String entry : get()) {
			total += countIn(entry);
		}
		return total;
	}

	public static String keyOf(String entry) {
		int cut = entry.lastIndexOf('|');
		return cut < 0 ? entry : entry.substring(0, cut);
	}

	public static int countIn(String entry) {
		int cut = entry.lastIndexOf('|');
		if (cut < 0) {
			return 0;
		}
		try {
			return Integer.parseInt(entry.substring(cut + 1));
		} catch (NumberFormatException e) {
			return 0; // hand-edited config; treat as absent rather than crash the GUI
		}
	}
}

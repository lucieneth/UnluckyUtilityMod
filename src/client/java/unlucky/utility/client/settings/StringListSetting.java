package unlucky.utility.client.settings;

import java.util.ArrayList;
import java.util.List;

/** Ordered, persisted text entries. Unlike a free-text setting, each entry remains distinct. */
public class StringListSetting extends Setting<List<String>> {
	private final int maxEntries;
	private final int maxEntryLength;

	public StringListSetting(String name, String description) {
		this(name, description, List.of(), 64, 128);
	}

	public StringListSetting(String name, String description, List<String> defaults,
			int maxEntries, int maxEntryLength) {
		super(name, description, new ArrayList<>());
		this.maxEntries = maxEntries;
		this.maxEntryLength = maxEntryLength;
		setAll(defaults);
	}

	public void setAll(Iterable<String> entries) {
		value.clear();
		for (String entry : entries) {
			if (entry == null) continue;
			String normalized = entry.trim();
			if (normalized.isEmpty() || value.size() >= maxEntries) continue;
			value.add(normalized.length() > maxEntryLength
					? normalized.substring(0, maxEntryLength) : normalized);
		}
	}

	/** Editor representation. Semicolons and backslashes can be escaped with a backslash. */
	public String editorText() {
		return String.join("; ", value.stream()
				.map(entry -> entry.replace("\\", "\\\\").replace(";", "\\;"))
				.toList());
	}

	/** Parses the compact ClickGUI editor form without giving commas special meaning to regexes. */
	public void setEditorText(String text) {
		List<String> entries = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean escaped = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (escaped) {
				current.append(c);
				escaped = false;
			} else if (c == '\\') {
				escaped = true;
			} else if (c == ';') {
				entries.add(current.toString());
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		if (escaped) current.append('\\');
		entries.add(current.toString());
		setAll(entries);
	}

	/** Imports BetterChat's former comma-delimited config syntax, including escaped commas. */
	public void setLegacyCommaSeparated(String text) {
		List<String> entries = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean escaped = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (escaped) {
				current.append(c);
				escaped = false;
			} else if (c == '\\') {
				escaped = true;
			} else if (c == ',') {
				entries.add(current.toString());
				current.setLength(0);
			} else {
				current.append(c);
			}
		}
		if (escaped) current.append('\\');
		entries.add(current.toString());
		setAll(entries);
	}
}

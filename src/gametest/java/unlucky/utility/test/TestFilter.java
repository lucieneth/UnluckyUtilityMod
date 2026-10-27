package unlucky.utility.test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;

/**
 * Which slice of the client gametests this run covers.
 *
 * <p>The whole suite is about two and a half minutes, and most of that is spent on things
 * unrelated to whatever is being worked on. The filter lets a run cover one area — a
 * category, one module, the screens — and skip the rest, shared passes included.
 *
 * <p>Read from {@code -Dunlucky.test=...} (which {@code build.gradle} forwards to the
 * {@code clientGameTest} run from the Gradle command line) or the {@code UNLUCKY_TEST}
 * environment variable. A comma-separated list of:
 * <ul>
 *   <li>{@code all} — everything, and the default;</li>
 *   <li>{@code modules} — every module and the module-wide passes, but no UI or input;</li>
 *   <li>a category name ({@code combat}, {@code render}, ...) — that category's modules
 *       and the contracts that cover them;</li>
 *   <li>{@code module:<Name>} — one module, by name, ignoring case;</li>
 *   <li>{@code ui} or {@code screens} — {@link ScreenSmokeTest} and the ClickGUI input checks;</li>
 *   <li>{@code panic} — the visibility metadata and the panic sweep;</li>
 *   <li>{@code input} — {@link InputTest}.</li>
 * </ul>
 *
 * <p>An unknown word is an error rather than an empty run: a typo that quietly tests
 * nothing reads exactly like a pass.
 */
final class TestFilter {
	static final String PROPERTY = "unlucky.test";
	static final String ENV = "UNLUCKY_TEST";

	static final TestFilter CURRENT = parse(read());

	private final String raw;
	private final boolean all;
	private final boolean modules;
	private final boolean ui;
	private final boolean panic;
	private final boolean input;
	private final Set<Category> categories;
	/** Lower-case, as typed. */
	private final Set<String> moduleNames;

	private TestFilter(String raw, boolean all, boolean modules, boolean ui, boolean panic,
			boolean input, Set<Category> categories, Set<String> moduleNames) {
		this.raw = raw;
		this.all = all;
		this.modules = modules;
		this.ui = ui;
		this.panic = panic;
		this.input = input;
		this.categories = categories;
		this.moduleNames = moduleNames;
	}

	private static String read() {
		String value = System.getProperty(PROPERTY);
		if (value == null || value.isBlank()) {
			value = System.getenv(ENV);
		}
		return value == null || value.isBlank() ? "all" : value.trim();
	}

	static TestFilter parse(String raw) {
		boolean all = false;
		boolean modules = false;
		boolean ui = false;
		boolean panic = false;
		boolean input = false;
		Set<Category> categories = EnumSet.noneOf(Category.class);
		Set<String> names = new LinkedHashSet<>();

		for (String part : raw.split(",")) {
			String token = part.trim().toLowerCase(Locale.ROOT);
			if (token.isEmpty()) {
				continue;
			}
			if (token.startsWith("module:")) {
				String name = token.substring("module:".length()).trim();
				if (name.isEmpty()) {
					throw new IllegalArgumentException(PROPERTY + ": 'module:' needs a module name");
				}
				names.add(name);
				continue;
			}
			switch (token) {
				case "all" -> all = true;
				case "modules" -> modules = true;
				case "ui", "screens" -> ui = true;
				case "panic" -> panic = true;
				case "input" -> input = true;
				default -> {
					Category category = category(token);
					if (category == null) {
						throw new IllegalArgumentException(PROPERTY + ": unknown filter '" + part.trim()
								+ "'. Expected all, modules, ui, screens, panic, input, module:<Name> or "
								+ "a category (" + categoryNames() + ").");
					}
					categories.add(category);
				}
			}
		}
		if (!modules && !ui && !panic && !input && categories.isEmpty() && names.isEmpty()) {
			all = true;
		}
		return new TestFilter(raw, all, modules, ui, panic, input, categories, names);
	}

	private static Category category(String token) {
		for (Category category : Category.values()) {
			if (category.name().equalsIgnoreCase(token) || category.displayName().equalsIgnoreCase(token)) {
				return category;
			}
		}
		return null;
	}

	private static String categoryNames() {
		List<String> names = new ArrayList<>();
		for (Category category : Category.values()) {
			names.add(category.name().toLowerCase(Locale.ROOT));
		}
		return String.join(", ", names);
	}

	boolean isAll() {
		return all;
	}

	/** Every module, plus the passes that only mean something across all of them. */
	boolean moduleSuite() {
		return all || modules;
	}

	boolean ui() {
		return all || ui;
	}

	boolean panic() {
		return all || panic;
	}

	boolean input() {
		return all || input;
	}

	/** Whether any module at all is to be swept. */
	boolean anyModules() {
		return moduleSuite() || !categories.isEmpty() || !moduleNames.isEmpty();
	}

	boolean selects(Module module) {
		return moduleSuite()
				|| categories.contains(module.getCategory())
				|| moduleNames.contains(module.getName().toLowerCase(Locale.ROOT));
	}

	/** Whether the whole of {@code category} is in the run, not just a module from it. */
	boolean covers(Category category) {
		return moduleSuite() || categories.contains(category);
	}

	/** Whether the run selects any of these modules — the gate for a contract that tests them. */
	@SafeVarargs
	final boolean covers(Class<? extends Module>... types) {
		return covers(List.of(types));
	}

	boolean covers(List<Class<? extends Module>> types) {
		for (Class<? extends Module> type : types) {
			Module module = UnluckyClient.INSTANCE.modules.get(type);
			if (module != null && selects(module)) {
				return true;
			}
		}
		return false;
	}

	/** The {@code module:} names, lower-case, for checking each one matched something. */
	Set<String> moduleNames() {
		return moduleNames;
	}

	@Override
	public String toString() {
		return PROPERTY + "=" + raw;
	}
}

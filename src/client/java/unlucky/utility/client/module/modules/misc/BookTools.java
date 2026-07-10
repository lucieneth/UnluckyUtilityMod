package unlucky.utility.client.module.modules.misc;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;

/**
 * Enhancements for working with books: formatting-code buttons in the book
 * editor and a deobfuscate button in the book viewer.
 * Inspired by Stardust's BookTools.
 */
public class BookTools extends Module {
	public final BooleanSetting editorButtons = add(new BooleanSetting("Editor buttons", "Format code buttons in the book editor", true));
	public final BooleanSetting deobfuscate = add(new BooleanSetting("Deobfuscate", "Deobfuscate button in the book viewer", true));

	/** Color codes cycled by the editor's color button. */
	public static final char[] COLOR_CODES = "0123456789abcdef".toCharArray();
	public static final String[] COLOR_NAMES = {
			"Black", "Dark Blue", "Dark Green", "Dark Aqua", "Dark Red", "Purple", "Gold", "Gray",
			"Dark Gray", "Blue", "Green", "Aqua", "Red", "Pink", "Yellow", "White"
	};

	public BookTools() {
		super("BookTools", "Book editing and reading helpers", Category.MISC);
		setEnabledSilently(true);
	}
}

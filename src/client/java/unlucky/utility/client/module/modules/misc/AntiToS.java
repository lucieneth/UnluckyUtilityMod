package unlucky.utility.client.module.modules.misc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import unlucky.utility.client.UnluckyClientMod;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.util.ChatUtil;

/**
 * Censors player-generated text (chat, signs) against a user-editable
 * blacklist file. Inspired by Stardust's AntiToS.
 */
public class AntiToS extends Module {
	private final List<Pattern> patterns = new ArrayList<>();

	public AntiToS() {
		super("AntiToS", "Censors blacklisted words on render", Category.MISC);
	}

	private Path blacklistFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("unlucky-antitos.txt");
	}

	@Override
	protected void onEnable() {
		loadBlacklist();
		if (patterns.isEmpty()) {
			ChatUtil.info("§7AntiToS blacklist is empty. Add words to §fconfig/unlucky-antitos.txt§7, one per line, then re-toggle.");
		} else {
			ChatUtil.info("§7AntiToS loaded §f" + patterns.size() + "§7 blacklist entries.");
		}
	}

	private void loadBlacklist() {
		patterns.clear();
		Path file = blacklistFile();
		try {
			if (!Files.exists(file)) {
				Files.writeString(file, """
						# Unlucky AntiToS blacklist
						# One word or phrase per line, case-insensitive. Lines starting with # are ignored.
						""");
				return;
			}
			for (String line : Files.readAllLines(file)) {
				String trimmed = line.trim();
				if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
					patterns.add(Pattern.compile(Pattern.quote(trimmed), Pattern.CASE_INSENSITIVE));
				}
			}
		} catch (IOException e) {
			UnluckyClientMod.LOGGER.error("Failed to load AntiToS blacklist", e);
		}
	}

	public String censorString(String text) {
		String result = text;
		for (Pattern pattern : patterns) {
			result = pattern.matcher(result).replaceAll(match -> "*".repeat(match.group().length()));
		}
		return result;
	}

	/**
	 * Censored copy of the component, or the original when nothing matched.
	 * Styling is dropped on offending text only.
	 */
	public Component censor(Component original) {
		if (patterns.isEmpty()) {
			return original;
		}
		String text = original.getString();
		String censored = censorString(text);
		return censored.equals(text) ? original : Component.literal(censored);
	}
}

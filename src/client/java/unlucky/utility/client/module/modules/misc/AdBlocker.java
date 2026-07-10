package unlucky.utility.client.module.modules.misc;

import java.util.List;
import java.util.Locale;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.util.ChatUtil;

/**
 * Blocks advertisers in chat, including death-message advertisers.
 * Inspired by Stardust's AdBlocker.
 */
public class AdBlocker extends Module {
	// links are almost always ads on anarchy servers
	private static final List<String> LINK_PATTERNS = List.of(
			"discord.gg/", "dsc.gg/", "discord.com/invite", "bit.ly/", "tinyurl.com/",
			".shop", ".store", ".buycraft"
	);
	// common shop-advertiser phrasing
	private static final List<String> SHOP_PATTERNS = List.of(
			"selling kits", "cheap kits", "buy kits", "kits for sale", "stacked account",
			"cheap accounts", "selling account", "/visit", "shulkers for sale"
	);

	public final ModeSetting strictness = add(new ModeSetting("Strictness", "What counts as an ad", "Links+Shops", "Links", "Links+Shops"));
	public final BooleanSetting notify = add(new BooleanSetting("Notify", "Say in chat when a message is blocked", false));

	private long blockedCount;

	public AdBlocker() {
		super("AdBlocker", "Blocks chat advertisers", Category.MISC);
	}

	public boolean shouldBlock(String message) {
		String lower = message.toLowerCase(Locale.ROOT);
		for (String pattern : LINK_PATTERNS) {
			if (lower.contains(pattern)) {
				return true;
			}
		}
		if (strictness.is("Links+Shops")) {
			for (String pattern : SHOP_PATTERNS) {
				if (lower.contains(pattern)) {
					return true;
				}
			}
		}
		return false;
	}

	public void onBlocked() {
		blockedCount++;
		if (notify.get()) {
			ChatUtil.info("§7Blocked an ad §8(#" + blockedCount + ")");
		}
	}
}

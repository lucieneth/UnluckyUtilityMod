package unlucky.utility.client.module.modules.player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.InteractUtil;

/**
 * Sounds a goat horn whenever a player enters your render distance.
 * Inspired by Stardust's Honker.
 */
public class Honker extends Module {
	public final BooleanSetting notify = add(new BooleanSetting("Notify", "Say who triggered the honk", true));

	private final Set<UUID> knownPlayers = new HashSet<>();
	private Level lastLevel;

	public Honker() {
		super("Honker", "Honks at arriving players", Category.PLAYER);
	}

	@Override
	protected void onEnable() {
		rememberCurrentPlayers();
	}

	@Override
	public void onTick() {
		if (mc().level == null || mc().player == null) {
			return;
		}
		if (mc().level != lastLevel) {
			rememberCurrentPlayers();
			return;
		}

		for (AbstractClientPlayer player : mc().level.players()) {
			if (player == mc().player || !knownPlayers.add(player.getUUID())) {
				continue;
			}
			int slot = InteractUtil.findHotbarItem(Items.GOAT_HORN);
			if (slot < 0) {
				return; // no horn, no honk — keep the player marked as seen
			}
			InteractUtil.withHotbarSlot(slot, InteractUtil::useItem);
			if (notify.get()) {
				ChatUtil.info("§7Honked at §f" + player.getName().getString());
			}
			return; // one honk per tick is plenty
		}
	}

	private void rememberCurrentPlayers() {
		knownPlayers.clear();
		lastLevel = mc().level;
		if (mc().level != null) {
			for (AbstractClientPlayer player : mc().level.players()) {
				knownPlayers.add(player.getUUID());
			}
		}
	}
}

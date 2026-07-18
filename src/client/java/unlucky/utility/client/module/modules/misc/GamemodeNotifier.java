package unlucky.utility.client.module.modules.misc;

import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.level.GameType;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.FriendManager;
import unlucky.utility.client.util.PingSound;

/**
 * Says in chat when someone's gamemode changes — the usual tell that a staff
 * member just went creative or spectator on you.
 *
 * <p>Fed from {@code ClientPacketListenerMixin} at the {@code handlePlayerInfoUpdate}
 * HEAD, which is the one moment both halves of the diff exist: the tab list still
 * holds the old mode and the packet entry carries the new one.
 */
public class GamemodeNotifier extends Module {
	public final ModeSetting filter = add(new ModeSetting("Filter", "Whose changes to announce", "All",
			"All", "Creative+Spectator", "Friends"));
	public final BooleanSetting self = add(new BooleanSetting("Self", "Also announce your own changes", false));
	public final BooleanSetting ping = add(new BooleanSetting("Ping", "Play a sound with the message", true));
	public final ModeSetting sound = add(new ModeSetting("Sound", "Which sound the ping plays", "Bell", PingSound.MODES));
	public final NumberSetting pitch = add(new NumberSetting("Pitch", "Ping pitch", 1.0, 0.5, 2.0, 0.1));

	public GamemodeNotifier() {
		super("GamemodeNotifier", "Announces when players switch gamemode", Category.MISC);
	}

	/**
	 * ClientPacketListenerMixin, on the main-thread pass at HEAD — before vanilla
	 * writes the new mode into the tab list.
	 */
	public void onPlayerInfoUpdate(ClientboundPlayerInfoUpdatePacket packet) {
		if (!packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE)
				|| mc().getConnection() == null) {
			return;
		}
		for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
			PlayerInfo known = mc().getConnection().getPlayerInfo(entry.profileId());
			// no tab-list entry yet = this player is joining (the server bundles
			// ADD_PLAYER with UPDATE_GAME_MODE), not switching — otherwise every
			// world join would announce the whole player list
			if (known == null) {
				continue;
			}
			GameType from = known.getGameMode();
			GameType to = entry.gameMode();
			if (from == to || to == null || !announces(entry, to)) {
				continue;
			}
			announce(entry, from, to);
		}
	}

	private boolean announces(ClientboundPlayerInfoUpdatePacket.Entry entry, GameType to) {
		if (!self.get() && mc().getUser() != null && entry.profileId().equals(mc().getUser().getProfileId())) {
			return false;
		}
		return switch (filter.get()) {
			case "Creative+Spectator" -> to == GameType.CREATIVE || to == GameType.SPECTATOR;
			case "Friends" -> FriendManager.isFriend(entry.profileId());
			default -> true;
		};
	}

	private void announce(ClientboundPlayerInfoUpdatePacket.Entry entry, GameType from, GameType to) {
		String name = entry.profile() == null ? null : entry.profile().name();
		if (name == null || name.isEmpty()) {
			// the entry only carries a profile on ADD_PLAYER; a bare mode switch
			// leaves it null, so fall back to the name the tab list already knows
			PlayerInfo known = mc().getConnection().getPlayerInfo(entry.profileId());
			name = known == null ? entry.profileId().toString().substring(0, 8) : known.getProfile().name();
		}
		String friend = FriendManager.isFriend(entry.profileId())
				? "§9" + UnluckyClient.INSTANCE.modules.get(Friends.class).markerText() + " " : "";
		ChatUtil.info(friend + "§f" + name + " §7" + color(from) + from.getShortDisplayName().getString()
				+ " §8→ " + color(to) + to.getShortDisplayName().getString());
		if (ping.get()) {
			PingSound.play(sound.get(), pitch.getFloat());
		}
	}

	/** The colors the tab list and F3 already train you to read these modes in. */
	private static String color(GameType mode) {
		return switch (mode) {
			case CREATIVE -> ChatFormatting.LIGHT_PURPLE.toString();
			case SPECTATOR -> ChatFormatting.GRAY.toString();
			case ADVENTURE -> ChatFormatting.AQUA.toString();
			default -> ChatFormatting.GREEN.toString();
		};
	}
}

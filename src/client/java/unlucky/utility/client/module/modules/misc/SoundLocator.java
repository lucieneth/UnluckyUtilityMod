package unlucky.utility.client.module.modules.misc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.settings.StringListSetting;
import unlucky.utility.client.util.ChatUtil;

/**
 * Logs incoming sounds with their position — great for noticing player
 * activity around you (doors, chests, pistons...) before you see it.
 */
public class SoundLocator extends Module {
	// sound id fragments that are almost always caused by a player
	private static final List<String> PLAYER_MADE = List.of(
			"door", "trapdoor", "fence_gate", "chest", "barrel", "shulker", "anvil", "piston",
			"firework", "totem", "armor.equip", "grindstone", "smithing", "brewing_stand",
			"experience_orb", "bottle", "fishing_bobber", "crossbow", "bow"
	);

	public final ModeSetting filter = add(new ModeSetting("Filter", "Which sounds to log", "Player-made",
			"Player-made", "All", "Whitelist", "Blacklist"));
	public final StringListSetting patterns = add(new StringListSetting("Sound patterns",
			"Semicolon-separated sound-id fragments used by Whitelist and Blacklist"));
	public final NumberSetting minDistance = add(new NumberSetting("Min distance", "Ignore sounds closer than this", 12, 0, 64, 2));
	public final NumberSetting maxDistance = add(new NumberSetting("Max distance", "Ignore sounds farther away (0 disables the limit)",
			128, 0, 512, 8));
	public final NumberSetting minVolume = add(new NumberSetting("Min volume",
			"Ignore quiet sound packets that are unlikely to be useful", 0.0, 0.0, 4.0, 0.1));
	public final NumberSetting cooldownSeconds = add(new NumberSetting("Repeat cooldown",
			"Seconds before the same sound can be reported again", 2.0, 0.0, 30.0, 0.5));

	// per-sound cooldown so spammy sounds do not flood the chat
	private final Map<String, Long> lastLogged = new HashMap<>();

	public SoundLocator() {
		super("SoundLocator", "Logs nearby sounds with coordinates", Category.MISC, ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected void onEnable() {
		lastLogged.clear();
	}

	/** Called from the packet mixin. */
	public void onSound(ClientboundSoundPacket packet) {
		if (mc().player == null) {
			return;
		}
		String id = packet.getSound().value().location().getPath();
		if (!matchesFilter(id)) {
			return;
		}
		if (packet.getVolume() < minVolume.get()) {
			return;
		}
		double dx = packet.getX() - mc().player.getX();
		double dy = packet.getY() - mc().player.getY();
		double dz = packet.getZ() - mc().player.getZ();
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (distance < minDistance.get() || (maxDistance.get() > 0 && distance > maxDistance.get())) {
			return;
		}

		long now = System.currentTimeMillis();
		Long last = lastLogged.get(id);
		if (last != null && now - last < cooldownSeconds.get() * 1000.0) {
			return;
		}
		lastLogged.put(id, now);

		ChatUtil.info(String.format("§7%s §8@ §f%d %d %d §8(%dm)",
				id, (int) packet.getX(), (int) packet.getY(), (int) packet.getZ(), (int) distance));
	}

	private boolean matchesFilter(String id) {
		if (filter.is("All")) return true;
		if (filter.is("Player-made")) return PLAYER_MADE.stream().anyMatch(id::contains);
		boolean listed = patterns.get().stream()
				.map(pattern -> pattern.toLowerCase(java.util.Locale.ROOT))
				.anyMatch(pattern -> !pattern.isEmpty() && id.contains(pattern));
		return filter.is("Whitelist") ? listed : !listed;
	}
}

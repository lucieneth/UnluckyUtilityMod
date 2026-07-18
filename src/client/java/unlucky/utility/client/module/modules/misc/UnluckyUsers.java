package unlucky.utility.client.module.modules.misc;

import net.minecraft.core.ClientAsset;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.UnluckyClientMod;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.player.Capes;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.util.CapeManager;
import unlucky.utility.client.util.net.RegistryUsers;
import unlucky.utility.client.util.net.UnluckyApi;

/**
 * Shows who else on the server is running Unlucky: a marker beside their name in
 * the tab list, and their registered cape on their back.
 *
 * <p>The data is a public batched lookup against the registry
 * ({@link RegistryUsers}) — cached, negative-cached, and refreshed on a slow
 * poll. Claiming a cape needs the Mojang handshake ({@code registry login});
 * <i>seeing</i> other people's needs nothing at all.
 */
public class UnluckyUsers extends Module {
	private static final int POLL_TICKS = 100; // 5s

	/** Shown for a user who has never chosen a color of their own. */
	private static final int DEFAULT_COLOR = 0xFFB478FF;

	// ★ default — the crisp twin of the old ✦ (which only exists in the blocky
	// unifont fallback; so does ☘, kept because it's the actual clover). Marks are
	// local rendering, so everyone picks their own; only the COLOR is shared.
	public final unlucky.utility.client.settings.ModeSetting style = add(new unlucky.utility.client.settings.ModeSetting(
			"Style", "Your mark for Unlucky users — pick a vibe", "★",
			"★", "∞", "†", "♥", "♣", "♠", "❤", "☘", "⚡", "◆", "‡", "☠", "ᴜʟ"));
	public final BooleanSetting tablist = add(new BooleanSetting("Tablist marker", "Mark Unlucky users in the tab list", true));
	public final BooleanSetting capes = add(new BooleanSetting("Capes", "Render other users' registered capes", true));
	public final BooleanSetting share = add(new BooleanSetting("Share", "Publish your cape and marker color to the registry", true));
	public final ColorSetting myColor = add(new ColorSetting("My marker color",
			"The color YOUR marker shows in — on everyone's screen, not just yours", DEFAULT_COLOR));

	/** The Unlucky mark as text — {@code ✦} or {@code ᴜʟ} per Style — for every text site. */
	public String markerText() {
		return style.get();
	}

	private int ticks;
	/** Last profile the registry has accepted, so we only PUT on a real change. */
	private String published;

	public UnluckyUsers() {
		super("UnluckyUsers", "See other Unlucky users and their capes", Category.MISC);
		// the one module a fresh install starts with: it's what makes the client
		// recognisable to other users, and it does nothing until you're on a server
		setEnabledSilently(true);
	}

	@Override
	protected void onDisable() {
		RegistryUsers.clear();
		published = null;
	}

	@Override
	public void onTick() {
		if (!isEnabled() || mc().getConnection() == null) {
			return;
		}
		if (++ticks >= POLL_TICKS) {
			ticks = 0;
			RegistryUsers.poll();
			publishOwnCape();
		}
	}

	/**
	 * Mirrors whatever the Capes module has selected up to the registry. Nothing to
	 * pick twice: the module <i>is</i> the picker. Module off, or "None" chosen, and
	 * we publish {@code none} so the cape comes off everyone else's screen too.
	 *
	 * <p>{@code custom} capes are skipped — those PNGs live only on this disk, so no
	 * other client could resolve them.
	 */
	private void publishOwnCape() {
		if (!share.get() || mc().getUser() == null) {
			return;
		}
		Capes module = UnluckyClient.INSTANCE.modules.get(Capes.class);
		String id = "none";
		if (module.isEnabled() && !module.cape.is("None")) {
			String group = module.folder.get();
			if (CapeManager.isShareable(group)) {
				id = group + ":" + module.cape.get();
			}
		}
		int rgb = myColor.get() & 0xFFFFFF;
		// bind the state to the account too, so switching alts always republishes
		String state = mc().getUser().getProfileId() + ":" + id + "@" + Integer.toHexString(rgb);
		if (state.equals(published)) {
			return;
		}
		// only remember it as published once the registry has accepted it — marking it
		// up front means a rejected PUT (registry restart, network blip) is never retried
		// and the cape silently never appears. On success also invalidate our own cache,
		// so our marker shows up for us without waiting out the negative-cache backoff.
		UnluckyApi.setProfile(id, rgb,
				cape -> {
					published = state;
					if (mc().player != null) {
						RegistryUsers.invalidate(mc().player.getUUID());
					}
				},
				err -> {
					published = null;
					UnluckyClientMod.LOGGER.warn("Could not publish profile: {}", err);
				});
	}

	/**
	 * The marker color for a player, or 0 for "not a user / don't mark".
	 *
	 * <p>It's <b>their</b> color, not ours: the registry carries the one they chose,
	 * so a player who picked green is green on every screen. We only fall back to the
	 * default for a user who never set one.
	 */
	public int markerFor(java.util.UUID uuid) {
		if (!isEnabled() || !tablist.get() || uuid == null || !RegistryUsers.isUser(uuid)) {
			return 0;
		}
		Integer theirs = RegistryUsers.colorOf(uuid);
		return theirs == null ? DEFAULT_COLOR : 0xFF000000 | theirs;
	}

	/**
	 * The cape texture a player registered, or null (no cape / still streaming).
	 *
	 * <p>The registry only ever hands us a {@code group:name} id — the texture itself
	 * comes from the same place it would for our own cape: Mojang's texture server or
	 * the GitHub cape repo, streamed and cached by {@link CapeManager}. So the registry
	 * hosts nothing, and a new cape in the repo works for everyone with no client update.
	 */
	public ClientAsset.Texture capeFor(java.util.UUID uuid) {
		if (!isEnabled() || !capes.get() || uuid == null) {
			return null;
		}
		String id = RegistryUsers.capeOf(uuid);
		if (id == null) {
			return null;
		}
		int split = id.indexOf(':');
		if (split <= 0) {
			return null;
		}
		String group = id.substring(0, split);
		if (!CapeManager.isShareable(group)) {
			return null; // a local-only cape somehow got registered; ignore it
		}
		return CapeManager.textureFor(group, id.substring(split + 1));
	}
}

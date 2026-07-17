package unlucky.utility.client.module.modules.misc;

import net.minecraft.client.multiplayer.ServerData;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.util.discord.DiscordRpcThread;

/**
 * Shows Unlucky Client on your Discord profile.
 *
 * <p>All the socket work lives on {@link DiscordRpcThread}; this only decides
 * what the presence should say and hands it over. Discord not being open is fine
 * and silent — the thread retries on its own.
 *
 * <p><b>Privacy:</b> the server you're on is never shown unless you turn
 * {@link #showServer} on. Default is off, because a Discord presence is public to
 * everyone on every server you're in, and "which anarchy server is this person on
 * right now" is not ours to broadcast by default.
 */
public class DiscordRPC extends Module {
	/**
	 * The Discord application this presence belongs to. Public by design — it's
	 * the app's identity, not a secret (the client secret, which we never need,
	 * is the secret one).
	 */
	private static final String CLIENT_ID = "1345365368147021905";
	/** Art-asset key uploaded on the Discord app; must match the name there. */
	private static final String LARGE_IMAGE = "chatgpt_image_9_7_2026_21_42_15";
	private static final int UPDATE_TICKS = 20;

	public final ModeSetting detail = add(new ModeSetting("Detail", "What the top line says", "Client",
			"Client", "Dimension", "Nothing"));
	public final BooleanSetting showServer = add(new BooleanSetting("Show server",
			"Put the server address on your Discord profile — everyone who can see your profile sees it", false));
	public final BooleanSetting elapsed = add(new BooleanSetting("Elapsed", "Show how long you've been playing", true));

	private DiscordRpcThread rpc;
	/** When this presence session started, for the elapsed timer. */
	private long startedAt;
	private int ticks;

	public DiscordRPC() {
		super("DiscordRPC", "Shows Unlucky Client on your Discord profile", Category.MISC);
	}

	@Override
	protected void onEnable() {
		startedAt = System.currentTimeMillis() / 1000L;
		rpc = new DiscordRpcThread(CLIENT_ID);
		ticks = UPDATE_TICKS; // push immediately rather than after the first second
	}

	@Override
	protected void onDisable() {
		if (rpc != null) {
			rpc.shutdown();
			rpc = null;
		}
	}

	@Override
	public void onTick() {
		if (rpc == null || ++ticks < UPDATE_TICKS) {
			return;
		}
		ticks = 0;
		rpc.set(new DiscordRpcThread.Presence(details(), state(), elapsed.get() ? startedAt : 0L,
				LARGE_IMAGE, "Unlucky Client"));
	}

	private String details() {
		return switch (detail.get()) {
			case "Dimension" -> mc().level == null
					? "In the menus"
					: "In the " + dimensionName();
			case "Nothing" -> null;
			default -> "Unlucky Client";
		};
	}

	/** "the Nether" / "the End" / "the Overworld" — the path of the dimension id. */
	private String dimensionName() {
		String path = mc().level.dimension().identifier().getPath();
		return switch (path) {
			case "the_nether" -> "Nether";
			case "the_end" -> "End";
			case "overworld" -> "Overworld";
			default -> path.replace('_', ' ');
		};
	}

	private String state() {
		if (mc().level == null) {
			return "In the menus";
		}
		if (mc().hasSingleplayerServer()) {
			return "Singleplayer";
		}
		if (!showServer.get()) {
			return "Multiplayer";
		}
		ServerData server = mc().getCurrentServer();
		return server == null ? "Multiplayer" : "On " + server.ip;
	}
}

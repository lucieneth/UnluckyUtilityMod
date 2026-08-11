package unlucky.utility.client.module.modules.misc;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Puts you back on the server you fell off.
 *
 * <p><b>Why the disconnect is classified at all.</b> A reconnect is only ever wanted for the
 * disconnects you did not choose. Reconnecting after you clicked Disconnect is obnoxious;
 * reconnecting after AutoLog pulled you out of a fight is actively dangerous, since it walks
 * you straight back into the thing you fled — which is why {@link #markDeliberate} exists and
 * why the safety modules call it before they go.
 *
 * <p><b>The kick/timeout/ban split is a text match, and it is honest about that.</b> The
 * protocol gives us one disconnect message and no code; "Timed out" and "You are banned" are
 * strings a server operator writes. So those three switches work on well-known wording and
 * will miss a server that phrases it differently. The two that matter — deliberate versus
 * not — are structural and always right, because they are recorded at the point the
 * disconnect is asked for rather than guessed afterwards.
 */
public class AutoReconnect extends Module {
	private static final int TICKS_PER_SECOND = 20;

	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Seconds to wait before reconnecting", 3.5, 0.0, 60.0, 0.5));
	public final NumberSetting maxAttempts = add(new NumberSetting("Max attempts",
			"Give up after this many tries. 0 keeps going.", 0, 0, 50, 1));
	public final NumberSetting backoff = add(new NumberSetting("Retry delay increase",
			"Seconds added to the wait after each failed attempt", 0.0, 0.0, 30.0, 0.5));
	public final BooleanSetting afterKick = add(new BooleanSetting("After kick",
			"Reconnect when the server drops you", true));
	public final BooleanSetting afterTimeout = add(new BooleanSetting("After timeout",
			"Reconnect when the connection times out", true));
	public final BooleanSetting afterManual = add(new BooleanSetting("After manual disconnect",
			"Reconnect even when you chose to leave", false));
	public final BooleanSetting afterAutoLog = add(new BooleanSetting("After AutoLog",
			"Reconnect after a safety logout. Off, because that walks you back into whatever "
					+ "you just left.", false));
	public final BooleanSetting afterAuthError = add(new BooleanSetting("After ban or auth error",
			"Reconnect when the message looks like a ban, whitelist or authentication failure",
			false));
	public final BooleanSetting showCountdown = add(new BooleanSetting("Show countdown",
			"Put the countdown and its buttons on the disconnect screen", true));

	/** How the last disconnect came about. Set at the moment it happens, never inferred later. */
	public enum Cause {
		KICK, TIMEOUT, MANUAL, DELIBERATE, AUTH
	}

	/** The last server we were actually connected to, remembered while we still can be asked. */
	private static ServerData lastServer;
	/** Set by a module that is about to disconnect on purpose — see {@link #markDeliberate}. */
	private static String deliberateBy;
	/** An explicit per-disconnect decision from a safety module, if it supplied one. */
	private static Boolean deliberateReconnect;
	/** Set by the Minecraft mixin when a disconnect originates locally rather than from the wire. */
	private static boolean localDisconnect;

	private Cause cause;
	private Boolean reconnectDeliberate;
	private int ticksLeft = -1;
	private int attempts;
	private Button countdownButton;

	public AutoReconnect() {
		super("AutoReconnect", "Puts you back on the server you fell off", Category.MISC,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	/**
	 * Records that the disconnect about to happen is this client's own doing, so it is never
	 * mistaken for a kick. Called by AutoLog and RoadTrip immediately before they go.
	 */
	public static void markDeliberate(String who) {
		markDeliberate(who, null);
	}

	/**
	 * Marks a deliberate disconnect and, when non-null, overrides the usual AutoLog rule for
	 * this one disconnect. This lets AutoLog's policy use this module's delay without changing
	 * the user's general reconnect preference.
	 */
	public static void markDeliberate(String who, Boolean reconnect) {
		deliberateBy = who;
		deliberateReconnect = reconnect;
	}

	/** Called from {@code MinecraftMixin}: this disconnect came from our side, not the server's. */
	public static void onLocalDisconnect() {
		localDisconnect = true;
	}

	@Override
	protected void onDisable() {
		cancel();
	}

	/** Stops a scheduled reconnect and forgets the attempt count. */
	public void cancel() {
		ticksLeft = -1;
		attempts = 0;
		countdownButton = null;
	}

	public boolean isScheduled() {
		return ticksLeft >= 0;
	}

	/** Seconds still to wait, rounded up, for the button label. */
	public int secondsLeft() {
		return ticksLeft < 0 ? 0 : (ticksLeft + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
	}

	@Override
	public void onTick() {
		Minecraft mc = mc();

		// Remember the server while we are on it. getCurrentServer() is null by the time the
		// disconnect screen is up, so this cannot be left until it is needed.
		if (mc.getConnection() != null && mc.getCurrentServer() != null) {
			lastServer = mc.getCurrentServer();
			cancel();
			return;
		}

		if (!(mc.gui.screen() instanceof DisconnectedScreen)) {
			if (!(mc.gui.screen() instanceof ConnectScreen)) {
				// Anywhere else — the title screen, the server list — means the player moved
				// on deliberately, and a pending reconnect would drag them back.
				ticksLeft = -1;
			}
			return;
		}

		if (ticksLeft < 0) {
			return;
		}
		if (--ticksLeft <= 0) {
			ticksLeft = -1;
			reconnect();
			return;
		}
		if (countdownButton != null) {
			countdownButton.setMessage(Component.literal("Reconnecting in " + secondsLeft() + "s"));
		}
	}

	/**
	 * Decides whether this disconnect earns a reconnect, and schedules it if so. Called from
	 * the disconnect screen once it knows the reason.
	 */
	public void onDisconnected(Component reason) {
		cause = classify(reason);
		reconnectDeliberate = deliberateReconnect;
		deliberateBy = null;
		deliberateReconnect = null;
		localDisconnect = false;

		if (!isEnabled() || lastServer == null || !allowed(cause)) {
			ticksLeft = -1;
			return;
		}
		if (maxAttempts.getInt() > 0 && attempts >= maxAttempts.getInt()) {
			ticksLeft = -1;
			return;
		}
		double seconds = delay.get() + backoff.get() * attempts;
		ticksLeft = Math.max(1, (int) Math.round(seconds * TICKS_PER_SECOND));
	}

	private boolean allowed(Cause which) {
		return switch (which) {
			case KICK -> afterKick.get();
			case TIMEOUT -> afterTimeout.get();
			case MANUAL -> afterManual.get();
			case DELIBERATE -> reconnectDeliberate != null ? reconnectDeliberate : afterAutoLog.get();
			case AUTH -> afterAuthError.get();
		};
	}

	/**
	 * Structural facts first, wording second. Whether a module asked for this, and whether it
	 * came from our side at all, are both recorded rather than guessed; only once neither
	 * applies does the message get read.
	 */
	private static Cause classify(Component reason) {
		if (deliberateBy != null) {
			return Cause.DELIBERATE;
		}
		if (localDisconnect) {
			return Cause.MANUAL;
		}
		String text = reason == null ? "" : reason.getString().toLowerCase(Locale.ROOT);
		if (text.contains("timed out") || text.contains("timeout")) {
			return Cause.TIMEOUT;
		}
		if (text.contains("banned") || text.contains("whitelist") || text.contains("not authenticated")
				|| text.contains("authentication")) {
			return Cause.AUTH;
		}
		return Cause.KICK;
	}

	/** Registers the disconnect screen's countdown button so the label can be kept live. */
	public void setCountdownButton(Button button) {
		this.countdownButton = button;
	}

	/** Goes now, whatever the countdown says. Also the "Reconnect now" button's action. */
	public void reconnect() {
		Minecraft mc = mc();
		if (lastServer == null) {
			return;
		}
		attempts++;
		ticksLeft = -1;
		countdownButton = null;
		ConnectScreen.startConnecting(new TitleScreen(), mc,
				ServerAddress.parseString(lastServer.ip), lastServer, false, null);
	}

	/** Whether there is a server worth offering to return to. */
	public boolean hasTarget() {
		return lastServer != null;
	}

	/** For the disconnect screen's label. */
	public String targetName() {
		return lastServer == null ? "" : lastServer.name;
	}
}

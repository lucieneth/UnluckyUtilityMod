package unlucky.utility.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/**
 * The short alert sounds modules ping with, shared so the option list and the
 * lookup stay in one place.
 *
 * <p>{@link #MODES} is varargs-ready for {@code new ModeSetting(..., PingSound.MODES)}.
 */
public final class PingSound {
	public static final String[] MODES = {"Pling", "Bell", "Orb", "Chime"};

	/**
	 * The two sounds this client ships itself, declared in {@code assets/unlucky/sounds.json}.
	 *
	 * <p><b>Built, not registered.</b> {@code BuiltInRegistries.SOUND_EVENT} is frozen long before
	 * a client mod initialises, and nothing here needs it to be in there: the sound manager
	 * resolves a {@link SoundEvent} by its location against the sound definitions it loaded from
	 * every resource pack, and ours is one of those. Registration would only matter for a sound
	 * the <em>server</em> asks a client to play, which is not what these are.
	 */
	public static final SoundEvent HITMARKER = SoundEvent.createVariableRangeEvent(
			Identifier.fromNamespaceAndPath("unlucky", "hitmarker"));
	public static final SoundEvent HIT = SoundEvent.createVariableRangeEvent(
			Identifier.fromNamespaceAndPath("unlucky", "hit1"));

	private PingSound() {
	}

	/**
	 * Plays a UI-space (non-positional) ping; unknown modes fall back to Pling.
	 *
	 * <p>The combat cues — {@code "Hitmarker"}, {@code "Classic"} and {@code "Crit"} — are
	 * deliberately playable but not in {@link #MODES}: they belong to the modules that mark hits
	 * and would be noise in the notification-style pickers every other caller builds from that
	 * array. Routing them through here anyway keeps one place that knows which sound a name means.
	 */
	public static void play(String mode, float pitch) {
		play(mode, pitch, 1.0f);
	}

	/** As {@link #play(String, float)}, with an explicit volume. */
	public static void play(String mode, float pitch, float volume) {
		Minecraft mc = Minecraft.getInstance();
		// SoundEvents mixes plain SoundEvent and Holder<SoundEvent> fields, and only the
		// two-argument forUI overloads both. The three-argument one — the one that takes a volume
		// — is SoundEvent-only, so the held ones are unwrapped here rather than at every call.
		SoundEvent event = switch (mode) {
			case "Hitmarker" -> HITMARKER;
			case "Classic" -> HIT;
			case "Crit" -> SoundEvents.PLAYER_ATTACK_CRIT;
			case "Bell" -> SoundEvents.NOTE_BLOCK_BELL.value();
			case "Orb" -> SoundEvents.EXPERIENCE_ORB_PICKUP;
			case "Chime" -> SoundEvents.AMETHYST_BLOCK_CHIME;
			default -> SoundEvents.NOTE_BLOCK_PLING.value();
		};
		mc.getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, volume));
	}
}

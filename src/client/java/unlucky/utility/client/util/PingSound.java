package unlucky.utility.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * The short alert sounds modules ping with, shared so the option list and the
 * lookup stay in one place.
 *
 * <p>{@link #MODES} is varargs-ready for {@code new ModeSetting(..., PingSound.MODES)}.
 */
public final class PingSound {
	public static final String[] MODES = {"Pling", "Bell", "Orb", "Chime"};

	private PingSound() {
	}

	/** Plays a UI-space (non-positional) ping; unknown modes fall back to Pling. */
	public static void play(String mode, float pitch) {
		Minecraft mc = Minecraft.getInstance();
		// SoundEvents mixes plain SoundEvent and Holder<SoundEvent> fields; forUI
		// overloads both, so either kind resolves here
		switch (mode) {
			case "Bell" -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL, pitch));
			case "Orb" -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, pitch));
			case "Chime" -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, pitch));
			default -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, pitch));
		}
	}
}

package unlucky.utility.client.util;

/**
 * One owner for every client-side weather hook.
 *
 * <p>{@code LevelMixin} and {@code ClientLevelMixin} are the hooks already proven to cover
 * levels, effects/sound and lightning flash. Modules describe a state here instead of adding
 * another handler to those methods. Nullable levels mean "keep the server value", which lets
 * the transitional NoWeather toggles suppress rain and thunder independently.
 */
public final class WeatherOverrideManager {
	public enum Mode {
		SERVER, CLEAR, RAIN, THUNDER, SNOW
	}

	public record State(Mode mode, Float rainLevel, Float thunderLevel,
			float precipitationOpacity, boolean particles, boolean ambientSound,
			boolean skyFlash, boolean snowEverywhere) {
		public State {
			rainLevel = rainLevel == null ? null : Math.clamp(rainLevel, 0.0f, 1.0f);
			thunderLevel = thunderLevel == null ? null : Math.clamp(thunderLevel, 0.0f, 1.0f);
			precipitationOpacity = Math.clamp(precipitationOpacity, 0.0f, 1.0f);
		}

		public static State noWeather(boolean hideRain, boolean hideThunder) {
			return new State(Mode.CLEAR, hideRain ? 0.0f : null,
					hideThunder ? 0.0f : null, hideRain ? 0.0f : 1.0f,
					!hideRain, !hideRain, !hideThunder, false);
		}

		public static State forMode(Mode mode, float rainStrength, float thunderStrength,
				float opacity, boolean particles, boolean ambientSound, boolean skyFlash,
				boolean snowEverywhere) {
			return switch (mode) {
				case SERVER -> new State(mode, null, null, opacity, particles, ambientSound,
						skyFlash, false);
				case CLEAR -> new State(mode, 0.0f, 0.0f, 0.0f, particles, ambientSound,
						skyFlash, false);
				case RAIN -> new State(mode, rainStrength, 0.0f, opacity, particles,
						ambientSound, skyFlash, false);
				case THUNDER -> new State(mode, rainStrength, thunderStrength, opacity,
						particles, ambientSound, skyFlash, false);
				case SNOW -> new State(mode, rainStrength, 0.0f, opacity, particles,
						ambientSound, skyFlash, snowEverywhere);
			};
		}
	}

	private static Object owner;
	private static State state;
	private static int priority;

	private WeatherOverrideManager() {
	}

	public static synchronized boolean request(Object requester, State requested) {
		return request(requester, requested, 0);
	}

	public static synchronized boolean request(Object requester, State requested, int requestedPriority) {
		if (requester == null || requested == null
				|| (owner != null && owner != requester && requestedPriority <= priority)) {
			return false;
		}
		owner = requester;
		state = requested;
		priority = requestedPriority;
		return true;
	}

	public static synchronized void release(Object requester) {
		if (owner == requester) {
			reset();
		}
	}

	public static synchronized float rainLevel(float server) {
		return state == null || state.rainLevel() == null ? server : state.rainLevel();
	}

	public static synchronized float thunderLevel(float server) {
		return state == null || state.thunderLevel() == null ? server : state.thunderLevel();
	}

	public static synchronized boolean weatherEffectsAllowed() {
		return state == null || state.particles() || state.ambientSound();
	}

	public static synchronized boolean particlesAllowed() {
		return state == null || state.particles();
	}

	public static synchronized boolean ambientSoundAllowed() {
		return state == null || state.ambientSound();
	}

	public static synchronized boolean skyFlashAllowed() {
		return state == null || state.skyFlash();
	}

	public static synchronized State state() {
		return state;
	}

	public static synchronized boolean snowEverywhere() {
		return state != null && state.mode() == Mode.SNOW && state.snowEverywhere();
	}

	public static synchronized void reset() {
		owner = null;
		state = null;
		priority = 0;
	}
}

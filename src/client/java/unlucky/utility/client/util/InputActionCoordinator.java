package unlucky.utility.client.util;

import java.lang.ref.WeakReference;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.mixin.KeyMappingAccessor;

/**
 * One owner at a time for each synthetic key hold — movement, jump, sneak, sprint, use, attack.
 *
 * <p>{@code KeyMapping.setDown} is a global write with no record of who wrote it. Two modules
 * holding the same key is not two holds: it is one hold and one module that thinks it will get
 * to let go, so whichever stops first releases a key the other still needs, and whichever stops
 * last leaves one down that nobody is watching. A stuck use key is a right-click held for as
 * long as it takes you to notice; a stuck forward key walks you off the map.
 *
 * <p><b>Requests are declarative and expire every tick.</b> Ask again each tick for as long as
 * you want the key, exactly like {@link MovementActionCoordinator}. Stop asking and the key is
 * released for you at tick end — there is no "I forgot to release" failure mode left to have,
 * which is the entire reason this exists rather than a lease that must be handed back.
 *
 * <p><b>Releasing restores the player's real key state, not "up".</b> Vanilla only rewrites a
 * mapping on a key <em>event</em>, so blindly clearing a key the player is physically holding
 * leaves them unable to walk until they let go and press it again. The hardware is polled
 * through {@link KeyMappingAccessor} so a release hands control back to the hand on the
 * keyboard instead of taking it away. (While a screen is open there is nothing to hand back —
 * vanilla has already released everything — so the restore is "up" there.)
 *
 * @see MovementActionCoordinator for the velocity that results, which is a separate arbiter
 */
public final class InputActionCoordinator {
	/** Idle filler: AntiAFK. Anything with a reason to move outranks looking busy. */
	public static final int PRIORITY_IDLE = 20;
	/** Deliberate travel the player asked for: AutoWalk. */
	public static final int PRIORITY_TRAVEL = 40;
	/** Staying alive and fed: AutoEat's held use key. */
	public static final int PRIORITY_SURVIVAL = 80;
	/** Reactive rescue movement — a hold that exists because the alternative is dying. */
	public static final int PRIORITY_SAFETY = 100;

	/** The keys this coordinator is allowed to touch. Deliberately not "every mapping". */
	public enum Key {
		FORWARD,
		BACK,
		LEFT,
		RIGHT,
		JUMP,
		SNEAK,
		SPRINT,
		USE,
		ATTACK;
	}

	private static final Key[] KEYS = Key.values();

	private static final Object[] OWNERS = new Object[KEYS.length];
	private static final int[] PRIORITIES = new int[KEYS.length];
	/** Whether the owner asked again during the tick that is now ending. */
	private static final boolean[] REQUESTED = new boolean[KEYS.length];
	/** Whether we are the reason the mapping currently reads as down. */
	private static final boolean[] HELD = new boolean[KEYS.length];

	/**
	 * Weak on purpose, for the same reason as {@link InventoryActionCoordinator}: these exist
	 * only to notice that the world or connection was replaced, and a strong reference would
	 * pin a dead level alive.
	 */
	private static WeakReference<Object> level = new WeakReference<>(null);
	private static WeakReference<Object> connection = new WeakReference<>(null);

	private InputActionCoordinator() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	// ---- requests ----------------------------------------------------------

	/**
	 * Asks to hold {@code key} for this tick. Call every tick for as long as you want it.
	 *
	 * <p>Equal priority keeps the incumbent, so two peers cannot alternate control tick by
	 * tick — the same rule the other coordinators use, for the same reason.
	 *
	 * @return whether the caller owns the key right now
	 */
	public static boolean hold(Object holder, int priority, Key key) {
		if (holder == null || key == null || mc().player == null) {
			return false;
		}
		int index = key.ordinal();
		if (OWNERS[index] != null && OWNERS[index] != holder && priority <= PRIORITIES[index]) {
			return false;
		}
		OWNERS[index] = holder;
		PRIORITIES[index] = priority;
		REQUESTED[index] = true;
		return true;
	}

	/** {@link #hold} for several keys at once; true only if every one of them was granted. */
	public static boolean hold(Object holder, int priority, Key... keys) {
		boolean all = true;
		for (Key key : keys) {
			all &= hold(holder, priority, key);
		}
		return all;
	}

	public static boolean owns(Object holder, Key key) {
		return holder != null && key != null && OWNERS[key.ordinal()] == holder;
	}

	/** Whether anything synthetic is currently holding {@code key}. */
	public static boolean isHeld(Key key) {
		return key != null && HELD[key.ordinal()];
	}

	/**
	 * Drops every key this holder owns, restoring the player's real input immediately rather
	 * than at tick end. For {@code onDisable}, where "one more tick" is a tick of a module that
	 * is supposed to be off still driving the game.
	 */
	public static void release(Object holder) {
		if (holder == null) {
			return;
		}
		for (int index = 0; index < KEYS.length; index++) {
			if (OWNERS[index] == holder) {
				clear(index);
			}
		}
	}

	/** Drops one key without giving up the others. */
	public static void release(Object holder, Key key) {
		if (holder != null && key != null && OWNERS[key.ordinal()] == holder) {
			clear(key.ordinal());
		}
	}

	// ---- lifecycle ---------------------------------------------------------

	/**
	 * Applies this tick's winners and releases everything nobody asked for again.
	 *
	 * <p>Runs at {@code END_CLIENT_TICK}, which is after vanilla's {@code handleKeybinds}, so a
	 * hold taken here is read by vanilla on the next tick. That one-tick lag applies equally to
	 * the release, which is what makes "stop asking" a complete way to let go.
	 */
	public static void onTickEnd() {
		Minecraft mc = mc();
		Object currentLevel = mc.level;
		Object currentConnection = mc.getConnection();
		if (currentLevel != level.get() || currentConnection != connection.get()) {
			level = new WeakReference<>(currentLevel);
			connection = new WeakReference<>(currentConnection);
			reset();
			return;
		}
		if (mc.player == null) {
			reset();
			return;
		}
		for (int index = 0; index < KEYS.length; index++) {
			if (REQUESTED[index]) {
				REQUESTED[index] = false;
				HELD[index] = true;
				KeyMapping mapping = mapping(KEYS[index]);
				if (mapping != null) {
					mapping.setDown(true);
				}
			} else if (OWNERS[index] != null || HELD[index]) {
				clear(index);
			}
		}
	}

	/**
	 * Lets go of everything, for Panic, disconnect and world change. Unlike {@link #onTickEnd}
	 * this is unconditional: a request made earlier in the same tick is dropped rather than
	 * honoured, because the point of a panic is that nothing gets one more turn.
	 */
	public static void reset() {
		for (int index = 0; index < KEYS.length; index++) {
			clear(index);
		}
	}

	private static void clear(int index) {
		OWNERS[index] = null;
		PRIORITIES[index] = 0;
		REQUESTED[index] = false;
		if (!HELD[index]) {
			return;
		}
		HELD[index] = false;
		KeyMapping mapping = mapping(KEYS[index]);
		if (mapping != null) {
			mapping.setDown(physicallyDown(mapping));
		}
	}

	// ---- the player's own hands --------------------------------------------

	/**
	 * Whether the human is physically pressing {@code key} right now, independent of whatever
	 * we have written into the mapping. This is the only honest way to ask, since our own holds
	 * make {@code isDown()} answer for us instead of for them.
	 */
	public static boolean physicallyDown(Key key) {
		KeyMapping mapping = key == null ? null : mapping(key);
		return mapping != null && physicallyDown(mapping);
	}

	/** Whether the player is pressing any key this coordinator is capable of holding. */
	public static boolean anyUserInput() {
		for (Key key : KEYS) {
			if (physicallyDown(key)) {
				return true;
			}
		}
		return false;
	}

	private static boolean physicallyDown(KeyMapping mapping) {
		Minecraft mc = mc();
		// A screen has already taken the keyboard — vanilla released every mapping when it
		// opened, so there is no held key to hand back to.
		if (mc.gui == null || mc.gui.screen() != null || mc.getWindow() == null) {
			return false;
		}
		InputConstants.Key bound = ((KeyMappingAccessor) mapping).unlucky$key();
		int value = bound.getValue();
		if (value == GLFW.GLFW_KEY_UNKNOWN) {
			return false;
		}
		return switch (bound.getType()) {
			case KEYSYM -> InputConstants.isKeyDown(mc.getWindow(), value);
			case MOUSE -> GLFW.glfwGetMouseButton(mc.getWindow().handle(), value) == GLFW.GLFW_PRESS;
			// SCANCODE mappings have no hardware query that takes a scancode; treat as up.
			default -> false;
		};
	}

	private static KeyMapping mapping(Key key) {
		Options options = mc().options;
		if (options == null) {
			return null;
		}
		return switch (key) {
			case FORWARD -> options.keyUp;
			case BACK -> options.keyDown;
			case LEFT -> options.keyLeft;
			case RIGHT -> options.keyRight;
			case JUMP -> options.keyJump;
			case SNEAK -> options.keyShift;
			case SPRINT -> options.keySprint;
			case USE -> options.keyUse;
			case ATTACK -> options.keyAttack;
		};
	}

	/** One line of internal state, for the debug read-out. */
	public static String debug() {
		StringBuilder text = new StringBuilder();
		for (int index = 0; index < KEYS.length; index++) {
			if (OWNERS[index] == null && !HELD[index]) {
				continue;
			}
			if (text.length() > 0) {
				text.append(' ');
			}
			text.append(KEYS[index].name().toLowerCase()).append('=')
					.append(OWNERS[index] == null ? "none" : OWNERS[index].getClass().getSimpleName())
					.append('/').append(PRIORITIES[index]);
		}
		return text.length() == 0 ? "idle" : text.toString();
	}
}

package unlucky.utility.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import unlucky.utility.client.mixin.MultiPlayerGameModeAccessor;

/**
 * What the client is currently breaking, and how far along it is — one answer, one owner.
 *
 * <p>Six modules care about the local break and each of them used to work it out for itself.
 * That is not merely duplicated arithmetic: the copies <em>disagree</em>. A progress bar drawn
 * from re-derived numbers and a packet sent at a re-derived threshold are two opinions about
 * the same block, and the moment they differ the visual is lying about what the module is
 * doing. Everything that wants to know reads it here instead.
 *
 * <p><b>Vanilla progress is read, never recomputed.</b> The client already owns that arithmetic
 * ({@code MultiPlayerGameMode.destroyProgress}); a second implementation beside it drifts the
 * first time either changes. Only a <em>packet</em> break — where nothing on the client is
 * counting, because vanilla was never asked to start one — is predicted here, from the same
 * {@code getDestroyProgress} the vanilla loop accumulates.
 *
 * <p>The lifecycle is the point of the {@link Stage} field. A block action on the wire is a
 * START that must be closed by exactly one STOP or ABORT; leaking one leaves the server
 * believing you are still mining a block you walked away from, and sending two closes a break
 * that a later START then reopens out of order.
 *
 * @see MiningActionCoordinator for who is allowed to drive a break
 */
public final class MiningTracker {
	/** How a break is being driven. */
	public enum Mode {
		/** Vanilla's own progress loop, via {@code start/continueDestroyBlock}. */
		VANILLA,
		/** Raw block actions with the client's progress predicted alongside. */
		PACKET
	}

	/** Where a break is in its START → STOP/ABORT lifecycle. */
	public enum Stage {
		IDLE,
		/** START is on the wire; progress is accumulating. */
		STARTED,
		/** STOP has been sent and the server's answer has not landed yet. */
		FINISHING,
		/** The block came apart. Terminal; cleared on the next start or after the fade window. */
		COMPLETED,
		/** ABORT was sent, or the target stopped being valid. Terminal. */
		ABORTED
	}

	/**
	 * How long a terminal stage stays readable after the fact, in ticks.
	 *
	 * <p>BreakIndicators fades a finished break out, and it cannot fade something that was
	 * erased the instant it completed. Half a second is longer than any fade worth configuring
	 * and short enough that a stale target can never be mistaken for a live one.
	 */
	private static final int TERMINAL_TICKS = 10;

	private static BlockPos target;
	private static Direction face;
	private static Mode mode = Mode.VANILLA;
	private static Stage stage = Stage.IDLE;
	private static float progress;
	private static int startTick;
	private static long startMillis;
	private static ItemStack tool = ItemStack.EMPTY;
	private static int toolSlot = -1;
	private static boolean rotationRequested;
	/** The module driving this break, or null when it is the player's own left-click. */
	private static Object driver;
	private static int terminalTicks;
	/** Ticks elapsed, counted here rather than against a game clock that pauses. */
	private static int ticks;

	private MiningTracker() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	// ---- lifecycle ---------------------------------------------------------

	/**
	 * A break has begun on {@code pos}.
	 *
	 * <p>Called for the player's manual break too ({@code driver} null), because a module that
	 * wants to accelerate or visualise the player's mining needs the same record as one that
	 * drives its own.
	 */
	public static void onStart(BlockPos pos, Direction hitFace, Mode breakMode, Object breakDriver) {
		LocalPlayer player = mc().player;
		if (pos == null || player == null) {
			return;
		}
		if (target != null && !target.equals(pos) && stage == Stage.STARTED) {
			// A new target while one is open is an implicit abandonment of the old one. Recording
			// it as ABORTED rather than overwriting it silently is what lets a caller notice it
			// owes the server a close.
			stage = Stage.ABORTED;
		}
		target = pos.immutable();
		face = hitFace;
		mode = breakMode == null ? Mode.VANILLA : breakMode;
		stage = Stage.STARTED;
		driver = breakDriver;
		progress = 0.0f;
		ticks = 0;
		startTick = player.tickCount;
		startMillis = System.currentTimeMillis();
		tool = player.getMainHandItem().copy();
		toolSlot = player.getInventory().getSelectedSlot();
		rotationRequested = false;
		terminalTicks = 0;
	}

	/**
	 * The break continues on the same block; a different one restarts the record.
	 *
	 * <p>A terminal record for the <em>same</em> block is left alone rather than restarted.
	 * Vanilla calls {@code destroyBlock} from inside {@code continueDestroyBlock}, so the
	 * completion arrives before this does — treating "not STARTED" as "start again" would wipe
	 * every completion the instant it happened, and the fade would never have anything to fade.
	 */
	public static void onContinue(BlockPos pos, Direction hitFace) {
		if (pos == null) {
			return;
		}
		if (target != null && target.equals(pos)) {
			if (stage == Stage.STARTED) {
				face = hitFace;
			}
			return;
		}
		onStart(pos, hitFace, mode, driver);
	}

	/** STOP is on the wire: the break is finished as far as this client is concerned. */
	public static void onFinishing() {
		if (stage == Stage.STARTED) {
			stage = Stage.FINISHING;
			terminalTicks = 0;
		}
	}

	/** The block actually came apart. */
	public static void onDestroyed(BlockPos pos) {
		if (target != null && target.equals(pos)) {
			progress = 1.0f;
			stage = Stage.COMPLETED;
			terminalTicks = 0;
		}
	}

	/** The break was abandoned — out of range, target changed, module disabled, panic. */
	public static void onAbort() {
		if (stage == Stage.STARTED || stage == Stage.FINISHING) {
			stage = Stage.ABORTED;
			terminalTicks = 0;
		}
	}

	/** Notes that the driver asked RotationManager to face this block. */
	public static void setRotationRequested(boolean requested) {
		rotationRequested = requested;
	}

	/**
	 * End of tick: refresh progress and expire terminal records.
	 *
	 * <p>Vanilla's own break is read straight out of {@code MultiPlayerGameMode} — including
	 * whether it is still the block we think it is, which is how a target vanilla dropped
	 * underneath us (out of range, screen opened, key released) is noticed at all.
	 */
	public static void onTickEnd() {
		Minecraft mc = mc();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null || mc.gameMode == null) {
			reset();
			return;
		}
		if (target == null) {
			return;
		}
		if (stage == Stage.STARTED) {
			ticks++;
			if (mode == Mode.VANILLA) {
				MultiPlayerGameModeAccessor accessor = (MultiPlayerGameModeAccessor) mc.gameMode;
				BlockPos vanillaTarget = accessor.unlucky$destroyBlockPos();
				if (mc.gameMode.isDestroying() && target.equals(vanillaTarget)) {
					progress = clamp01(accessor.unlucky$destroyProgress());
				} else {
					// Vanilla let go of this break without anybody telling us. There is nothing
					// left accumulating, so the record has to stop claiming otherwise.
					onAbort();
				}
			} else {
				progress = clamp01(progress + tickProgress(player, level, target));
			}
		} else if (++terminalTicks > TERMINAL_TICKS) {
			reset();
		}
	}

	/** Drops the record entirely. Disconnect, world change and panic. */
	public static void reset() {
		target = null;
		face = null;
		stage = Stage.IDLE;
		progress = 0.0f;
		ticks = 0;
		startTick = 0;
		startMillis = 0L;
		tool = ItemStack.EMPTY;
		toolSlot = -1;
		rotationRequested = false;
		driver = null;
		terminalTicks = 0;
	}

	// ---- queries -----------------------------------------------------------

	/** The block being broken, or null. Live and terminal records both answer here. */
	public static BlockPos target() {
		return target;
	}

	public static Direction face() {
		return face;
	}

	public static Mode mode() {
		return mode;
	}

	public static Stage stage() {
		return stage;
	}

	/** Break progress 0..1 — read from vanilla for a vanilla break, predicted for a packet one. */
	public static float progress() {
		return progress;
	}

	/** Whether a break is currently accumulating progress. */
	public static boolean isBreaking() {
		return target != null && stage == Stage.STARTED;
	}

	/** Whether a module drives the current break, as opposed to the player's own left-click. */
	public static boolean isDriven() {
		return driver != null && isBreaking();
	}

	public static Object driver() {
		return driver;
	}

	/** Ticks the current break has been running. */
	public static int elapsedTicks() {
		return ticks;
	}

	public static long elapsedMillis() {
		return startMillis == 0L ? 0L : System.currentTimeMillis() - startMillis;
	}

	public static int startTick() {
		return startTick;
	}

	/** A copy of the stack the break started with — for "abort if the tool changed". */
	public static ItemStack tool() {
		return tool;
	}

	public static int toolSlot() {
		return toolSlot;
	}

	public static boolean rotationRequested() {
		return rotationRequested;
	}

	/**
	 * Whether the effective tool is still the one the break started with.
	 *
	 * <p>Compared by item and by enchantments-affecting-speed rather than by stack identity: a
	 * pickaxe that lost a point of durability mid-break is the same tool, and treating it as a
	 * change would abort every break that lasts longer than one hit.
	 */
	public static boolean toolChanged() {
		LocalPlayer player = mc().player;
		if (player == null || tool.isEmpty()) {
			return false;
		}
		ItemStack held = player.getMainHandItem();
		return held.getItem() != tool.getItem()
				|| player.getInventory().getSelectedSlot() != toolSlot;
	}

	/**
	 * Estimated ticks left on the current break, or -1 when that cannot be predicted.
	 * Rate is taken from the block as it stands now, so a tool swap re-estimates immediately.
	 */
	public static int remainingTicks() {
		LocalPlayer player = mc().player;
		ClientLevel level = mc().level;
		if (player == null || level == null || target == null || !isBreaking()) {
			return -1;
		}
		float rate = tickProgress(player, level, target);
		if (rate <= 0.0f) {
			return -1;
		}
		return Math.max(0, (int) Math.ceil((1.0f - progress) / rate));
	}

	/** Progress one tick of mining adds to {@code pos} — vanilla's own per-tick increment. */
	public static float tickProgress(LocalPlayer player, ClientLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return state.isAir() ? 0.0f : state.getDestroyProgress(player, level, pos);
	}

	/** One line of internal state, for the debug read-out. */
	public static String debug() {
		if (target == null) {
			return "idle";
		}
		return String.format("%s %s %s %.2f t=%d driver=%s", target.toShortString(), mode, stage, progress,
				ticks, driver == null ? "player" : driver.getClass().getSimpleName());
	}

	private static float clamp01(float value) {
		return Mth.clamp(value, 0.0f, 1.0f);
	}
}

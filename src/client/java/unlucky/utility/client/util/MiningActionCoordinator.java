package unlucky.utility.client.util;

import java.lang.ref.WeakReference;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import unlucky.utility.client.mixin.MultiPlayerGameModeAccessor;

/**
 * One owner at a time for the local break, and one answer to "is a module mining right now".
 *
 * <p>There is exactly one break in flight on a client: {@code MultiPlayerGameMode} keeps a
 * single {@code destroyBlockPos} and a single progress figure. Two modules driving it are not
 * two breaks, they are one block whose progress is reset to zero by whichever of them ran
 * second — and every call either made returned success, which is what makes this failure so
 * expensive to find. VeinMiner's own header already names the same trap in vanilla's clothing.
 *
 * <p><b>{@link #isModuleMining()} is the reason this is a coordinator and not just a tracker.</b>
 * {@code Minecraft.continueAttack} calls {@code stopDestroyBlock()} on every tick the attack key
 * is not held, and module ticks run after it, so a module that mines by calling start/continue
 * itself has its progress wiped every tick. {@code MinecraftMixin} drops that vanilla pass while
 * a module owns the break. That check used to name Printer and VeinMiner by hand, which meant
 * every new mining module had to remember to add itself to a mixin, and the two that forgot
 * would have looked exactly like a module that simply did not work.
 *
 * <p>The lease is check-every-tick, like {@link InventoryActionCoordinator}: ask before you act,
 * and treat a refusal as "something more important is mid-break".
 *
 * @see MiningTracker for what is being broken and how far along it is
 */
public final class MiningActionCoordinator {
	/**
	 * The player's own left-click, and SpeedMine riding on it. Nothing automatic may take a
	 * block out from under the hand that is actually mining it.
	 */
	public static final int PRIORITY_MANUAL = 100;
	/** Printer: a schematic break is part of a build in progress and losing it strands the lane. */
	public static final int PRIORITY_SCHEMATIC = 70;
	/** VeinMiner: a queue seeded by a break the player deliberately made. */
	public static final int PRIORITY_VEIN = 50;
	/** Nuker: area mining, the cheapest thing here to interrupt and resume. */
	public static final int PRIORITY_AREA = 30;
	/** Farming utilities that happen to break a block — VillagerRoller's lectern. */
	public static final int PRIORITY_UTILITY = 20;

	private static Object owner;
	private static int priority;

	/** Weak for the same reason as the other coordinators — never pin a dead level alive. */
	private static WeakReference<Object> level = new WeakReference<>(null);
	private static WeakReference<Object> connection = new WeakReference<>(null);

	private MiningActionCoordinator() {
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	// ---- the lease ---------------------------------------------------------

	/**
	 * Claims the break for {@code holder}, evicting a lower-priority holder.
	 *
	 * <p>Equal priority does not evict, and eviction closes the outgoing holder's break on the
	 * wire before the handover — an unclosed START is a server that still believes you are
	 * mining a block you have since walked away from.
	 */
	public static boolean acquire(Object holder, int newPriority) {
		if (holder == null || mc().player == null || mc().gameMode == null) {
			return false;
		}
		if (owner == holder) {
			priority = newPriority;
			return true;
		}
		if (owner != null && newPriority <= priority) {
			return false;
		}
		if (owner != null) {
			abort();
		}
		owner = holder;
		priority = newPriority;
		return true;
	}

	/** Whether {@code holder} may drive a break right now. Ask every tick. */
	public static boolean owns(Object holder) {
		return holder != null && owner == holder;
	}

	public static Object owner() {
		return owner;
	}

	/**
	 * Hands the lease back, closing any break still open on the wire.
	 *
	 * <p>Safe to call when you do not hold it, so {@code onDisable} can call it unconditionally.
	 */
	public static void release(Object holder) {
		if (owns(holder)) {
			abort();
			owner = null;
			priority = 0;
		}
	}

	/**
	 * Whether a module — not the player's own left-click — is driving a break this tick.
	 *
	 * <p>Read by {@code MinecraftMixin} to keep vanilla's {@code continueAttack} off a destroy
	 * it did not start. This is the single query the hard-coded Printer/VeinMiner test became.
	 */
	public static boolean isModuleMining() {
		return owner != null && MiningTracker.isBreaking();
	}

	// ---- driving a break ---------------------------------------------------

	/**
	 * Drives one tick of a vanilla break on {@code pos}, starting it if needed.
	 *
	 * <p>Goes through {@code start/continueDestroyBlock} rather than raw packets on purpose:
	 * that is where AutoTool already hooks, so a module mining this way gets the right tool
	 * without knowing what a tool is.
	 *
	 * @return whether the break is being driven this tick
	 */
	public static boolean mine(Object holder, BlockPos pos, Direction face) {
		if (!owns(holder) || pos == null || face == null || mc().gameMode == null || mc().level == null) {
			return false;
		}
		BlockPos current = MiningTracker.target();
		if (current != null && !current.equals(pos) && MiningTracker.isBreaking()) {
			// Switching targets closes the old one first, so exactly one START is ever open.
			abort();
		}
		if (!mc().gameMode.isDestroying()) {
			MiningTracker.onStart(pos, face, MiningTracker.Mode.VANILLA, holder);
			return mc().gameMode.startDestroyBlock(pos, face);
		}
		MiningTracker.onContinue(pos, face);
		return mc().gameMode.continueDestroyBlock(pos, face);
	}

	/**
	 * Ends the current break cleanly — the STOP that closes the START this client opened.
	 * A module that finishes a target calls this before moving to the next one.
	 */
	public static void stop(Object holder) {
		if (owns(holder)) {
			abort();
		}
	}

	// ---- packet breaks -----------------------------------------------------

	/**
	 * Opens a block action on the wire without asking vanilla to run its own progress loop.
	 *
	 * <p>Routed through vanilla's {@code startPrediction} so the sequence number is real. That
	 * is not a detail: the server acknowledges block actions by sequence, and a fabricated one
	 * desyncs the prediction for everything else the client is doing, not just the break.
	 *
	 * <p>A packet break is the client saying "I am mining this" and later "I have finished".
	 * The server keeps its own progress and decides whether to believe the second message, which
	 * is why nothing here can mine faster than the server allows — only sooner than the client
	 * would otherwise have asked.
	 */
	public static boolean packetStart(Object holder, BlockPos pos, Direction face) {
		if (!owns(holder) || pos == null || face == null || mc().level == null || mc().gameMode == null) {
			return false;
		}
		if (MiningTracker.isBreaking()) {
			// One open START at a time, always. Leaking one leaves the server believing you are
			// still mining a block you walked away from.
			abort();
		}
		send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face);
		MiningTracker.onStart(pos, face, MiningTracker.Mode.PACKET, holder);
		return true;
	}

	/** The STOP that closes a {@link #packetStart}. */
	public static boolean packetStop(Object holder) {
		BlockPos target = MiningTracker.target();
		if (!owns(holder) || target == null || !MiningTracker.isBreaking()) {
			return false;
		}
		send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, target, MiningTracker.face());
		MiningTracker.onFinishing();
		return true;
	}

	/**
	 * START and STOP in the same tick — a whole break in one message pair.
	 *
	 * <p>What Nuker does, and the reason the block is removed by the server's answer rather than
	 * by client prediction: if the server will not let you break it, it honestly stays there.
	 */
	public static boolean packetBreak(Object holder, BlockPos pos, Direction face) {
		if (!packetStart(holder, pos, face)) {
			return false;
		}
		send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face);
		MiningTracker.onFinishing();
		return true;
	}

	/** The ABORT that closes a START the caller has given up on. */
	public static void packetAbort(Object holder) {
		BlockPos target = MiningTracker.target();
		if (owns(holder) && target != null && MiningTracker.isBreaking()) {
			send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, target, MiningTracker.face());
			MiningTracker.onAbort();
		}
	}

	private static void send(ServerboundPlayerActionPacket.Action action, BlockPos pos, Direction face) {
		Direction hit = face == null ? Direction.UP : face;
		((MultiPlayerGameModeAccessor) mc().gameMode).unlucky$startPrediction(mc().level,
				sequence -> new ServerboundPlayerActionPacket(action, pos, hit, sequence));
	}

	/**
	 * Clears vanilla's post-break cooldown, so an automatic miner can chain straight into the
	 * next block. Only the lease holder may — the delay is what makes a manual break feel
	 * like vanilla, and nothing should shorten it behind the player's back.
	 */
	public static void clearDestroyDelay(Object holder) {
		if (owns(holder) && mc().gameMode != null) {
			((MultiPlayerGameModeAccessor) mc().gameMode).unlucky$setDestroyDelay(0);
		}
	}

	private static void abort() {
		if (mc().gameMode != null) {
			mc().gameMode.stopDestroyBlock();
		}
		MiningTracker.onAbort();
	}

	// ---- lifecycle ---------------------------------------------------------

	/** End of client tick: notice a world or connection change and expire the record with it. */
	public static void onTickEnd() {
		Minecraft mc = mc();
		Object currentLevel = mc.level;
		Object currentConnection = mc.getConnection();
		if (currentLevel != level.get() || currentConnection != connection.get()) {
			// Block coordinates from another world are not coordinates, they are numbers.
			level = new WeakReference<>(currentLevel);
			connection = new WeakReference<>(currentConnection);
			reset();
			return;
		}
		if (mc.player == null) {
			reset();
			return;
		}
		MiningTracker.onTickEnd();
	}

	/**
	 * Drops the lease without touching the game. For a world change, where a stop packet would
	 * be aimed at a block that no longer exists.
	 */
	public static void reset() {
		owner = null;
		priority = 0;
		MiningTracker.reset();
	}

	/** Panic: close the break on the wire <em>while the world is still there</em>, then drop it. */
	public static void panic() {
		abort();
		reset();
	}

	/** One line of internal state, for the debug read-out. */
	public static String debug() {
		return String.format("owner=%s priority=%d %s",
				owner == null ? "none" : owner.getClass().getSimpleName(), priority, MiningTracker.debug());
	}
}

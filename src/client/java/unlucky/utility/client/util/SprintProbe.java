package unlucky.utility.client.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * A tick-by-tick recorder for the sprint flag, for when "AutoSprint goes wild"
 * needs an answer rather than another theory.
 *
 * <p>The whole question is <i>who</i> is writing the flag and <i>when</i>, and a
 * client tick touches it in three places, in this order:
 *
 * <ol>
 * <li>{@code LocalPlayer.aiStep} — vanilla's own start (double tap / sprint key)
 *     and its cancel ({@code shouldStopRunSprinting}: wall, no forward impulse,
 *     hunger, shallow water).</li>
 * <li>{@code sendIsSprintingIfNeeded} — the packet, sent only when the flag
 *     differs from what the server was last told.</li>
 * <li>END_CLIENT_TICK — us. Anything we set here is judged by the <i>next</i>
 *     tick's aiStep before it can ever reach the wire.</li>
 * </ol>
 *
 * <p>So a row is: what the flag was when the tick started (whatever we left
 * behind), what vanilla made of it, whether a packet went out, and what the
 * module decided to do about it — against the collision, ground and input state
 * that explains all four. A file of those rows says which of the three writers
 * is misbehaving; reasoning about it from the source has already been tried.
 */
public final class SprintProbe {
	/** 30 s at 20 tps — long enough to jump a staircase and still find the start. */
	private static final int CAPACITY = 600;

	private static boolean recording;
	private static final ArrayDeque<String> rows = new ArrayDeque<>();
	private static long tick;
	private static long startedAt;

	private static int packets;
	private static int cancels;
	private static int sets;

	/** Whether {@link #aiStepStart} has opened a row that nothing has committed yet. */
	private static boolean open;
	private static boolean flagIn;
	private static boolean flagOut;
	private static boolean ground;
	private static boolean collided;
	private static boolean minorCollision;
	private static boolean forwardImpulse;
	private static Input keys = Input.EMPTY;
	private static Vec2 move = Vec2.ZERO;
	private static Vec3 velocity = Vec3.ZERO;
	private static double y;
	private static int food;
	private static String packet;
	private static String decision;
	/** Flag as our own tick handed it on, or -1 if we never got that far. */
	private static int flagEnd;
	/** Every write to the flag this tick, in order, each with the frame that made it. */
	private static final StringBuilder writes = new StringBuilder();

	private SprintProbe() {
	}

	public static boolean recording() {
		return recording;
	}

	/** Console/chat entry point: flips recording, and reports what it saw on the way down. */
	public static String toggle() {
		if (recording) {
			commit(); // the tick in progress is part of the recording too
			String summary = summary();
			recording = false;
			return "sprint probe off - " + summary;
		}
		rows.clear();
		tick = 0;
		packets = 0;
		cancels = 0;
		sets = 0;
		open = false;
		startedAt = System.currentTimeMillis();
		recording = true;
		return "sprint probe on - jump up some blocks, then run \"sprint\" again";
	}

	/** LocalPlayer.aiStep HEAD. Opens the row, and commits the one before it. */
	public static void aiStepStart(LocalPlayer player) {
		if (!recording || player != Minecraft.getInstance().player) {
			return;
		}
		commit();
		open = true;
		tick++;
		flagIn = player.isSprinting();
		flagOut = flagIn;
		// Collision comes from last tick's move() and is exactly what vanilla's cancel
		// check is about to read; the input fields are picked up at RETURN instead,
		// because aiStep ticks ClientInput on its way through.
		ground = player.onGround();
		collided = player.horizontalCollision;
		minorCollision = player.minorHorizontalCollision;
		food = player.getFoodData().getFoodLevel();
		packet = "-";
		decision = "off"; // AutoSprint overwrites this at END_CLIENT_TICK if it is running
		flagEnd = -1;
		writes.setLength(0);
	}

	/** LocalPlayer.aiStep RETURN: vanilla's verdict on the flag it was handed. */
	public static void aiStepEnd(LocalPlayer player) {
		if (!recording || !open || player != Minecraft.getInstance().player) {
			return;
		}
		flagOut = player.isSprinting();
		if (flagIn && !flagOut) {
			cancels++;
		}
		forwardImpulse = player.input.hasForwardImpulse();
		keys = player.input.keyPresses;
		move = player.input.getMoveVector();
		velocity = player.getDeltaMovement();
		y = player.getY();
	}

	/**
	 * {@code sendIsSprintingIfNeeded} HEAD, with the field it compares against —
	 * the same test vanilla is about to make, so the row records the packet that
	 * actually goes out rather than one we assume from the flag.
	 */
	public static void packetCheck(LocalPlayer player, boolean wasSprinting) {
		if (!recording || !open || player != Minecraft.getInstance().player) {
			return;
		}
		boolean sprinting = player.isSprinting();
		if (sprinting == wasSprinting) {
			return;
		}
		packet = sprinting ? "START" : "STOP";
		packets++;
	}

	/**
	 * Every write to the flag, with who made it. The first run of this probe found the
	 * flag going false <i>between</i> the packet check and the next {@code aiStep} — a
	 * window no part of the recording covered — so the writers have to name themselves.
	 *
	 * @param source already resolved by the caller: building a stack trace is only
	 *               affordable because it happens exclusively while recording
	 */
	public static void flagWrite(boolean value, String source) {
		if (!recording || !open) {
			return;
		}
		if (writes.length() > 0) {
			writes.append(',');
		}
		writes.append(value ? '+' : '-').append(source);
	}

	/**
	 * The frames around a flag write, minus the plumbing. Two of them: the immediate
	 * caller alone does not separate "vanilla's own aiStep" from "a packet handler that
	 * happens to end up in the same setter".
	 */
	public static String caller() {
		StackTraceElement[] frames = new Throwable().getStackTrace();
		StringBuilder out = new StringBuilder();
		int kept = 0;
		for (StackTraceElement frame : frames) {
			String type = frame.getClassName();
			// Mixin handlers are merged into the target class, so they read as
			// "Entity.handler$..." rather than as anything named Mixin.
			if (type.startsWith("unlucky.utility.client.util.SprintProbe")
					|| type.endsWith("Mixin")
					|| frame.getMethodName().startsWith("handler$")
					|| "setSprinting".equals(frame.getMethodName())
					|| "onSyncedDataUpdated".equals(frame.getMethodName())) {
				continue;
			}
			if (kept > 0) {
				out.append('<');
			}
			out.append(type.substring(type.lastIndexOf('.') + 1))
					.append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber());
			if (++kept == 3) {
				break;
			}
		}
		return kept == 0 ? "?" : out.toString();
	}

	/** The very end of our own tick: the last value anything of ours leaves behind. */
	public static void tickEnd() {
		if (!recording || !open) {
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			flagEnd = player.isSprinting() ? 1 : 0;
		}
	}

	/** AutoSprint, at END_CLIENT_TICK: what it decided, in its own words. */
	public static void decision(String what, boolean setFlag) {
		if (!recording || !open) {
			return;
		}
		decision = what;
		if (setFlag) {
			sets++;
		}
	}

	private static void commit() {
		if (!open) {
			return;
		}
		open = false;
		rows.addLast(String.format(
				"t=%5d in=%d out=%d pkt=%-5s end=%s gnd=%d col=%d minor=%d fwd=%d "
						+ "keys=%s%s%s%s%s%s%s mv=(%+.2f,%+.2f) vel=(%+.3f,%+.3f,%+.3f) y=%.3f food=%2d "
						+ "dec=%-12s wr=%s",
				tick, flagIn ? 1 : 0, flagOut ? 1 : 0, packet, flagEnd < 0 ? "?" : String.valueOf(flagEnd),
				ground ? 1 : 0, collided ? 1 : 0,
				minorCollision ? 1 : 0, forwardImpulse ? 1 : 0,
				keys.forward() ? "W" : "-", keys.backward() ? "S" : "-", keys.left() ? "A" : "-",
				keys.right() ? "D" : "-", keys.jump() ? "J" : "-", keys.shift() ? "C" : "-",
				keys.sprint() ? "R" : "-",
				move.x, move.y, velocity.x, velocity.y, velocity.z, y, food, decision,
				writes.length() == 0 ? "-" : writes.toString()));
		while (rows.size() > CAPACITY) {
			rows.removeFirst();
		}
	}

	private static String summary() {
		return String.format("%d ticks, %d sprint packets, %d vanilla cancels, %d module sets",
				tick, packets, cancels, sets);
	}

	/** Writes the buffer next to the Printer's reports, in the same shape. */
	public static void save(Consumer<String> out) {
		commit();
		if (rows.isEmpty()) {
			out.accept("Nothing recorded yet - run \"sprint\" first");
			return;
		}
		List<String> lines = new ArrayList<>();
		lines.add("Unlucky sprint probe " + java.time.LocalDateTime.now());
		lines.add(summary() + (recording ? " (still recording)" : ""));
		lines.add("recording started " + (System.currentTimeMillis() - startedAt) / 1000 + "s ago");
		var autoSprint = unlucky.utility.client.UnluckyClient.INSTANCE.modules
				.get(unlucky.utility.client.module.modules.movement.AutoSprint.class);
		lines.add("AutoSprint: " + (autoSprint.isEnabled() ? "on" : "off")
				+ " omni=" + autoSprint.omniDirectional.get()
				+ " keepSprinting=" + autoSprint.keepSprinting.get()
				+ " stopUsing=" + autoSprint.stopUsing.get()
				+ " stopSneaking=" + autoSprint.stopSneaking.get()
				+ " stopInGui=" + autoSprint.stopInGui.get());
		lines.add("");
		lines.add("in  = sprint flag at aiStep HEAD (what the last tick left behind)");
		lines.add("out = sprint flag at aiStep RETURN (vanilla's verdict)");
		lines.add("pkt = packet actually sent this tick; dec = AutoSprint's decision after all of it");
		lines.add("end = flag as our own END_CLIENT_TICK handed it on");
		lines.add("wr  = every setSprinting / synced-data write this tick and the frame behind it,");
		lines.add("      including the ones after end= (that gap is the next tick's aiStep window)");
		lines.add("gnd/col/minor = collision state vanilla judged this tick (from last tick's move)");
		lines.add("keys/mv/vel/y = after aiStep, so the same input vanilla's cancel check read");
		lines.add("keys = WSADJCR (jump, crouch, spRint)");
		lines.add("");
		lines.add("in=1 out=0 is vanilla cancelling a sprint; a run of those with dec=SET after each");
		lines.add("is the module and vanilla arguing, and pkt says what the server hears of it.");
		lines.add("");
		lines.addAll(rows);

		try {
			java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance()
					.getConfigDir().resolve("unlucky/sprint-probes");
			java.nio.file.Files.createDirectories(dir);
			java.nio.file.Path file = dir.resolve("sprint-" + new java.text.SimpleDateFormat(
					"yyyyMMdd-HHmmss").format(new java.util.Date()) + ".txt");
			java.nio.file.Files.write(file, lines);
			out.accept("Sprint probe saved: " + file.getFileName() + " - " + summary());
		} catch (java.io.IOException e) {
			out.accept("Could not write probe: " + e.getMessage());
		}
	}
}

package unlucky.utility.client.module.modules.render;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.ActionSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render3D;

/**
 * Draws where you have been.
 *
 * <p><b>The two clear rules are the module.</b> A trail is a line between consecutive samples, and
 * the client's position is not continuous: a teleport, a portal, a server correction all produce
 * two samples with nothing between them, and connecting those draws a bright line across the whole
 * world through terrain you never touched. Worse, it looks exactly like a route — which is the one
 * thing a breadcrumb trail must never lie about. Both discontinuity rules default on for that
 * reason, and the dimension one has no honest "off": a trail that crossed dimensions would be
 * joining two different coordinate spaces.
 *
 * <p><b>Memory is bounded by construction, not by hoping.</b> The buffer is a fixed-capacity ring
 * per source, so a session left running overnight costs the same as one left running for a minute.
 * Age expiry sits on top of that and is off by default: "keep the last two thousand points" is a
 * promise about memory, "keep the last ten minutes" is a promise about time, and only the first
 * one can be kept.
 */
public class Breadcrumbs extends Module {
	/** Squared distance treated as a discontinuity rather than as movement. */
	private static final double TELEPORT_SQR = 64.0 * 64.0;

	public final ModeSetting source = add(new ModeSetting("Source",
			"Whose path to record", "Player", "Player", "Freecam", "Both"));
	public final NumberSetting maximumPoints = add(new NumberSetting("Maximum points",
			"Ring-buffer capacity per source", 2000, 100, 20000, 100));
	public final NumberSetting minimumDistance = add(new NumberSetting("Minimum point distance",
			"Blocks of movement before a new point is recorded", 0.25, 0.05, 5.0, 0.05));
	public final NumberSetting maximumAge = add(new NumberSetting("Maximum age",
			"Seconds a point survives; 0 keeps them until the buffer is full", 0, 0, 3600, 10));

	public final NumberSetting lineWidth = add(new NumberSetting("Line width",
			"Trail thickness", 1.5, 0.5, 5.0, 0.1));
	public final BooleanSetting throughWalls = add(new BooleanSetting("Through walls",
			"Draw the trail through terrain", true));

	public final ModeSetting colorMode = add(new ModeSetting("Color mode",
			"Static uses one colour, Speed colours each segment by how fast you were moving",
			"Theme", "Static", "Theme", "Rainbow", "Speed"));
	public final ColorSetting staticColor = add(new ColorSetting("Static color",
			"Trail colour", 0xFFB478FF), () -> colorMode.is("Static") || colorMode.is("Theme"));
	public final BooleanSetting fadeOldest = add(new BooleanSetting("Fade oldest",
			"Fade the trail out toward its oldest end", true));

	public final BooleanSetting clearOnTeleport = add(new BooleanSetting("Clear on teleport",
			"Break the trail on a large jump instead of drawing a line across the map", true));
	public final BooleanSetting clearOnDimension = add(new BooleanSetting("Clear on dimension change",
			"Never connect two dimensions", true));
	public final BooleanSetting clearOnDisable = add(new BooleanSetting("Clear on disable",
			"Throw the trail away when the module is toggled off", false));
	public final ActionSetting clear = add(new ActionSetting("Clear",
			"Throw away every recorded trail now", this::clearAll));

	/** One recorded position and when it was taken. */
	private record Point(Vec3 at, long millis, double speed) {
	}

	/**
	 * A trail is a deque used as a ring: appended at the tail, evicted from the head. Two of
	 * them, because the player and the Freecam camera are two paths and interleaving them into
	 * one buffer would connect a point of yours to a point of the camera's.
	 */
	private final Deque<Point> playerTrail = new ArrayDeque<>();
	private final Deque<Point> freecamTrail = new ArrayDeque<>();

	private Vec3 lastPlayer;
	private Vec3 lastFreecam;
	/** Last level seen, so a dimension change is noticed rather than drawn. */
	private java.lang.ref.WeakReference<Object> level = new java.lang.ref.WeakReference<>(null);

	public Breadcrumbs() {
		super("Breadcrumbs", "Draws a trail behind you", Category.RENDER,
				ServerVisibility.CLIENT_ONLY);
	}

	@Override
	protected void onDisable() {
		if (clearOnDisable.get()) {
			clearAll();
		}
		lastPlayer = null;
		lastFreecam = null;
	}

	private void clearAll() {
		playerTrail.clear();
		freecamTrail.clear();
		lastPlayer = null;
		lastFreecam = null;
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null) {
			return;
		}
		if (mc().level != level.get()) {
			level = new java.lang.ref.WeakReference<>(mc().level);
			if (clearOnDimension.get()) {
				clearAll();
			} else {
				// Even with the clear off, the two ends must not be joined: forgetting the last
				// point breaks the segment without discarding the trail the player asked to keep.
				lastPlayer = null;
				lastFreecam = null;
			}
		}

		if (!source.is("Freecam")) {
			lastPlayer = sample(playerTrail, mc().player.position(), lastPlayer);
		}
		if (!source.is("Player")) {
			Freecam freecam = UnluckyClient.INSTANCE.modules.get(Freecam.class);
			lastFreecam = freecam.isEnabled()
					? sample(freecamTrail, freecam.getPosition(), lastFreecam)
					: null; // the camera stopped existing; do not bridge to where it starts next
		}

		expire(playerTrail);
		expire(freecamTrail);
		render(playerTrail);
		render(freecamTrail);
	}

	/**
	 * Records {@code position} if it is far enough from the last one.
	 *
	 * @return the new "last position", which is the old one when nothing was recorded — sampling
	 *         against the last <em>recorded</em> point rather than against last tick is what makes
	 *         the minimum distance a real spacing instead of a speed threshold
	 */
	private Vec3 sample(Deque<Point> trail, Vec3 position, Vec3 previous) {
		if (previous == null) {
			trail.addLast(new Point(position, System.currentTimeMillis(), 0.0));
			trim(trail);
			return position;
		}
		double moved = previous.distanceToSqr(position);
		double minimum = minimumDistance.get();
		if (moved < minimum * minimum) {
			return previous;
		}
		if (clearOnTeleport.get() && moved > TELEPORT_SQR) {
			// This trail only. The player teleporting says nothing about where the Freecam camera
			// has been, and clearing both would throw away a path the player is still looking at.
			trail.clear();
			trail.addLast(new Point(position, System.currentTimeMillis(), 0.0));
			return position;
		}
		trail.addLast(new Point(position, System.currentTimeMillis(), Math.sqrt(moved)));
		trim(trail);
		return position;
	}

	/** The ring's only enforcement point: capacity is a hard bound, checked on every append. */
	private void trim(Deque<Point> trail) {
		int capacity = maximumPoints.getInt();
		while (trail.size() > capacity) {
			trail.removeFirst();
		}
	}

	private void expire(Deque<Point> trail) {
		int seconds = maximumAge.getInt();
		if (seconds <= 0 || trail.isEmpty()) {
			return;
		}
		long cutoff = System.currentTimeMillis() - seconds * 1000L;
		while (!trail.isEmpty() && trail.peekFirst().millis() < cutoff) {
			trail.removeFirst();
		}
	}

	/**
	 * One line per consecutive pair.
	 *
	 * <p>Emitted every tick because gizmos live for exactly one — there is no retained geometry
	 * to update, which is also why the point buffer and not the drawing is where the cost lives.
	 */
	private void render(Deque<Point> trail) {
		if (trail.size() < 2) {
			return;
		}
		int total = trail.size();
		int index = 0;
		Point previous = null;
		float width = lineWidth.getFloat();
		for (Iterator<Point> it = trail.iterator(); it.hasNext(); index++) {
			Point point = it.next();
			if (previous != null) {
				float age = (float) index / total;
				int color = colorFor(point, age);
				if (color != 0) {
					Render3D.line(previous.at(), point.at(), color, width, throughWalls.get());
				}
			}
			previous = point;
		}
	}

	/**
	 * Colour for one segment.
	 *
	 * <p>Speed is scaled against a sprint-ish top end rather than against the fastest segment in
	 * the buffer: a scale that renormalised itself would make the same walk look different
	 * depending on whether an elytra flight happened to be in memory.
	 */
	private int colorFor(Point point, float age) {
		int base = switch (colorMode.get()) {
			case "Rainbow" -> ColorUtil.hsb(((System.currentTimeMillis() / 20L) % 360L) / 360.0f
					+ age, 0.7f, 1.0f, 255);
			case "Speed" -> {
				float fraction = (float) Math.min(1.0, point.speed() / 0.6);
				// green through to red, the usual reading of a speed ramp
				yield ColorUtil.hsb((1.0f - fraction) * 0.33f, 0.8f, 1.0f, 255);
			}
			default -> staticColor.get();
		};
		return fadeOldest.get() ? ColorUtil.multiplyAlpha(base, 0.25f + age * 0.75f) : base;
	}

	/** Points currently retained across both trails, for the debug read-out. */
	public int pointCount() {
		return playerTrail.size() + freecamTrail.size();
	}
}

package unlucky.utility.client.module.modules.render;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.waypoints.Waypoint;
import unlucky.utility.client.util.waypoints.WaypointManager;

/**
 * Saved places, drawn as a beacon beam with a floating label — and a death
 * marker dropped automatically wherever you die.
 *
 * <p>Waypoints in the <i>other</i> half of an overworld/nether pair are projected
 * through the 8:1 ratio (see {@link WaypointManager#displayPos}) and marked with a
 * {@code ~}, so a nether-side waypoint tells you where to walk in the overworld.
 *
 * <p>Everything is emitted from {@link #onTick} — {@code Render3D}'s box/label
 * queues are drained by the level renderer, same as the ESPs.
 */
public class Waypoints extends Module {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	public final BooleanSetting beam = add(new BooleanSetting("Beam", "Beacon beam at the waypoint", true));
	public final NumberSetting beamHeight = add(new NumberSetting("Beam height", "How tall the beam is", 64, 8, 320, 8));
	public final NumberSetting beamWidth = add(new NumberSetting("Beam width", "Beam thickness", 0.3, 0.1, 1.0, 0.1));
	public final BooleanSetting label = add(new BooleanSetting("Label", "Name + distance above the waypoint", true));
	public final NumberSetting labelScale = add(new NumberSetting("Label scale", "Label text size", 1.0, 0.5, 3.0, 0.1));
	public final NumberSetting fade = add(new NumberSetting("Fade distance", "Fade the beam out within this range", 12, 0, 64, 2));
	public final BooleanSetting deaths = add(new BooleanSetting("Death points", "Drop a marker where you die", true));
	public final NumberSetting maxDeaths = add(new NumberSetting("Max deaths", "Keep only the newest N death points", 3, 1, 20, 1));
	public final ColorSetting deathColor = add(new ColorSetting("Death color", "Color of death markers", 0xFFFF5555));
	public final ColorSetting color = add(new ColorSetting("Color", "Color of new waypoints", 0xFF4A9BFF));

	/** Latched so one death drops exactly one marker, not one per tick while dead. */
	private boolean deathRecorded;

	public Waypoints() {
		super("Waypoints", "Beacon beams for saved places, and a marker where you died", Category.RENDER);
	}

	/** Adds a waypoint at your feet — used by the console command and the GUI. */
	public Waypoint addHere(String name) {
		String dimension = WaypointManager.currentDimension();
		if (mc().player == null || dimension == null) {
			return null;
		}
		Waypoint waypoint = new Waypoint(name, mc().player.blockPosition(), dimension, color.get());
		WaypointManager.add(waypoint);
		return waypoint;
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().level == null) {
			return;
		}
		trackDeath();
		if (!isEnabled()) {
			return;
		}

		String dimension = WaypointManager.currentDimension();
		Vec3 eye = mc().player.getEyePosition();
		List<Waypoint> toDelete = new ArrayList<>();

		for (Waypoint waypoint : WaypointManager.all()) {
			if (!waypoint.visible) {
				continue;
			}
			BlockPos pos = WaypointManager.displayPos(waypoint, dimension);
			if (pos == null) {
				continue; // belongs to a dimension that doesn't map onto this one
			}
			Vec3 center = Vec3.atCenterOf(pos);
			double distance = eye.distanceTo(center);
			if (distance > waypoint.maxVisible) {
				continue;
			}
			if (distance <= waypoint.nearDistance) {
				switch (waypoint.nearAction) {
					case DELETE -> toDelete.add(waypoint);
					case HIDE -> {
						continue;
					}
					default -> { }
				}
			}
			draw(waypoint, pos, center, distance, WaypointManager.isProjected(waypoint, dimension));
		}

		for (Waypoint waypoint : toDelete) {
			WaypointManager.remove(waypoint);
		}
	}

	private void draw(Waypoint waypoint, BlockPos pos, Vec3 center, double distance, boolean projected) {
		// close up, the beam is more in the way than useful — fade it out
		float alpha = fade.get() <= 0 ? 1.0f
				: (float) Math.clamp(distance / fade.get(), 0.0, 1.0);
		if (beam.get() && alpha > 0.02f) {
			double half = beamWidth.get() / 2.0;
			AABB column = new AABB(
					center.x - half, pos.getY(), center.z - half,
					center.x + half, pos.getY() + beamHeight.get(), center.z + half);
			int fill = ColorUtil.withAlpha(waypoint.color, (int) (90 * alpha));
			int outline = ColorUtil.withAlpha(waypoint.color, (int) (200 * alpha));
			Render3D.box(column, outline, 1.0f, fill, true);
		}
		if (label.get()) {
			String text = waypoint.name + (projected ? " ~" : "") + "  " + (int) distance + "m";
			Render3D.blockLabel(text, pos.above(1), waypoint.color, labelScale.getFloat());
		}
	}

	/**
	 * Death markers. The client knows you died the moment your health hits zero, so
	 * latch on that edge and clear the latch once you're alive again — waiting for
	 * the death screen would miss the position after a respawn packet moves you.
	 */
	private void trackDeath() {
		boolean dead = mc().player.isDeadOrDying();
		if (!dead) {
			deathRecorded = false;
			return;
		}
		if (deathRecorded || !deaths.get()) {
			return;
		}
		deathRecorded = true;
		String dimension = WaypointManager.currentDimension();
		if (dimension == null) {
			return;
		}
		String name = "Death " + LocalTime.now().format(TIME);
		WaypointManager.addDeath(mc().player.blockPosition(), dimension,
				deathColor.get(), maxDeaths.getInt(), name);
	}

	/** Waypoints to mark on the compass bar: the ones visible in this dimension. */
	public List<Marker> markers() {
		List<Marker> markers = new ArrayList<>();
		if (!isEnabled() || mc().player == null) {
			return markers;
		}
		String dimension = WaypointManager.currentDimension();
		for (Iterator<Waypoint> it = WaypointManager.all().iterator(); it.hasNext();) {
			Waypoint waypoint = it.next();
			if (!waypoint.visible) {
				continue;
			}
			BlockPos pos = WaypointManager.displayPos(waypoint, dimension);
			if (pos != null) {
				markers.add(new Marker(waypoint.name, Vec3.atCenterOf(pos), waypoint.color, waypoint.death));
			}
		}
		return markers;
	}

	/** One compass-bar tick: where it is, what color, and whether it's a death point. */
	public record Marker(String name, Vec3 pos, int color, boolean death) {
	}
}

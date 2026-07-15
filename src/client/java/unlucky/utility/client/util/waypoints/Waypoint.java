package unlucky.utility.client.util.waypoints;

import java.util.UUID;

import net.minecraft.core.BlockPos;

/**
 * One saved place. Mirrors Meteor's waypoint model: a name, a position in a
 * named dimension, a color, and a "what happens when I get close" rule — plus
 * {@code death}, which marks the ones we drop automatically.
 *
 * <p>{@code dimension} is the {@code ResourceKey} path ("overworld", "the_nether",
 * "the_end"), so it survives across sessions without holding a registry object.
 */
public class Waypoint {
	/** What a waypoint does once you reach it. */
	public enum NearAction {
		KEEP,
		HIDE,
		DELETE
	}

	public final UUID id;
	public final long createdAt;
	public String name;
	public BlockPos pos;
	public String dimension;
	public int color;
	public boolean visible = true;
	public boolean death;
	public NearAction nearAction = NearAction.KEEP;
	public int nearDistance = 5;
	public int maxVisible = 5000;

	public Waypoint(UUID id, long createdAt, String name, BlockPos pos, String dimension, int color) {
		this.id = id;
		this.createdAt = createdAt;
		this.name = name;
		this.pos = pos;
		this.dimension = dimension;
		this.color = color;
	}

	public Waypoint(String name, BlockPos pos, String dimension, int color) {
		this(UUID.randomUUID(), System.currentTimeMillis(), name, pos, dimension, color);
	}
}

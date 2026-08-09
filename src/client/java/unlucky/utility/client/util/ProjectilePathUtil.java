package unlucky.utility.client.util;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Small allocation-conscious projectile simulation shared by trajectory modules. */
public final class ProjectilePathUtil {
	private ProjectilePathUtil() {
	}

	public record Path(List<Vec3> points, HitResult hit) {
		public Vec3 end() {
			return hit != null ? hit.getLocation() : points.getLast();
		}
	}

	/**
	 * Simulates the same order used by vanilla throwable projectiles: gravity,
	 * air drag, then movement/collision. Entity interception is deliberately left
	 * to the server; the displayed line is a stable terrain trajectory.
	 */
	public static Path simulate(Level level, Entity context, Vec3 start, Vec3 initialVelocity,
			double gravity, double drag, int maxSteps) {
		List<Vec3> points = new ArrayList<>(Math.min(maxSteps + 1, 401));
		Vec3 position = start;
		Vec3 velocity = initialVelocity;
		points.add(position);
		for (int tick = 0; tick < maxSteps; tick++) {
			velocity = velocity.add(0, -gravity, 0).scale(drag);
			Vec3 next = position.add(velocity);
			HitResult hit = level.clip(new ClipContext(position, next,
					ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, context));
			if (hit.getType() != HitResult.Type.MISS) {
				points.add(hit.getLocation());
				return new Path(points, hit);
			}
			position = next;
			points.add(position);
			if (position.y < level.getMinY() - 64 || position.y > level.getMaxY() + 64) {
				break;
			}
		}
		return new Path(points, null);
	}
}

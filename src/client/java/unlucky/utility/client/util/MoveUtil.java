package unlucky.utility.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public final class MoveUtil {
	private MoveUtil() {
	}

	public static boolean hasInput(LocalPlayer player) {
		Vec2 move = player.input.getMoveVector();
		return move.x != 0 || move.y != 0;
	}

	/** Horizontal unit direction from the player's movement input and yaw. */
	public static Vec3 inputDirection(LocalPlayer player) {
		Vec2 move = player.input.getMoveVector();
		if (move.x == 0 && move.y == 0) {
			return Vec3.ZERO;
		}
		float yaw = (float) Math.toRadians(player.getYRot());
		Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
		// move.x is left-positive (vanilla leftImpulse); a south-facing player's left is east (+X)
		Vec3 left = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
		Vec3 dir = forward.scale(move.y).add(left.scale(move.x));
		return dir.lengthSqr() < 1.0e-6 ? Vec3.ZERO : dir.normalize();
	}

	public static double horizontalSpeed(LocalPlayer player) {
		Vec3 velocity = player.getDeltaMovement();
		return Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
	}

	/**
	 * Whether {@code pos} is something you can actually stand on.
	 *
	 * <p>A collision shape is the test, not "is solid": slabs, stairs, chests and trapdoors all
	 * hold a player up and none of them are full cubes. Fluids and replaceable blocks are excluded
	 * even where they collide, because a landing that is a lily pad is not a landing — the reactive
	 * movement modules ask this question before committing to a drop they cannot take back.
	 *
	 * <p>Unloaded chunks answer false. That is deliberate: nothing is a worse landing than terrain
	 * the client has not been told about yet.
	 */
	public static boolean solidSupport(BlockPos pos) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || pos == null || !mc.level.isLoaded(pos)) {
			return false;
		}
		BlockState state = mc.level.getBlockState(pos);
		if (state.liquid() || state.canBeReplaced()) {
			return false;
		}
		return !state.getCollisionShape(mc.level, pos).isEmpty();
	}

	/**
	 * Whether landing in {@code pos} would hurt.
	 *
	 * <p>Damage on contact only — the things that make a drop the player did not choose into a
	 * drop they regret. Deliberately not a general danger model: a mob standing there or a long
	 * fall beyond it are different questions with different answers, and each caller already asks
	 * the one it needs.
	 */
	public static boolean hazardous(BlockPos pos) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || pos == null || !mc.level.isLoaded(pos)) {
			return false;
		}
		if (mc.level.getFluidState(pos).is(FluidTags.LAVA)) {
			return true;
		}
		BlockState state = mc.level.getBlockState(pos);
		return state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES)
				|| state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK)
				|| state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SWEET_BERRY_BUSH)
				|| state.is(Blocks.WITHER_ROSE);
	}
}

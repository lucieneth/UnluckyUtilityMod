package unlucky.utility.client.module.modules.world;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.player.AutoEat;
import unlucky.utility.client.settings.BooleanSetting;

/**
 * Lets you place blocks against air (no supporting neighbor). Hold use while
 * looking where you want the block; whether the server accepts it depends on
 * its placement checks. Inspired by Stardust's BlockAirPlace.
 */
public class BlockAirPlace extends Module {
	public final BooleanSetting pauseOnEat = addPauseOnEat();

	public BlockAirPlace() {
		super("BlockAirPlace", "Place blocks against air", Category.WORLD, ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	public void onTick() {
		if (mc().player == null || mc().gameMode == null || mc().gui.screen() != null) {
			return;
		}
		// Reads the very key AutoEat holds down to eat, so without this it places a block
		// every tick of every meal — the one module where yielding is not a nicety.
		if (AutoEat.pauses(pauseOnEat)) {
			return;
		}
		if (!mc().options.keyUse.isDown() || mc().player.getMainHandItem().isEmpty()) {
			return;
		}
		// only act when the crosshair is on nothing (air); otherwise vanilla handles it
		if (mc().hitResult != null && mc().hitResult.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
			return;
		}

		Vec3 eye = mc().player.getEyePosition();
		Vec3 target = eye.add(mc().player.getLookAngle().scale(3.5));
		net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(target);
		BlockHitResult hit = new BlockHitResult(target, Direction.UP, pos, false);
		mc().gameMode.useItemOn(mc().player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
	}
}

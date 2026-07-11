package unlucky.utility.client.module.modules.movement;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Teleport to the block you click. Position is client-authoritative, so simply
 * moving the player is enough — the next movement packet carries it. The hop is
 * capped though: the vanilla server rubber-bands you ("moved too quickly") once
 * a single tick's movement gets large, so keep the distance modest.
 *
 * <p>We raycast ourselves rather than using {@code Minecraft.hitResult}, which
 * only reaches as far as your interaction range. Hooked in {@code MinecraftMixin}.
 */
public class ClickTP extends Module {
	public final ModeSetting button = add(new ModeSetting("Button",
			"Which click teleports you (Right also swallows the use action)",
			"Right", "Right", "Middle"));
	public final NumberSetting maxDistance = add(new NumberSetting("Max distance",
			"Blocks per hop. Long hops trip the server's moved-too-quickly check", 8.0, 1.0, 10.0, 0.5));
	public final BooleanSetting landOnTop = add(new BooleanSetting("Land on top",
			"Stand on the block you clicked, rather than at the face you hit", true));

	public ClickTP() {
		super("ClickTP", "Teleport to the block you click", Category.MOVEMENT);
	}

	/** Returns true when it teleported, so the caller can swallow the click. */
	public boolean tryTeleport() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || player.isSpectator()) {
			return false;
		}
		double max = maxDistance.get();
		HitResult hit = player.pick(max, 1.0f, false);
		if (hit.getType() != HitResult.Type.BLOCK) {
			return false;
		}
		Vec3 destination;
		if (landOnTop.get()) {
			BlockPos pos = ((BlockHitResult) hit).getBlockPos();
			destination = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
		} else {
			destination = hit.getLocation();
		}
		if (destination.distanceTo(player.position()) > max) {
			return false;
		}
		player.setPos(destination.x, destination.y, destination.z);
		player.setDeltaMovement(Vec3.ZERO);
		player.fallDistance = 0.0; // don't land taking the fall we skipped
		return true;
	}
}

package unlucky.utility.client.util;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;

/**
 * Arm swings, 26.3 style.
 *
 * <p>26.2's one-argument swing on LivingEntity is gone. 26.3 makes the animation a
 * property of the held item — two data components, ATTACK_ANIMATION and
 * INTERACT_ANIMATION, each a {@link net.minecraft.world.item.component.SwingAnimation}
 * of type whack or stab — and the swing method now takes it explicitly. Passing
 * SwingAnimation.DEFAULT everywhere would compile and would make a stabbing weapon
 * whack, so the animation is read off the stack the way vanilla reads it.
 *
 * <p>The trailing false is sendToSwingingEntity, which is what every client-side
 * vanilla caller passes; it only matters server-side.
 *
 * <p>26.3 also stopped the swing from telling the server. 26.2's swing sent the swing
 * packet itself; now vanilla sends {@link ServerboundPunchPacket} by hand after attack
 * and break swings, and nothing at all for interactions, whose swing the server
 * derives from the interaction result. So {@link #attack} sends the punch and
 * {@link #interact} stays client-only.
 */
public final class SwingUtil {
	/** Swing as if attacking or breaking with the held item, and tell the server. */
	public static void attack(Player player, InteractionHand hand) {
		player.swing(hand, player.getItemInHand(hand).getAttackAnimation(), false);
		if (player instanceof LocalPlayer local) {
			local.connection.send(ServerboundPunchPacket.INSTANCE);
		}
	}

	/** Swing as if using or placing the held item. */
	public static void interact(Player player, InteractionHand hand) {
		player.swing(hand, player.getItemInHand(hand).getInteractAnimation(), false);
	}

	private SwingUtil() {
	}
}

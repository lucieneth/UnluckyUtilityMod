package unlucky.utility.client.util;

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
 */
public final class SwingUtil {
	/** Swing as if attacking or breaking with the held item. */
	public static void attack(Player player, InteractionHand hand) {
		player.swing(hand, player.getItemInHand(hand).getAttackAnimation(), false);
	}

	/** Swing as if using or placing the held item. */
	public static void interact(Player player, InteractionHand hand) {
		player.swing(hand, player.getItemInHand(hand).getInteractAnimation(), false);
	}

	private SwingUtil() {
	}
}

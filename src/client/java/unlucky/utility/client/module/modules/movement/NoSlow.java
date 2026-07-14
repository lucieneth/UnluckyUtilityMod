package unlucky.utility.client.module.modules.movement;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;

/**
 * Removes the movement penalties the client applies to itself.
 *
 * <p>All three are client-side multipliers folded into the movement input before
 * it ever becomes a packet, so cancelling them simply means we walk at full speed
 * while eating, blocking or drawing a bow:
 * <ul>
 *   <li>{@code items} — {@code LocalPlayer.modifyInput} scales the move vector by
 *       {@code itemUseSpeedMultiplier()} while an item is in use.</li>
 *   <li>{@code web} — {@code Player.makeStuckInBlock} sets the stuck multiplier
 *       (cobwebs, sweet berries, powder snow).</li>
 *   <li>{@code blocks} — {@code Player.getBlockSpeedFactor}, the soul sand and
 *       honey block drag. Only factors below 1 are lifted, so speed <i>boosts</i>
 *       (soul speed) still apply.</li>
 * </ul>
 */
public class NoSlow extends Module {
	public final BooleanSetting items = add(new BooleanSetting("Items", "Full speed while eating / blocking / drawing", true));
	public final BooleanSetting web = add(new BooleanSetting("Webs", "Ignore cobwebs, berries and powder snow", false));
	public final BooleanSetting blocks = add(new BooleanSetting("Blocks", "Ignore soul sand and honey drag", false));

	public NoSlow() {
		super("NoSlow", "Cancel the client's own movement slowdowns", Category.MOVEMENT);
	}
}

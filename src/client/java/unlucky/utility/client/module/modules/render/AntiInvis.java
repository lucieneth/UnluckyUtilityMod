package unlucky.utility.client.module.modules.render;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;

/**
 * Draws invisible entities the way vanilla draws an invisible teammate: see-through, but there.
 *
 * <p>The renderer already has that path — {@code isInvisibleToPlayer} false while
 * {@code isInvisible} is true gives the translucent model — and it is chosen by
 * {@code Entity.isInvisibleTo}. Answering "no" there (see {@code EntityMixin}) is the whole
 * module: no shader, no second pass, and armour and held items keep rendering as normal.
 * Only the local answer changes; the server's idea of who is invisible is untouched.
 */
public class AntiInvis extends Module {
	public final BooleanSetting playersOnly = add(new BooleanSetting("Players only",
			"Reveal invisible players but leave mobs and armour stands alone", true));

	public AntiInvis() {
		super("AntiInvis", "Shows invisible entities as translucent", Category.RENDER,
				ServerVisibility.CLIENT_ONLY);
	}

	/** Whether {@code entity} should render as visible to the local player. */
	public boolean reveals(Entity entity) {
		return isEnabled() && (!playersOnly.get() || entity instanceof Player);
	}
}

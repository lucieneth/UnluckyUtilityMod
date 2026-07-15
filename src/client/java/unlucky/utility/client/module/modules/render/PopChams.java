package unlucky.utility.client.module.modules.render;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.entity.LivingEntity;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;

/**
 * A colored ghost of a player washes over them the instant they pop a totem, then
 * fades — so a pop is impossible to miss in a fight, even off to the side of your
 * crosshair.
 *
 * <p>Fed by the same totem-pop signal the pop counter already uses
 * ({@code LivingEntity.handleEntityEvent} id 35), and drawn by re-submitting the
 * player's own model tinted and translucent, via {@code PopChamsFeature}.
 */
public class PopChams extends Module {
	public final ColorSetting color = add(new ColorSetting("Color", "Ghost tint", 0xFFFFD24A));
	public final NumberSetting duration = add(new NumberSetting("Duration", "How long the ghost lasts (ms)", 900, 200, 3000, 50));
	public final BooleanSetting self = add(new BooleanSetting("Self", "Also flash when you pop", true));

	/** uuid → when they popped. Small and self-pruning: entries die with their fade. */
	private final Map<UUID, Long> pops = new HashMap<>();

	public PopChams() {
		super("PopChams", "Flash a fading ghost over players who pop a totem", Category.RENDER);
	}

	@Override
	protected void onDisable() {
		pops.clear();
	}

	/** Called from the totem-pop hook. */
	public void onPop(LivingEntity entity) {
		if (!isEnabled()) {
			return;
		}
		if (!self.get() && entity == mc().player) {
			return;
		}
		pops.put(entity.getUUID(), System.currentTimeMillis());
	}

	/**
	 * The tint for this player right now — full strength at the pop, fading to
	 * nothing over {@code duration}. 0 once they're done (and the entry is dropped).
	 */
	public int tintFor(UUID uuid) {
		if (!isEnabled()) {
			return 0;
		}
		Long at = pops.get(uuid);
		if (at == null) {
			return 0;
		}
		long age = System.currentTimeMillis() - at;
		double life = duration.get();
		if (age >= life) {
			pops.remove(uuid);
			return 0;
		}
		float fade = (float) (1.0 - age / life);
		int base = color.get();
		int alpha = (int) (((base >>> 24) == 0 ? 255 : (base >>> 24)) * fade);
		return ColorUtil.withAlpha(base, alpha);
	}
}

package unlucky.utility.client.module.modules.movement;

import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import net.minecraft.world.effect.MobEffects;

/**
 * No fall damage. The server derives fall damage from the {@code onGround} flag
 * on our movement packets — it resets its own fall distance whenever we claim to
 * be grounded — so {@code LocalPlayerMixin} lies about that flag while we fall.
 * Nothing is spoofed client-side, so the world still looks and feels normal.
 */
public class NoFall extends Module {
	public final BooleanSetting water = add(new BooleanSetting("Disable in water",
			"Do not spoof while water or lava already prevents fall damage", true));
	public final BooleanSetting climbables = add(new BooleanSetting("Disable on climbables",
			"Do not spoof while climbing ladders or vines", true));
	public final BooleanSetting slowFalling = add(new BooleanSetting("Disable with Slow Falling",
			"Do not spoof while the effect already prevents fall damage", true));
	public final ModeSetting mode = add(new ModeSetting("Mode",
			"Packet: only lie while actually falling. Constant: always claim to be grounded",
			"Packet", "Packet", "Constant"));
	public final NumberSetting minFall = add(new NumberSetting("Min fall distance",
			"Start lying once you've fallen this far (blocks)", 3.0, 0.0, 10.0, 0.5),
			() -> mode.is("Packet"));
	public final BooleanSetting elytra = add(new BooleanSetting("Disable during elytra",
			"Leave elytra flight alone — claiming to be grounded mid-glide can cancel it", true));

	public final BooleanSetting vehicles = add(new BooleanSetting("Disable in vehicles",
			"Leave mount and boat movement packets untouched", true));

	public NoFall() {
		super("NoFall", "Removes fall damage", Category.MOVEMENT, ServerVisibility.SERVER_OBSERVABLE);
	}

	/** Centralises the safety checks so all outgoing movement packet variants agree. */
	public boolean shouldSpoof(net.minecraft.client.player.LocalPlayer player) {
		if (!isEnabled() || (elytra.get() && player.isFallFlying())) return false;
		if (vehicles.get() && player.isPassenger()) return false;
		if (water.get() && (player.isInWater() || player.isInLava())) return false;
		if (climbables.get() && player.onClimbable()) return false;
		if (slowFalling.get() && player.hasEffect(MobEffects.SLOW_FALLING)) return false;
		return mode.is("Constant") || player.fallDistance > minFall.get();
	}
}

package unlucky.utility.client.mixin;

import net.minecraft.client.entity.ClientAvatarState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.ElytraPhysics;

/**
 * ElytraPhysics "Smooth cape sim": vanilla moveCloak hard-snaps the cloak
 * point per axis once it trails more than 10 blocks, which spikes the
 * capeFlap/capeLean values every time it fires — at ElytraFly/RocketMan
 * speeds that's constant, and both the cape and the elytra sway jerk
 * violently. Replaces the snap with a smooth 9.5-block clamp along the trail
 * direction (OhHeyItsJosh's fix). When the module or setting is off, the
 * inject returns without cancelling and vanilla runs untouched.
 */
@Mixin(ClientAvatarState.class)
public class ClientAvatarStateMixin {
	@Shadow
	private double xCloak;
	@Shadow
	private double yCloak;
	@Shadow
	private double zCloak;
	@Shadow
	private double xCloakO;
	@Shadow
	private double yCloakO;
	@Shadow
	private double zCloakO;

	@Unique
	private boolean unlucky$cloakInit;

	@Inject(method = "moveCloak", at = @At("HEAD"), cancellable = true)
	private void unlucky$smoothCloak(Vec3 target, CallbackInfo ci) {
		ElytraPhysics module = UnluckyClient.INSTANCE.modules.get(ElytraPhysics.class);
		if (!module.isEnabled() || !module.smoothCape.get()) {
			return;
		}
		ci.cancel();
		this.xCloakO = this.xCloak;
		this.yCloakO = this.yCloak;
		this.zCloakO = this.zCloak;

		// First call for this player: start the cloak at the body instead of
		// letting it swing in from wherever the field defaulted (vanilla hides
		// this behind the snap we just removed).
		if (!unlucky$cloakInit) {
			unlucky$cloakInit = true;
			this.xCloak = target.x();
			this.yCloak = target.y();
			this.zCloak = target.z();
			this.xCloakO = this.xCloak;
			this.yCloakO = this.yCloak;
			this.zCloakO = this.zCloak;
			return;
		}

		double dx = target.x() - this.xCloak;
		double dy = target.y() - this.yCloak;
		double dz = target.z() - this.zCloak;
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);

		if (length > 10.0) {
			// too far behind: place the cloak 9.5 blocks behind the body along
			// the trail direction, history synced so interpolation doesn't glitch
			double scale = 9.5 / length;
			this.xCloak = target.x() - dx * scale;
			this.yCloak = target.y() - dy * scale;
			this.zCloak = target.z() - dz * scale;
			this.xCloakO = this.xCloak;
			this.yCloakO = this.yCloak;
			this.zCloakO = this.zCloak;
		} else {
			// vanilla's lerp, minus the per-axis snap
			this.xCloak += dx * 0.25;
			this.yCloak += dy * 0.25;
			this.zCloak += dz * 0.25;
		}
	}
}

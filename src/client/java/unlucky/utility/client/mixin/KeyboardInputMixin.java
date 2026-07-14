package unlucky.utility.client.mixin;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.movement.InventoryMove;
import unlucky.utility.client.module.modules.render.Freecam;

/**
 * Freezes the player's movement input while Freecam flies the camera, and feeds
 * it hardware key state for InventoryMove.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
	/**
	 * InventoryMove. tick() builds its Input from seven {@code KeyMapping.isDown()}
	 * calls, all of which read false while a screen is open — vanilla releases every
	 * mapping on open. One redirect covers all seven: poll the hardware instead.
	 */
	@Redirect(method = "tick",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
	private boolean unlucky$inventoryMove(KeyMapping mapping) {
		InventoryMove inventoryMove = UnluckyClient.INSTANCE.modules.get(InventoryMove.class);
		if (inventoryMove.active()) {
			return inventoryMove.isDown(mapping);
		}
		return mapping.isDown();
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void unlucky$freecamFreeze(CallbackInfo ci) {
		if (UnluckyClient.INSTANCE.modules.get(Freecam.class).isEnabled()) {
			this.keyPresses = Input.EMPTY;
			this.moveVector = Vec2.ZERO;
		}
	}
}

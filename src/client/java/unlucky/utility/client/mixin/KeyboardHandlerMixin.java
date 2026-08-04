package unlucky.utility.client.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;

@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
	private void unlucky$onKeyPress(long handle, int action, KeyEvent event, CallbackInfo ci) {
		// GLFW reports several consumer/media keys as KEY_UNKNOWN. They cannot be
		// valid client binds because KEY_UNKNOWN is also our unbound sentinel.
		if (action == GLFW.GLFW_PRESS && event.key() != GLFW.GLFW_KEY_UNKNOWN
				&& UnluckyClient.INSTANCE.onKeyPress(event.key())) {
			ci.cancel();
		}
	}
}

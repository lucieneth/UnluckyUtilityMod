package unlucky.utility.client.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.ItemFrames;

/**
 * ItemFrames culling. This is the earliest point that can drop an entity: say no
 * here and the renderer never extracts a render state for it, so the whole
 * per-frame cost (block model, item model, map texture) goes away rather than
 * being drawn and then discarded.
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void unlucky$cullItemFrames(Entity entity, Frustum frustum, double cameraX, double cameraY,
			double cameraZ, CallbackInfoReturnable<Boolean> cir) {
		if (entity instanceof ItemFrame frame
				&& UnluckyClient.INSTANCE.modules.get(ItemFrames.class).cull(frame, cameraX, cameraY, cameraZ)) {
			cir.setReturnValue(false);
		}
	}
}

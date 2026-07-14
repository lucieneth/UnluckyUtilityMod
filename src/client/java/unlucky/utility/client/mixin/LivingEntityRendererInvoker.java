package unlucky.utility.client.mixin;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code LivingEntityRenderer.addLayer} (protected, declared on the
 * superclass) so {@code AvatarRendererMixin} can attach the 3D skin layer from
 * the avatar renderer's constructor. A plain {@code @Shadow} can't reach it
 * because it isn't declared on {@code AvatarRenderer} itself.
 */
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererInvoker {
	@Invoker("addLayer")
	boolean unlucky$addLayer(RenderLayer<?, ?> layer);
}

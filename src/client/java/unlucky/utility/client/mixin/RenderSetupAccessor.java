package unlucky.utility.client.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * RainbowEnchant: reads the texture transform a render type draws with.
 *
 * <p>Needed only because {@code RenderSetup.textureTransform} is package-private.
 * It is what tells a glint draw apart from everything else in 26.3, where glint
 * render types are built per texture and there are no longer singletons to compare
 * against — see {@link RenderTypeMixin}.
 */
@Mixin(RenderSetup.class)
public interface RenderSetupAccessor {
	@Accessor("textureTransform")
	TextureTransform unlucky$textureTransform();
}

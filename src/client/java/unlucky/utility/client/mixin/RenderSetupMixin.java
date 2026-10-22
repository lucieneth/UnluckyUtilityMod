package unlucky.utility.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import unlucky.utility.client.util.GlintTint;

/**
 * RainbowEnchant's one hook: the texture a render type binds, looked up per draw.
 *
 * <p>{@code prepareTextures} runs from {@code RenderType.prepare()} on every draw and resolves each
 * sampler's {@code Identifier} through the texture manager right here, so answering the two glint
 * identifiers with a tinted copy reaches every glint 26.3 draws — the item, entity and armour
 * passes that sample {@code GlintSampler}, and the separate glint pass of shields and trims that
 * samples it as {@code Sampler0}. Why a texture rather than the colour modulator is in
 * {@link GlintTint}.
 */
@Mixin(RenderSetup.class)
public class RenderSetupMixin {
	@WrapOperation(method = "prepareTextures", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/texture/TextureManager;getTexture(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/client/renderer/texture/AbstractTexture;"))
	private AbstractTexture unlucky$tintGlint(TextureManager textures, Identifier location,
			Operation<AbstractTexture> original) {
		return GlintTint.swap(location, original.call(textures, location));
	}
}

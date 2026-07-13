package unlucky.utility.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import unlucky.utility.client.module.modules.render.XRay;

/**
 * XRay fullbright under Sodium. The vanilla fullbright path (byFace flat shade,
 * getLightCoords → 0xF000F0, non-AO tesselation) is all bypassed by Sodium's
 * own lighting, so listed ores came back dark — the "square one" regression.
 *
 * <p>Sodium packs a block's light into one int in
 * {@code LightDataAccess.compute(x, y, z)} — block light, sky light, luminance,
 * AO factor and the opacity/full-cube flags — which every downstream lighting
 * read ({@code getLightmap}, {@code unpackAO}) consumes. Rebuilding that word
 * with full block+sky light, flat AO and no emissive gives the same even,
 * shadeless look the vanilla path produced. Flags are preserved so the mesher's
 * neighbor/occlusion logic is untouched. Gated by {@link XRay#fullbrightAt} —
 * the position-based check, because Sodium meshes off the vanilla section
 * thread where the usual {@code fullbrightActive} ThreadLocal is never set.
 *
 * <p>String target + require 0: self-skips without Sodium, logs (not crashes)
 * on a Sodium rename. The pack/unpack helpers are {@code public static} on the
 * target, reached via {@link Shadow} stubs.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.model.light.data.LightDataAccess")
public abstract class SodiumLightDataAccessMixin {
	@Shadow(remap = false) private static native int packBL(int blockLight);
	@Shadow(remap = false) private static native int packSL(int skyLight);
	@Shadow(remap = false) private static native int packLU(int luminance);
	@Shadow(remap = false) private static native int packAO(float ao);
	@Shadow(remap = false) private static native int packEM(boolean emissive);
	@Shadow(remap = false) private static native int packOP(boolean opaque);
	@Shadow(remap = false) private static native int packFO(boolean fullOpaque);
	@Shadow(remap = false) private static native int packFC(boolean fullCube);
	@Shadow(remap = false) private static native boolean unpackOP(int word);
	@Shadow(remap = false) private static native boolean unpackFO(int word);
	@Shadow(remap = false) private static native boolean unpackFC(int word);

	@ModifyReturnValue(method = "compute(III)I", at = @At("RETURN"), remap = false, require = 0)
	private int unlucky$xrayFullbright(int word, int x, int y, int z) {
		if (!XRay.fullbrightAt(x, y, z)) {
			return word;
		}
		return packBL(15) | packSL(15) | packLU(15) | packAO(1.0f) | packEM(false)
				| packOP(unpackOP(word)) | packFO(unpackFO(word)) | packFC(unpackFC(word));
	}
}

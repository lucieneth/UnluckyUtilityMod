package unlucky.utility.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.Shader;
import unlucky.utility.client.module.modules.world.VanityESP;

/**
 * Decides which entities get an ESP silhouette and in what color.
 * Consulted by the glow mixins every frame. Returns 0 for "no glow".
 *
 * <p>{@link Shader} owns this: one module with a target list, which is how the reference
 * clients organise it and what keeps a single coherent palette across every category.
 * {@link VanityESP} is consulted afterwards for item frames it has specifically marked,
 * so its own highlighting still works for frames Shader is not targeting.
 */
public final class EspGlow {
	private EspGlow() {
	}

	public static int colorFor(Entity entity) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return 0;
		}
		int color = UnluckyClient.INSTANCE.modules.get(Shader.class).colorFor(entity);
		if (color != 0) {
			return color | 0xFF000000;
		}
		if (entity instanceof ItemFrame frame) {
			int vanity = UnluckyClient.INSTANCE.modules.get(VanityESP.class).frameColor(frame);
			if (vanity != 0) {
				return vanity | 0xFF000000;
			}
		}
		return 0;
	}
}

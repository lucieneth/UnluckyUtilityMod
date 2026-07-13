package unlucky.utility.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.contextualbar.LocatorBar;
import net.minecraft.resources.Identifier;
import net.minecraft.world.waypoints.TrackedWaypoint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.Heads;
import unlucky.utility.client.util.HeadRenderer;
import unlucky.utility.client.util.Render2D;

/**
 * Heads on the vanilla locator bar: the colored waypoint dot is a single
 * 7-arg (color) {@code blitSprite} call inside the {@code forEachWaypoint}
 * lambda — the pitch arrows use the 6-arg variant, so the target is
 * unambiguous and {@code method = "*"} safely finds the synthetic lambda.
 * The waypoint is the lambda's parameter, captured via {@code @Local} for its
 * player UUID; string-named waypoints keep the vanilla dot. Friends get their
 * blue corner dot.
 */
@Mixin(LocatorBar.class)
public class LocatorBarMixin {
	@WrapOperation(method = "*", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V"))
	private void unlucky$headDot(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
			int x, int y, int width, int height, int color, Operation<Void> original,
			@Local(argsOnly = true) TrackedWaypoint waypoint) {
		Heads heads = UnluckyClient.INSTANCE.modules.get(Heads.class);
		UUID id = heads.marksLocator() ? waypoint.id().left().orElse(null) : null;
		if (id == null) {
			original.call(graphics, pipeline, sprite, x, y, width, height, color);
			return;
		}
		HeadRenderer.draw(graphics, id, x, y + 1, 8);
		int dot = UnluckyClient.INSTANCE.modules
				.get(unlucky.utility.client.module.modules.misc.Friends.class).dotColor(id);
		if (dot != 0) {
			Render2D.rect(graphics, x + 6, y + 7, 3, 3, dot);
		}
	}
}

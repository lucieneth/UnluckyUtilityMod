package unlucky.utility.client.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.util.EspGlow;

@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V",
			at = @At("TAIL"))
	private void unlucky$espOutlineColor(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
		int color = EspGlow.colorFor(entity);
		if (color != 0) {
			state.outlineColor = color;
		}
		// NameTags draws its own richer billboard, so drop the built-in player tag —
		// and the below_name scoreboard line (a separate field!), which NameTags
		// re-renders in its own style as the score row
		if (unlucky.utility.client.module.modules.render.NameTags.hidesVanilla(entity)) {
			state.nameTag = null;
			state.scoreText = null;
		}
		if (state instanceof net.minecraft.client.renderer.entity.state.LivingEntityRenderState chamsState) {
			unlucky.utility.client.util.ChamsRenderState carrier =
					(unlucky.utility.client.util.ChamsRenderState) chamsState;
			carrier.unlucky$setChamsColor(unlucky.utility.client.UnluckyClient.INSTANCE.modules
					.get(unlucky.utility.client.module.modules.render.Chams.class).colorFor(entity));
			// Spinbot's real-facing ghost: only for your own model, at your true yaw
			net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
			int spinOutline = entity == mc.player
					? unlucky.utility.client.UnluckyClient.INSTANCE.modules
							.get(unlucky.utility.client.module.modules.misc.Spinbot.class).outlineArgb()
					: 0;
			carrier.unlucky$setSpinOutline(spinOutline, mc.player != null ? mc.player.getYRot() : 0.0f);
		}
		// silent rotations: show the spoofed head pitch in third person/freecam
		// (yaw is handled via yBodyRot/yHeadRot; pitch is camera-read at render
		// time, so it has to be overridden here to stay invisible in first person)
		if (entity == net.minecraft.client.Minecraft.getInstance().player
				&& unlucky.utility.client.util.RotationManager.isSpoofing()
				&& state instanceof net.minecraft.client.renderer.entity.state.LivingEntityRenderState living) {
			living.xRot = unlucky.utility.client.util.RotationManager.getPitch();
		}
	}
}

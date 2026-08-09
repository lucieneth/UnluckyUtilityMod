package unlucky.utility.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.movement.Velocity;

/** Scales only the flow vector before vanilla accumulates liquid current movement. */
@Mixin(EntityFluidInteraction.class)
public class EntityFluidInteractionMixin {
	@ModifyExpressionValue(method = "update", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/material/FluidState;getFlow(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 unlucky$velocityLiquidFlow(Vec3 flow, Entity entity, boolean ignoreCurrent) {
		return UnluckyClient.INSTANCE.modules.get(Velocity.class).fluidFlow(entity, flow);
	}
}

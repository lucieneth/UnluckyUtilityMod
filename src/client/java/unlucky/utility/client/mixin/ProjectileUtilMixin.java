package unlucky.utility.client.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import unlucky.utility.client.util.HitboxPickContext;

/** Changes the candidate box only while LocalPlayer's scoped crosshair pick is in flight. */
@Mixin(ProjectileUtil.class)
public class ProjectileUtilMixin {
	@Redirect(method = "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"))
	private static AABB unlucky$selectionBox(Entity entity) {
		return HitboxPickContext.expand(entity, entity.getBoundingBox());
	}
}

package unlucky.utility.client.mixin;

import java.util.function.Predicate;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import net.minecraft.client.gui.screens.Screen;
import unlucky.utility.client.module.modules.movement.InventoryMove;
import unlucky.utility.client.module.modules.movement.EventlessFly;
import unlucky.utility.client.module.modules.movement.EntityControl;
import unlucky.utility.client.module.modules.movement.NoFall;
import unlucky.utility.client.module.modules.movement.NoSlow;
import unlucky.utility.client.module.modules.movement.Phase;
import unlucky.utility.client.module.modules.movement.Velocity;
import unlucky.utility.client.module.modules.combat.Hitboxes;
import unlucky.utility.client.module.modules.player.AntiHunger;
import unlucky.utility.client.module.modules.player.LiquidInteract;
import unlucky.utility.client.util.HitboxPickContext;
import unlucky.utility.client.module.modules.world.Printer;

/**
 * Shapes the outgoing movement packets for NoFall and AntiHunger — both lie
 * about the same {@code onGround} flag, so they share one hook.
 *
 * <p>{@code sendPosition} passes {@code this.onGround()} into every packet
 * variant (PosRot / Pos / Rot / StatusOnly) and into its own {@code lastOnGround}
 * bookkeeping. Redirecting the call covers all of them at once, which keeps the
 * spoof self-consistent — a partial lie would make the client send spurious
 * status packets.
 */
@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {
	@Shadow
	private float itemUseSpeedMultiplier() {
		throw new AssertionError();
	}

	/** LiquidInteract changes only the block clip nested inside LocalPlayer's pick. */
	@WrapOperation(method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/entity/Entity;pick(DFZ)Lnet/minecraft/world/phys/HitResult;"))
	private static HitResult unlucky$liquidPick(Entity source, double range, float partialTick,
			boolean fluids, Operation<HitResult> original) {
		return UnluckyClient.INSTANCE.modules.get(LiquidInteract.class)
				.pick(source, range, partialTick, fluids, original);
	}

	/**
	 * Scopes Hitboxes to this crosshair query before ProjectileUtil is entered. ProjectileUtil
	 * also serves arrows and thrown items, so its redirect may never key off module enabled alone.
	 */
	@WrapOperation(method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/entity/projectile/ProjectileUtil;getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;"))
	private static EntityHitResult unlucky$hitboxPick(Entity source, Vec3 start, Vec3 end,
			AABB searchBox, Predicate<Entity> predicate, double maxDistanceSquared,
			Operation<EntityHitResult> original) {
		Hitboxes hitboxes = UnluckyClient.INSTANCE.modules.get(Hitboxes.class);
		if (!hitboxes.activeForPick() || source != Minecraft.getInstance().player) {
			return original.call(source, start, end, searchBox, predicate, maxDistanceSquared);
		}
		HitboxPickContext.enter(hitboxes);
		try {
			return original.call(source, start, end, searchBox, predicate, maxDistanceSquared);
		} finally {
			HitboxPickContext.exit();
		}
	}

	@Inject(method = "moveTowardsClosestSpace", at = @At("HEAD"), cancellable = true)
	private void unlucky$velocityBlockPush(double x, double z, CallbackInfo ci) {
		Velocity velocity = UnluckyClient.INSTANCE.modules.get(Velocity.class);
		if (velocity.preventsBlockPush((LocalPlayer) (Object) this)) {
			ci.cancel();
		}
	}

	@Inject(method = "getJumpRidingScale", at = @At("RETURN"), cancellable = true)
	private void unlucky$entityControlMaxJump(CallbackInfoReturnable<Float> cir) {
		if (UnluckyClient.INSTANCE.modules.get(EntityControl.class).maximizesJump()) {
			cir.setReturnValue(1.0f);
		}
	}

	/** TP Phase keeps movement client-side until its disable packet commits the endpoint. */
	@Inject(method = "sendPosition", at = @At("HEAD"), cancellable = true)
	private void unlucky$phaseDeferredMovement(CallbackInfo ci) {
		Phase phase = UnluckyClient.INSTANCE.modules.get(Phase.class);
		EventlessFly eventless = UnluckyClient.INSTANCE.modules.get(EventlessFly.class);
		if (phase.suppressesMovementPackets() || eventless.suppressesMovementPackets()) {
			ci.cancel();
		}
	}

	/**
	 * NoSlow. modifyInput scales the move vector by itemUseSpeedMultiplier() while
	 * an item is in use — hand back 1 instead and the slowdown never happens.
	 */
	@Redirect(method = "modifyInput",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;itemUseSpeedMultiplier()F"))
	private float unlucky$noSlowItemUse(LocalPlayer self) {
		NoSlow noSlow = UnluckyClient.INSTANCE.modules.get(NoSlow.class);
		if (noSlow.isEnabled() && noSlow.items.get() && self == Minecraft.getInstance().player) {
			return 1.0f;
		}
		return this.itemUseSpeedMultiplier(); // redirect's receiver is always this
	}

	/**
	 * InventoryMove's portal option. Inside a portal, handlePortalTransitionEffect
	 * closes any screen that isn't the pause menu — that check is this call. Say yes
	 * and the inventory / ClickGUI survive while the portal charges.
	 */
	@Redirect(method = "handlePortalTransitionEffect",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;isAllowedInPortal()Z"))
	private boolean unlucky$screensInPortal(Screen screen) {
		if (UnluckyClient.INSTANCE.modules.get(InventoryMove.class).allowInPortal()) {
			return true;
		}
		return screen.isAllowedInPortal();
	}

	@Redirect(method = "sendPosition",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;onGround()Z"))
	private boolean unlucky$spoofOnGround(LocalPlayer self) {
		boolean real = self.onGround();
		if (real || self != Minecraft.getInstance().player) {
			return real; // standing on ground: nothing worth lying about
		}
		// The server runs updateFallFlying on the state we report, so claiming to be
		// grounded mid-glide makes it stop the elytra for us.
		boolean gliding = self.isFallFlying();
		// The printer only asks for this during its final, centred descent onto solid ground
		// beside a refill box. It is not a general flight spoof: the flag is cleared as soon
		// as the player is grounded or the restocker resumes flying.
		if (UnluckyClient.INSTANCE.modules.get(Printer.class).protectsRestockLanding()) {
			return true;
		}

		AntiHunger antiHunger = UnluckyClient.INSTANCE.modules.get(AntiHunger.class);
		if (antiHunger.isEnabled() && antiHunger.spoofGround.get() && !gliding) {
			return true; // server never sees the jump, so it charges no jump exhaustion
		}

		NoFall noFall = UnluckyClient.INSTANCE.modules.get(NoFall.class);
		if (!noFall.isEnabled() || (gliding && noFall.elytra.get())) {
			return real;
		}
		if (noFall.mode.is("Constant")) {
			return true;
		}
		// Packet mode: only lie once the drop is far enough to hurt, so ordinary
		// jumping still looks honest to the server
		return self.fallDistance > noFall.minFall.get();
	}

	@Inject(method = "sendIsSprintingIfNeeded", at = @At("HEAD"), cancellable = true)
	private void unlucky$spoofSprint(CallbackInfo ci) {
		AntiHunger antiHunger = UnluckyClient.INSTANCE.modules.get(AntiHunger.class);
		if (antiHunger.isEnabled() && antiHunger.spoofSprint.get()) {
			ci.cancel(); // server keeps thinking we walk; sprint costs nothing
		}
	}
}

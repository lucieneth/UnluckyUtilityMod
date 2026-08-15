package unlucky.utility.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.render.ViewModel;

/**
 * ViewModel's four hooks, all inside the one method that draws one first-person arm.
 *
 * <p>{@code submitArmWithItem} is the funnel: main hand and offhand, item and bare hand, every
 * frame. Transforming here rather than at {@code submitHandsWithItems} is what makes the two hands
 * separable at all, and it is also what keeps the transform off everything else — the map, the
 * crosshair and the world are all outside this call.
 *
 * <p><b>HEAD pushes, RETURN pops, and the module owns the pairing.</b> A push that is not popped
 * leaves the pose stack one level deep for the rest of the frame, which misplaces the world rather
 * than the arm. RETURN is unconditional and asks the module whether it pushed, so toggling a
 * setting between the two injections cannot desynchronise them.
 */
@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {
	@Inject(method = "submitArmWithItem", at = @At("HEAD"))
	private void unlucky$viewModelPush(AbstractClientPlayer player, float partialTick, float pitch,
			InteractionHand hand, float swingProgress, ItemStack stack, float equipProgress,
			PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
		UnluckyClient.INSTANCE.modules.get(ViewModel.class).push(pose, hand);
	}

	@Inject(method = "submitArmWithItem", at = @At("RETURN"))
	private void unlucky$viewModelPop(AbstractClientPlayer player, float partialTick, float pitch,
			InteractionHand hand, float swingProgress, ItemStack stack, float equipProgress,
			PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
		UnluckyClient.INSTANCE.modules.get(ViewModel.class).pop(pose);
	}

	/**
	 * Swing progress, by float ordinal: {@code partialTick} is 0, {@code pitch} 1,
	 * {@code swingProgress} 2, {@code equipProgress} 3.
	 *
	 * <p>Visual only, and worth saying twice: this value reaches the arm transform and nothing
	 * else. The attack it belongs to was sent before the frame started.
	 */
	@ModifyVariable(method = "submitArmWithItem", at = @At("HEAD"), argsOnly = true, ordinal = 2)
	private float unlucky$viewModelSwing(float swingProgress) {
		return UnluckyClient.INSTANCE.modules.get(ViewModel.class).swingProgress(swingProgress);
	}

	@ModifyVariable(method = "submitArmWithItem", at = @At("HEAD"), argsOnly = true, ordinal = 3)
	private float unlucky$viewModelEquip(float equipProgress) {
		return UnluckyClient.INSTANCE.modules.get(ViewModel.class).equipProgress(equipProgress);
	}

	/**
	 * The eat/drink jiggle, which is its own transform rather than part of the arm's.
	 *
	 * <p>Cancelling is the whole of {@code Hidden}: the method's only effect is the transform it
	 * applies, so skipping it leaves the hand where the ordinary arm transform put it.
	 */
	@Inject(method = "applyEatTransform", at = @At("HEAD"), cancellable = true)
	private void unlucky$viewModelEatTransform(CallbackInfo ci) {
		if (!UnluckyClient.INSTANCE.modules.get(ViewModel.class).showsUseAnimation()) {
			ci.cancel();
		}
	}
}

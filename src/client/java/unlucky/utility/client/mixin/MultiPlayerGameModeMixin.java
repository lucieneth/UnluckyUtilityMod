package unlucky.utility.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.combat.BlatantMaceKill;
import unlucky.utility.client.module.modules.combat.Criticals;
import unlucky.utility.client.module.modules.combat.LegitMaceKill;
import unlucky.utility.client.module.modules.combat.MaceCombo;
import unlucky.utility.client.module.modules.movement.WindChargeJump;
import unlucky.utility.client.module.modules.player.InfiniteInteract;
import unlucky.utility.client.module.modules.world.AutoBrew;

/**
 * The one place every attack passes through — manual clicks and Aura/TriggerBot
 * alike, since {@code CombatUtil.attack} routes here too.
 *
 * <p>Criticals and the session tracker share this handler rather than injecting
 * twice: mixin wouldn't order two handlers, and a swallowed jump-crit must not
 * be counted now and again when it replays.
 */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
	@Inject(method = "attack", at = @At("HEAD"), cancellable = true)
	private void unlucky$attack(Player player, Entity target, CallbackInfo ci) {
		if (player != Minecraft.getInstance().player) {
			return;
		}
		Criticals criticals = UnluckyClient.INSTANCE.modules.get(Criticals.class);
		if (criticals.isEnabled() && criticals.onAttack(target)) {
			// held for the jump; it comes back through here when it lands and counts then
			ci.cancel();
			return;
		}
		MaceCombo combo = UnluckyClient.INSTANCE.modules.get(MaceCombo.class);
		if (combo.isEnabled()) {
			combo.onAttack(target);
		}
		BlatantMaceKill blatant = UnluckyClient.INSTANCE.modules.get(BlatantMaceKill.class);
		LegitMaceKill legit = UnluckyClient.INSTANCE.modules.get(LegitMaceKill.class);
		if (blatant.isEnabled()) {
			blatant.beforeAttack(target);
		} else if (legit.isEnabled()) {
			legit.beforeAttack(target);
		}
		InfiniteInteract infinite = UnluckyClient.INSTANCE.modules.get(InfiniteInteract.class);
		if (infinite.isEnabled()) {
			infinite.begin(target, InfiniteInteract.Action.ATTACK_ENTITY);
		}
		UnluckyClient.INSTANCE.session.onAttack(target);
	}

	@Inject(method = "useItem", at = @At("HEAD"))
	private void unlucky$windChargeJump(Player player, InteractionHand hand,
			CallbackInfoReturnable<InteractionResult> cir) {
		if (player != Minecraft.getInstance().player) {
			return;
		}
		WindChargeJump module = UnluckyClient.INSTANCE.modules.get(WindChargeJump.class);
		if (module.isEnabled()) {
			module.onUseItem(hand);
		}
	}

	/**
	 * Remembers which block we just right-clicked, for AutoBrew.
	 *
	 * <p>A container menu arrives as {@code ClientboundOpenScreen}, which carries a
	 * title and a type but <b>no position</b> — the server assumes the client knows
	 * what it just clicked. It does, but only right here. Reading
	 * {@code mc.hitResult} when the menu shows up would usually agree and quietly
	 * disagree the moment the player turns their head during the round trip, so the
	 * click itself is the only honest place to take this.
	 */
	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void unlucky$useItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
			CallbackInfoReturnable<InteractionResult> cir) {
		if (player != Minecraft.getInstance().player) {
			return;
		}
		// Eating is a held right-click, and a held right-click at a block is a block
		// interaction first: stand in front of a chest to eat and you open the chest, in
		// front of a lever and you flip it. Returning PASS is precisely what holding shift
		// does here — vanilla falls straight through to useItem, which is the meal — and it
		// does it without the sneak a real shift would apply to movement, which on a flying
		// printer is a descent.
		if (unlucky.utility.client.module.modules.player.AutoEat.busy()) {
			cir.setReturnValue(InteractionResult.PASS);
			return;
		}
		AutoBrew.onBlockUsed(hit.getBlockPos());
		InfiniteInteract infinite = UnluckyClient.INSTANCE.modules.get(InfiniteInteract.class);
		if (infinite.isEnabled()) {
			infinite.begin(hit.getBlockPos(), InfiniteInteract.Action.INTERACT_BLOCK);
		}
	}

	@Inject(method = "useItemOn", at = @At("RETURN"))
	private void unlucky$finishInfiniteBlockUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
			CallbackInfoReturnable<InteractionResult> cir) {
		if (player == Minecraft.getInstance().player) {
			UnluckyClient.INSTANCE.modules.get(InfiniteInteract.class).finish();
		}
	}

	@Inject(method = "interact", at = @At("HEAD"))
	private void unlucky$infiniteEntityUse(Player player, Entity entity, EntityHitResult hit, InteractionHand hand,
			CallbackInfoReturnable<InteractionResult> cir) {
		if (player != Minecraft.getInstance().player) {
			return;
		}
		InfiniteInteract infinite = UnluckyClient.INSTANCE.modules.get(InfiniteInteract.class);
		if (infinite.isEnabled()) {
			infinite.begin(entity, InfiniteInteract.Action.INTERACT_ENTITY);
		}
	}

	@Inject(method = "interact", at = @At("RETURN"))
	private void unlucky$finishInfiniteEntityUse(Player player, Entity entity, EntityHitResult hit, InteractionHand hand,
			CallbackInfoReturnable<InteractionResult> cir) {
		if (player == Minecraft.getInstance().player) {
			UnluckyClient.INSTANCE.modules.get(InfiniteInteract.class).finish();
		}
	}

	@Inject(method = "startDestroyBlock", at = @At("HEAD"))
	private void unlucky$infiniteStartBreak(BlockPos pos, Direction direction,
			CallbackInfoReturnable<Boolean> cir) {
		InfiniteInteract infinite = UnluckyClient.INSTANCE.modules.get(InfiniteInteract.class);
		if (infinite.isEnabled()) {
			infinite.begin(pos, InfiniteInteract.Action.BREAK_BLOCK);
		}
	}

	@Inject(method = "startDestroyBlock", at = @At("RETURN"))
	private void unlucky$finishInfiniteStartBreak(BlockPos pos, Direction direction,
			CallbackInfoReturnable<Boolean> cir) {
		UnluckyClient.INSTANCE.modules.get(InfiniteInteract.class).finish();
	}

	@Inject(method = "continueDestroyBlock", at = @At("HEAD"))
	private void unlucky$infiniteContinueBreak(BlockPos pos, Direction direction,
			CallbackInfoReturnable<Boolean> cir) {
		InfiniteInteract infinite = UnluckyClient.INSTANCE.modules.get(InfiniteInteract.class);
		if (infinite.isEnabled()) {
			infinite.begin(pos, InfiniteInteract.Action.BREAK_BLOCK);
		}
	}

	@Inject(method = "continueDestroyBlock", at = @At("RETURN"))
	private void unlucky$finishInfiniteContinueBreak(BlockPos pos, Direction direction,
			CallbackInfoReturnable<Boolean> cir) {
		UnluckyClient.INSTANCE.modules.get(InfiniteInteract.class).finish();
	}

	/**
	 * Closes Criticals' sprint-reset bracket once the interact packet is behind us.
	 * Only reached when HEAD didn't cancel, which is exactly when a bracket can be
	 * open — a cancelled (jump-held) attack never opens one here.
	 */
	@Inject(method = "attack", at = @At("RETURN"))
	private void unlucky$attackEnd(Player player, Entity target, CallbackInfo ci) {
		if (player == Minecraft.getInstance().player) {
			UnluckyClient.INSTANCE.modules.get(InfiniteInteract.class).finish();
			UnluckyClient.INSTANCE.modules.get(BlatantMaceKill.class).afterAttack();
			UnluckyClient.INSTANCE.modules.get(LegitMaceKill.class).afterAttack();
			Criticals criticals = UnluckyClient.INSTANCE.modules.get(Criticals.class);
			if (criticals.isEnabled()) {
				criticals.onAttackEnd();
			}
		}
	}
}

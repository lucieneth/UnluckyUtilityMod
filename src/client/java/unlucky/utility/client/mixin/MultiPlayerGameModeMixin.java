package unlucky.utility.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.combat.Criticals;
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
		UnluckyClient.INSTANCE.session.onAttack(target);
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
	@Inject(method = "useItemOn", at = @At("HEAD"))
	private void unlucky$useItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
			CallbackInfoReturnable<InteractionResult> cir) {
		if (player == Minecraft.getInstance().player) {
			AutoBrew.onBlockUsed(hit.getBlockPos());
		}
	}

	/**
	 * Closes Criticals' sprint-reset bracket once the interact packet is behind us.
	 * Only reached when HEAD didn't cancel, which is exactly when a bracket can be
	 * open — a cancelled (jump-held) attack never opens one here.
	 */
	@Inject(method = "attack", at = @At("RETURN"))
	private void unlucky$attackEnd(Player player, Entity target, CallbackInfo ci) {
		if (player == Minecraft.getInstance().player) {
			Criticals criticals = UnluckyClient.INSTANCE.modules.get(Criticals.class);
			if (criticals.isEnabled()) {
				criticals.onAttackEnd();
			}
		}
	}
}

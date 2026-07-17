package unlucky.utility.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.multiplayer.ClientPacketListener;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.combat.Dodge;
import unlucky.utility.client.module.modules.misc.GamemodeNotifier;
import unlucky.utility.client.module.modules.misc.SoundLocator;
import unlucky.utility.client.module.modules.movement.Velocity;
import unlucky.utility.client.module.modules.player.AutoFish;
import unlucky.utility.client.util.ServerStats;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
	@Inject(method = "handleSoundEvent", at = @At("TAIL"))
	private void unlucky$soundLocator(ClientboundSoundPacket packet, CallbackInfo ci) {
		SoundLocator soundLocator = UnluckyClient.INSTANCE.modules.get(SoundLocator.class);
		if (soundLocator.isEnabled()) {
			soundLocator.onSound(packet);
		}
		// AutoFish listens for the bobber splash — the server's own "a fish bit" signal
		AutoFish autoFish = UnluckyClient.INSTANCE.modules.get(AutoFish.class);
		if (autoFish.isEnabled()) {
			autoFish.onSound(packet);
		}
	}

	/**
	 * HEAD is the only place the gamemode diff still exists — vanilla writes the
	 * packet's mode into the tab list further down, so from here on the "old" one
	 * is gone. Like the pickup handler, HEAD also runs once on the netty thread
	 * before the thread-check reschedules, so only the main-thread pass counts.
	 */
	@Inject(method = "handlePlayerInfoUpdate", at = @At("HEAD"))
	private void unlucky$gamemodeNotifier(ClientboundPlayerInfoUpdatePacket packet, CallbackInfo ci) {
		GamemodeNotifier notifier = UnluckyClient.INSTANCE.modules.get(GamemodeNotifier.class);
		if (notifier.isEnabled() && Minecraft.getInstance().isSameThread()) {
			notifier.onPlayerInfoUpdate(packet);
		}
	}

	/**
	 * The server reporting a hit it already applied — the earliest the client ever
	 * learns it was attacked. TAIL, so the level lookup for the attacker sees the
	 * same state vanilla just did.
	 */
	@Inject(method = "handleDamageEvent", at = @At("TAIL"))
	private void unlucky$dodgeOnHit(ClientboundDamageEventPacket packet, CallbackInfo ci) {
		Dodge dodge = UnluckyClient.INSTANCE.modules.get(Dodge.class);
		if (dodge.isEnabled()) {
			dodge.onDamage(packet);
		}
	}

	@Inject(method = "handleAnimate", at = @At("TAIL"))
	private void unlucky$dodgeOnSwing(ClientboundAnimatePacket packet, CallbackInfo ci) {
		Dodge dodge = UnluckyClient.INSTANCE.modules.get(Dodge.class);
		if (dodge.isEnabled()) {
			dodge.onAnimate(packet);
		}
	}

	@Inject(method = "handleSetTime", at = @At("TAIL"))
	private void unlucky$tps(ClientboundSetTimePacket packet, CallbackInfo ci) {
		ServerStats.onSetTime(packet.gameTime());
	}

	@Inject(method = "handleTakeItemEntity", at = @At("HEAD"))
	private void unlucky$pickup(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		// HEAD runs once on the netty thread before the thread-check reschedules;
		// only act on the main-thread pass, where the item entity is still intact
		if (!mc.isSameThread() || mc.player == null || mc.level == null
				|| packet.getPlayerId() != mc.player.getId()) {
			return;
		}
		if (mc.level.getEntity(packet.getItemId()) instanceof ItemEntity item && !item.getItem().isEmpty()) {
			UnluckyClient.INSTANCE.hud.itemPickups().onPickup(item.getItem(), packet.getAmount());
		}
	}

	@Redirect(method = "handleSetEntityMotion",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;lerpMotion(Lnet/minecraft/world/phys/Vec3;)V"))
	private void unlucky$scaleKnockback(Entity entity, Vec3 motion) {
		Velocity velocity = UnluckyClient.INSTANCE.modules.get(Velocity.class);
		if (entity == Minecraft.getInstance().player && velocity.isEnabled()) {
			entity.lerpMotion(new Vec3(
					motion.x * velocity.horizontal.get(),
					motion.y * velocity.vertical.get(),
					motion.z * velocity.horizontal.get()));
		} else {
			entity.lerpMotion(motion);
		}
	}
}

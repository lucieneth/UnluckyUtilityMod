package unlucky.utility.client.mixin;

import net.minecraft.client.Minecraft;
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
import unlucky.utility.client.module.modules.misc.SoundLocator;
import unlucky.utility.client.module.modules.movement.Velocity;
import unlucky.utility.client.util.ServerStats;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
	@Inject(method = "handleSoundEvent", at = @At("TAIL"))
	private void unlucky$soundLocator(ClientboundSoundPacket packet, CallbackInfo ci) {
		SoundLocator soundLocator = UnluckyClient.INSTANCE.modules.get(SoundLocator.class);
		if (soundLocator.isEnabled()) {
			soundLocator.onSound(packet);
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

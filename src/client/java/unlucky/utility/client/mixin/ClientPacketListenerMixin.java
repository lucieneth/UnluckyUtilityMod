package unlucky.utility.client.mixin;

import java.util.Optional;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.combat.Criticals;
import unlucky.utility.client.module.modules.combat.Dodge;
import unlucky.utility.client.module.modules.misc.GamemodeNotifier;
import unlucky.utility.client.module.modules.misc.SoundLocator;
import unlucky.utility.client.module.modules.movement.Velocity;
import unlucky.utility.client.module.modules.movement.LongJump;
import unlucky.utility.client.module.modules.movement.Speed;
import unlucky.utility.client.module.modules.player.AutoFish;
import unlucky.utility.client.module.modules.player.NoRotate;
import unlucky.utility.client.module.modules.world.NewChunks;
import unlucky.utility.client.util.PacketQueueManager;
import unlucky.utility.client.util.ServerStats;

@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
	@ModifyExpressionValue(method = "handleMovePlayer", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/protocol/game/ClientboundPlayerPositionPacket;change()Lnet/minecraft/world/entity/PositionMoveRotation;"))
	private PositionMoveRotation unlucky$keepCorrectionCamera(PositionMoveRotation original,
			ClientboundPlayerPositionPacket packet) {
		return UnluckyClient.INSTANCE.modules.get(NoRotate.class).filter(packet, original);
	}

	@ModifyExpressionValue(method = "handleRotatePlayer", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/protocol/game/ClientboundPlayerRotationPacket;yRot()F"))
	private float unlucky$keepRotationYaw(float original, ClientboundPlayerRotationPacket packet) {
		return UnluckyClient.INSTANCE.modules.get(NoRotate.class).filterYaw(packet, original);
	}

	@ModifyExpressionValue(method = "handleRotatePlayer", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/protocol/game/ClientboundPlayerRotationPacket;xRot()F"))
	private float unlucky$keepRotationPitch(float original, ClientboundPlayerRotationPacket packet) {
		return UnluckyClient.INSTANCE.modules.get(NoRotate.class).filterPitch(packet, original);
	}

	@ModifyArg(method = { "handleMovePlayer", "handleRotatePlayer" },
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/network/Connection;send(Lnet/minecraft/network/protocol/Packet;)V"),
			index = 0)
	private Packet<?> unlucky$correctionAcknowledgement(Packet<?> packet) {
		return UnluckyClient.INSTANCE.modules.get(NoRotate.class).acknowledgement(packet);
	}

	@Inject(method = "handleMovePlayer", at = @At("TAIL"))
	private void unlucky$serverPosition(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			PacketQueueManager.recordServerPosition(mc.player.position());
		}
		UnluckyClient.INSTANCE.modules.get(NoRotate.class).onCorrection();
		UnluckyClient.INSTANCE.modules.get(LongJump.class).onCorrection();
		UnluckyClient.INSTANCE.modules.get(Speed.class).onCorrection();
	}

	@Inject(method = "handleRotatePlayer", at = @At("TAIL"))
	private void unlucky$serverRotation(ClientboundPlayerRotationPacket packet, CallbackInfo ci) {
		UnluckyClient.INSTANCE.modules.get(NoRotate.class).onCorrection();
	}

	@Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
	private void unlucky$newChunksLoad(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
		NewChunks module = UnluckyClient.INSTANCE.modules.get(NewChunks.class);
		if (module.isEnabled()) module.onChunkLoaded(packet);
	}

	@Inject(method = "handleForgetLevelChunk", at = @At("TAIL"))
	private void unlucky$newChunksUnload(ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
		NewChunks module = UnluckyClient.INSTANCE.modules.get(NewChunks.class);
		if (module.isEnabled()) module.onChunkForgotten(packet);
	}

	@Inject(method = "handleBlockUpdate", at = @At("TAIL"))
	private void unlucky$newChunksBlock(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
		NewChunks module = UnluckyClient.INSTANCE.modules.get(NewChunks.class);
		if (module.isEnabled()) module.onBlockUpdate(packet);
	}

	@Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
	private void unlucky$newChunksSection(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
		NewChunks module = UnluckyClient.INSTANCE.modules.get(NewChunks.class);
		if (module.isEnabled()) module.onSectionUpdate(packet);
	}

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
		Criticals criticals = UnluckyClient.INSTANCE.modules.get(Criticals.class);
		if (criticals.isEnabled()) {
			criticals.onDamage(packet);
		}
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
		motion = UnluckyClient.INSTANCE.modules.get(Criticals.class).correctThornsMotion(entity, motion);
		Velocity velocity = UnluckyClient.INSTANCE.modules.get(Velocity.class);
		entity.lerpMotion(velocity.attackKnockback(entity, motion));
	}

	@ModifyExpressionValue(method = "handleExplosion", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;playerKnockback()Ljava/util/Optional;"))
	private Optional<Vec3> unlucky$scaleExplosion(Optional<Vec3> original, ClientboundExplodePacket packet) {
		Velocity velocity = UnluckyClient.INSTANCE.modules.get(Velocity.class);
		return original.map(velocity::explosionKnockback);
	}
}

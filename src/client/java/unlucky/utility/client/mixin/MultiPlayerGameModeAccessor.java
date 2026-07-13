package unlucky.utility.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the private {@code startPrediction} so Nuker can send raw block-action
 * packets with the correct prediction sequence — the same mechanism vanilla uses,
 * which lets us fire a START+STOP pair to break a block server-side in one tick
 * (see {@code modules/world/Nuker}, §6). Without a real sequence the server's
 * prediction ack desyncs.
 */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
	@Invoker("startPrediction")
	void unlucky$startPrediction(ClientLevel level, PredictiveAction action);
}

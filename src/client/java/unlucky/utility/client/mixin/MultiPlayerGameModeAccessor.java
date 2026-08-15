package unlucky.utility.client.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
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

	/**
	 * Pushes a just-changed hotbar selection to the server immediately, instead of waiting
	 * for the next {@code MultiPlayerGameMode.tick()} to notice.
	 *
	 * <p>AutoTool needs this and nothing else does. Vanilla sends the held-item change once a
	 * tick, which is fine for a hand that changed because a hand moved — but a tool chosen
	 * <em>for</em> a block has to be on the server before the block action that follows it in
	 * the same tick, or the server spends the first tick of the break computing progress for
	 * whatever was in your hand a moment ago. Going through vanilla's own method rather than
	 * sending the packet ourselves keeps its {@code carriedIndex} honest, so it does not then
	 * send a second, identical one.
	 */
	@Invoker("ensureHasSentCarriedItem")
	void unlucky$ensureHasSentCarriedItem();

	/**
	 * Vanilla's own break progress, 0..1, and the position it belongs to.
	 *
	 * <p>{@code getDestroyStage()} is public but it is the <em>rendered</em> stage — the
	 * progress quantised to ten crack textures — which is the wrong number to reason with.
	 * MiningTracker reports the real one, and SpeedMine scales it, so both need the field.
	 *
	 * <p>Reading it is also the only honest way to know how far along the player's own manual
	 * break is: the client owns that arithmetic outright, and re-deriving it beside vanilla
	 * would be a second implementation that drifts the first time either changes.
	 */
	@Accessor("destroyProgress")
	float unlucky$destroyProgress();

	@Accessor("destroyProgress")
	void unlucky$setDestroyProgress(float progress);

	@Accessor("destroyBlockPos")
	BlockPos unlucky$destroyBlockPos();

	/**
	 * The cooldown vanilla imposes after a break completes. Clearing it is what lets a module
	 * chain one break straight into the next; leaving it alone is what keeps a manual break
	 * feeling like vanilla.
	 */
	@Accessor("destroyDelay")
	void unlucky$setDestroyDelay(int delay);
}

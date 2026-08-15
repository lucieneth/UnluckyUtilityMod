package unlucky.utility.client.module.modules.world;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.player.AutoEat;
import unlucky.utility.client.module.modules.player.AutoTool;
import unlucky.utility.client.settings.BlockListSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.MiningActionCoordinator;
import unlucky.utility.client.util.MiningTracker;
import unlucky.utility.client.util.RotationManager;

/**
 * Makes the one block you are mining by hand come apart sooner.
 *
 * <p><b>One block, and only the one you chose.</b> Nuker owns area mining, VeinMiner owns
 * following a vein and the Printer owns schematic breaks; this module never picks a target. It
 * takes the target the player's own left-click already produced and finishes it faster. That
 * boundary is the reason all four can be on at once without any of them fighting: they hold the
 * same {@link MiningActionCoordinator} lease, and this one holds it at {@code MANUAL}, which
 * nothing automatic outranks.
 *
 * <p><b>Two modes, and neither of them is a bypass.</b>
 * <ul>
 * <li><b>Vanilla</b> scales the client's own progress accumulation. The server keeps its own
 * count and will not remove a block before its own arithmetic says so, which is precisely why
 * the multiplier is capped at 2× — beyond that the client simply waits at 100% for a server
 * that has not caught up, which looks broken and is.
 * <li><b>Packet</b> sends the START once and the STOP when the predicted progress crosses the
 * threshold, instead of when the client's animation finishes. The threshold exists to pay for
 * the round trip: the message has to arrive after the server's own progress completes, and 0.85
 * is early enough to matter on a real connection without being early enough to be refused.
 * </ul>
 *
 * <p>There is deliberately no DoubleMine, no named server profile, no retry spam, no crystal
 * mode and no timer. Those are all ways of asking a server to be wrong rather than ways of
 * mining, and the client's standing policy is vanilla semantics.
 */
public class SpeedMine extends Module {
	public final ModeSetting mode = add(new ModeSetting("Mode",
			"Vanilla scales local progress; Packet sends the finish message early enough to pay "
					+ "for the round trip", "Vanilla", "Vanilla", "Packet"));
	public final NumberSetting speedMultiplier = add(new NumberSetting("Speed multiplier",
			"How much faster local break progression runs", 1.20, 1.00, 2.00, 0.05),
			() -> mode.is("Vanilla"));
	public final NumberSetting finishThreshold = add(new NumberSetting("Finish threshold",
			"Predicted progress at which the STOP is sent", 0.85, 0.00, 1.00, 0.05),
			() -> mode.is("Packet"));
	public final BooleanSetting instantMine = add(new BooleanSetting("Instant mine",
			"Immediately finish blocks vanilla already predicts as one-hit", true));

	public final ModeSetting filter = add(new ModeSetting("Filter",
			"How the block list is used", "All", "All", "Whitelist", "Blacklist"));
	public final BlockListSetting blocks = add(new BlockListSetting("Blocks",
			"Used by Whitelist and Blacklist — right-click to pick", java.util.Set.of()),
			() -> !filter.is("All"));
	public final NumberSetting range = add(new NumberSetting("Range",
			"Abort a target further away than this", 6, 1, 8, 0.5));
	public final BooleanSetting autoTool = add(new BooleanSetting("Auto tool",
			"Ask AutoTool for the right tool before the progress is worked out", true));
	public final ModeSetting rotation = add(new ModeSetting("Rotation",
			"Whether the break is aimed, and whether the camera follows", "Off",
			"Off", "Silent", "Visible"));
	public final ModeSetting swing = add(new ModeSetting("Swing",
			"Hand swing while mining", "Client", "Client", "Packet", "None"));
	public final BooleanSetting pauseWhileUsing = add(new BooleanSetting("Pause while using",
			"Stand down while eating, drinking or drawing", true));
	public final BooleanSetting abortOnSlotChange = add(new BooleanSetting("Abort on slot change",
			"Abort a packet target when the effective tool changes unexpectedly", true));
	public final BooleanSetting resetOnTargetChange = add(new BooleanSetting("Reset on target change",
			"Send a correct abort before starting a new target", true));
	public final BooleanSetting clientRemove = add(new BooleanSetting("Client remove",
			"Hide the block once the shared tracker predicts completion, before the server "
					+ "confirms it. Cosmetic, and it comes back if the server disagrees.", false));
	public final BooleanSetting renderTarget = add(new BooleanSetting("Render target",
			"Let BreakIndicators draw the progress rather than drawing a second box here", true));

	/** The packet target this module opened, so exactly one lifecycle exists for it. */
	private BlockPos packetTarget;
	private Direction packetFace;
	private float predicted;
	private boolean stopSent;

	public SpeedMine() {
		super("SpeedMine", "Finishes the block you are mining by hand sooner", Category.WORLD,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onDisable() {
		abort();
	}

	@Override
	protected void onPanic() {
		abort();
	}

	/** Closes any open lifecycle and drops the lease. Disable, panic, world change, dimension. */
	private void abort() {
		if (packetTarget != null && !stopSent) {
			MiningActionCoordinator.packetAbort(this);
		}
		MiningActionCoordinator.release(this);
		clearSession();
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || mc().gameMode == null) {
			abort();
			return;
		}
		if (paused(player)) {
			abort();
			return;
		}
		if (mode.is("Packet")) {
			tickPacket(player);
		} else if (packetTarget != null) {
			// Switched modes mid-break: close the lifecycle we opened rather than leaving the
			// server holding a START nobody is going to finish.
			abort();
		}
	}

	private boolean paused(LocalPlayer player) {
		return mc().gui.screen() != null || player.isSpectator()
				|| (pauseWhileUsing.get() && (player.isUsingItem() || AutoEat.busy()));
	}

	// ---- vanilla mode ------------------------------------------------------

	/**
	 * Extra progress to add to this tick's vanilla accumulation, or 0.
	 *
	 * <p>Called from {@code MultiPlayerGameModeMixin} at the head of {@code continueDestroyBlock},
	 * <em>before</em> vanilla adds its own increment and checks for completion. Pre-loading the
	 * extra there rather than adding it afterwards is what lets vanilla own the completion: the
	 * block breaks on vanilla's own test, one tick sooner, instead of this module having to
	 * decide when a block is finished and getting that decision slightly different.
	 */
	public float extraProgressFor(BlockPos pos) {
		LocalPlayer player = mc().player;
		if (!isEnabled() || mode.is("Packet") || player == null || mc().level == null) {
			return 0.0f;
		}
		if (paused(player) || !targetAllowed(player, pos)) {
			return 0.0f;
		}
		// Only the player's own break. A Printer, VeinMiner or Nuker target belongs to somebody
		// else and speeding it up would be a second opinion about a block that already has an
		// owner — which is exactly what the lease exists to prevent.
		if (MiningActionCoordinator.owner() != null) {
			return 0.0f;
		}
		if (autoTool.get()) {
			// Before the progress arithmetic, not after: the tool in hand is what the rate is
			// computed from, and asking afterwards would boost the wrong number.
			UnluckyClient.INSTANCE.modules.get(AutoTool.class).onDestroy(pos);
		}
		float perTick = MiningTracker.tickProgress(player, mc().level, pos);
		if (perTick <= 0.0f) {
			return 0.0f;
		}
		if (instantMine.get() && perTick >= 1.0f) {
			// Vanilla already calls this a one-hit block; there is nothing to accelerate and
			// nothing to add.
			return 0.0f;
		}
		aim(pos);
		return perTick * (float) (speedMultiplier.get() - 1.0);
	}

	// ---- packet mode -------------------------------------------------------

	/**
	 * The player just started breaking a block. In Packet mode this module takes it over.
	 *
	 * <p>Called from {@code MultiPlayerGameModeMixin} at the head of {@code startDestroyBlock},
	 * after AutoTool has had its say. Taking over means <b>vanilla's own destroy loop is
	 * cancelled</b> for this block, which is not optional: vanilla accumulating progress toward
	 * its own completion while this module sends its own START and STOP is two lifecycles for
	 * one block, and the second one is the one that gets refused.
	 *
	 * @return whether the break was taken over, in which case vanilla must not run its own
	 */
	public boolean onStartDestroy(BlockPos pos, Direction face) {
		LocalPlayer player = mc().player;
		if (!isEnabled() || !mode.is("Packet") || player == null || mc().level == null
				|| paused(player) || !targetAllowed(player, pos)) {
			return false;
		}
		if (packetTarget != null && !packetTarget.equals(pos)) {
			// A new target while one is open. Reset on target change sends the correct abort
			// first; without it the server holds two opinions about what we are mining.
			if (resetOnTargetChange.get()) {
				abort();
			} else {
				clearSession();
			}
		}
		if (packetTarget != null) {
			return true; // already ours; vanilla still must not run
		}
		if (!MiningActionCoordinator.acquire(this, MiningActionCoordinator.PRIORITY_MANUAL)) {
			return false;
		}
		packetFace = face == null ? faceToward(player, pos) : face;
		if (!MiningActionCoordinator.packetStart(this, pos, packetFace)) {
			MiningActionCoordinator.release(this);
			return false;
		}
		packetTarget = pos.immutable();
		predicted = 0.0f;
		stopSent = false;
		return true;
	}

	/**
	 * Whether vanilla's per-tick destroy must be skipped for this block.
	 *
	 * <p>The other half of the takeover. Without it vanilla opens its own break on the very next
	 * tick of the held click, and the block ends up with a client-side progress bar that has
	 * nothing to do with the messages actually on the wire.
	 */
	public boolean suppressVanillaContinue(BlockPos pos) {
		return isEnabled() && mode.is("Packet") && packetTarget != null && packetTarget.equals(pos);
	}

	/**
	 * One tick of an open packet break.
	 *
	 * <p>Never starts one — that is {@link #onStartDestroy}'s job, driven by the player's own
	 * click. When the player is not mining anything, this module is not doing anything.
	 */
	private void tickPacket(LocalPlayer player) {
		if (packetTarget == null) {
			return;
		}
		if (!mc().options.keyAttack.isDown() || !targetAllowed(player, packetTarget)) {
			// Letting go of the button is a cancelled break, not a finished one.
			abort();
			return;
		}
		if (!MiningActionCoordinator.owns(this)) {
			abort();
			return;
		}
		if (autoTool.get()) {
			UnluckyClient.INSTANCE.modules.get(AutoTool.class).onDestroy(packetTarget);
		}
		if (abortOnSlotChange.get() && MiningTracker.toolChanged()) {
			abort();
			return;
		}
		aim(packetTarget);
		swing(player);

		float perTick = MiningTracker.tickProgress(player, mc().level, packetTarget);
		predicted += perTick;
		boolean instant = instantMine.get() && perTick >= 1.0f;
		if (!stopSent && (instant || predicted >= finishThreshold.get())) {
			// Exactly one STOP per START. The flag guarantees it, not the threshold — predicted
			// progress keeps climbing after it crosses.
			stopSent = MiningActionCoordinator.packetStop(this);
			if (stopSent && clientRemove.get()) {
				removeClientSide(packetTarget);
			}
		}
		if (stopSent && mc().level.getBlockState(packetTarget).isAir()) {
			// The server agreed. Close the session so the next click starts a fresh one.
			MiningActionCoordinator.release(this);
			clearSession();
		}
	}

	private void clearSession() {
		packetTarget = null;
		packetFace = null;
		predicted = 0.0f;
		stopSent = false;
	}

	/**
	 * Hides the block locally once the finish message is on the wire.
	 *
	 * <p>Cosmetic and reversible by design: the server's next block update is authoritative, so
	 * a break the server refuses simply reappears. That is the honest behaviour — a client-side
	 * removal that stuck would be showing the player a world that does not exist.
	 */
	private void removeClientSide(BlockPos pos) {
		BlockState state = mc().level.getBlockState(pos);
		if (!state.isAir()) {
			mc().level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 0);
		}
	}

	// ---- shared ------------------------------------------------------------

	/** Filter, range, and nothing else — the two reasons a target is refused. */
	private boolean targetAllowed(LocalPlayer player, BlockPos pos) {
		BlockState state = mc().level.getBlockState(pos);
		if (state.isAir()) {
			return false;
		}
		double reach = range.get();
		if (Vec3.atCenterOf(pos).distanceToSqr(player.getEyePosition()) > reach * reach) {
			return false;
		}
		Block block = state.getBlock();
		return switch (filter.get()) {
			case "Whitelist" -> blocks.contains(block);
			case "Blacklist" -> !blocks.contains(block);
			default -> true;
		};
	}

	private void aim(BlockPos pos) {
		if (rotation.is("Off")) {
			return;
		}
		RotationManager.lookAt(Vec3.atCenterOf(pos));
		MiningTracker.setRotationRequested(true);
	}

	private void swing(LocalPlayer player) {
		switch (swing.get()) {
			case "Client" -> player.swing(InteractionHand.MAIN_HAND);
			case "Packet" -> player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
			default -> { }
		}
	}

	/** The face pointing back at the player, so the break is aimed at a side they can see. */
	private static Direction faceToward(LocalPlayer player, BlockPos pos) {
		Vec3 delta = player.getEyePosition().subtract(Vec3.atCenterOf(pos));
		return Direction.getApproximateNearest(delta.x, delta.y, delta.z);
	}

	/**
	 * Whether the module is currently doing something a server can see.
	 *
	 * <p>Vanilla mode is a local progress figure and nothing else, so it is invisible; Packet
	 * mode is messages on the wire. Declaring the module SERVER_OBSERVABLE overall is the
	 * honest classification, since the mode is a setting rather than a separate module.
	 */
	public boolean hasOpenPacketTarget() {
		return packetTarget != null;
	}
}

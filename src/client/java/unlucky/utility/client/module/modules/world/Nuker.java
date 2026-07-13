package unlucky.utility.client.module.modules.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.mixin.MultiPlayerGameModeAccessor;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.player.AutoEat;
import unlucky.utility.client.settings.BlockListSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.RotationManager;

/**
 * Breaks every matching block around you — the flagship interact module.
 *
 * <p><b>Breaking must round-trip the server</b>, or the block only vanishes on the
 * client and reappears on relog. So each block is broken with a
 * {@code START_DESTROY_BLOCK} + {@code STOP_DESTROY_BLOCK} action pair in the same
 * tick ("packet mine"), routed through vanilla's prediction for a valid sequence
 * number — the server sees a full mine and removes the block for real. We also send
 * a silent server-side rotation toward each block first (camera-free, like Aura),
 * since a server rejects a break you aren't facing. Learned from MeteorClient's
 * Nuker/BlockUtils. Yields to {@link AutoEat} via the Phase-4 {@code busy()} hook.
 */
public class Nuker extends Module {
	public final ModeSetting shape = add(new ModeSetting("Shape", "Region breaks fill", "Sphere", "Sphere", "Cube"));
	public final NumberSetting range = add(new NumberSetting("Range", "Break radius in blocks", 4, 1, 6, 1));
	public final ModeSetting mode = add(new ModeSetting("Mode", "How the block list is used", "All", "All", "Whitelist", "Blacklist"));
	public final BlockListSetting blocks = add(new BlockListSetting("Blocks", "White/blacklisted blocks — right-click to pick", Set.of()));
	public final BooleanSetting flatten = add(new BooleanSetting("Flatten", "Only break at or above your feet (don't dig down)", false));
	public final BooleanSetting smash = add(new BooleanSetting("Smash", "Only break blocks you could shatter in one hit", false));
	public final ModeSetting sort = add(new ModeSetting("Sort", "Which blocks go first", "Closest", "Closest", "Furthest", "Top-down"));
	public final NumberSetting blocksPerTick = add(new NumberSetting("Blocks per tick", "How many blocks to break each tick", 1, 1, 8, 1));
	public final NumberSetting breakDelay = add(new NumberSetting("Break delay", "Ticks to wait between bursts", 0, 0, 10, 1));
	public final BooleanSetting avoidLiquids = add(new BooleanSetting("Avoid liquids", "Skip blocks touching fluids (anti-flood)", true));
	public final ModeSetting swing = add(new ModeSetting("Swing", "Hand swing on break", "Client", "Client", "Packet", "None"));
	public final BooleanSetting pauseEat = add(new BooleanSetting("Pause while eating", "Yield to AutoEat", true));

	private int delayTicks;

	public Nuker() {
		super("Nuker", "Breaks all matching blocks around you", Category.WORLD);
	}

	@Override
	protected void onDisable() {
		delayTicks = 0;
	}

	@Override
	public void onTick() {
		LocalPlayer player = mc().player;
		if (player == null || mc().level == null || mc().gameMode == null || mc().gui.screen() != null) {
			return;
		}
		if (pauseEat.get() && AutoEat.busy()) {
			return;
		}
		if (breakDelay.getInt() > 0 && delayTicks++ < breakDelay.getInt()) {
			return;
		}
		delayTicks = 0;

		List<BlockPos> targets = gather(player);
		int broken = 0;
		int cap = Math.max(1, blocksPerTick.getInt());
		for (BlockPos pos : targets) {
			if (broken >= cap) {
				break;
			}
			// face the block server-side first (silent, camera-free) so it isn't rejected
			RotationManager.lookAt(Vec3.atCenterOf(pos));
			packetMine(pos, faceToward(player, pos));
			swing();
			broken++;
		}
	}

	/**
	 * Server-side one-tick break: START then STOP through vanilla's prediction so the
	 * sequence is valid. The block is removed by the server's response, not client
	 * prediction — so if the server won't let you break it, it honestly stays.
	 */
	private void packetMine(BlockPos pos, Direction face) {
		MultiPlayerGameModeAccessor gameMode = (MultiPlayerGameModeAccessor) mc().gameMode;
		ClientLevel level = mc().level;
		gameMode.unlucky$startPrediction(level, seq ->
				new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face, seq));
		gameMode.unlucky$startPrediction(level, seq ->
				new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face, seq));
	}

	/** Collects, filters and sorts the breakable blocks in range. */
	private List<BlockPos> gather(LocalPlayer player) {
		int r = range.getInt();
		BlockPos origin = player.blockPosition();
		Vec3 center = player.position();
		double rangeSq = (double) r * r;
		List<BlockPos> out = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
			if (shape.is("Sphere") && Vec3.atCenterOf(pos).distanceToSqr(center) > rangeSq) {
				continue;
			}
			if (flatten.get() && pos.getY() < origin.getY()) {
				continue;
			}
			BlockState state = mc().level.getBlockState(pos);
			if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
				continue;
			}
			if (!allowed(state)) {
				continue;
			}
			if (destroyProgress(player, pos) <= 0.0f) {
				continue; // unbreakable for us (bedrock, or wrong tool with 0 progress)
			}
			if (smash.get() && destroyProgress(player, pos) < 1.0f) {
				continue;
			}
			if (avoidLiquids.get() && touchesLiquid(pos)) {
				continue;
			}
			out.add(pos.immutable());
		}
		sort(out, center);
		return out;
	}

	private boolean allowed(BlockState state) {
		return switch (mode.get()) {
			case "Whitelist" -> blocks.contains(state.getBlock());
			case "Blacklist" -> !blocks.contains(state.getBlock());
			default -> true;
		};
	}

	private void sort(List<BlockPos> list, Vec3 center) {
		switch (sort.get()) {
			case "Furthest" -> list.sort(Comparator.comparingDouble((BlockPos p) -> Vec3.atCenterOf(p).distanceToSqr(center)).reversed());
			case "Top-down" -> list.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed()
					.thenComparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(center)));
			default -> list.sort(Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(center)));
		}
	}

	private boolean touchesLiquid(BlockPos pos) {
		for (Direction dir : Direction.values()) {
			if (!mc().level.getBlockState(pos.relative(dir)).getFluidState().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private float destroyProgress(LocalPlayer player, BlockPos pos) {
		return mc().level.getBlockState(pos).getDestroyProgress(player, mc().level, pos);
	}

	/** The block face nearest the player's eye, so the hit looks natural to the server. */
	private Direction faceToward(LocalPlayer player, BlockPos pos) {
		Vec3 diff = player.getEyePosition().subtract(Vec3.atCenterOf(pos));
		return Direction.getApproximateNearest(diff.x, diff.y, diff.z);
	}

	private void swing() {
		LocalPlayer player = mc().player;
		switch (swing.get()) {
			case "Client" -> player.swing(InteractionHand.MAIN_HAND);
			case "Packet" -> player.connection.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
			default -> { }
		}
	}
}

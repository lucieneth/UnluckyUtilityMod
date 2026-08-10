package unlucky.utility.client.module.modules.player;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CartographyTableBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.SmithingTableBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.module.modules.combat.Reach;
import unlucky.utility.client.settings.BooleanSetting;

/** Uses an allowed interactable on the view ray behind an ordinary obstruction. */
public class GhostHand extends Module {
	public final BooleanSetting containers = add(new BooleanSetting("Containers", "Target storage blocks", true));
	public final BooleanSetting doors = add(new BooleanSetting("Doors and trapdoors", "Target doors and trapdoors", true));
	public final BooleanSetting controls = add(new BooleanSetting("Buttons and levers", "Target buttons and levers", true));
	public final BooleanSetting workstations = add(new BooleanSetting("Crafting and workstations", "Target crafting and workstation blocks", true));
	public final BooleanSetting beds = add(new BooleanSetting("Beds", "Target beds", true));
	public final BooleanSetting otherBlockEntities = add(new BooleanSetting("Other block entities", "Target block entities outside the named groups", true));
	public final BooleanSetting blockEntitiesOnly = add(new BooleanSetting("Block entities only",
			"Ignore allowed blocks that do not carry a block entity", true));
	public final BooleanSetting onlyObstructed = add(new BooleanSetting("Only when obstructed",
			"Leave an already visible allowed target to vanilla", true));
	public final BooleanSetting ignoreSneaking = add(new BooleanSetting("Ignore while sneaking",
			"Do not redirect use while sneak is held", true));

	public GhostHand() {
		super("GhostHand", "Uses interactable blocks behind an obstruction at legal reach",
				Category.PLAYER, ServerVisibility.SERVER_OBSERVABLE);
	}

	/** Invoked from MinecraftMixin's one ordered right-click handler. */
	public boolean tryUse() {
		if (!isEnabled() || mc().player == null || mc().level == null || mc().gameMode == null
				|| mc().gui.screen() != null || mc().player.isHandsBusy()
				|| (ignoreSneaking.get() && mc().player.isShiftKeyDown())
				|| !(mc().hitResult instanceof BlockHitResult obstruction)
				|| (onlyObstructed.get() && (obstruction.getType() != HitResult.Type.BLOCK
						|| allowed(obstruction.getBlockPos())))) {
			return false;
		}

		double vanilla = mc().player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
		double range = UnluckyClient.INSTANCE.modules.get(InfiniteInteract.class).isEnabled()
				? vanilla : UnluckyClient.INSTANCE.modules.get(Reach.class).blockRange(vanilla);
		Vec3 start = mc().player.getEyePosition();
		Vec3 end = start.add(mc().player.getLookAngle().scale(range));
		double obstructionDistance = onlyObstructed.get()
				? start.distanceTo(obstruction.getLocation()) : -1.0e-4;
		BlockHitResult target = findTarget(start, end, obstructionDistance);
		if (target == null) return false;

		for (InteractionHand hand : InteractionHand.values()) {
			InteractionResult result = mc().gameMode.useItemOn(mc().player, hand, target);
			if (result instanceof InteractionResult.Success success) {
				if (success.swingSource() == InteractionResult.SwingSource.CLIENT) {
					mc().player.swing(hand);
				}
				return true;
			}
			if (result instanceof InteractionResult.Fail) return true;
		}
		return false;
	}

	private BlockHitResult findTarget(Vec3 start, Vec3 end, double obstructionDistance) {
		int minX = (int) Math.floor(Math.min(start.x, end.x)) - 1;
		int minY = (int) Math.floor(Math.min(start.y, end.y)) - 1;
		int minZ = (int) Math.floor(Math.min(start.z, end.z)) - 1;
		int maxX = (int) Math.floor(Math.max(start.x, end.x)) + 1;
		int maxY = (int) Math.floor(Math.max(start.y, end.y)) + 1;
		int maxZ = (int) Math.floor(Math.max(start.z, end.z)) + 1;
		BlockHitResult best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		for (BlockPos cursor : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
			BlockPos pos = cursor.immutable();
			if (!mc().level.hasChunkAt(pos) || !mc().level.getWorldBorder().isWithinBounds(pos)
					|| !allowed(pos)) continue;
			VoxelShape shape = mc().level.getBlockState(pos).getShape(mc().level, pos);
			if (shape.isEmpty()) continue;
			BlockHitResult hit = shape.clip(start, end, pos);
			if (hit == null) continue;
			double distance = start.distanceTo(hit.getLocation());
			if (distance > obstructionDistance + 1.0e-4 && distance < bestDistance) {
				best = hit;
				bestDistance = distance;
			}
		}
		return best;
	}

	private boolean allowed(BlockPos pos) {
		BlockState state = mc().level.getBlockState(pos);
		BlockEntity blockEntity = mc().level.getBlockEntity(pos);
		if (blockEntitiesOnly.get() && blockEntity == null) return false;
		Object block = state.getBlock();
		if (block instanceof DoorBlock || block instanceof TrapDoorBlock) return doors.get();
		if (block instanceof ButtonBlock || block instanceof LeverBlock) return controls.get();
		if (block instanceof BedBlock) return beds.get();
		if (isWorkstation(block)) return workstations.get();
		if (blockEntity instanceof Container || block instanceof EnderChestBlock) return containers.get();
		return blockEntity != null && otherBlockEntities.get();
	}

	private static boolean isWorkstation(Object block) {
		return block instanceof CraftingTableBlock || block instanceof AbstractFurnaceBlock
				|| block instanceof BrewingStandBlock || block instanceof AnvilBlock
				|| block instanceof CartographyTableBlock || block instanceof SmithingTableBlock
				|| block instanceof StonecutterBlock || block instanceof GrindstoneBlock
				|| block instanceof LoomBlock || block instanceof EnchantingTableBlock;
	}
}

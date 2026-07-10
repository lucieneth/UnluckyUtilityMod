package unlucky.utility.client.module.modules.world;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.WorldScan;

/**
 * Highlights buried chests — fully enclosed by solid blocks, the signature of
 * buried treasure. Inspired by Stardust's TreasureESP.
 */
public class TreasureESP extends Module {
	public final NumberSetting range = add(new NumberSetting("Range", "Scan radius", 64, 16, 128, 8));
	public final ColorSetting outline = add(new ColorSetting("Outline", "Box outline color", 0xFF5CD6FF));
	public final ColorSetting fillColor = add(new ColorSetting("Fill color", "Fill color (alpha matters)", 0x335CD6FF));
	public final BooleanSetting label = add(new BooleanSetting("Label", "Floating text above the chest", true));
	public final BooleanSetting notify = add(new BooleanSetting("Notify", "Chat message on discovery", true));
	public final BooleanSetting occlusion = add(new BooleanSetting("Occlusion cull", "Hide boxes hidden behind other ESP boxes (less clutter)", true));

	private final List<BlockPos> cached = new ArrayList<>();
	// same boxes blockBox() would build, kept so occluded()'s identity check works
	private final List<AABB> boxes = new ArrayList<>();
	private final Set<BlockPos> announced = new HashSet<>();
	private Level lastLevel;
	private int ticksUntilScan;

	public TreasureESP() {
		super("TreasureESP", "Finds buried chests", Category.WORLD);
	}

	@Override
	protected void onEnable() {
		cached.clear();
		boxes.clear();
		announced.clear();
		ticksUntilScan = 0;
	}

	@Override
	public void onTick() {
		if (mc().level == null || mc().player == null) {
			return;
		}
		if (mc().level != lastLevel) {
			lastLevel = mc().level;
			cached.clear();
			announced.clear();
		}
		if (--ticksUntilScan <= 0) {
			ticksUntilScan = 20;
			cached.clear();
			boxes.clear();
			for (BlockEntity blockEntity : WorldScan.blockEntitiesAround(range.get())) {
				if (blockEntity instanceof ChestBlockEntity && isBuried(blockEntity.getBlockPos())) {
					BlockPos pos = blockEntity.getBlockPos().immutable();
					cached.add(pos);
					boxes.add(new AABB(pos).deflate(0.02));
					if (notify.get() && announced.add(pos)) {
						ChatUtil.info("§bBuried chest §7at §f" + pos.getX() + " " + pos.getY() + " " + pos.getZ());
					}
				}
			}
		}
		boolean cull = occlusion.get() && boxes.size() > 1;
		Vec3 eye = mc().player.getEyePosition();
		for (int i = 0; i < cached.size(); i++) {
			AABB box = boxes.get(i);
			if (cull && Render3D.occluded(box, eye, boxes)) {
				continue;
			}
			Render3D.box(box, outline.get(), 2.0f, fillColor.get(), true);
			if (label.get()) {
				Render3D.blockLabel("Treasure", cached.get(i), 0xFF5CD6FF, 1.0f);
			}
		}
	}

	private boolean isBuried(BlockPos pos) {
		for (Direction direction : Direction.values()) {
			if (!mc().level.getBlockState(pos.relative(direction)).isSolidRender()) {
				return false;
			}
		}
		return true;
	}
}

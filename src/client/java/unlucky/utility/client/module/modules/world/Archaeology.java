package unlucky.utility.client.module.modules.world;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ChatUtil;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.WorldScan;

/**
 * Highlights suspicious sand and gravel (archaeology dig sites).
 * Inspired by Stardust's Archaeology.
 */
public class Archaeology extends Module {
	public final NumberSetting range = add(new NumberSetting("Range", "Scan radius", 64, 16, 128, 8));
	public final ColorSetting outline = add(new ColorSetting("Outline", "Box outline color", 0xFFE8DC5C));
	public final ColorSetting fillColor = add(new ColorSetting("Fill color", "Fill color (alpha matters)", 0x2EE8DC5C));
	public final BooleanSetting notify = add(new BooleanSetting("Notify", "Chat message on discovery", true));

	private final List<BlockPos> cached = new ArrayList<>();
	private final Set<BlockPos> announced = new HashSet<>();
	private Level lastLevel;
	private int ticksUntilScan;

	public Archaeology() {
		super("Archaeology", "Finds suspicious blocks", Category.WORLD);
	}

	@Override
	protected void onEnable() {
		cached.clear();
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
			for (BlockEntity blockEntity : WorldScan.blockEntitiesAround(range.get())) {
				if (blockEntity instanceof BrushableBlockEntity) {
					BlockPos pos = blockEntity.getBlockPos().immutable();
					cached.add(pos);
					if (notify.get() && announced.add(pos)) {
						ChatUtil.info("§eSuspicious block §7at §f" + pos.getX() + " " + pos.getY() + " " + pos.getZ());
					}
				}
			}
		}
		for (BlockPos pos : cached) {
			Render3D.blockBox(pos, outline.get(), 2.0f, fillColor.get(), true);
		}
	}
}

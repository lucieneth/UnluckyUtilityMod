package unlucky.utility.client.module.modules.world;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ColorSetting;
import unlucky.utility.client.settings.NumberSetting;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render3D;
import unlucky.utility.client.util.WorldScan;

/**
 * Highlights vanity builds: item frames holding maparts (glow silhouette) and
 * banners (boxes). Inspired by Stardust's VanityESP.
 */
public class VanityESP extends Module {
	public final NumberSetting range = add(new NumberSetting("Range", "Scan radius", 64, 16, 128, 8));
	public final BooleanSetting mapFrames = add(new BooleanSetting("Map frames", "Item frames containing maps", true));
	public final ColorSetting mapColor = add(new ColorSetting("Map color", "Mapart frame glow color", 0xFF5CD6FF));
	public final BooleanSetting banners = add(new BooleanSetting("Banners", "Banner blocks", true));
	public final ColorSetting bannerColor = add(new ColorSetting("Banner color", "Banner box color", 0xFFB65CFF));
	public final BooleanSetting fill = add(new BooleanSetting("Fill", "Translucent banner fill", true));
	public final NumberSetting fillOpacity = add(new NumberSetting("Fill opacity", "Alpha of the fill", 45, 10, 160, 5));
	public final BooleanSetting occlusion = add(new BooleanSetting("Occlusion cull", "Hide boxes hidden behind other ESP boxes (less clutter)", true));

	private final List<AABB> cachedBanners = new ArrayList<>();
	private int ticksUntilScan;

	public VanityESP() {
		super("VanityESP", "Highlights maparts and banners", Category.WORLD);
	}

	/** Glow color for an item frame, or 0. Consulted by EspGlow. */
	public int frameColor(ItemFrame frame) {
		if (!isEnabled() || !mapFrames.get()) {
			return 0;
		}
		if (mc().player == null || frame.distanceTo(mc().player) > range.get()) {
			return 0;
		}
		return frame.getItem().has(DataComponents.MAP_ID) ? mapColor.get() : 0;
	}

	@Override
	protected void onEnable() {
		cachedBanners.clear();
		ticksUntilScan = 0;
	}

	@Override
	public void onTick() {
		if (mc().level == null || mc().player == null || !banners.get()) {
			return;
		}
		if (--ticksUntilScan <= 0) {
			ticksUntilScan = 10;
			cachedBanners.clear();
			for (BlockEntity blockEntity : WorldScan.blockEntitiesAround(range.get())) {
				if (blockEntity instanceof BannerBlockEntity) {
					cachedBanners.add(new AABB(blockEntity.getBlockPos()).deflate(0.03));
				}
			}
		}
		int fillArgb = fill.get() ? ColorUtil.withAlpha(bannerColor.get(), fillOpacity.getInt()) : 0;
		boolean cull = occlusion.get() && cachedBanners.size() > 1;
		Vec3 eye = mc().player.getEyePosition();
		for (AABB box : cachedBanners) {
			if (cull && Render3D.occluded(box, eye, cachedBanners)) {
				continue;
			}
			Render3D.box(box, bannerColor.get(), 2.0f, fillArgb, true);
		}
	}
}

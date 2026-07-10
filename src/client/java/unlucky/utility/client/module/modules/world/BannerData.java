package unlucky.utility.client.module.modules.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.phys.BlockHitResult;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.util.ChatUtil;

/**
 * Right-click a banner to read its base color and pattern layers.
 * Inspired by Stardust's BannerData.
 */
public class BannerData extends Module {
	public final BooleanSetting copy = add(new BooleanSetting("Copy", "Copy the data to your clipboard", false));

	private BlockPos lastBanner;
	private long lastReadTime;

	public BannerData() {
		super("BannerData", "Right-click banners to read their data", Category.WORLD);
	}

	@Override
	public void onTick() {
		if (mc().level == null || mc().player == null || mc().gui.screen() != null) {
			return;
		}
		if (!mc().options.keyUse.isDown()) {
			return;
		}
		if (!(mc().hitResult instanceof BlockHitResult hit)) {
			return;
		}
		if (!(mc().level.getBlockEntity(hit.getBlockPos()) instanceof BannerBlockEntity banner)) {
			return;
		}

		long now = System.currentTimeMillis();
		if (hit.getBlockPos().equals(lastBanner) && now - lastReadTime < 2000) {
			return;
		}
		lastBanner = hit.getBlockPos().immutable();
		lastReadTime = now;

		StringBuilder plain = new StringBuilder("Base: " + banner.getBaseColor().getName());
		ChatUtil.info("§7Banner base: §f" + banner.getBaseColor().getName());
		BannerPatternLayers patterns = banner.getPatterns();
		if (patterns.layers().isEmpty()) {
			ChatUtil.info("§7No pattern layers.");
		} else {
			int index = 1;
			for (BannerPatternLayers.Layer layer : patterns.layers()) {
				String description = layer.description().getString();
				ChatUtil.info("§8" + index + ".§r " + description);
				plain.append(" | ").append(description);
				index++;
			}
		}
		if (copy.get()) {
			mc().keyboardHandler.setClipboard(plain.toString());
			ChatUtil.info("§7Copied to clipboard.");
		}
	}
}

package unlucky.utility.test.module;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;

import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.render.RainbowEnchant;

import static unlucky.utility.test.module.ModuleTestSupport.LOGGER;

/**
 * RainbowEnchant colours the glint and leaves the item under it alone, read off real frames.
 */
public final class RainbowEnchantTest implements ModuleTest {
	@Override
	public List<Class<? extends Module>> covers() {
		return List.of(RainbowEnchant.class);
	}

	@Override
	public void run(ClientGameTestContext context, TestServerContext server) {
		verifyRainbowEnchant(context, server);
	}

	/**
	 * RainbowEnchant tints the glint and only the glint, judged from the pixels of a real frame.
	 *
	 * <p>26.3 draws the glint inside the item's own pass, so a tint on the draw colour — the 26.2
	 * way, which shipped in 2.4 — recolours the whole item and leaves the glint purple. An
	 * enchanted and a plain sword sit side by side in the hotbar and are compared inside one
	 * screenshot, with the glint's scroll frozen (glint speed 0) so nothing moves between frames:
	 * vanilla, the two must differ, or there is no glint to tint; hidden, they must match; tinted
	 * pure green, the enchanted one must gain green without losing red — a tinted item loses
	 * nearly all of it.
	 */
	private void verifyRainbowEnchant(ClientGameTestContext context, TestServerContext server) {
		RainbowEnchant rainbow = context.computeOnClient(mc -> UnluckyClient.INSTANCE.modules.get(RainbowEnchant.class));
		boolean wasEnabled = rainbow.isEnabled();
		String wasMode = rainbow.mode.get();
		int wasColor = rainbow.color.get();
		boolean wasHide = rainbow.hideGlint.get();
		double wasOpacity = rainbow.opacity.get();
		double wasSpeed = context.computeOnClient(mc -> mc.options.glintSpeed().get());
		int wasSlot = context.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		List<String> problems = new ArrayList<>();

		server.runCommand("item replace entity @p hotbar.0 with minecraft:diamond_sword[enchantment_glint_override=true]");
		server.runCommand("item replace entity @p hotbar.1 with minecraft:diamond_sword");
		context.setScreen(() -> null);
		context.runOnClient(mc -> {
			mc.options.glintSpeed().set(0.0);
			mc.player.getInventory().setSelectedSlot(8); // no selection frame over either sword
			rainbow.setEnabledSilently(false);
			rainbow.mode.set("Static");
			rainbow.color.set(0xFF00FF00);
			rainbow.opacity.set(100.0);
			rainbow.hideGlint.set(false);
		});
		context.waitTicks(5);
		try {
			double[][] vanilla = hotbarSwords(context, "rainbow_vanilla");
			context.runOnClient(mc -> {
				rainbow.setEnabledSilently(true);
				rainbow.hideGlint.set(true);
			});
			context.waitTicks(2);
			double[][] hidden = hotbarSwords(context, "rainbow_hidden");
			context.runOnClient(mc -> rainbow.hideGlint.set(false));
			context.waitTicks(2);
			double[][] green = hotbarSwords(context, "rainbow_green");
			LOGGER.info("[rainbow] enchanted/plain RGB — vanilla {} / {}, hidden {} / {}, green {} / {}",
					rgb(vanilla[0]), rgb(vanilla[1]), rgb(hidden[0]), rgb(hidden[1]), rgb(green[0]), rgb(green[1]));

			if (difference(vanilla[0], vanilla[1]) < 3.0) {
				problems.add("the vanilla glint did not show on the enchanted sword, so nothing below means anything");
			}
			if (difference(hidden[0], hidden[1]) > 1.5) {
				problems.add("Hide glint left the enchanted sword looking different from a plain one ("
						+ rgb(hidden[0]) + " vs " + rgb(hidden[1]) + ")");
			}
			if (green[0][0] < green[1][0] - 3.0) {
				problems.add("a green glint took red out of the sword itself — the item is being tinted, not the glint ("
						+ rgb(green[0]) + " vs plain " + rgb(green[1]) + ")");
			}
			if (green[0][1] - green[1][1] < 3.0 || green[0][1] - green[1][1] < 2.0 * (green[0][2] - green[1][2])) {
				problems.add("a green glint did not come out green (" + rgb(green[0]) + " vs plain " + rgb(green[1]) + ")");
			}
		} finally {
			context.runOnClient(mc -> {
				rainbow.mode.set(wasMode);
				rainbow.color.set(wasColor);
				rainbow.hideGlint.set(wasHide);
				rainbow.opacity.set(wasOpacity);
				rainbow.setEnabledSilently(wasEnabled);
				mc.options.glintSpeed().set(wasSpeed);
				mc.player.getInventory().setSelectedSlot(wasSlot);
			});
			server.runCommand("item replace entity @p hotbar.1 with minecraft:air");
			server.runCommand("item replace entity @p hotbar.0 with minecraft:diamond_sword");
		}

		if (!problems.isEmpty()) {
			throw new AssertionError("RainbowEnchant contracts failed: " + String.join("; ", problems));
		}
		LOGGER.info("[rainbow] the glint takes the colour and the sword under it does not");
	}

	/**
	 * Mean RGB over the item square of hotbar slots 0 and 1, from a screenshot of the next frame.
	 * Same geometry as {@code Hud.extractItemHotbar}: slot {@code i} at centre - 90 + 20i + 2,
	 * 19 up from the bottom, sixteen GUI pixels square.
	 */
	private static double[][] hotbarSwords(ClientGameTestContext context, String name) {
		java.nio.file.Path shot = context.takeScreenshot(name);
		int scale = context.computeOnClient(mc -> mc.getWindow().getGuiScale());
		try (java.io.InputStream in = java.nio.file.Files.newInputStream(shot);
				com.mojang.blaze3d.platform.NativeImage image = com.mojang.blaze3d.platform.NativeImage.read(in)) {
			int guiWidth = image.getWidth() / scale;
			int guiHeight = image.getHeight() / scale;
			double[][] means = new double[2][3];
			for (int slot = 0; slot < 2; slot++) {
				int x0 = (guiWidth / 2 - 90 + slot * 20 + 2) * scale;
				int y0 = (guiHeight - 19) * scale;
				int count = 0;
				for (int y = y0; y < y0 + 16 * scale; y++) {
					for (int x = x0; x < x0 + 16 * scale; x++) {
						int argb = image.getPixel(x, y);
						means[slot][0] += argb >> 16 & 0xFF;
						means[slot][1] += argb >> 8 & 0xFF;
						means[slot][2] += argb & 0xFF;
						count++;
					}
				}
				for (int channel = 0; channel < 3; channel++) {
					means[slot][channel] /= count;
				}
			}
			return means;
		} catch (java.io.IOException e) {
			throw new AssertionError("could not read the screenshot " + shot, e);
		}
	}

	private static double difference(double[] a, double[] b) {
		return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
	}

	private static String rgb(double[] mean) {
		return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", mean[0], mean[1], mean[2]);
	}
}

package unlucky.utility.client.module.modules.misc;

import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;

/**
 * Makes item tooltips carry more information — a suite of toggleable previews.
 *
 * <p><b>Container preview</b> — hovering a shulker box (or anything holding the
 * {@code CONTAINER} component) draws its contents as a grid instead of the terse
 * vanilla text list. <b>Ender chest preview</b> does the same from the client's
 * cached ender-chest inventory (last-seen since you opened it this session).
 * <b>Map preview</b> blits a filled map's image; <b>Banner preview</b> renders the
 * banner scaled up; <b>Book preview</b> appends the first written page. <b>Byte
 * size</b> appends the item's encoded network size.
 *
 * <p>Plumbing: {@code ItemStackTooltipMixin} hands the tooltip system a data carrier
 * ({@code ContainerTooltipData}/{@code MapTooltipData}/{@code BannerTooltipData}); the
 * Fabric {@code ClientTooltipComponentCallback} (registered in {@code UnluckyClient})
 * maps each to its renderer. {@code ItemContainerContentsMixin} suppresses the vanilla
 * container text so it doesn't double up with the grid.
 */
public class InventoryInfo extends Module {
	public final BooleanSetting containerPreview = add(new BooleanSetting("Container preview",
			"Show a shulker/container's contents as a grid on hover", true));
	public final ModeSetting previewStyle = add(new ModeSetting("Preview style",
			"Backdrop for the grid — Slot cells or the full GUI panel", "Slot", "Slot", "GUI"));
	public final BooleanSetting enderChestPreview = add(new BooleanSetting("Ender chest preview",
			"Show your last-seen ender chest contents on the ender chest item", true));
	public final BooleanSetting mapPreview = add(new BooleanSetting("Map preview",
			"Show a filled map's image on hover", true));
	public final BooleanSetting bannerPreview = add(new BooleanSetting("Banner preview",
			"Show a scaled-up banner on hover", true));
	public final BooleanSetting bookPreview = add(new BooleanSetting("Book preview",
			"Append the first page of a written book", true));
	public final BooleanSetting byteSize = add(new BooleanSetting("Byte size",
			"Append the item's encoded network size", false));

	public InventoryInfo() {
		super("InventoryInfo", "Richer item tooltips", Category.MISC);
	}

	private static InventoryInfo get() {
		return UnluckyClient.INSTANCE.modules.get(InventoryInfo.class);
	}

	/** True when the container-grid preview should replace the vanilla text list. */
	public static boolean showContainerGrid() {
		return get().isEnabled() && get().containerPreview.get();
	}

	/** True when the grid should use the full GUI panel look instead of slot cells. */
	public static boolean guiPreviewStyle() {
		return get().previewStyle.is("GUI");
	}

	public static boolean showEnderChest() {
		return get().isEnabled() && get().enderChestPreview.get();
	}

	public static boolean showMap() {
		return get().isEnabled() && get().mapPreview.get();
	}

	public static boolean showBanner() {
		return get().isEnabled() && get().bannerPreview.get();
	}

	public static boolean showBook() {
		return get().isEnabled() && get().bookPreview.get();
	}

	/** True when the encoded-size line should be appended to tooltips. */
	public static boolean showByteSize() {
		return get().isEnabled() && get().byteSize.get();
	}
}

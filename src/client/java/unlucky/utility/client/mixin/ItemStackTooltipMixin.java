package unlucky.utility.client.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.saveddata.maps.MapId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import unlucky.utility.client.module.modules.misc.InventoryInfo;
import unlucky.utility.client.util.tooltip.BannerTooltipData;
import unlucky.utility.client.util.tooltip.BookTooltipData;
import unlucky.utility.client.util.tooltip.ContainerTooltipData;
import unlucky.utility.client.util.tooltip.MapTooltipData;

/**
 * InventoryInfo tooltip hooks on {@link ItemStack}:
 * <ul>
 *   <li>{@code getTooltipImage} — hands the tooltip system a preview carrier for
 *       containers ({@code CONTAINER}), the ender chest, maps and banners.</li>
 *   <li>{@code getTooltipLines} — appends the book's first page and the byte-size
 *       line.</li>
 * </ul>
 * The carriers are mapped to renderers by the Fabric {@code ClientTooltipComponentCallback}
 * registered in {@code UnluckyClient}.
 */
@Mixin(ItemStack.class)
public class ItemStackTooltipMixin {
	// Hover caches (Phase 10 Tier 3): vanilla rebuilds tooltip lines AND the tooltip
	// image every frame the cursor rests on an item, so without these a hovered
	// shulker re-copied its contents ~250x a second and the byte-size line re-encoded
	// the stack's full NBT just as often. Keyed by stack identity — the hovered
	// instance is stable while the cursor rests; leaving the slot swaps the instance
	// and refreshes the cache. Client render thread only.
	@org.spongepowered.asm.mixin.Unique
	private static ItemStack unlucky$previewStack;
	@org.spongepowered.asm.mixin.Unique
	private static Optional<TooltipComponent> unlucky$previewResult = Optional.empty();

	@Inject(method = "getTooltipImage", at = @At("RETURN"), cancellable = true)
	private void unlucky$preview(CallbackInfoReturnable<Optional<TooltipComponent>> cir) {
		ItemStack stack = (ItemStack) (Object) this;
		if (stack == unlucky$previewStack) {
			if (unlucky$previewResult.isPresent()) {
				cir.setReturnValue(unlucky$previewResult);
			}
			return;
		}
		unlucky$previewStack = stack;
		unlucky$previewResult = Optional.empty();

		if (InventoryInfo.showContainerGrid()) {
			ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
			if (contents != null) {
				List<ItemStack> items = contents.nonEmptyItemCopyStream().toList();
				if (!items.isEmpty()) {
					unlucky$previewResult = Optional.of(new ContainerTooltipData(items, false));
					cir.setReturnValue(unlucky$previewResult);
					return;
				}
			}
		}
		if (InventoryInfo.showEnderChest() && stack.is(Items.ENDER_CHEST)) {
			List<ItemStack> items = enderChestItems();
			if (!items.isEmpty()) {
				unlucky$previewResult = Optional.of(new ContainerTooltipData(items, true));
				cir.setReturnValue(unlucky$previewResult);
				return;
			}
		}
		if (InventoryInfo.showMap()) {
			MapId mapId = stack.get(DataComponents.MAP_ID);
			if (mapId != null) {
				unlucky$previewResult = Optional.of(new MapTooltipData(mapId));
				cir.setReturnValue(unlucky$previewResult);
				return;
			}
		}
		if (InventoryInfo.showBanner() && stack.getItem() instanceof BannerItem) {
			unlucky$previewResult = Optional.of(new BannerTooltipData(stack));
			cir.setReturnValue(unlucky$previewResult);
			return;
		}
		if (InventoryInfo.showBook()) {
			WrittenBookContent book = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
			if (book != null && !book.pages().isEmpty()) {
				unlucky$previewResult = Optional.of(new BookTooltipData(book.pages().get(0).get(false)));
				cir.setReturnValue(unlucky$previewResult);
			}
		}
	}

	private static List<ItemStack> enderChestItems() {
		List<ItemStack> items = new ArrayList<>();
		if (Minecraft.getInstance().player == null) {
			return items;
		}
		Container ender = Minecraft.getInstance().player.getEnderChestInventory();
		for (int i = 0; i < ender.getContainerSize(); i++) {
			ItemStack s = ender.getItem(i);
			if (!s.isEmpty()) {
				items.add(s);
			}
		}
		return items;
	}

	// byte-size cache: the encode is the expensive part (full NBT serialize), so it
	// runs once per hovered stack instead of once per frame. Count is part of the key
	// because it can change in place (e.g. picking items into the hovered slot).
	@org.spongepowered.asm.mixin.Unique
	private static ItemStack unlucky$sizeStack;
	@org.spongepowered.asm.mixin.Unique
	private static int unlucky$sizeCount;
	@org.spongepowered.asm.mixin.Unique
	private static Component unlucky$sizeLine;

	@Inject(method = "getTooltipLines", at = @At("RETURN"))
	private void unlucky$textLines(CallbackInfoReturnable<List<Component>> cir) {
		List<Component> lines = cir.getReturnValue();
		if (lines == null) {
			return;
		}
		ItemStack stack = (ItemStack) (Object) this;

		if (InventoryInfo.showByteSize()) {
			if (stack != unlucky$sizeStack || stack.getCount() != unlucky$sizeCount) {
				unlucky$sizeStack = stack;
				unlucky$sizeCount = stack.getCount();
				unlucky$sizeLine = null;
				RegistryAccess registries = Minecraft.getInstance().level != null
						? Minecraft.getInstance().level.registryAccess() : null;
				if (registries != null) {
					RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
					try {
						ItemStack.STREAM_CODEC.encode(buf, stack);
						int bytes = buf.readableBytes();
						String size = bytes < 1024 ? bytes + " B" : String.format("%.2f KB", bytes / 1024.0);
						unlucky$sizeLine = Component.literal("= " + size).withStyle(ChatFormatting.DARK_GRAY);
					} finally {
						buf.release();
					}
				}
			}
			if (unlucky$sizeLine != null) {
				lines.add(unlucky$sizeLine);
			}
		}
	}
}

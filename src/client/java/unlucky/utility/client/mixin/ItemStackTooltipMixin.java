package unlucky.utility.client.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
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
	@org.spongepowered.asm.mixin.Unique
	private static int unlucky$previewEnderGen = -1;

	@Inject(method = "getTooltipImage", at = @At("RETURN"), cancellable = true)
	private void unlucky$preview(CallbackInfoReturnable<Optional<TooltipComponent>> cir) {
		ItemStack stack = (ItemStack) (Object) this;
		// ender snapshots invalidate the hover cache: hovering the chest before
		// first opening it must not pin the cached "no preview"
		if (stack == unlucky$previewStack && unlucky$previewEnderGen != InventoryInfo.enderChestGeneration()) {
			unlucky$previewStack = null;
		}
		if (stack == unlucky$previewStack) {
			if (unlucky$previewResult.isPresent()) {
				cir.setReturnValue(unlucky$previewResult);
			}
			return;
		}
		unlucky$previewStack = stack;
		unlucky$previewResult = Optional.empty();
		unlucky$previewEnderGen = InventoryInfo.enderChestGeneration();

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
				return;
			}
		}
		if (unlucky.utility.client.module.modules.render.FoodOverlay.showTooltip()) {
			var food = stack.get(DataComponents.FOOD);
			if (food != null) {
				unlucky$previewResult = Optional.of(new unlucky.utility.client.util.tooltip.FoodTooltipData(
						food.nutrition(), food.saturation(), unlucky$givesHunger(stack)));
				cir.setReturnValue(unlucky$previewResult);
			}
		}
	}

	/** Rotten food = eating applies the Hunger effect (AppleSkin's isRotten). */
	@org.spongepowered.asm.mixin.Unique
	private static boolean unlucky$givesHunger(ItemStack stack) {
		var consumable = stack.get(DataComponents.CONSUMABLE);
		if (consumable == null) {
			return false;
		}
		for (var effect : consumable.onConsumeEffects()) {
			if (effect instanceof net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect apply) {
				for (var instance : apply.effects()) {
					if (instance.getEffect() == net.minecraft.world.effect.MobEffects.HUNGER) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Last-seen contents snapshotted by InventoryInfo while the ender chest
	 * screen was open — the client-side getEnderChestInventory() is a dummy
	 * vanilla never fills, so reading it directly always came back empty.
	 */
	private static List<ItemStack> enderChestItems() {
		List<ItemStack> items = new ArrayList<>();
		for (ItemStack s : InventoryInfo.enderChestItems()) {
			if (!s.isEmpty()) {
				items.add(s);
			}
		}
		return items;
	}

	// Byte-size cache. Container screens request the hovered stack's tooltip on their
	// first rendered frame, and STREAM_CODEC walks every nested data component. A filled
	// shulker or a server-authored item can therefore turn opening an otherwise ordinary
	// chest into a visible render-thread stall. The stack copy is a cheap component-map
	// snapshot; the actual encode runs where packet codecs normally do, off-thread. The
	// request number prevents a late result from a slot the cursor has already left from
	// being shown for the new one. Count is part of the key because it can change in place.
	@org.spongepowered.asm.mixin.Unique
	private static ItemStack unlucky$sizeStack;
	@org.spongepowered.asm.mixin.Unique
	private static int unlucky$sizeCount;
	@org.spongepowered.asm.mixin.Unique
	private static DataComponentPatch unlucky$sizePatch = DataComponentPatch.EMPTY;
	@org.spongepowered.asm.mixin.Unique
	private static Component unlucky$sizeLine;
	@org.spongepowered.asm.mixin.Unique
	private static int unlucky$sizeRequest;
	@org.spongepowered.asm.mixin.Unique
	private static Object unlucky$sizeConnection;
	@org.spongepowered.asm.mixin.Unique
	private static RegistryAccess unlucky$sizeRegistries;
	@org.spongepowered.asm.mixin.Unique
	private static boolean unlucky$sizeEncoding;
	@org.spongepowered.asm.mixin.Unique
	private static ItemStack unlucky$pendingSizeStack;
	@org.spongepowered.asm.mixin.Unique
	private static RegistryAccess unlucky$pendingSizeRegistries;
	@org.spongepowered.asm.mixin.Unique
	private static int unlucky$pendingSizeRequest;

	@Inject(method = "getTooltipLines", at = @At("RETURN"))
	private void unlucky$textLines(CallbackInfoReturnable<List<Component>> cir) {
		List<Component> lines = cir.getReturnValue();
		if (lines == null) {
			return;
		}
		ItemStack stack = (ItemStack) (Object) this;
		unlucky.utility.client.module.modules.render.NBTTooltip nbt =
				unlucky.utility.client.UnluckyClient.INSTANCE.modules.get(
						unlucky.utility.client.module.modules.render.NBTTooltip.class);
		if (nbt.isEnabled()) {
			lines.addAll(nbt.lines(stack));
		}

		if (InventoryInfo.showByteSize()) {
			Minecraft minecraft = Minecraft.getInstance();
			RegistryAccess registries = minecraft.level != null ? minecraft.level.registryAccess() : null;
			DataComponentPatch patch = stack.getComponentsPatch();
			if (stack != unlucky$sizeStack
					|| stack.getCount() != unlucky$sizeCount
					|| !patch.equals(unlucky$sizePatch)
					|| minecraft.getConnection() != unlucky$sizeConnection
					|| registries != unlucky$sizeRegistries) {
				unlucky$sizeStack = stack;
				unlucky$sizeCount = stack.getCount();
				unlucky$sizePatch = patch;
				unlucky$sizeLine = null;
				unlucky$sizeConnection = minecraft.getConnection();
				unlucky$sizeRegistries = registries;
				int request = ++unlucky$sizeRequest;
				if (registries != null) {
					unlucky$queueByteSize(stack.copy(), registries, request);
				}
			}
			if (unlucky$sizeLine != null) {
				lines.add(unlucky$sizeLine);
			}
		}
	}

	/** Keep at most one encode running and one latest request pending. */
	@org.spongepowered.asm.mixin.Unique
	private static void unlucky$queueByteSize(ItemStack stack, RegistryAccess registries, int request) {
		if (unlucky$sizeEncoding) {
			unlucky$pendingSizeStack = stack;
			unlucky$pendingSizeRegistries = registries;
			unlucky$pendingSizeRequest = request;
			return;
		}

		unlucky$sizeEncoding = true;
		CompletableFuture.supplyAsync(() -> unlucky$encodedByteSize(stack, registries))
				.whenComplete((bytes, error) -> Minecraft.getInstance().execute(() -> {
					if (error == null && bytes >= 0 && unlucky$isCurrentSizeRequest(registries, request)) {
						unlucky$sizeLine = Component.literal("= " + unlucky$formatByteSize(bytes))
								.withStyle(ChatFormatting.DARK_GRAY);
					}

					unlucky$sizeEncoding = false;
					ItemStack pendingStack = unlucky$pendingSizeStack;
					RegistryAccess pendingRegistries = unlucky$pendingSizeRegistries;
					int pendingRequest = unlucky$pendingSizeRequest;
					unlucky$pendingSizeStack = null;
					unlucky$pendingSizeRegistries = null;
					if (pendingStack != null
							&& unlucky$isCurrentSizeRequest(pendingRegistries, pendingRequest)) {
						unlucky$queueByteSize(pendingStack, pendingRegistries, pendingRequest);
					}
				}));
	}

	/** Called only on the render thread, including from the worker completion. */
	@org.spongepowered.asm.mixin.Unique
	private static boolean unlucky$isCurrentSizeRequest(RegistryAccess registries, int request) {
		Minecraft minecraft = Minecraft.getInstance();
		return request == unlucky$sizeRequest
				&& registries == unlucky$sizeRegistries
				&& minecraft.getConnection() == unlucky$sizeConnection
				&& minecraft.level != null
				&& minecraft.level.registryAccess() == registries;
	}

	/** Full component serialization, deliberately never called by the render thread. */
	@org.spongepowered.asm.mixin.Unique
	private static int unlucky$encodedByteSize(ItemStack stack, RegistryAccess registries) {
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
		try {
			ItemStack.STREAM_CODEC.encode(buf, stack);
			return buf.readableBytes();
		} catch (RuntimeException ignored) {
			// A tooltip decoration must not turn an exotic server item into a client crash.
			return -1;
		} finally {
			buf.release();
		}
	}

	@org.spongepowered.asm.mixin.Unique
	private static String unlucky$formatByteSize(int bytes) {
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.2f KB", bytes / 1024.0);
		return String.format(java.util.Locale.ROOT, "%.2f MB", bytes / (1024.0 * 1024.0));
	}
}

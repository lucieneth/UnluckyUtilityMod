package unlucky.utility.client.mixin;

import java.util.Optional;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.misc.BookTools;
import unlucky.utility.client.util.ChatUtil;

/** Adds a deobfuscate button to the written book viewer. */
@Mixin(BookViewScreen.class)
public abstract class BookViewScreenMixin extends Screen {
	@Shadow
	private BookViewScreen.BookAccess bookAccess;
	@Shadow
	private int currentPage;

	protected BookViewScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void unlucky$addDeobfuscateButton(CallbackInfo ci) {
		BookTools bookTools = UnluckyClient.INSTANCE.modules.get(BookTools.class);
		if (!bookTools.isEnabled() || !bookTools.deobfuscate.get()) {
			return;
		}
		this.addRenderableWidget(Button.builder(Component.literal("Deobfuscate"), button -> {
			Component pageContent = this.bookAccess.getPage(this.currentPage);
			ChatUtil.info(Component.literal("§7Page " + (this.currentPage + 1) + ":§r ")
					.append(unlucky$stripObfuscation(pageContent)));
		}).bounds(this.width - 104, 4, 100, 16).build());
	}

	@Unique
	private static Component unlucky$stripObfuscation(Component component) {
		MutableComponent result = Component.empty();
		component.visit((style, text) -> {
			result.append(Component.literal(text).withStyle(style.withObfuscated(false)));
			return Optional.empty();
		}, Style.EMPTY);
		return result;
	}
}

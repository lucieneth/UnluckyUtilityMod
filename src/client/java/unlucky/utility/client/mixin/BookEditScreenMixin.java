package unlucky.utility.client.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.misc.BookTools;

/** Adds formatting-code buttons to the writable book editor. */
@Mixin(BookEditScreen.class)
public abstract class BookEditScreenMixin extends Screen {
	@Shadow
	private MultiLineEditBox page;

	@Unique
	private int unlucky$colorIndex = 15;
	@Unique
	private Button unlucky$colorButton;

	protected BookEditScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init", at = @At("TAIL"))
	private void unlucky$addFormatButtons(CallbackInfo ci) {
		BookTools bookTools = UnluckyClient.INSTANCE.modules.get(BookTools.class);
		if (!bookTools.isEnabled() || !bookTools.editorButtons.get()) {
			return;
		}

		int x = 4;
		int y = 28;
		int height = 16;
		String[][] formats = {
				{"§lB", "§l"}, {"§oI", "§o"}, {"§nU", "§n"}, {"§mS", "§m"}, {"§kM§r", "§k"}, {"R", "§r"}
		};
		for (String[] format : formats) {
			String code = format[1];
			this.addRenderableWidget(Button.builder(Component.literal(format[0]), button -> unlucky$insert(code))
					.bounds(x, y, 20, height).build());
			y += height + 2;
		}

		y += 4;
		unlucky$colorButton = this.addRenderableWidget(Button.builder(unlucky$colorLabel(), button -> {
			unlucky$colorIndex = (unlucky$colorIndex + 1) % BookTools.COLOR_CODES.length;
			unlucky$colorButton.setMessage(unlucky$colorLabel());
		}).bounds(x, y, 78, height).build());
		y += height + 2;
		this.addRenderableWidget(Button.builder(Component.literal("Insert color"),
						button -> unlucky$insert("§" + BookTools.COLOR_CODES[unlucky$colorIndex]))
				.bounds(x, y, 78, height).build());
	}

	@Unique
	private void unlucky$insert(String code) {
		((MultiLineEditBoxAccessor) this.page).unlucky$getTextField().insertText(code);
	}

	@Unique
	private Component unlucky$colorLabel() {
		char code = BookTools.COLOR_CODES[unlucky$colorIndex];
		return Component.literal("§" + code + BookTools.COLOR_NAMES[unlucky$colorIndex]);
	}
}

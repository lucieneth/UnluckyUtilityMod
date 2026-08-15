package unlucky.utility.client.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.modules.world.AutoBrew;
import unlucky.utility.client.module.modules.world.Printer;

/**
 * Silent containers: the menu opens, the window doesn't.
 *
 * <p>This works because of the order inside {@code MenuScreens.ScreenConstructor
 * .fromPacket}, which is what handles {@code ClientboundOpenScreen}:
 * <pre>menu = type.create(id, inventory);
 * screen = constructor.create(menu, inventory, title);
 * player.containerMenu = screen.getMenu();   // (1) the menu goes live here
 * mc.gui.setScreen(screen);                  // (2) the window appears here</pre>
 * (1) happens <b>before</b> (2), so dropping (2) leaves a fully working container
 * with nothing on screen — clicks, contents sync and all. The discarded screen was
 * only ever the view.
 *
 * <p>Deliberately narrow: only container screens, and only while a module is mid-cycle
 * on a container <b>it asked for</b>. A chest the player opens themselves still shows —
 * which matters, because opening chests by hand is how AutoBrew is told about them in
 * the first place.
 *
 * <p>Each claimant is asked explicitly rather than through a registry: with two of them
 * an OR is easier to read and impossible to get subtly wrong, and a module that forgets
 * to un-claim would otherwise swallow every container in the game.
 */
@Mixin(Gui.class)
public class GuiMixin {
	@Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
	private void unlucky$silentContainer(Screen screen, CallbackInfo ci) {
		AutoBrew brew = UnluckyClient.INSTANCE.modules.get(AutoBrew.class);
		Printer printer = UnluckyClient.INSTANCE.modules.get(Printer.class);
		if (screen instanceof AbstractContainerScreen<?>
				&& (brew.suppressesScreens() || printer.suppressesScreens())) {
			ci.cancel();
			return;
		}
		// AutoSign answers for its own screen: the sign it was opened for is the one
		// the queued update is addressed to, so the interception and the packet are
		// the same decision and live together in the module.
		if (UnluckyClient.INSTANCE.modules
				.get(unlucky.utility.client.module.modules.world.AutoSign.class).interceptScreen(screen)) {
			ci.cancel();
			return;
		}
		// The other half of silent: vanilla's close path ends in setScreen(null), and it
		// doesn't know the screen it's clearing is *yours*. A module that closes a
		// container every few ticks was shutting chat and the pause menu a tick after you
		// opened them. Only dropped for the instant ContainerUtil is closing its own menu.
		if (screen == null && (brew.suppressesClose() || printer.suppressesClose())) {
			ci.cancel();
		}
	}
}

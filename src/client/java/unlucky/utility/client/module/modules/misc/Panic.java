package unlucky.utility.client.module.modules.misc;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Items;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.BlursBackground;
import unlucky.utility.client.gui.clickgui.ClickGuiScreen;
import unlucky.utility.client.gui.clickgui.FutureClickGuiScreen;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.ActionSetting;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.ModeSetting;
import unlucky.utility.client.util.InventoryActionCoordinator;
import unlucky.utility.client.util.ItemUtil;
import unlucky.utility.client.util.OffhandManager;
import unlucky.utility.client.util.RotationManager;

/**
 * One key that stops the client being interesting.
 *
 * <p>Not a module you turn on — a module you <em>press</em>. There is nothing for an Enabled
 * checkbox to mean here, so there isn't one: the box holds the settings, the bind holds the
 * behaviour, and {@link #onKeyBind()} does the work instead of toggling.
 *
 * <p><b>Minimal is the default, and it is the interesting mode.</b> "All" is easy and mostly
 * wrong: it takes your ESP down with your Aura, and an ESP is not something a server can see.
 * Minimal asks each module what the server can currently see of it — see
 * {@link ServerVisibility} — and disables exactly that set. Which means it covers modules that
 * do not exist yet, without anybody remembering to come back here and add them.
 *
 * <p><b>Order matters and is not obvious.</b> Modules go first, because a module's own
 * shutdown is the only code that knows what it was in the middle of; the shared owners go
 * second, as the backstop for anything a module left claimed; keys go last, because a module
 * ticking one more time could otherwise press one again after we cleared it. The whole
 * sequence runs while the world is still there, which is the point — a cursor stack put back
 * after the menu closes is a cursor stack on the floor.
 */
public class Panic extends Module {
	public final ModeSetting mode = add(new ModeSetting("Mode",
			"Minimal turns off only what the server can currently see and leaves your ESP, chat "
					+ "and HUD alone. All turns off everything that can be turned off.",
			"Minimal", "Minimal", "All"));
	public final BooleanSetting closeScreens = add(new BooleanSetting("Close client screens",
			"Shut the ClickGUI, HUD editor or console on the way out", true));
	public final BooleanSetting clearKeys = add(new BooleanSetting("Clear automated keys",
			"Let go of every key a module might be holding — use, attack, movement. A key you "
					+ "are physically holding stays released until you press it again.", true));
	public final BooleanSetting toast = add(new BooleanSetting("Toast summary",
			"Say what was turned off", true));
	public final ActionSetting now = add(new ActionSetting("Panic now",
			"Run it from here, without a bind", this::fire));

	public Panic() {
		super("Panic", "One key that turns off everything the server can see", Category.MISC,
				ServerVisibility.CLIENT_ONLY);
		setEnabledSilently(true);
	}

	/**
	 * Always on: there is nothing to switch off, and a panic key that only works when you
	 * remembered to arm it is not a panic key.
	 */
	@Override
	public boolean isToggleable() {
		return false;
	}

	@Override
	public void onKeyBind() {
		fire();
	}

	/**
	 * Whether the panic bind should still be honoured with {@code screen} open.
	 *
	 * <p>Client keybinds are normally dropped while any screen has focus, for the obvious
	 * reason that letters typed into a search box must not also toggle modules. Panic is worth
	 * the exception — the ClickGUI is exactly where you are when you notice the problem — and
	 * the exception is safe because both ClickGUI renderers already answer {@code isTyping()}
	 * for InventoryMove's benefit. Anything with a text field of its own (the console, chat)
	 * is left out: there, the same key press is a character.
	 */
	public boolean reachableFrom(Screen screen) {
		if (screen instanceof ClickGuiScreen clickGui) {
			return !clickGui.isTyping();
		}
		if (screen instanceof FutureClickGuiScreen clickGui) {
			return !clickGui.isTyping();
		}
		return false;
	}

	/** Runs a panic at the configured mode. Safe from anywhere, including the title screen. */
	public void fire() {
		boolean all = mode.is("All");
		List<String> stopped = new ArrayList<>();

		// 1. The modules themselves, each given the chance to shut down differently first.
		for (Module module : UnluckyClient.INSTANCE.modules.all()) {
			if (module == this || !module.isEnabled()) {
				continue;
			}
			if (!all && !module.isServerObservableNow()) {
				continue;
			}
			if (module.panic()) {
				stopped.add(module.getName());
			}
		}

		// 2. The shared owners. Modules release their own claims on disable; this is what
		// catches the ones that were mid-action, and the ones that never had an owner to
		// speak of. Rotation first — it is the one still going out on the wire.
		RotationManager.cancel();
		InventoryActionCoordinator.panic();
		OffhandManager.reset();

		// 3. Keys, after every module has stopped ticking, so nothing presses one back down.
		if (clearKeys.get()) {
			releaseAutomatedKeys();
		}

		if (closeScreens.get()) {
			closeClientScreen();
		}
		if (toast.get()) {
			announce(all, stopped);
		}
	}

	/**
	 * Lets go of every key a module is capable of holding.
	 *
	 * <p>Vanilla only rewrites a mapping's state on a key <em>event</em>, so releasing one the
	 * player is physically holding leaves it released until they let go and press it again.
	 * That is a real cost and it is still the right trade: the failure this prevents is
	 * AutoEat's held use key surviving the panic, which is a right-click stuck down for as long
	 * as it takes you to notice.
	 */
	private void releaseAutomatedKeys() {
		Options options = mc().options;
		for (KeyMapping key : new KeyMapping[] {
				options.keyUse, options.keyAttack, options.keyJump, options.keyShift,
				options.keySprint, options.keyUp, options.keyDown, options.keyLeft, options.keyRight }) {
			key.setDown(false);
		}
	}

	/**
	 * Closes an Unlucky screen, and deliberately not a vanilla one.
	 *
	 * <p>{@link BlursBackground} is every screen this client owns — both ClickGUIs, the HUD
	 * editor, Friends, Configs, the console — and nothing else, which makes it a better test
	 * than a list of screen classes that would go stale the next time one is added. Vanilla's
	 * screens are left alone on purpose: panicking with a chest open should not also throw
	 * away whatever you were doing in it.
	 */
	private void closeClientScreen() {
		Minecraft mc = mc();
		if (mc.gui.screen() instanceof BlursBackground) {
			mc.gui.setScreen(null);
		}
	}

	private void announce(boolean all, List<String> stopped) {
		String what = stopped.isEmpty()
				? "nothing was running"
				: stopped.size() + (stopped.size() == 1 ? " module off" : " modules off");
		UnluckyClient.INSTANCE.notifications.add("Panic (" + (all ? "All" : "Minimal") + ")", what,
				ItemUtil.icon(Items.REDSTONE));
	}
}

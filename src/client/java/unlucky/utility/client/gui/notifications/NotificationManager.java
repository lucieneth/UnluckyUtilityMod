package unlucky.utility.client.gui.notifications;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.modules.hud.HudModule;

/** Pushes native, achievement-style Minecraft toasts through the vanilla ToastManager. */
public final class NotificationManager {
	public void onModuleToggle(Module module) {
		HudModule hud = UnluckyClient.INSTANCE.modules.get(HudModule.class);
		if (hud == null || !hud.notifications.get()) {
			return;
		}
		boolean on = module.isEnabled();
		add("Unlucky", module.getName() + " " + (on ? "enabled" : "disabled"),
				new ItemStack(on ? Items.EMERALD : Items.REDSTONE));
	}

	/** Queues a toast with a header line, a title line and a 16px item icon. */
	public void add(String header, String title, ItemStack icon) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui == null) {
			return;
		}
		HudModule hud = UnluckyClient.INSTANCE.modules.get(HudModule.class);
		int nameColor = hud.notificationColor.get() | 0xFF000000; // force opaque so the name is readable
		mc.gui.toastManager().addToast(new UnluckyToast(header, title, icon, nameColor));
		if (hud.notificationSound.get()) {
			mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_IN, 1.0f));
		}
	}
}

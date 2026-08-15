package unlucky.utility.client.module.modules.world;

import java.util.ArrayDeque;
import java.util.Queue;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import unlucky.utility.client.mixin.AbstractSignEditScreenAccessor;
import unlucky.utility.client.module.Category;
import unlucky.utility.client.module.Module;
import unlucky.utility.client.module.ServerVisibility;
import unlucky.utility.client.settings.BooleanSetting;
import unlucky.utility.client.settings.NumberSetting;

/**
 * Writes every sign you place with the text of the last one you wrote by hand.
 *
 * <p>The first sign is the template: write it normally and this remembers the
 * four lines from the update packet on its way out. After that the edit screen
 * never opens — it is cancelled the moment vanilla tries to show it, and the
 * same four lines are sent for the block it was opened for.
 *
 * <p>The delay is not politeness, it is the module working at all on the servers
 * it is for. 2b2t rejects a sign update that arrives too close behind the swing
 * and block-click packets that created the sign, so updates go through a queue
 * and one leaves per interval. Sleeping in the handler would do the same thing
 * to one sign and freeze the client for a wall of them.
 *
 * <p>Reference: Meteor's AutoSign.
 */
public class AutoSign extends Module {
	public final NumberSetting delay = add(new NumberSetting("Delay",
			"Ticks between queued sign updates", 10, 0, 100, 1));
	public final BooleanSetting keepText = add(new BooleanSetting("Keep text",
			"Remember the template between sessions of the module being on", false));

	/** The template, or null until you have written one sign by hand. */
	private String[] text;
	private final Queue<ServerboundSignUpdatePacket> queue = new ArrayDeque<>();
	private int timer;

	public AutoSign() {
		super("AutoSign", "Writes placed signs with your last sign's text", Category.WORLD,
				ServerVisibility.SERVER_OBSERVABLE);
	}

	@Override
	protected void onDisable() {
		queue.clear();
		timer = 0;
		if (!keepText.get()) {
			text = null;
		}
	}

	@Override
	public void onTick() {
		// An empty queue keeps the timer at zero, so the next packet to arrive waits
		// the full interval behind the click that produced it rather than a stale
		// remainder of one.
		if (mc().player == null || queue.isEmpty()) {
			timer = 0;
			return;
		}
		if (timer < delay.getInt()) {
			timer++;
			return;
		}
		mc().player.connection.send(queue.poll());
		timer = 0;
	}

	/**
	 * Called from the outgoing-packet hook for every sign update, including the
	 * ones this module queued — which is harmless: re-recording our own template
	 * stores exactly what was already there.
	 */
	public void captureTemplate(ServerboundSignUpdatePacket packet) {
		if (isEnabled()) {
			text = packet.getLines().clone();
		}
	}

	/**
	 * GuiMixin's sign-screen branch.
	 *
	 * @return whether the screen was swallowed and must not be shown
	 */
	public boolean interceptScreen(Screen screen) {
		if (!isEnabled() || text == null || !(screen instanceof AbstractSignEditScreen)) {
			return false;
		}
		SignBlockEntity sign = ((AbstractSignEditScreenAccessor) screen).unlucky$sign();
		if (sign == null) {
			return false;
		}
		// Front text: a freshly placed sign always opens on its front, and the
		// screen's own side flag is private. Editing the back stays manual.
		queue.add(new ServerboundSignUpdatePacket(sign.getBlockPos(), true,
				text[0], text[1], text[2], text[3]));
		return true;
	}
}

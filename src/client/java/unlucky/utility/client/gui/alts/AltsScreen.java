package unlucky.utility.client.gui.alts;

import java.util.UUID;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import unlucky.utility.client.util.Render2D;
import unlucky.utility.client.util.alts.AccountSwitcher;
import unlucky.utility.client.util.alts.AltAccount;
import unlucky.utility.client.util.alts.AltManager;
import unlucky.utility.client.util.alts.MicrosoftAuth;

/**
 * The alt manager (opened from the title-screen alt panel). Lists saved
 * accounts — click one to switch the live session to it (no restart) — with
 * add-Microsoft (device-code sign-in), add-offline (username only), and remove.
 * Classic-Minecraft styling to match the skin changer.
 *
 * <p>Shows the sensitive-file warning: {@code alts.json} stores Microsoft
 * tokens that grant account access.
 */
public class AltsScreen extends Screen {
	private static final int GREEN = 0xFF55FF55;
	private static final int RED = 0xFFFF5555;
	private static final int YELLOW = 0xFFE0C020;
	private static final int AQUA = 0xFF55FFFF;
	private static final int GRAY = 0xFFA0A0A0;

	private final Screen parent;
	private EditBox offlineName;
	private String status = "";
	private int statusColor = GRAY;
	private UUID selected;

	public AltsScreen(Screen parent) {
		super(Component.literal("Alt Manager"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		selected = AccountSwitcher.activeUuid();
		int cx = width / 2;

		// account rows — click to switch, ❌ to remove
		int y = 44;
		for (AltAccount account : AltManager.accounts()) {
			boolean active = account.uuid().equals(selected);
			String tag = account.isMicrosoft() ? "" : " (offline)";
			Component label = Component.literal((active ? "✔ " : "") + account.name() + tag)
					.withStyle(active ? ChatFormatting.GREEN : ChatFormatting.WHITE);
			addRenderableWidget(Button.builder(label, b -> switchTo(account))
					.bounds(cx - 130, y, 200, 20).build());
			addRenderableWidget(Button.builder(Component.literal("❌"), b -> remove(account.uuid()))
					.bounds(cx + 74, y, 20, 20).build());
			y += 22;
		}

		// add-offline row
		int addY = Math.min(y + 8, height - 96);
		offlineName = new EditBox(font, cx - 130, addY, 150, 20, Component.literal("Offline name"));
		offlineName.setHint(Component.literal("Offline username").withStyle(ChatFormatting.DARK_GRAY));
		offlineName.setMaxLength(16);
		addRenderableWidget(offlineName);
		addRenderableWidget(Button.builder(Component.literal("Add offline"), b -> addOffline())
				.bounds(cx + 24, addY, 70, 20).build());

		addRenderableWidget(Button.builder(Component.literal("Add Microsoft account"), b -> addMicrosoft())
				.bounds(cx - 130, addY + 24, 224, 20).build());

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
				.bounds(cx - 130, height - 32, 224, 20).build());
	}

	private void switchTo(AltAccount account) {
		if (!AccountSwitcher.canSwitch()) {
			setStatus("Leave the server before switching accounts.", RED);
			return;
		}
		if (account.isMicrosoft() && account.refreshToken() != null) {
			// live token may have expired — silently re-auth from the refresh token
			setStatus("Signing in …", YELLOW);
			MicrosoftAuth.refresh(account, refreshed -> {
				AltManager.add(refreshed);
				AccountSwitcher.switchTo(refreshed);
				rebuild();
			}, err -> setStatus(err, RED));
			return;
		}
		AccountSwitcher.switchTo(account);
		rebuild();
	}

	private void addOffline() {
		String name = offlineName.getValue().trim();
		if (name.length() < 3) {
			setStatus("Enter a name of at least 3 characters.", RED);
			return;
		}
		AltManager.add(AltAccount.offline(name));
		offlineName.setValue("");
		rebuild();
	}

	private void addMicrosoft() {
		setStatus("Opening your browser …", YELLOW);
		MicrosoftAuth.addAccount(
				message -> setStatus(message, AQUA),
				account -> {
					AltManager.add(account);
					setStatus("Added " + account.name() + "!", GREEN);
					rebuild();
				},
				err -> setStatus(err, RED));
	}

	private void remove(UUID uuid) {
		AltManager.remove(uuid);
		rebuild();
	}

	private void setStatus(String text, int color) {
		this.status = text;
		this.statusColor = color;
	}

	private void rebuild() {
		String keepStatus = status;
		int keepColor = statusColor;
		clearWidgets();
		init();
		status = keepStatus;
		statusColor = keepColor;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		String t = title.getString();
		Render2D.text(g, t, (width - Render2D.width(t)) / 2, 16, 0xFFFFFFFF);
		if (AltManager.accounts().isEmpty() && status.isEmpty()) {
			setStatus("No alts yet — add one below.", GRAY);
		}
		if (!status.isEmpty()) {
			Render2D.textNoShadow(g, status, (width - Render2D.width(status)) / 2, height - 52, statusColor);
		}
		String warn = "alts.json stores account tokens — keep it private";
		Render2D.textNoShadow(g, warn, (width - Render2D.width(warn)) / 2, height - 44, 0xFF808080);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}

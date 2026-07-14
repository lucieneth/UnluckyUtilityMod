package unlucky.utility.client.gui.friends;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.gui.clickgui.ClickGuiToolbar;
import unlucky.utility.client.module.modules.client.ThemeModule;
import unlucky.utility.client.ui.TextBox;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.FriendManager;
import unlucky.utility.client.util.MojangLookup;
import unlucky.utility.client.util.Render2D;

/**
 * The Friends panel behind the toolbar's Friends button: type a name to add
 * (resolved from the current tablist, falling back to Mojang's profile API for
 * offline players), and a scrollable list of friends with per-row remove.
 * Middle-clicking players in-game remains the quick path; this is management.
 */
public class FriendsScreen extends Screen {
	private static final int W = 280;
	private static final int FIELD_H = 16;
	private static final int ROW_H = 24;
	private static final int PAD = 8;

	private static final TextBox NAME = new TextBox();
	private static int scroll;

	private int windowX;
	private int windowY;
	private int windowH;
	/** Feedback from the last add attempt ("Player not found: x"), dim when ok. */
	private String status = "";
	private int statusColor = Theme.textDim;
	private boolean draggingName;
	private boolean lookupBusy;

	/** Menu we return to on close — null in-game, the title screen when opened from it. */
	private final Screen parent;

	public FriendsScreen() {
		this(null);
	}

	public FriendsScreen(Screen parent) {
		super(Component.literal("Friends"));
		this.parent = parent;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	protected void init() {
		windowH = Math.min(240, height - 60);
		windowX = (width - W) / 2;
		windowY = (height - windowH) / 2;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		if (UnluckyClient.INSTANCE.modules.get(ThemeModule.class).blur.get()) {
			g.blurBeforeThisStratum();
		}
		g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0x50 << 24);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// skeet frame matching the ClickGUI window
		Render2D.rect(g, windowX - 2, windowY - 2, W + 4, windowH + 4, Theme.frame);
		Render2D.rect(g, windowX, windowY, W, windowH, Theme.window);
		g.outline(windowX, windowY, W, windowH, Theme.frameBevel);

		int x = windowX + PAD;
		int y = windowY + PAD;
		Render2D.text(g, "Friends", x, y, Theme.text);
		String count = FriendManager.all().size() + " added";
		Render2D.textNoShadow(g, count, windowX + W - PAD - Render2D.width(count), y, Theme.textDim);
		y += 14;

		// input field + Add button
		int addW = 34;
		int fieldW = W - 2 * PAD - addW - 4;
		Render2D.rect(g, x - 1, y - 1, fieldW + 2, FIELD_H + 2, Theme.borderDark);
		Render2D.rect(g, x, y, fieldW, FIELD_H, Theme.surface);
		NAME.render(g, x + 4, y + 4, fieldW - 8, true, "Player name...");
		boolean addHover = Render2D.hovered(mouseX, mouseY, addX(), y, addW, FIELD_H);
		Render2D.rect(g, addX() - 1, y - 1, addW + 2, FIELD_H + 2, Theme.borderDark);
		Render2D.rect(g, addX(), y, addW, FIELD_H, addHover ? Theme.surface : Theme.sidebar);
		String addLabel = lookupBusy ? "..." : "Add";
		Render2D.textNoShadow(g, addLabel, addX() + (addW - Render2D.width(addLabel)) / 2, y + 4,
				addHover ? Theme.text : Theme.textDim);
		y += FIELD_H + 4;

		if (!status.isEmpty()) {
			Render2D.textNoShadow(g, status, x, y, statusColor);
		}
		y += 11;

		// friend list
		int listTop = y;
		int listBottom = windowY + windowH - PAD;
		List<Map.Entry<UUID, String>> friends = new ArrayList<>(FriendManager.all().entrySet());
		int contentH = friends.size() * ROW_H;
		scroll = Math.clamp(scroll, 0, Math.max(0, contentH - (listBottom - listTop)));
		g.enableScissor(windowX, listTop, windowX + W, listBottom);
		if (friends.isEmpty()) {
			Render2D.textNoShadow(g, "No friends yet — middle-click a player", x, listTop + 4, Theme.textDim);
		}
		int rowY = listTop - scroll;
		for (Map.Entry<UUID, String> entry : friends) {
			if (rowY + ROW_H >= listTop && rowY <= listBottom) {
				boolean rowHover = Render2D.hovered(mouseX, mouseY, x, rowY, W - 2 * PAD, ROW_H);
				if (rowHover) {
					Render2D.rect(g, x, rowY, W - 2 * PAD, ROW_H, Theme.surface);
				}
				// chibi sprite icon (async-composed); flat face until it cooks
				var sprite = unlucky.utility.client.util.PlayerSprite.get(entry.getKey());
				if (sprite != null) {
					unlucky.utility.client.util.PlayerSprite.draw(g, sprite, x + 2, rowY + 2, ROW_H - 4);
				} else {
					unlucky.utility.client.util.HeadRenderer.draw(g, entry.getKey(), x + 4, rowY + 6, 12);
				}
				int textX = x + 2 + (ROW_H - 4) * unlucky.utility.client.util.PlayerSprite.WIDTH
						/ unlucky.utility.client.util.PlayerSprite.HEIGHT + 5;
				Render2D.textNoShadow(g, FriendManager.DOT + " ", textX, rowY + 8, FriendManager.COLOR);
				Render2D.textNoShadow(g, entry.getValue(), textX + Render2D.width(FriendManager.DOT + " "),
						rowY + 8, Theme.text);
				boolean removeHover = Render2D.hovered(mouseX, mouseY, removeX(), rowY, 12, ROW_H);
				Render2D.textNoShadow(g, "x", removeX() + 3, rowY + 8,
						removeHover ? 0xFFFF5555 : (rowHover ? Theme.textDim : ColorUtil.withAlpha(Theme.textDim, 90)));
			}
			rowY += ROW_H;
		}
		g.disableScissor();

		// scrollbar
		int viewH = listBottom - listTop;
		if (contentH > viewH) {
			int barH = Math.max(viewH * viewH / contentH, 10);
			int barY = listTop + (viewH - barH) * scroll / Math.max(1, contentH - viewH);
			Render2D.rect(g, windowX + W - 4, listTop, 2, viewH, Theme.surface);
			Render2D.verticalGradient(g, windowX + W - 4, barY, 2, barH, Theme.accent1, Theme.accent2);
		}

		String toolbarLabel = ClickGuiToolbar.draw(g, mouseX, mouseY, width, ClickGuiToolbar.FRIENDS);
		if (toolbarLabel != null) {
			ClickGuiToolbar.tooltip(g, toolbarLabel, mouseX, mouseY);
		}
	}

	private int addX() {
		return windowX + W - PAD - 34;
	}

	private int removeX() {
		return windowX + W - PAD - 14;
	}

	private int fieldX() {
		return windowX + PAD;
	}

	private int fieldY() {
		return windowY + PAD + 14;
	}

	private int listTop() {
		return fieldY() + FIELD_H + 4 + 11;
	}

	private void submit() {
		String name = NAME.text().trim();
		if (name.isEmpty() || lookupBusy) {
			return;
		}
		lookupBusy = true;
		status = "Looking up " + name + "...";
		statusColor = Theme.textDim;
		MojangLookup.resolve(name,
				(uuid, realName) -> {
					lookupBusy = false;
					boolean added = FriendManager.add(uuid, realName);
					status = realName + (added ? " added" : " already added (name refreshed)");
					statusColor = added ? Theme.accent1 : Theme.textDim;
					NAME.clear();
				},
				message -> {
					lookupBusy = false;
					status = message;
					statusColor = 0xFFFF5555;
				});
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		int toolbarButton = ClickGuiToolbar.buttonAt(mx, my, width);
		if (toolbarButton >= 0) {
			if (toolbarButton != ClickGuiToolbar.FRIENDS) {
				ClickGuiToolbar.activate(toolbarButton, parent);
			}
			return true;
		}
		int fieldW = W - 2 * PAD - 34 - 4;
		if (event.button() == 0 && Render2D.hovered(mx, my, fieldX(), fieldY(), fieldW, FIELD_H)) {
			NAME.click(mx - (fieldX() + 4));
			draggingName = true;
			return true;
		}
		if (event.button() == 0 && Render2D.hovered(mx, my, addX(), fieldY(), 34, FIELD_H)) {
			submit();
			return true;
		}
		// remove buttons in the list
		if (event.button() == 0) {
			int listTop = listTop();
			int listBottom = windowY + windowH - PAD;
			int rowY = listTop - scroll;
			for (Map.Entry<UUID, String> entry : new ArrayList<>(FriendManager.all().entrySet())) {
				if (my >= Math.max(rowY, listTop) && my < Math.min(rowY + ROW_H, listBottom)
						&& Render2D.hovered(mx, my, removeX(), rowY, 12, ROW_H)) {
					FriendManager.remove(entry.getKey());
					status = entry.getValue() + " removed";
					statusColor = Theme.textDim;
					return true;
				}
				rowY += ROW_H;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (draggingName) {
			NAME.drag(event.x() - (fieldX() + 4));
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		draggingName = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scroll -= (int) (scrollY * 15);
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (NAME.charTyped(event)) {
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
			submit();
			return true;
		}
		if (NAME.keyPressed(event)) {
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}
}

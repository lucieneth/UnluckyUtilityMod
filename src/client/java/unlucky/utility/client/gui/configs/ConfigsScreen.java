package unlucky.utility.client.gui.configs;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import unlucky.utility.client.UnluckyClient;
import unlucky.utility.client.config.ConfigManager;
import unlucky.utility.client.gui.clickgui.ClickGuiToolbar;
import unlucky.utility.client.module.modules.client.ThemeModule;
import unlucky.utility.client.ui.TextBox;
import unlucky.utility.client.ui.Theme;
import unlucky.utility.client.util.ColorUtil;
import unlucky.utility.client.util.Render2D;

/**
 * The Configs panel behind the toolbar's Configs button (was the "(soon)"
 * placeholder). Named profiles live as plain JSON in
 * {@code config/unlucky/configs/} — one file per profile, the active config
 * stays {@code config.json} beside them, so a profile is just "a config that
 * isn't loaded right now" and sharing one is sending a file.
 *
 * <p>Save snapshots the live settings under the typed name; Load applies a
 * profile <i>and</i> makes it the active config (a load that vanished on
 * restart would read as failed); Import/Export are native file dialogs
 * (tinyfd, same as the skin picker — run off-thread because they block);
 * Open folder is for everything else, like dropping in a friend's file.
 *
 * <p>Layout, scroll and input handling follow {@link
 * unlucky.utility.client.gui.friends.FriendsScreen} — same window, same rows.
 */
public class ConfigsScreen extends Screen {
	private static final int W = 280;
	private static final int FIELD_H = 16;
	private static final int ROW_H = 24;
	private static final int PAD = 8;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d.M. HH:mm");

	private static final TextBox NAME = new TextBox();
	private static int scroll;

	private int windowX;
	private int windowY;
	private int windowH;
	private String status = "";
	private int statusColor = Theme.textDim;
	private boolean draggingName;
	/** Cached per frame-batch; refreshed after every mutation instead of every draw. */
	private List<Path> profiles = List.of();

	private final Screen parent;

	public ConfigsScreen() {
		this(null);
	}

	public ConfigsScreen(Screen parent) {
		super(Component.literal("Configs"));
		this.parent = parent;
	}

	private ConfigManager config() {
		return UnluckyClient.INSTANCE.config;
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
		profiles = config().listProfiles();
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
		Render2D.rect(g, windowX - 2, windowY - 2, W + 4, windowH + 4, Theme.frame);
		Render2D.rect(g, windowX, windowY, W, windowH, Theme.window);
		g.outline(windowX, windowY, W, windowH, Theme.frameBevel);

		int x = windowX + PAD;
		int y = windowY + PAD;
		Render2D.text(g, "Configs", x, y, Theme.text);
		String count = profiles.size() + " saved";
		Render2D.textNoShadow(g, count, windowX + W - PAD - Render2D.width(count), y, Theme.textDim);
		y += 14;

		// name field + Save
		int saveW = 34;
		int fieldW = W - 2 * PAD - saveW - 4;
		Render2D.rect(g, x - 1, y - 1, fieldW + 2, FIELD_H + 2, Theme.borderDark);
		Render2D.rect(g, x, y, fieldW, FIELD_H, Theme.surface);
		NAME.render(g, x + 4, y + 4, fieldW - 8, true, "Config name...");
		boolean saveHover = Render2D.hovered(mouseX, mouseY, saveX(), y, saveW, FIELD_H);
		Render2D.rect(g, saveX() - 1, y - 1, saveW + 2, FIELD_H + 2, Theme.borderDark);
		Render2D.rect(g, saveX(), y, saveW, FIELD_H, saveHover ? Theme.surface : Theme.sidebar);
		Render2D.textNoShadow(g, "Save", saveX() + (saveW - Render2D.width("Save")) / 2, y + 4,
				saveHover ? Theme.text : Theme.textDim);
		y += FIELD_H + 4;

		if (!status.isEmpty()) {
			boolean error = status.startsWith("§c");
			Render2D.textNoShadow(g, error ? status.substring(2) : status, x, y,
					error ? 0xFFFF5555 : statusColor);
		}
		y += 11;

		// profile list
		int listTop = y;
		int listBottom = windowY + windowH - PAD - FIELD_H - 6;
		int contentH = profiles.size() * ROW_H;
		scroll = Math.clamp(scroll, 0, Math.max(0, contentH - (listBottom - listTop)));
		g.enableScissor(windowX, listTop, windowX + W, listBottom);
		if (profiles.isEmpty()) {
			Render2D.textNoShadow(g, "No configs yet — name one and hit Save", x, listTop + 4, Theme.textDim);
		}
		int rowY = listTop - scroll;
		for (Path profile : profiles) {
			if (rowY + ROW_H >= listTop && rowY <= listBottom) {
				boolean rowHover = Render2D.hovered(mouseX, mouseY, x, rowY, W - 2 * PAD, ROW_H);
				if (rowHover) {
					Render2D.rect(g, x, rowY, W - 2 * PAD, ROW_H, Theme.surface);
				}
				Render2D.textNoShadow(g, ConfigManager.profileName(profile), x + 4, rowY + 4, Theme.text);
				Render2D.textNoShadow(g, modified(profile), x + 4, rowY + 13, Theme.textDim);

				boolean loadHover = Render2D.hovered(mouseX, mouseY, loadX(), rowY + 4, 30, FIELD_H);
				Render2D.rect(g, loadX(), rowY + 4, 30, FIELD_H, loadHover ? Theme.surface : Theme.sidebar);
				Render2D.textNoShadow(g, "Load", loadX() + (30 - Render2D.width("Load")) / 2, rowY + 8,
						loadHover ? Theme.accent1 : Theme.textDim);

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

		// bottom action row: Open folder / Import / Export
		int by = bottomY();
		String[] labels = {"Open folder", "Import", "Export"};
		int bx = x;
		for (String label : labels) {
			int bw = buttonW();
			boolean hover = Render2D.hovered(mouseX, mouseY, bx, by, bw, FIELD_H);
			Render2D.rect(g, bx - 1, by - 1, bw + 2, FIELD_H + 2, Theme.borderDark);
			Render2D.rect(g, bx, by, bw, FIELD_H, hover ? Theme.surface : Theme.sidebar);
			Render2D.textNoShadow(g, label, bx + (bw - Render2D.width(label)) / 2, by + 4,
					hover ? Theme.text : Theme.textDim);
			bx += bw + 4;
		}

		String toolbarLabel = ClickGuiToolbar.draw(g, mouseX, mouseY, width, ClickGuiToolbar.CONFIGS);
		if (toolbarLabel != null) {
			ClickGuiToolbar.tooltip(g, toolbarLabel, mouseX, mouseY);
		}
	}

	private static String modified(Path profile) {
		try {
			return DATE.format(Instant.ofEpochMilli(Files.getLastModifiedTime(profile).toMillis())
					.atZone(ZoneId.systemDefault()));
		} catch (Exception e) {
			return "";
		}
	}

	private int saveX() {
		return windowX + W - PAD - 34;
	}

	private int loadX() {
		return windowX + W - PAD - 50;
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

	private int bottomY() {
		return windowY + windowH - PAD - FIELD_H;
	}

	private int buttonW() {
		return (W - 2 * PAD - 2 * 4) / 3;
	}

	private void setStatus(String message) {
		status = message;
		statusColor = Theme.textDim;
		profiles = config().listProfiles();
	}

	private void saveTyped() {
		setStatus(config().saveProfile(NAME.text()));
		if (!status.startsWith("§c")) {
			statusColor = Theme.accent1;
			NAME.clear();
		}
	}

	/** Import: pick any JSON, copy it into the configs folder, then load it. */
	private void importFile() {
		Thread thread = new Thread(() -> {
			String chosen;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				PointerBuffer patterns = stack.mallocPointer(1);
				patterns.put(stack.UTF8("*.json")).flip();
				chosen = TinyFileDialogs.tinyfd_openFileDialog("Import Unlucky config",
						config().configsDir().toAbsolutePath() + File.separator, patterns, "Unlucky config", false);
			}
			if (chosen != null) {
				Minecraft.getInstance().execute(() -> {
					try {
						Path source = Path.of(chosen);
						Path target = config().configsDir().resolve(source.getFileName());
						Files.createDirectories(config().configsDir());
						if (!source.equals(target)) {
							Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
						}
						setStatus(config().loadProfile(target));
					} catch (Exception e) {
						setStatus("§cImport failed: " + e.getMessage());
					}
				});
			}
		}, "unlucky-config-import");
		thread.setDaemon(true);
		thread.start();
	}

	/** Export: save the live settings wherever the dialog points (Desktop, Discord drop, ...). */
	private void exportFile() {
		Thread thread = new Thread(() -> {
			String suggested = NAME.text().trim().isEmpty() ? "unlucky-config" : NAME.text().trim();
			String chosen;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				PointerBuffer patterns = stack.mallocPointer(1);
				patterns.put(stack.UTF8("*.json")).flip();
				chosen = TinyFileDialogs.tinyfd_saveFileDialog("Export Unlucky config",
						suggested + ".json", patterns, "Unlucky config");
			}
			if (chosen != null) {
				Minecraft.getInstance().execute(() -> {
					try {
						String path = chosen.endsWith(".json") ? chosen : chosen + ".json";
						Files.writeString(Path.of(path),
								new com.google.gson.GsonBuilder().setPrettyPrinting().create()
										.toJson(config().toJson()));
						setStatus("Exported to " + Path.of(path).getFileName());
						statusColor = Theme.accent1;
					} catch (Exception e) {
						setStatus("§cExport failed: " + e.getMessage());
					}
				});
			}
		}, "unlucky-config-export");
		thread.setDaemon(true);
		thread.start();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		int toolbarButton = ClickGuiToolbar.buttonAt(mx, my, width);
		if (toolbarButton >= 0) {
			if (toolbarButton != ClickGuiToolbar.CONFIGS) {
				ClickGuiToolbar.activate(toolbarButton, parent);
			}
			return true;
		}
		if (event.button() != 0) {
			return super.mouseClicked(event, doubleClick);
		}
		int fieldW = W - 2 * PAD - 34 - 4;
		if (Render2D.hovered(mx, my, fieldX(), fieldY(), fieldW, FIELD_H)) {
			NAME.click(mx - (fieldX() + 4));
			draggingName = true;
			return true;
		}
		if (Render2D.hovered(mx, my, saveX(), fieldY(), 34, FIELD_H)) {
			saveTyped();
			return true;
		}
		// bottom row
		int bx = windowX + PAD;
		int by = bottomY();
		for (int i = 0; i < 3; i++) {
			if (Render2D.hovered(mx, my, bx, by, buttonW(), FIELD_H)) {
				switch (i) {
					case 0 -> {
						try {
							Files.createDirectories(config().configsDir());
						} catch (Exception ignored) {
						}
						Util.getPlatform().openPath(config().configsDir());
					}
					case 1 -> importFile();
					case 2 -> exportFile();
					default -> { }
				}
				return true;
			}
			bx += buttonW() + 4;
		}
		// rows: Load / delete
		int listTop = listTop();
		int listBottom = windowY + windowH - PAD - FIELD_H - 6;
		int rowY = listTop - scroll;
		for (Path profile : List.copyOf(profiles)) {
			boolean inView = my >= Math.max(rowY, listTop) && my < Math.min(rowY + ROW_H, listBottom);
			if (inView && Render2D.hovered(mx, my, loadX(), rowY + 4, 30, FIELD_H)) {
				setStatus(config().loadProfile(profile));
				if (!status.startsWith("§c")) {
					statusColor = Theme.accent1;
				}
				return true;
			}
			if (inView && Render2D.hovered(mx, my, removeX(), rowY, 12, ROW_H)) {
				try {
					Files.deleteIfExists(profile);
					setStatus(ConfigManager.profileName(profile) + " deleted");
				} catch (Exception e) {
					setStatus("§cDelete failed: " + e.getMessage());
				}
				return true;
			}
			rowY += ROW_H;
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
			saveTyped();
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

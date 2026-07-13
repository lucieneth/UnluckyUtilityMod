package unlucky.utility.client.gui.skins;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.PlayerModelType;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import unlucky.utility.client.UnluckyClientMod;
import unlucky.utility.client.util.MinecraftServicesApi;
import unlucky.utility.client.util.MojangLookup;
import unlucky.utility.client.util.Render2D;

/**
 * The skin & cape editor behind the title screen's Edit button — styled like a
 * classic Minecraft options screen (vanilla background, vanilla buttons,
 * centered title, Back at the bottom) per Lucien's request. Real account
 * changes via {@link MinecraftServicesApi}: staged locally (preview updates
 * instantly), sent in one go on <b>Apply Changes</b>.
 *
 * <p>Left: mouse-following model preview + Arms toggle + Default skin +
 * Revert. Right: one smart input (skin URL <i>or</i> player name to copy),
 * file picker (LWJGL TinyFileDialogs, off-thread — it blocks), skins folder,
 * and the owned-capes grid (front crops via the same
 * {@link SkinTextureDownloader} pipeline CapeManager uses; "None" hides).
 */
public class SkinsScreen extends Screen {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFAAAAAA;
	private static final int RED = 0xFFFF5555;
	private static final int GREEN = 0xFF55FF55;

	private static final int LEFT_W = 100;
	private static final int RIGHT_W = 250;
	private static final int CONTENT_W = LEFT_W + 10 + RIGHT_W;
	private static final int PREVIEW_H = 120;
	private static final int CELL_W = 46;
	private static final int CELL_H = 56;
	private static final int TOP = 45;

	private final Screen parent;

	private MinecraftServicesApi.Profile profile;
	private String status = "Loading profile...";
	private int statusColor = GRAY;
	private boolean busy;
	private int scroll;

	// staged changes — nothing hits the API until Apply
	private enum SkinStage {
		KEEP, FILE, URL, RESET
	}

	private SkinStage skinStage = SkinStage.KEEP;
	private Path stagedFile;
	private String stagedUrl;
	private Boolean stagedSlim; // null = keep account variant
	private boolean capeStaged;
	private String stagedCapeId; // null while capeStaged = hide

	/** Staged skin shown by the preview before (and after) Apply. */
	private Identifier previewTexture;
	private int previewVersion;

	/** Owned-cape id -> registered texture (null value = download in flight). */
	private final Map<String, Identifier> capeTextures = new HashMap<>();
	private SkinTextureDownloader downloader;

	private EditBox input;
	private Button applyButton;
	private Button revertButton;
	private Button defaultButton;

	public SkinsScreen(Screen parent) {
		super(Component.literal("Skins"));
		this.parent = parent;
	}

	private int leftX() {
		return (width - CONTENT_W) / 2;
	}

	private int rightX() {
		return leftX() + LEFT_W + 10;
	}

	@Override
	protected void init() {
		if (profile == null && !busy) {
			fetchProfile();
		}
		int lx = leftX();
		int rx = rightX();

		// left column: arms variant, default skin, revert
		addRenderableWidget(CycleButton.booleanBuilder(
						Component.literal("Slim"), Component.literal("Classic"), previewSlim())
				.create(lx, TOP + PREVIEW_H + 4, LEFT_W, 20, Component.literal("Arms"),
						(button, value) -> stagedSlim = value));
		defaultButton = addRenderableWidget(Button.builder(defaultSkinLabel(), button -> {
			skinStage = skinStage == SkinStage.RESET ? SkinStage.KEEP : SkinStage.RESET;
			button.setMessage(defaultSkinLabel());
			setStatus(skinStage == SkinStage.RESET ? "Default skin staged — Apply to save" : "", GRAY);
		}).bounds(lx, TOP + PREVIEW_H + 28, LEFT_W, 20).build());
		revertButton = addRenderableWidget(Button.builder(Component.literal("Revert"), button -> revert())
				.bounds(lx, TOP + PREVIEW_H + 52, LEFT_W, 20).build());

		// right column: smart input + Use, file/folder row
		if (input == null) {
			input = new EditBox(font, 0, 0, RIGHT_W - 68, 20, Component.literal("Skin"));
			input.setMaxLength(256);
			input.setHint(Component.literal("Skin URL or player name..."));
		}
		input.setPosition(rx, TOP);
		addRenderableWidget(input);
		addRenderableWidget(Button.builder(Component.literal("Use"), button -> use())
				.bounds(rx + RIGHT_W - 64, TOP, 64, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Choose File..."), button -> pickFile())
				.bounds(rx, TOP + 24, (RIGHT_W - 4) / 2, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Open Folder"),
						button -> Util.getPlatform().openPath(skinsFolder()))
				.bounds(rx + (RIGHT_W - 4) / 2 + 4, TOP + 24, (RIGHT_W - 4) / 2, 20).build());

		// classic bottom row: Apply Changes + Back
		applyButton = addRenderableWidget(Button.builder(Component.literal("Apply Changes"), button -> apply())
				.bounds(width / 2 - 152, height - 28, 150, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, button -> onClose())
				.bounds(width / 2 + 2, height - 28, 150, 20).build());
	}

	private Component defaultSkinLabel() {
		return Component.literal(skinStage == SkinStage.RESET ? "Default Skin: on" : "Default Skin");
	}

	private void setStatus(String text, int color) {
		status = text;
		statusColor = color;
	}

	private void fetchProfile() {
		busy = true;
		MinecraftServicesApi.fetchProfile(result -> {
			busy = false;
			profile = result;
			setStatus(result.capes().size() + " capes owned", GRAY);
			rebuildWidgets(); // Arms toggle picks up the account variant
		}, message -> {
			busy = false;
			setStatus(message, RED);
		});
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		applyButton.active = isDirty() && !busy;
		revertButton.active = isDirty();
		super.extractRenderState(g, mouseX, mouseY, a);

		// centered title + status, classic options-screen style
		Render2D.text(g, title.getString(), (width - Render2D.width(title.getString())) / 2, 15, WHITE);
		if (!status.isEmpty()) {
			Render2D.textNoShadow(g, status, (width - Render2D.width(status)) / 2, 30, statusColor);
		}

		// preview inset
		int lx = leftX();
		g.fill(lx - 1, TOP - 1, lx + LEFT_W + 1, TOP + PREVIEW_H + 1, 0xFF000000);
		g.fill(lx, TOP, lx + LEFT_W, TOP + PREVIEW_H, 0x66000000);
		SkinRender.draw(g, previewTexture(), previewSlim(), lx + 4, TOP + 4,
				LEFT_W - 8, PREVIEW_H - 8, mouseX, mouseY);

		// capes grid
		int rx = rightX();
		Render2D.textNoShadow(g, "Capes", rx, gridTop() - 11, GRAY);
		int gridBottom = gridBottom();
		int columns = columns();
		int count = 1 + (profile == null ? 0 : profile.capes().size());
		int rows = (count + columns - 1) / columns;
		int contentH = rows * (CELL_H + 4);
		scroll = Math.clamp(scroll, 0, Math.max(0, contentH - (gridBottom - gridTop())));
		g.enableScissor(rx, gridTop(), rx + RIGHT_W + 2, gridBottom);
		for (int i = 0; i < count; i++) {
			int cx = rx + (i % columns) * (CELL_W + 4);
			int cy = gridTop() + (i / columns) * (CELL_H + 4) - scroll;
			if (cy + CELL_H < gridTop() || cy > gridBottom) {
				continue;
			}
			drawCapeCell(g, cx, cy, i == 0 ? null : profile.capes().get(i - 1), mouseX, mouseY);
		}
		g.disableScissor();

		// scrollbar, vanilla-gray
		int viewH = gridBottom - gridTop();
		if (contentH > viewH) {
			int barH = Math.max(viewH * viewH / contentH, 10);
			int barY = gridTop() + (viewH - barH) * scroll / Math.max(1, contentH - viewH);
			g.fill(rx + RIGHT_W + 4, gridTop(), rx + RIGHT_W + 6, gridBottom, 0xFF000000);
			g.fill(rx + RIGHT_W + 4, barY, rx + RIGHT_W + 6, barY + barH, 0xFF808080);
		}
	}

	/** Classic pack-selection look: dark cell, white border when selected. */
	private void drawCapeCell(GuiGraphicsExtractor g, int cx, int cy, MinecraftServicesApi.Cape cape,
			int mouseX, int mouseY) {
		boolean hover = Render2D.hovered(mouseX, mouseY, cx, cy, CELL_W, CELL_H);
		boolean selected = isSelectedCape(cape);
		g.fill(cx, cy, cx + CELL_W, cy + CELL_H, hover ? 0x99000000 : 0x66000000);
		if (selected) {
			g.outline(cx, cy, CELL_W, CELL_H, WHITE);
		} else if (hover) {
			g.outline(cx, cy, CELL_W, CELL_H, 0xFF808080);
		}
		if (cape == null) {
			Render2D.textNoShadow(g, "None", cx + (CELL_W - Render2D.width("None")) / 2,
					cy + CELL_H / 2 - 4, selected || hover ? WHITE : GRAY);
			return;
		}
		Identifier texture = capeTexture(cape);
		if (texture != null) {
			// cape front face: (1,1)..(11,17) of the 64x32 layout, drawn 2x
			g.blit(RenderPipelines.GUI_TEXTURED, texture, cx + (CELL_W - 20) / 2, cy + 3,
					1.0f, 1.0f, 20, 32, 10, 16, 64, 32, -1);
		} else {
			Render2D.textNoShadow(g, "...", cx + CELL_W / 2 - 4, cy + 16, GRAY);
		}
		String alias = cape.alias().isEmpty() ? "Cape" : cape.alias();
		while (Render2D.width(alias) > CELL_W - 4 && alias.length() > 3) {
			alias = alias.substring(0, alias.length() - 1);
		}
		Render2D.textNoShadow(g, alias, cx + (CELL_W - Render2D.width(alias)) / 2,
				cy + CELL_H - 11, selected || hover ? WHITE : GRAY);
	}

	/** Cape preview texture, downloading + caching on first sight. */
	private Identifier capeTexture(MinecraftServicesApi.Cape cape) {
		if (capeTextures.containsKey(cape.id())) {
			return capeTextures.get(cape.id());
		}
		capeTextures.put(cape.id(), null); // in flight
		Identifier id = Identifier.fromNamespaceAndPath("unlucky", "capes/owned/" + cape.id().replace("-", ""));
		Path cache = FabricLoader.getInstance().getConfigDir()
				.resolve("unlucky/capes/owned/" + cape.id().replace("-", "") + ".png");
		try {
			Files.createDirectories(cache.getParent());
		} catch (Exception e) {
			return null;
		}
		downloader().downloadAndRegisterSkin(id, cache, cape.url(), false)
				.thenAccept(texture -> capeTextures.put(cape.id(), id))
				.exceptionally(error -> {
					UnluckyClientMod.LOGGER.warn("Owned cape download failed: {}", cape.alias(), error);
					return null;
				});
		return null;
	}

	private SkinTextureDownloader downloader() {
		if (downloader == null) {
			Minecraft mc = Minecraft.getInstance();
			downloader = new SkinTextureDownloader(mc.getProxy(), mc.getTextureManager(), mc::execute);
		}
		return downloader;
	}

	// --- staging ---

	private boolean isDirty() {
		return skinStage != SkinStage.KEEP || capeStaged
				|| (stagedSlim != null && profile != null && stagedSlim != profile.slim());
	}

	private boolean isSelectedCape(MinecraftServicesApi.Cape cape) {
		if (capeStaged) {
			return cape == null ? stagedCapeId == null : cape.id().equals(stagedCapeId);
		}
		return cape == null
				? profile != null && profile.capes().stream().noneMatch(MinecraftServicesApi.Cape::active)
				: cape.active();
	}

	private Identifier previewTexture() {
		return previewTexture != null && skinStage != SkinStage.RESET
				? previewTexture
				: SkinRender.ownSkin().body().texturePath();
	}

	private boolean previewSlim() {
		if (stagedSlim != null) {
			return stagedSlim;
		}
		if (profile != null) {
			return profile.slim();
		}
		return SkinRender.ownSkin().model() == PlayerModelType.SLIM;
	}

	private Path skinsFolder() {
		Path folder = FabricLoader.getInstance().getConfigDir().resolve("unlucky/skins");
		try {
			Files.createDirectories(folder);
		} catch (Exception ignored) {
		}
		return folder;
	}

	/** Choose File: native PNG picker, off-thread (tinyfd blocks). */
	private void pickFile() {
		Thread thread = new Thread(() -> {
			String chosen;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				PointerBuffer patterns = stack.mallocPointer(1);
				patterns.put(stack.UTF8("*.png")).flip();
				chosen = TinyFileDialogs.tinyfd_openFileDialog("Choose skin PNG",
						skinsFolder().toAbsolutePath() + java.io.File.separator, patterns, "PNG skin", false);
			}
			if (chosen != null) {
				Minecraft.getInstance().execute(() -> stageFile(Path.of(chosen)));
			}
		}, "unlucky-skin-file-dialog");
		thread.setDaemon(true);
		thread.start();
	}

	private void stageFile(Path file) {
		try (var in = Files.newInputStream(file)) {
			NativeImage image = NativeImage.read(in);
			Identifier id = Identifier.fromNamespaceAndPath("unlucky", "skins/staged" + ++previewVersion);
			Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(id::toString, image));
			previewTexture = id;
		} catch (Exception e) {
			setStatus("Not a readable PNG: " + file.getFileName(), RED);
			return;
		}
		skinStage = SkinStage.FILE;
		stagedFile = file;
		defaultButton.setMessage(defaultSkinLabel());
		setStatus("Staged " + file.getFileName() + " — Apply to upload", GREEN);
	}

	private void stageUrl(String url, Boolean slimFromSource) {
		skinStage = SkinStage.URL;
		stagedUrl = url;
		if (slimFromSource != null) {
			stagedSlim = slimFromSource;
			rebuildWidgets(); // Arms toggle shows the copied variant
		}
		// preview: force a fresh download through the vanilla skin pipeline
		Identifier id = Identifier.fromNamespaceAndPath("unlucky", "skins/staged" + ++previewVersion);
		Path cache = skinsFolder().resolve(".staged.png");
		try {
			Files.deleteIfExists(cache);
		} catch (Exception ignored) {
		}
		downloader().downloadAndRegisterSkin(id, cache, url, true)
				.thenAccept(texture -> previewTexture = id)
				.exceptionally(error -> {
					setStatus("Skin download failed", RED);
					return null;
				});
		setStatus("Staged skin — Apply to save", GREEN);
	}

	/** Use: URL directly, or a player name to copy (variant included). */
	private void use() {
		String text = input.getValue().trim();
		if (text.isEmpty() || busy) {
			return;
		}
		if (text.startsWith("http://") || text.startsWith("https://")) {
			stageUrl(text, null);
			input.setValue("");
			return;
		}
		setStatus("Looking up " + text + "...", GRAY);
		MojangLookup.resolve(text,
				(uuid, name) -> MinecraftServicesApi.fetchSkinOf(uuid,
						(url, slimSkin) -> {
							stageUrl(url, slimSkin);
							setStatus("Staged " + name + "'s skin — Apply to save", GREEN);
							input.setValue("");
						},
						message -> setStatus(message, RED)),
				message -> setStatus(message, RED));
	}

	private void revert() {
		skinStage = SkinStage.KEEP;
		stagedFile = null;
		stagedUrl = null;
		stagedSlim = null;
		capeStaged = false;
		stagedCapeId = null;
		previewTexture = null;
		setStatus("Reverted", GRAY);
		rebuildWidgets();
	}

	// --- apply: skin op, then cape op, then re-fetch ---

	private void apply() {
		if (!isDirty() || busy) {
			return;
		}
		busy = true;
		setStatus("Applying...", GRAY);
		boolean slim = previewSlim();
		Runnable capeThenRefresh = () -> {
			if (capeStaged) {
				MinecraftServicesApi.setCape(stagedCapeId, this::applied, this::applyFailed);
			} else {
				applied();
			}
		};
		switch (skinStage) {
			case FILE -> MinecraftServicesApi.uploadSkin(stagedFile, slim, capeThenRefresh, this::applyFailed);
			case URL -> MinecraftServicesApi.setSkinByUrl(stagedUrl, slim, capeThenRefresh, this::applyFailed);
			case RESET -> MinecraftServicesApi.resetSkin(capeThenRefresh, this::applyFailed);
			case KEEP -> {
				if (stagedSlim != null && profile != null && profile.skinUrl() != null
						&& stagedSlim != profile.slim()) {
					// variant-only change: re-post the current skin with the new arms
					MinecraftServicesApi.setSkinByUrl(profile.skinUrl(), slim, capeThenRefresh, this::applyFailed);
				} else {
					capeThenRefresh.run();
				}
			}
		}
	}

	private void applied() {
		skinStage = SkinStage.KEEP;
		stagedFile = null;
		stagedUrl = null;
		capeStaged = false;
		stagedCapeId = null;
		busy = false;
		setStatus("Saved to your account (servers see it on next join)", GREEN);
		fetchProfile();
	}

	private void applyFailed(String message) {
		busy = false;
		setStatus(message, RED);
	}

	// --- input beyond the widgets: cape cells, grid scroll, Enter-to-use ---

	private int columns() {
		return Math.max(1, RIGHT_W / (CELL_W + 4));
	}

	private int gridTop() {
		return TOP + 24 + 24 + 11;
	}

	private int gridBottom() {
		return height - 36;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		double mx = event.x();
		double my = event.y();
		if (event.button() == 0 && my >= gridTop() && my <= gridBottom()) {
			int count = 1 + (profile == null ? 0 : profile.capes().size());
			for (int i = 0; i < count; i++) {
				int cx = rightX() + (i % columns()) * (CELL_W + 4);
				int cy = gridTop() + (i / columns()) * (CELL_H + 4) - scroll;
				if (Render2D.hovered(mx, my, cx, cy, CELL_W, CELL_H)) {
					capeStaged = true;
					stagedCapeId = i == 0 ? null : profile.capes().get(i - 1).id();
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX >= rightX() && mouseY >= gridTop() && mouseY <= gridBottom()) {
			scroll -= (int) (scrollY * 15);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (input.isFocused()
				&& (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
			use();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}

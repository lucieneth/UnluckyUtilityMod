package unlucky.utility.client.util;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;
import unlucky.utility.client.UnluckyClientMod;

/**
 * Native open/save dialogs, on SDL.
 *
 * <p>26.3 dropped GLFW and with it {@code lwjgl-tinyfd}, which is what the config and
 * skin pickers used. SDL's dialogs replace it but invert the shape: tinyfd
 * <em>blocked</em> and returned a path, so every caller ran on its own daemon thread;
 * SDL returns immediately and calls back later, on a thread of its choosing. This
 * class is the adapter — callers hand over a consumer and get it back on the client
 * thread, with no thread of their own to manage.
 *
 * <p>The native callback has to outlive the call and then be released exactly once,
 * which is the whole reason this is a class rather than two inline lambdas.
 */
public final class FileDialogs {
	/** Pick an existing file. The consumer runs on the client thread; cancel means it never runs. */
	public static void open(String title, @Nullable Path startIn, String filterName, String pattern,
			Consumer<Path> onChosen) {
		show(SDLDialog.SDL_FILEDIALOG_OPENFILE, title, startIn, filterName, pattern, onChosen);
	}

	/** Pick a destination to write to. Same threading and cancel behaviour as {@link #open}. */
	public static void save(String title, @Nullable Path suggested, String filterName, String pattern,
			Consumer<Path> onChosen) {
		show(SDLDialog.SDL_FILEDIALOG_SAVEFILE, title, suggested, filterName, pattern, onChosen);
	}

	private static void show(int kind, String title, @Nullable Path location, String filterName,
			String pattern, Consumer<Path> onChosen) {
		Minecraft mc = Minecraft.getInstance();
		// SDL reads the filter on its dialog thread, after this method has returned, so it
		// lives on the heap rather than the stack and is released with the callback.
		ByteBuffer name = MemoryUtil.memUTF8(filterName);
		ByteBuffer glob = MemoryUtil.memUTF8(pattern);
		SDL_DialogFileFilter.Buffer filters = SDL_DialogFileFilter.malloc(1);
		filters.get(0).name(name).pattern(glob);
		Runnable release = () -> {
			filters.free();
			MemoryUtil.memFree(name);
			MemoryUtil.memFree(glob);
		};

		// A box holding its own callback: the lambda cannot free the callback it is
		// still running inside of without naming it first.
		SDL_DialogFileCallback[] self = new SDL_DialogFileCallback[1];
		self[0] = SDL_DialogFileCallback.create((userdata, fileList, filter) -> {
			try {
				Path chosen = firstPath(fileList);
				if (chosen != null) {
					mc.execute(() -> onChosen.accept(chosen));
				}
			} catch (Exception e) {
				UnluckyClientMod.LOGGER.error("File dialog callback failed", e);
			} finally {
				self[0].free();
				release.run();
			}
		});

		// The plain Show*FileDialog calls take no title; the properties form does. SDL
		// copies what it needs out of the property set, so it can go once the call returns.
		int props = SDLProperties.SDL_CreateProperties();
		try {
			if (mc.getWindow() != null) {
				SDLProperties.SDL_SetPointerProperty(props, SDLDialog.SDL_PROP_FILE_DIALOG_WINDOW_POINTER, mc.getWindow().handle());
			}
			SDLProperties.SDL_SetPointerProperty(props, SDLDialog.SDL_PROP_FILE_DIALOG_FILTERS_POINTER, filters.address());
			SDLProperties.SDL_SetNumberProperty(props, SDLDialog.SDL_PROP_FILE_DIALOG_NFILTERS_NUMBER, 1);
			SDLProperties.SDL_SetStringProperty(props, SDLDialog.SDL_PROP_FILE_DIALOG_TITLE_STRING, title);
			if (location != null) {
				SDLProperties.SDL_SetStringProperty(props, SDLDialog.SDL_PROP_FILE_DIALOG_LOCATION_STRING,
						location.toAbsolutePath().toString());
			}
			SDLDialog.SDL_ShowFileDialogWithProperties(kind, self[0], MemoryUtil.NULL, props);
		} catch (Exception e) {
			// The callback will never fire, so release everything here instead.
			self[0].free();
			release.run();
			UnluckyClientMod.LOGGER.error("Could not open the {} dialog", title, e);
		} finally {
			SDLProperties.SDL_DestroyProperties(props);
		}
	}

	/**
	 * Reads the first entry of SDL's result.
	 *
	 * <p>The argument is a NULL-terminated {@code char**}. SDL distinguishes three
	 * outcomes through it and only one of them is a file: a null list is an error, a
	 * list whose first entry is null is the user cancelling, and anything else is a
	 * path. Only single selection is ever requested, so the first entry is the answer.
	 */
	private static @Nullable Path firstPath(long fileList) {
		if (fileList == MemoryUtil.NULL) {
			UnluckyClientMod.LOGGER.warn("File dialog failed: {}", org.lwjgl.sdl.SDLError.SDL_GetError());
			return null;
		}
		long first = MemoryUtil.memGetAddress(fileList);
		return first == MemoryUtil.NULL ? null : Path.of(MemoryUtil.memUTF8(first));
	}

	private FileDialogs() {
	}
}

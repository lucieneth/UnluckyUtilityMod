package unlucky.utility.client.util;

import java.nio.file.Path;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryStack;
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
			}
		});

		try (MemoryStack stack = MemoryStack.stackPush()) {
			SDL_DialogFileFilter.Buffer filters = SDL_DialogFileFilter.malloc(1, stack);
			filters.get(0).name(stack.UTF8(filterName)).pattern(stack.UTF8(pattern));
			long window = mc.getWindow() != null ? mc.getWindow().handle() : MemoryUtil.NULL;
			String startIn = location != null ? location.toAbsolutePath().toString() : null;
			if (kind == SDLDialog.SDL_FILEDIALOG_SAVEFILE) {
				SDLDialog.SDL_ShowSaveFileDialog(self[0], MemoryUtil.NULL, window, filters, startIn);
			} else {
				SDLDialog.SDL_ShowOpenFileDialog(self[0], MemoryUtil.NULL, window, filters, startIn, false);
			}
		} catch (Exception e) {
			// The callback will never fire, so release it here instead.
			self[0].free();
			UnluckyClientMod.LOGGER.error("Could not open the {} dialog", title, e);
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

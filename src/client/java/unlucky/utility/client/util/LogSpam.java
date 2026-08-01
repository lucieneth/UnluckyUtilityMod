package unlucky.utility.client.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.filter.AbstractFilter;

/**
 * Drops a third-party mod's per-frame render logging.
 *
 * <p><b>Not our logging, and worth saying so.</b> The Litematica build for 26.2 prints a
 * line every time it rebuilds a schematic chunk:
 *
 * <pre>
 * [Render thread/INFO] (litematica) [WorldRenderer] updateChunks(): 1 Chunks updated.
 * [Render thread/WARN] (litematica) [WorldRenderer] setupTerrain / chunksToUpdate: 1
 * </pre>
 *
 * <p>A chunk is rebuilt whenever a block in it changes, so <em>printing</em> produces two
 * log lines per placement batch — thousands a minute, on the render thread, each one
 * formatted and written to disk. That is a real frame cost, and it is why the game gets
 * slower the faster the printer works. Litematica's own {@code debugLogging} option is
 * already off; these lines are unconditional in this build.
 *
 * <p>Scoped as narrowly as it can be: only the {@code litematica} logger, only messages
 * beginning with the one prefix, so every genuine warning that mod raises still comes
 * through. Delete this class and its call in {@code UnluckyClientMod} once Litematica
 * stops logging these.
 */
public final class LogSpam {
	/** The one prefix that is dropped. Everything else Litematica logs is left alone. */
	private static final String PREFIX = "[WorldRenderer]";

	private LogSpam() {
	}

	/**
	 * Installs the filter, quietly doing nothing if the logging backend is not the Log4j
	 * core we expect — a suppressed log line is never worth a crash on startup.
	 */
	public static void muteLitematicaRenderSpam() {
		try {
			if (!(LogManager.getContext(false) instanceof LoggerContext context)) {
				return;
			}
			context.getConfiguration().addFilter(new AbstractFilter() {
				@Override
				public Result filter(LogEvent event) {
					if (event.getLoggerName() == null
							|| !event.getLoggerName().contains("litematica")) {
						return Result.NEUTRAL;
					}
					String message = event.getMessage() == null
							? null : event.getMessage().getFormattedMessage();
					return message != null && message.startsWith(PREFIX)
							? Result.DENY
							: Result.NEUTRAL;
				}
			});
			context.updateLoggers();
		} catch (LinkageError | RuntimeException ignored) {
			// a different logging backend, or a locked-down configuration; not worth failing over
		}
	}
}

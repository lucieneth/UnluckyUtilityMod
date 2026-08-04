package unlucky.utility.client.util;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.spongepowered.asm.mixin.transformer.meta.MixinMerged;

import unlucky.utility.client.UnluckyClientMod;

/**
 * Asks every mixin in {@code unlucky.client.mixins.json} whether it reached its target class.
 *
 * <p><b>Scope, precisely, because the obvious reading is wrong.</b> This answers "did Mixin
 * apply this mixin to this class", and nothing finer. It does <em>not</em> verify that each
 * injection inside a mixin found its injection point: Mixin merges a handler method into the
 * target whether or not the injector bound, so an {@code @Inject} with {@code require = 0}
 * whose target method has been renamed away leaves a merged, never-called method behind and
 * this audit calls it applied. That was measured, not assumed — an injection was deliberately
 * pointed at a method that does not exist and the audit passed.
 *
 * <p>What it does catch is the class-level failure, and the reason that is worth a file is
 * the three Sodium mixins. They name their targets as <b>strings</b>
 * ({@code @Mixin(targets = "net.caffeinemc...")}), because Sodium is not a compile dependency
 * — so those three names are the only references in the entire codebase with no compile-time
 * checking at all. Sodium moves a class, or renames a package, and XRay-under-Sodium stops
 * working silently and permanently; nothing else in the build would ever say so. Everything
 * else is covered by {@code defaultRequire: 1}, which throws when an injection point moves.
 *
 * <p>The check reads Mixin's own record of what it merged, so a mixin that applied is
 * attributed to its target exactly — see {@link #statusOf}.
 *
 * <p>Targets are read out of each mixin's {@code @Mixin} annotation with ASM, straight from
 * the class bytes. Deliberately not by loading the mixin class and reading the annotation
 * reflectively: mixin classes are the transformer's input, not ordinary classes, and loading
 * one after it has been consumed is not something to rely on.
 *
 * <p>Cost is real but bounded — it force-loads every target class (without initialising
 * them), which is what makes the mixin apply in the first place. So it runs only behind
 * {@code -Dunlucky.mixinAudit} (or {@code UNLUCKY_MIXIN_AUDIT=true}), plus unconditionally
 * in the client gametest, which is where it earns its keep on a version bump.
 */
public final class MixinAudit {
	private static final String CONFIG = "unlucky.client.mixins.json";
	private static final String MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;";
	private static final String PREFIX = "unlucky$";

	public static final boolean ENABLED = Boolean.getBoolean("unlucky.mixinAudit")
			|| "true".equalsIgnoreCase(System.getenv("UNLUCKY_MIXIN_AUDIT"));

	public enum Status {
		/** Target class carries our injected members. */
		APPLIED,
		/**
		 * Target class isn't on the classpath. Expected and fine for the soft-dependency
		 * mixins — no Sodium installed means no Sodium classes to patch.
		 */
		TARGET_ABSENT,
		/** Target class is present and loaded, and none of our members are on it. */
		NOT_APPLIED
	}

	public record Result(String mixin, String target, Status status) {
	}

	private MixinAudit() {
	}

	/** Runs the audit and logs it. Returns everything it found, worst cases included. */
	public static List<Result> run() {
		List<Result> results = new ArrayList<>();
		for (String mixin : configuredMixins()) {
			List<String> targets = targetsOf(mixin);
			if (targets.isEmpty()) {
				// no @Mixin annotation found, or the class bytes were unreadable — either way
				// this is not a mixin we can speak for, and saying nothing is the honest answer
				continue;
			}
			for (String target : targets) {
				results.add(new Result(mixin, target, statusOf(mixin, target)));
			}
		}

		long applied = results.stream().filter(r -> r.status() == Status.APPLIED).count();
		long absent = results.stream().filter(r -> r.status() == Status.TARGET_ABSENT).count();
		List<Result> broken = results.stream().filter(r -> r.status() == Status.NOT_APPLIED).toList();

		UnluckyClientMod.LOGGER.info("Mixin audit: {} applied, {} target absent, {} not applied",
				applied, absent, broken.size());
		for (Result result : results) {
			if (result.status() == Status.TARGET_ABSENT) {
				UnluckyClientMod.LOGGER.info("  {} — target not on the classpath ({})",
						result.mixin(), result.target());
			}
		}
		for (Result result : broken) {
			UnluckyClientMod.LOGGER.warn("  {} did NOT apply to {} — the class is loaded and carries "
					+ "no {} members, so every injection in it was dropped",
					result.mixin(), result.target(), PREFIX);
		}
		return results;
	}

	/** Every mixin named in the config, fully qualified. */
	private static List<String> configuredMixins() {
		List<String> names = new ArrayList<>();
		try (InputStream in = MixinAudit.class.getClassLoader().getResourceAsStream(CONFIG)) {
			if (in == null) {
				UnluckyClientMod.LOGGER.warn("Mixin audit: {} not on the classpath", CONFIG);
				return names;
			}
			JsonObject json = JsonParser.parseReader(
					new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			String pkg = json.get("package").getAsString();
			for (String section : new String[]{"mixins", "client", "server"}) {
				JsonElement element = json.get(section);
				if (element == null) {
					continue;
				}
				for (JsonElement entry : element.getAsJsonArray()) {
					names.add(pkg + '.' + entry.getAsString());
				}
			}
		} catch (Exception e) {
			UnluckyClientMod.LOGGER.warn("Mixin audit: could not read {}", CONFIG, e);
		}
		return names;
	}

	/**
	 * The classes a mixin targets, from {@code @Mixin(value = ..., targets = ...)}.
	 *
	 * <p>{@code value} holds real class references and {@code targets} holds strings — the
	 * latter is how the Sodium mixins name classes that may not exist at compile time. Both
	 * are collected the same way.
	 */
	private static List<String> targetsOf(String mixin) {
		List<String> targets = new ArrayList<>();
		String resource = mixin.replace('.', '/') + ".class";
		try (InputStream in = MixinAudit.class.getClassLoader().getResourceAsStream(resource)) {
			if (in == null) {
				return targets;
			}
			new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
					if (!MIXIN_ANNOTATION.equals(descriptor)) {
						return null;
					}
					return new AnnotationVisitor(Opcodes.ASM9) {
						@Override
						public AnnotationVisitor visitArray(String name) {
							if (!"value".equals(name) && !"targets".equals(name)) {
								return null;
							}
							return new AnnotationVisitor(Opcodes.ASM9) {
								@Override
								public void visit(String unused, Object value) {
									if (value instanceof Type type) {
										targets.add(type.getClassName());
									} else if (value instanceof String name) {
										targets.add(name);
									}
								}
							};
						}
					};
				}
			}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		} catch (Exception e) {
			UnluckyClientMod.LOGGER.warn("Mixin audit: could not read {}", resource, e);
		}
		return targets;
	}

	/**
	 * Loads the target and asks whether this mixin's members are on it.
	 *
	 * <p><b>Mixin's own bookkeeping is the primary signal.</b> Every method merged into a
	 * target carries {@link MixinMerged}, whose {@code mixin()} names the class it came from
	 * — so the match is exact, per mixin, rather than "something of ours is on this class".
	 * That distinction matters where several mixins share a target: {@code GuiMixin} and
	 * {@code GuiBlurMixin} both patch {@code Gui}, and a name-based check would let either
	 * one vouch for the other.
	 *
	 * <p>The {@code unlucky$} fallback is for members Mixin does not annotate — generated
	 * accessor and invoker implementations, and unique fields. It is a substring test, not a
	 * prefix one: Mixin renames private handler methods on merge and the original name
	 * survives inside the new one. That looseness is why it is the fallback and not the rule.
	 *
	 * <p>{@code initialize = false}: loading is what triggers the transformation and is the
	 * whole point, but running a class's static initialiser out of order is not something an
	 * audit gets to do.
	 */
	private static Status statusOf(String mixin, String target) {
		Class<?> loaded;
		try {
			loaded = Class.forName(target, false, MixinAudit.class.getClassLoader());
		} catch (ClassNotFoundException | LinkageError e) {
			return Status.TARGET_ABSENT;
		}

		for (Method method : loaded.getDeclaredMethods()) {
			MixinMerged merged = method.getAnnotation(MixinMerged.class);
			if (merged != null && merged.mixin().equals(mixin)) {
				return Status.APPLIED;
			}
			if (method.getName().contains(PREFIX)) {
				return Status.APPLIED;
			}
		}
		for (Field field : loaded.getDeclaredFields()) {
			if (field.getName().contains(PREFIX)) {
				return Status.APPLIED;
			}
		}
		return Status.NOT_APPLIED;
	}
}

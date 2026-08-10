package unlucky.utility.client.module;

/**
 * Whether a module's behaviour is something the server can see.
 *
 * <p>Exists for one feature — <b>Panic Minimal</b>, which turns off everything the server
 * could notice and leaves everything it cannot. The obvious implementation is a list of
 * module names inside Panic, and a list is wrong the first time somebody adds a module and
 * forgets to update it: the module keeps running through a panic, silently, and nothing in a
 * passing build says so. Asking each module to answer for itself moves the decision to the
 * file where the behaviour lives, and {@link Module}'s constructor makes forgetting it a
 * compile error rather than a surprise on a server.
 *
 * <p><b>The question is not "is this cheating".</b> It is the narrower one Panic actually
 * needs: does this module change, suppress or send gameplay movement, rotation, inventory,
 * attack, interaction, respawn or reconnect behaviour? An ESP is invisible to the server no
 * matter how much of an advantage it is, and belongs in {@link #CLIENT_ONLY}; AutoSprint
 * puts a sprint packet on the wire that your hands did not, and does not.
 */
public enum ServerVisibility {
	/**
	 * Nothing this module does leaves the client. Rendering, chat display, tooltips, world
	 * analysis, camera work that does not also hold your body still.
	 *
	 * <p>Kept running by Panic Minimal — that is the whole point of the mode. Reading the
	 * world you were already sent is not observable, and a panic that blanked your ESP would
	 * only ever cost you the thing you turned it on for.
	 */
	CLIENT_ONLY,

	/**
	 * The module acts on the wire whenever it is enabled, whether or not it happens to be
	 * mid-action this tick.
	 *
	 * <p>This is the right answer for anything that <em>initiates</em>: an automation that is
	 * merely idle right now is still going to fire a second later, and "not currently doing
	 * it" is not a state Panic can rely on. AutoFish is between bites for most of its life and
	 * is still {@code SERVER_OBSERVABLE}.
	 */
	SERVER_OBSERVABLE,

	/**
	 * The module is <em>reactive</em>, and the client can check right now whether the thing it
	 * reacts to is happening. When that check says no, the module is genuinely inert.
	 *
	 * <p>Reserved for exactly that shape, because it is the only one where the answer is both
	 * knowable and useful. InventoryMove changes nothing at all until a screen is open;
	 * AutoEat changes nothing until it has claimed your hotbar. A module that declares
	 * {@code CONDITIONAL} <b>must</b> override {@link Module#isServerObservableNow()} — one
	 * that does not is just a slower {@link #SERVER_OBSERVABLE}, and {@code ModuleSmokeTest}
	 * fails the build to say so.
	 */
	CONDITIONAL
}

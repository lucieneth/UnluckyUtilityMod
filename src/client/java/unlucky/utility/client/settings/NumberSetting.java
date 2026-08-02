package unlucky.utility.client.settings;

public class NumberSetting extends Setting<Double> {
	private final double min;
	private final double max;
	private final double step;

	public NumberSetting(String name, String description, double defaultValue, double min, double max, double step) {
		super(name, description, defaultValue);
		this.min = min;
		this.max = max;
		this.step = step;
	}

	public double getMin() {
		return min;
	}

	public double getMax() {
		return max;
	}

	@Override
	public void set(Double value) {
		double stepped = Math.round(value / step) * step;
		super.set(Math.clamp(stepped, min, max));
	}

	public float getFloat() {
		return get().floatValue();
	}

	public int getInt() {
		return (int) Math.round(get());
	}

	/**
	 * Value as a human readable string, with as many decimals as the step actually
	 * distinguishes. A fixed "%.1f" rounds a 0.05-step slider's 0.05 and 0.10 to the
	 * same "0.1", so the number stops moving while the handle keeps sliding.
	 */
	public String display() {
		double v = get();
		if (step >= 1.0 && v == Math.floor(v)) {
			return Integer.toString((int) v);
		}
		int decimals = step >= 0.1 ? 1 : step >= 0.01 ? 2 : 3;
		return String.format(java.util.Locale.ROOT, "%." + decimals + "f", v);
	}
}

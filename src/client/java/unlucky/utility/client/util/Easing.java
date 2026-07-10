package unlucky.utility.client.util;

public enum Easing {
	LINEAR {
		@Override
		public double apply(double t) {
			return t;
		}
	},
	QUAD_OUT {
		@Override
		public double apply(double t) {
			return 1.0 - (1.0 - t) * (1.0 - t);
		}
	},
	CUBIC_OUT {
		@Override
		public double apply(double t) {
			return 1.0 - Math.pow(1.0 - t, 3.0);
		}
	},
	EXPO_OUT {
		@Override
		public double apply(double t) {
			return t >= 1.0 ? 1.0 : 1.0 - Math.pow(2.0, -10.0 * t);
		}
	},
	BACK_OUT {
		@Override
		public double apply(double t) {
			double c1 = 1.70158;
			double c3 = c1 + 1.0;
			return 1.0 + c3 * Math.pow(t - 1.0, 3.0) + c1 * Math.pow(t - 1.0, 2.0);
		}
	};

	public abstract double apply(double t);
}

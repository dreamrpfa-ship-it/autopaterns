package fr.autopaterns.fishing;

/**
 * Contrôleur du combat (formule exacte de la 2.x). Positions normalisées dans [0,1], 0 = haut de la jauge.
 * {@code true} = faire monter la zone (clic droit).
 */
final class FishControl {
	private double lastZone = Double.NaN;
	private double lastFish = Double.NaN;
	private double vz;
	private double vf;
	private boolean hold;

	void reset() {
		lastZone = Double.NaN;
		lastFish = Double.NaN;
		vz = 0;
		vf = 0;
		hold = false;
	}

	/**
	 * Une mesure. Les vitesses sont lissées par mesure ; {@code lead} est l'horizon d'anticipation exprimé en
	 * mesures (le bot le convertit depuis des ticks, voir FishingBot#effectiveLead).
	 */
	boolean step(double zone, double fish, double lead) {
		if (Double.isNaN(zone)) return hold;
		if (!Double.isNaN(lastZone)) vz = 0.5 * vz + 0.5 * (zone - lastZone);
		if (!Double.isNaN(fish) && !Double.isNaN(lastFish)) vf = 0.6 * vf + 0.4 * (fish - lastFish);
		lastZone = zone;
		if (!Double.isNaN(fish)) lastFish = fish;
		double target = Double.isNaN(lastFish) ? zone : lastFish + vf * lead * 0.5;
		double err = (zone + vz * lead) - target;
		if (err > 0.01) hold = true;
		else if (err < -0.01) hold = false;
		return hold;
	}
}

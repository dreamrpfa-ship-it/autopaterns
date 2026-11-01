package fr.autopaterns.patterns;

/**
 * État du joueur au début d'un tick d'enregistrement (format 2.x, une ligne par tick dans les fichiers .apt).
 * <p>
 * {@code state = -1} et {@code vx/vy/vz = NaN} désignent l'<b>ancien format</b> (9 valeurs, sans état physique).
 * Correction 3.0 : une frame sans état physique est réécrite en 9 valeurs (2.x écrivait {@code NaN NaN NaN -1},
 * ligne ensuite refusée au rechargement) : la migration est sans perte et le fichier reste lisible.
 */
public record TickFrame(int keys, int attacks, int uses, int slot, float yaw, float pitch,
						double x, double y, double z, double vx, double vy, double vz, int state) {
	public static final int K_FORWARD = 1;
	public static final int K_BACK = 2;
	public static final int K_LEFT = 4;
	public static final int K_RIGHT = 8;
	public static final int K_JUMP = 16;
	public static final int K_SNEAK = 32;
	public static final int K_SPRINT = 64;
	public static final int K_ATTACK = 128;
	public static final int K_USE = 256;

	public static final int S_ON_GROUND = 1;
	public static final int S_SPRINTING = 2;

	public boolean has(int bit) {
		return (keys & bit) != 0;
	}

	/** Frame complète (vitesse et état connus). */
	public boolean fullState() {
		return state >= 0 && !Double.isNaN(vx);
	}

	public boolean onGround() {
		return state >= 0 && (state & S_ON_GROUND) != 0;
	}

	public boolean sprinting() {
		return state >= 0 && (state & S_SPRINTING) != 0;
	}

	/** 13 valeurs séparées par un espace (9 pour une frame de l'ancien format), conversions Java standard. */
	public String serialize() {
		StringBuilder sb = new StringBuilder(96);
		sb.append(keys).append(' ').append(attacks).append(' ').append(uses).append(' ').append(slot).append(' ')
			.append(yaw).append(' ').append(pitch).append(' ')
			.append(x).append(' ').append(y).append(' ').append(z);
		if (fullState()) {
			sb.append(' ').append(vx).append(' ').append(vy).append(' ').append(vz).append(' ').append(state);
		}
		return sb.toString();
	}

	/**
	 * Lecture d'une ligne (9 ou 13 valeurs). Tolère plusieurs espaces ou tabulations entre les valeurs
	 * (2.x exigeait un seul espace). Les erreurs sont des {@link IllegalArgumentException} au message 2.x.
	 */
	public static TickFrame parse(String line) {
		String[] p = line.trim().split("\\s+");
		if (p.length != 9 && p.length != 13) {
			throw new IllegalArgumentException("9 ou 13 valeurs attendues, " + p.length + " trouvées");
		}
		int slot = Integer.parseInt(p[3]);
		if (slot < 0 || slot > 8) throw new IllegalArgumentException("emplacement hors limites : " + slot);
		boolean full = p.length == 13;
		int keys = Integer.parseInt(p[0]);
		int attacks = Integer.parseInt(p[1]);
		int uses = Integer.parseInt(p[2]);
		float yaw = Float.parseFloat(p[4]);
		float pitch = Float.parseFloat(p[5]);
		double x = Double.parseDouble(p[6]);
		double y = Double.parseDouble(p[7]);
		double z = Double.parseDouble(p[8]);
		double vx = Double.NaN, vy = Double.NaN, vz = Double.NaN;
		int state = -1;
		if (full) {
			vx = Double.parseDouble(p[9]);
			vy = Double.parseDouble(p[10]);
			vz = Double.parseDouble(p[11]);
			state = Integer.parseInt(p[12]);
		}
		boolean ok = Float.isFinite(yaw) && Float.isFinite(pitch) && Double.isFinite(x) && Double.isFinite(y)
			&& Double.isFinite(z) && attacks >= 0 && uses >= 0;
		// « NaN NaN NaN -1 » = frame de l'ancien format réécrite par la 2.x (fichier qu'elle ne savait plus relire) :
		// acceptée comme frame sans état physique, ce qui récupère ces fichiers sans perte.
		boolean legacyRewrite = full && state == -1 && Double.isNaN(vx) && Double.isNaN(vy) && Double.isNaN(vz);
		if (full && !legacyRewrite) ok &= Double.isFinite(vx) && Double.isFinite(vy) && Double.isFinite(vz);
		if (!ok) throw new IllegalArgumentException("valeur invalide");
		return new TickFrame(keys, attacks, uses, slot, yaw, pitch, x, y, z, vx, vy, vz, state);
	}
}

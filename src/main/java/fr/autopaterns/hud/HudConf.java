package fr.autopaterns.hud;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Réglages d'un module (équivalent de {@code Hud$Conf} 2.x, étendu en 3.0).
 * <ul>
 *   <li>2.x : {@code enabled}, {@code fx}/{@code fy} (fraction 0..1, &lt; 0 = automatique), {@code scale} (%),
 *       {@code background}, {@code always} ;</li>
 *   <li>3.0 : {@code opacity} (20–100 %), {@code width} (0 = automatique), informations visibles.</li>
 * </ul>
 */
public final class HudConf {
	public static final int MIN_SCALE = 25;
	public static final int MAX_SCALE = 300;
	public static final int MIN_OPACITY = 20;

	public final HudModule module;
	public boolean enabled = true;
	public double fx = -1.0;
	public double fy = -1.0;
	public int scale = 100;
	public boolean background = true;
	public boolean always;
	public int opacity = 100;
	public int width;
	private final Map<String, Boolean> infos = new LinkedHashMap<>();

	public HudConf(HudModule module) {
		this.module = module;
		for (HudModule.Info i : module.infos) infos.put(i.key(), i.def());
	}

	/** Placement automatique (empilement à gauche), comme {@code Hud.auto} 2.x : {@code fx < 0 || fy < 0}. */
	public boolean auto() {
		return fx < 0 || fy < 0;
	}

	public boolean shows(String key) {
		Boolean b = infos.get(key);
		return b != null && b;
	}

	public void setShows(String key, boolean v) {
		if (infos.containsKey(key)) infos.put(key, v);
	}

	/** Informations différentes des valeurs par défaut, sous forme « +clé|-clé » (vide si aucune). */
	String infosToString() {
		StringBuilder sb = new StringBuilder();
		for (HudModule.Info i : module.infos) {
			boolean v = shows(i.key());
			if (v == i.def()) continue;
			if (!sb.isEmpty()) sb.append('|');
			sb.append(v ? '+' : '-').append(i.key());
		}
		return sb.toString();
	}

	/** Lit « +clé|-clé » ; les clés inconnues sont ignorées. */
	void infosFromString(String s) {
		for (HudModule.Info i : module.infos) infos.put(i.key(), i.def());
		if (s == null) return;
		for (String tok : s.trim().split("\\|")) {
			if (tok.length() < 2) continue;
			char sign = tok.charAt(0);
			String key = tok.substring(1).trim();
			if (sign == '+') setShows(key, true);
			else if (sign == '-') setShows(key, false);
		}
	}

	public HudConf copy() {
		HudConf c = new HudConf(module);
		c.copyFrom(this);
		return c;
	}

	void copyFrom(HudConf o) {
		enabled = o.enabled;
		fx = o.fx;
		fy = o.fy;
		scale = o.scale;
		background = o.background;
		always = o.always;
		opacity = o.opacity;
		width = o.width;
		infos.clear();
		infos.putAll(o.infos);
	}
}

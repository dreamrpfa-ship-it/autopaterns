package fr.autopaterns.performance;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;

/**
 * Choix de l'utilisateur : profil, optimisations activées et leurs valeurs.
 * Clés de config.txt : {@code performance.profile}, {@code performance.optimizations} (liste séparée par des virgules,
 * utilisée en profil personnalisé), {@code performance.<id>} pour chaque valeur réglable.
 * Défaut : profil Maximum (= comportement 2.x).
 */
public final class PerfSettings {
	public static final String KEY_PROFILE = "performance.profile";
	public static final String KEY_OPTS = "performance.optimizations";

	private PerfProfile profile = PerfProfile.MAXIMUM;
	private final EnumSet<PerfOpt> enabled = EnumSet.noneOf(PerfOpt.class);
	private final EnumMap<PerfOpt, Integer> values = new EnumMap<>(PerfOpt.class);

	public PerfSettings() {
		for (PerfOpt o : PerfOpt.values()) if (o.hasValue()) values.put(o, o.def);
		applyProfile(PerfProfile.MAXIMUM);
	}

	public PerfProfile profile() {
		return profile;
	}

	/** Optimisation activée (la limite d'inactivité l'est toujours). */
	public boolean on(PerfOpt o) {
		return o.always || enabled.contains(o);
	}

	public int value(PerfOpt o) {
		Integer v = values.get(o);
		return v == null ? o.def : v;
	}

	/** Sélectionne un profil : ses optimisations et ses valeurs remplacent les choix actuels (sauf profil personnalisé). */
	public void applyProfile(PerfProfile p) {
		profile = p;
		if (p == PerfProfile.CUSTOM) return;
		enabled.clear();
		enabled.addAll(p.opts());
		for (PerfOpt o : PerfOpt.values()) {
			Integer v = p.value(o);
			if (v != null) values.put(o, v);
		}
	}

	/** Change une optimisation à la main : le profil devient « personnalisé ». */
	public void set(PerfOpt o, boolean on) {
		if (o.always || on == enabled.contains(o)) return;
		if (on) enabled.add(o);
		else enabled.remove(o);
		profile = PerfProfile.CUSTOM;
	}

	public void setValue(PerfOpt o, int v) {
		if (!o.hasValue()) return;
		int nv = clamp(o, v);
		if (nv == value(o)) return;
		values.put(o, nv);
		profile = PerfProfile.CUSTOM;
	}

	/** Optimisations activées (hors limite d'inactivité). */
	public List<PerfOpt> enabledList() {
		List<PerfOpt> l = new ArrayList<>();
		for (PerfOpt o : PerfOpt.values()) if (!o.always && enabled.contains(o)) l.add(o);
		return l;
	}

	private static int clamp(PerfOpt o, int v) {
		return Math.max(o.min, Math.min(o.max, v));
	}

	// ------------------------------------------------------------------ config.txt

	void load(Properties p) {
		for (PerfOpt o : PerfOpt.values()) {
			if (!o.hasValue()) continue;
			String s = p.getProperty("performance." + o.id);
			if (s == null) continue;
			try {
				values.put(o, clamp(o, Integer.parseInt(s.trim())));
			} catch (NumberFormatException ignored) {
				// valeur illisible : défaut conservé
			}
		}
		PerfProfile pr = PerfProfile.parse(p.getProperty(KEY_PROFILE), PerfProfile.MAXIMUM);
		if (pr == PerfProfile.CUSTOM) {
			profile = PerfProfile.CUSTOM;
			enabled.clear();
			String list = p.getProperty(KEY_OPTS, "");
			for (String tok : list.split(",")) {
				String t = tok.trim();
				for (PerfOpt o : PerfOpt.values()) {
					if (!o.always && (o.id.equalsIgnoreCase(t) || o.name().equalsIgnoreCase(t))) enabled.add(o);
				}
			}
		} else {
			applyProfile(pr);
		}
	}

	void save(Properties p) {
		p.setProperty(KEY_PROFILE, profile.name());
		StringBuilder sb = new StringBuilder();
		for (PerfOpt o : enabledList()) {
			if (!sb.isEmpty()) sb.append(',');
			sb.append(o.id);
		}
		p.setProperty(KEY_OPTS, sb.toString());
		for (PerfOpt o : PerfOpt.values()) {
			if (o.hasValue()) p.setProperty("performance." + o.id, Integer.toString(value(o)));
		}
	}
}

package fr.autopaterns.performance;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;

/**
 * Accès générique aux options graphiques de {@link Options} par une clé texte (celle de graphismes-origine.txt),
 * lecture/écriture typée et encodage {@code T:valeur} (format 2.x : B booléen, I entier, D décimal, E énumération).
 *
 * <p>Toutes les options utilisées sont listées dans {@link #ALL} (accès direct aux getters Mojang, aucune réflexion).
 * Les valeurs d'énumération sont désignées par leur nom (« OFF », « MINIMAL »…) et converties vers le type réel de
 * l'option : le code ne dépend pas des noms exacts des classes d'énumération.
 */
final class GraphicsOptions {
	private GraphicsOptions() {}

	/** Clé → getter de l'option (ordre = ordre de sauvegarde pour un instantané complet). */
	static final Map<String, Function<Options, OptionInstance<?>>> ALL;

	static {
		Map<String, Function<Options, OptionInstance<?>>> m = new LinkedHashMap<>();
		m.put("inactivityFpsLimit", Options::inactivityFpsLimit);
		m.put("preset", Options::graphicsPreset);
		m.put("improvedTransparency", Options::improvedTransparency);
		m.put("cloudRenderMode", Options::cloudStatus);
		m.put("entityShadows", Options::entityShadows);
		m.put("particles", Options::particles);
		m.put("ao", Options::ambientOcclusion);
		m.put("biomeBlendRadius", Options::biomeBlendRadius);
		m.put("viewDistance", Options::renderDistance);
		m.put("simulationDistance", Options::simulationDistance);
		m.put("entityDistanceScaling", Options::entityDistanceScaling);
		m.put("vignette", Options::vignette);
		m.put("chunkFade", Options::chunkSectionFadeInTime);
		m.put("weatherRadius", Options::weatherRadius);
		m.put("mipmapLevels", Options::mipmapLevels);
		m.put("cutoutLeaves", Options::cutoutLeaves);
		m.put("maxAnisotropyBit", Options::maxAnisotropyBit);
		m.put("textureFiltering", Options::textureFiltering);
		m.put("framerateLimit", Options::framerateLimit);
		ALL = Collections.unmodifiableMap(m);
	}

	/**
	 * Options mémorisées en plus quand le préréglage graphique est modifié : appliquer un préréglage peut changer
	 * plusieurs options d'un coup, toutes doivent pouvoir être restaurées exactement (bug 2.x n°14).
	 */
	static final List<String> PRESET_SNAPSHOT = List.of(
		"improvedTransparency", "cloudRenderMode", "entityShadows", "particles", "ao", "biomeBlendRadius",
		"viewDistance", "simulationDistance", "entityDistanceScaling", "vignette", "chunkFade", "weatherRadius",
		"mipmapLevels", "cutoutLeaves", "maxAnisotropyBit", "textureFiltering");

	/** Option de la clé, ou null si la clé est inconnue / l'option indisponible. */
	static OptionInstance<?> get(Options o, String key) {
		Function<Options, OptionInstance<?>> f = ALL.get(key);
		if (f == null || o == null) return null;
		try {
			return f.apply(o);
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	static void set(OptionInstance<?> opt, Object v) {
		((OptionInstance<Object>) opt).set(v);
	}

	/** Applique le préréglage graphique nommé (FAST…) ; true si la valeur a changé. */
	static boolean applyPreset(Options o, String name) {
		var opt = o.graphicsPreset();
		var cur = opt.get();
		var target = Enum.valueOf(cur.getDeclaringClass(), name);
		if (target == cur) return false;
		opt.set(target);
		o.applyGraphicsPreset(target);
		return opt.get() == target;
	}

	/** Nom de la constante d'un préréglage, ou null. */
	static String presetName(Options o) {
		try {
			return o.graphicsPreset().get().name();
		} catch (RuntimeException | LinkageError e) {
			return null;
		}
	}

	// ------------------------------------------------------------------ conversions

	/** Conversion de la cible vers le type de la valeur actuelle (2.x {@code convert}) ; null si impossible. */
	static Object convert(Object cur, Object target) {
		if (cur == null || target == null) return null;
		if (cur instanceof Boolean && target instanceof Boolean) return target;
		if (cur instanceof Integer && target instanceof Number n) return n.intValue();
		if (cur instanceof Double && target instanceof Number n) return n.doubleValue();
		if (cur instanceof Enum<?> e) {
			if (target instanceof Enum<?> t && t.getDeclaringClass() == e.getDeclaringClass()) return target;
			if (target instanceof String s) return enumValue(e, s);
		}
		return null;
	}

	/** Cible de la limite d'inactivité : MINIMIZED, sinon la première constante (2.x). */
	static Object inactivityTarget(Object cur) {
		if (!(cur instanceof Enum<?> e)) return null;
		Object v = enumValue(e, "MINIMIZED");
		if (v != null) return v;
		Object[] all = e.getDeclaringClass().getEnumConstants();
		return all.length > 0 ? all[0] : null;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object enumValue(Enum<?> cur, String name) {
		try {
			return Enum.valueOf((Class) cur.getDeclaringClass(), name);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Encodage 2.x {@code T:valeur} ; null si le type n'est pas pris en charge (option non mémorisable). */
	static String encode(Object v) {
		if (v instanceof Boolean b) return "B:" + b;
		if (v instanceof Integer i) return "I:" + i;
		if (v instanceof Double d) return "D:" + d;
		if (v instanceof Enum<?> e) return "E:" + e.name();
		return null;
	}

	/** Décodage 2.x selon le type de la valeur actuelle ; null si illisible ou de type différent. */
	static Object decode(Object cur, String s) {
		if (s == null || s.length() < 2 || s.charAt(1) != ':' || cur == null) return null;
		char t = s.charAt(0);
		String val = s.substring(2);
		try {
			return switch (t) {
				case 'B' -> cur instanceof Boolean ? Boolean.valueOf(val.trim()) : null;
				case 'I' -> cur instanceof Integer ? Integer.valueOf(val.trim()) : null;
				case 'D' -> cur instanceof Double ? Double.valueOf(val.trim()) : null;
				case 'E' -> cur instanceof Enum<?> e ? enumValue(e, val.trim()) : null;
				default -> null;
			};
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Valeur lisible pour l'écran (ON/OFF, nombre, nom d'énumération en minuscules). */
	static String display(Object v) {
		if (v instanceof Boolean b) return b ? "ON" : "OFF";
		if (v instanceof Double d) return String.format(java.util.Locale.FRANCE, "%.2f", d);
		if (v instanceof Enum<?> e) return e.name().toLowerCase(java.util.Locale.ROOT);
		return String.valueOf(v);
	}
}

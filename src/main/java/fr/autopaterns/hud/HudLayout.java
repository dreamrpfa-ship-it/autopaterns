package fr.autopaterns.hud;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

import fr.autopaterns.core.Log;
import fr.autopaterns.core.ModPaths;
import fr.autopaterns.storage.AtomicFiles;

/**
 * Disposition du HUD : réglages des 8 modules + opacité globale, persistés dans {@code autopaterns/hud.txt}.
 *
 * <p>Format (rétro-compatible 2.x, UTF-8, une ligne par module dans l'ordre de l'énumération) :
 * <pre>
 * # AutoPaterns : modules du HUD (activé, x, y, taille %, fond, toujours visible, opacité %, largeur, informations)
 * # format 3
 * global.opacity=100
 * STATUT=1,-1.0000,-1.0000,100,1,0,100,0,
 * TOUCHES=1,1.0000,0.8000,100,1,0,100,0,-mouse
 * </pre>
 * Les 6 premiers champs sont ceux de la 2.x ; les champs 7 à 9 sont ajoutés en fin de ligne (la 2.x les ignore, et
 * ignore aussi la ligne {@code global.opacity} dont le nom n'est pas un module). Un fichier 2.x (6 champs) est lu sans
 * perte ; les champs absents prennent leur valeur par défaut.
 *
 * <p>Corrections 2.x : une ligne invalide est ignorée en entier (plus d'application partielle), NaN/infini refusés,
 * écriture atomique, chemin toujours connu (plus de disposition jamais sauvegardée), sauvegarde différée
 * (au plus une écriture toutes les 0,5 s au lieu d'une écriture à chaque image de glisser).
 */
public final class HudLayout {
	public static final int FORMAT = 3;
	private static final String HEADER = "# AutoPaterns : modules du HUD (activé, x, y, taille %, fond, toujours visible, opacité %, largeur, informations)";
	private static final String GLOBAL_OPACITY = "global.opacity";
	private static final long SAVE_DELAY_NANOS = 500_000_000L;

	private final EnumMap<HudModule, HudConf> conf = new EnumMap<>(HudModule.class);
	/** Opacité globale du HUD (20–100 %). */
	private int opacity = 100;
	private boolean loaded;
	private boolean dirty;
	private long dirtyAt;

	public HudLayout() {
		reset();
	}

	public Path file() {
		return ModPaths.file("hud.txt");
	}

	public HudConf conf(HudModule m) {
		return conf.get(m);
	}

	public int opacity() {
		return opacity;
	}

	public void setOpacity(int v) {
		opacity = Math.max(HudConf.MIN_OPACITY, Math.min(100, v));
		changed();
	}

	/** Réglages par défaut (2.x) : tout activé, placement automatique, sauf TOUCHES à droite à 80 % de la hauteur. */
	public static HudConf defaults(HudModule m) {
		HudConf c = new HudConf(m);
		if (m == HudModule.TOUCHES) {
			c.fx = 1.0;
			c.fy = 0.8;
		}
		return c;
	}

	/** Remet toute la disposition par défaut (en mémoire). */
	public void reset() {
		for (HudModule m : HudModule.values()) conf.put(m, defaults(m));
		opacity = 100;
	}

	/** Réinitialise toute la disposition et la sauvegarde. */
	public void resetAll() {
		reset();
		changed();
	}

	/** Réinitialise un module et sauvegarde. */
	public void resetModule(HudModule m) {
		conf.get(m).copyFrom(defaults(m));
		changed();
	}

	public void setAllEnabled(boolean v) {
		for (HudConf c : conf.values()) c.enabled = v;
		changed();
	}

	public int enabledCount() {
		int n = 0;
		for (HudConf c : conf.values()) if (c.enabled) n++;
		return n;
	}

	// ------------------------------------------------------------------ sauvegarde automatique

	/** À appeler après chaque modification : la sauvegarde suit sous 0,5 s (ou à la fermeture de l'éditeur). */
	public void changed() {
		if (!dirty) dirtyAt = System.nanoTime();
		dirty = true;
	}

	/** Écrit le fichier si une modification attend depuis assez longtemps (appelé à chaque fin de tick). */
	public void flushIfDue() {
		if (dirty && System.nanoTime() - dirtyAt >= SAVE_DELAY_NANOS) flush();
	}

	/** Écrit immédiatement les modifications en attente. */
	public void flush() {
		if (!dirty) return;
		dirty = false;
		save();
	}

	// ------------------------------------------------------------------ fichier

	/** Lit hud.txt (format 2.x ou 3.0). Fichier absent : disposition par défaut. */
	public void load() {
		loaded = true;
		Path f = file();
		if (!Files.isRegularFile(f)) return;
		List<String> lines;
		try {
			lines = AtomicFiles.readLinesWithBackup(f);
		} catch (IOException | RuntimeException e) {
			Log.warn("hud.txt illisible : " + e);
			return;
		}
		int bad = 0;
		for (String raw : lines) {
			String line = raw.strip();
			int eq = line.indexOf('=');
			if (eq <= 0 || line.startsWith("#")) continue;
			String name = line.substring(0, eq).trim();
			String val = line.substring(eq + 1);
			if (GLOBAL_OPACITY.equals(name)) {
				try {
					opacity = Math.max(HudConf.MIN_OPACITY, Math.min(100, Integer.parseInt(val.trim())));
				} catch (NumberFormatException e) {
					bad++;
				}
				continue;
			}
			HudModule m;
			try {
				m = HudModule.valueOf(name);
			} catch (IllegalArgumentException e) {
				continue; // module inconnu (version future) : ignoré comme en 2.x
			}
			HudConf parsed = parseLine(m, val);
			if (parsed == null) bad++;
			else conf.get(m).copyFrom(parsed);
		}
		if (bad > 0) Log.warn("hud.txt : " + bad + " ligne(s) invalide(s) ignorée(s)");
	}

	/** Analyse une ligne complète ; null si un champ 2.x est invalide (la ligne est alors ignorée en entier). */
	static HudConf parseLine(HudModule m, String val) {
		String[] v = val.split(",", -1);
		if (v.length < 6) return null;
		HudConf c = defaults(m);
		try {
			c.enabled = "1".equals(v[0].trim());
			c.fx = frac(Double.parseDouble(v[1].trim()));
			c.fy = frac(Double.parseDouble(v[2].trim()));
			c.scale = Math.max(HudConf.MIN_SCALE, Math.min(HudConf.MAX_SCALE, Integer.parseInt(v[3].trim())));
			c.background = "1".equals(v[4].trim());
			c.always = "1".equals(v[5].trim());
		} catch (NumberFormatException e) {
			return null;
		}
		// Champs 3.0 (facultatifs : une valeur illisible garde sa valeur par défaut).
		if (v.length > 6) c.opacity = parseInt(v[6], 100, HudConf.MIN_OPACITY, 100);
		if (v.length > 7) c.width = m.clampWidth(parseInt(v[7], 0, 0, 10_000));
		if (v.length > 8) c.infosFromString(v[8]);
		return c;
	}

	/** Comme {@code clampFrac} 2.x, en refusant NaN/infini (→ automatique). */
	private static double frac(double d) {
		if (Double.isNaN(d) || Double.isInfinite(d) || d < 0) return -1.0;
		return Math.min(1.0, d);
	}

	private static int parseInt(String s, int def, int min, int max) {
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(s.trim())));
		} catch (NumberFormatException e) {
			return def;
		}
	}

	/** Écrit hud.txt (atomique). Jamais avant la première lecture (ne pas écraser un fichier non lu). */
	public void save() {
		if (!loaded) return;
		List<String> out = new ArrayList<>();
		out.add(HEADER);
		out.add("# format " + FORMAT);
		out.add(GLOBAL_OPACITY + "=" + opacity);
		for (HudModule m : HudModule.values()) out.add(m.name() + "=" + line(conf.get(m)));
		try {
			AtomicFiles.writeLines(file(), out);
		} catch (IOException | RuntimeException e) {
			Log.error("hud.txt non sauvegardé", e);
		}
	}

	static String line(HudConf c) {
		return (c.enabled ? "1" : "0")
			+ "," + String.format(Locale.ROOT, "%.4f", c.fx)
			+ "," + String.format(Locale.ROOT, "%.4f", c.fy)
			+ "," + c.scale
			+ "," + (c.background ? "1" : "0")
			+ "," + (c.always ? "1" : "0")
			+ "," + c.opacity
			+ "," + c.width
			+ "," + c.infosToString();
	}
}

package fr.autopaterns.waypoints;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import fr.autopaterns.core.Log;
import fr.autopaterns.core.ModPaths;
import fr.autopaterns.core.Texts;
import fr.autopaterns.storage.AtomicFiles;
import net.minecraft.client.Minecraft;

/**
 * Format texte des waypoints (TSV maison, UTF-8, compatible 2.x en lecture ET en écriture) :
 * <pre>
 * # AutoPaterns waypoints v1
 * L	dernierPatternLancé
 * F	enabled	radius	colorIndex	variants	throughWalls	cible1,cible2
 * P	nom	mine	click	hold	place	holdTicks	colorIndex	labelMode	maxDistance	visible	dupes	similarRadius	throughWalls
 * W	ctx	dim	x	y	z	type	time
 * </pre>
 * Les lignes P anciennes à 11 champs (sans dupes / similarRadius / throughWalls) restent lues.
 * Export / import : même format (lignes P et W seulement), fichier dans {@code autopaterns/waypoints-export}
 * et presse-papiers. Le format 2.x ne connaît aucun format tiers : seules ses propres lignes sont importées.
 */
final class WaypointIO {
	static final String HEADER = "# AutoPaterns waypoints v1";

	/** Rappel pour les lignes qui ne sont ni P ni W (L et F du fichier principal, lignes trop courtes). */
	interface Other {
		void line(String[] f);
	}

	private WaypointIO() {}

	static Path file() {
		return ModPaths.file("waypoints.txt");
	}

	static Path exportDir() {
		return ModPaths.file("waypoints-export");
	}

	/** Remplace tabulations et retours à la ligne par des espaces ({@code null} → ""). */
	static String clean(String s) {
		if (s == null) return "";
		return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
	}

	private static String b(boolean v) {
		return v ? "1" : "0";
	}

	/** Ligne P puis une ligne W par point (ordre d'insertion). */
	static void write(StringBuilder sb, WaypointPattern p) {
		sb.append("P\t").append(clean(p.name))
			.append('\t').append(b(p.mine))
			.append('\t').append(b(p.click))
			.append('\t').append(b(p.hold))
			.append('\t').append(b(p.place))
			.append('\t').append(p.holdTicks)
			.append('\t').append(p.colorIndex)
			.append('\t').append(p.labelMode)
			.append('\t').append(p.maxDistance)
			.append('\t').append(b(p.visible))
			.append('\t').append(b(p.dupes))
			.append('\t').append(p.similarRadius)
			.append('\t').append(b(p.throughWalls))
			.append('\n');
		for (Waypoint w : p.points) {
			sb.append("W\t").append(clean(w.ctx()))
				.append('\t').append(clean(w.dim()))
				.append('\t').append(w.x())
				.append('\t').append(w.y())
				.append('\t').append(w.z())
				.append('\t').append(w.type())
				.append('\t').append(w.time())
				.append('\n');
		}
	}

	static String write(WaypointPattern p) {
		StringBuilder sb = new StringBuilder(64 + p.points.size() * 48);
		write(sb, p);
		return sb.toString();
	}

	/** Entier tolérant : valeur par défaut si le champ est illisible (corrige la perte des points après une ligne P invalide). */
	private static int intOr(String s, int def) {
		try {
			return Integer.parseInt(s.trim());
		} catch (RuntimeException e) {
			return def;
		}
	}

	/**
	 * Analyse des lignes (fichier principal ou texte importé).
	 * Différence avec la 2.x : une ligne P dont un nombre est illisible n'est plus abandonnée (le champ prend sa valeur
	 * par défaut) ; en 2.x le pattern disparaissait et les lignes W suivantes étaient perdues avec lui.
	 */
	static List<WaypointPattern> parse(List<String> lines, Other other) {
		List<WaypointPattern> out = new ArrayList<>();
		WaypointPattern cur = null;
		for (String raw : lines) {
			if (raw == null) continue;
			String line = raw.replace("\r", "");
			if (line.isEmpty() || line.startsWith("#")) continue;
			// BOM éventuel d'un fichier édité à la main
			if (line.charAt(0) == '﻿') {
				line = line.substring(1);
				if (line.isEmpty() || line.startsWith("#")) continue;
			}
			String[] f = line.split("\t", -1);
			try {
				if ("P".equals(f[0]) && f.length >= 11) {
					WaypointPattern p = new WaypointPattern(f[1]);
					p.mine = "1".equals(f[2]);
					p.click = "1".equals(f[3]);
					p.hold = "1".equals(f[4]);
					p.place = "1".equals(f[5]);
					p.holdTicks = Math.max(1, intOr(f[6], 10));
					p.colorIndex = intOr(f[7], 0);
					p.labelMode = Math.floorMod(intOr(f[8], 1), 4);
					p.maxDistance = intOr(f[9], 128);
					p.visible = "1".equals(f[10]);
					if (f.length >= 12) p.dupes = "1".equals(f[11]);
					if (f.length >= 13) p.similarRadius = Math.max(0, intOr(f[12], 0));
					if (f.length >= 14) p.throughWalls = "1".equals(f[13]);
					out.add(p);
					cur = p;
				} else if ("W".equals(f[0]) && f.length >= 8) {
					if (cur != null) {
						char type = f[6].isEmpty() ? '?' : f[6].charAt(0);
						cur.add(new Waypoint(f[1], f[2], Integer.parseInt(f[3].trim()), Integer.parseInt(f[4].trim()),
							Integer.parseInt(f[5].trim()), type, Long.parseLong(f[7].trim())));
					}
					// ligne W avant toute ligne P : ignorée (comme en 2.x)
				} else if (other != null) {
					other.line(f);
				}
			} catch (RuntimeException ignored) {
				// ligne illisible ignorée (comme en 2.x)
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ export / import

	/** {@code [^A-Za-z0-9._ -]} → « _ », vide → « waypoints ». */
	static String safeFileName(String n) {
		String s = (n == null ? "" : n).replaceAll("[^A-Za-z0-9._ -]", "_").trim();
		return s.isEmpty() ? "waypoints" : s;
	}

	/** Nom du premier pattern d'un fichier d'export existant (null si illisible). */
	private static String patternNameIn(Path f) {
		try {
			for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
				String[] parts = l.replace("\r", "").split("\t", -1);
				if (parts.length >= 2 && "P".equals(parts[0])) return parts[1];
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
		return null;
	}

	/**
	 * Fichier d'export du pattern. Correction 2.x : si un fichier du même nom « sûr » contient déjà un AUTRE pattern
	 * (deux noms qui ne diffèrent que par des caractères spéciaux), on prend « nom (2).txt », « nom (3).txt »…
	 * au lieu de l'écraser silencieusement. Le fichier d'un même pattern est toujours remplacé (comme en 2.x).
	 */
	private static Path exportFile(Path dir, String name) {
		String base = safeFileName(name);
		Path f = dir.resolve(base + ".txt");
		for (int k = 2; k < 1000 && Files.exists(f); k++) {
			String inFile = patternNameIn(f);
			if (inFile == null || clean(name).equals(inFile)) break;
			f = dir.resolve(base + " (" + k + ").txt");
		}
		return f;
	}

	/** Exporte vers un fichier et le presse-papiers ; renvoie le message de statut. */
	static String export(Minecraft mc, WaypointPattern p) {
		String text = HEADER + "\n" + write(p);
		String fileMsg = null;
		try {
			Path dir = exportDir();
			Files.createDirectories(dir);
			Path f = exportFile(dir, p.name);
			AtomicFiles.writeString(f, text);
			fileMsg = Texts.tr("autopaterns.waypoints.export.file", f.getFileName().toString());
		} catch (IOException | RuntimeException e) {
			Log.error("export des waypoints impossible", e);
		}
		boolean clip = false;
		try {
			mc.keyboardHandler.setClipboard(text);
			clip = true;
		} catch (RuntimeException | LinkageError e) {
			Log.error("presse-papiers inaccessible (export)", e);
		}
		if (fileMsg == null && !clip) return Texts.tr("autopaterns.waypoints.export.failed");
		String where = (fileMsg == null ? "" : fileMsg)
			+ (clip ? (fileMsg != null ? " + " : "") + Texts.tr("autopaterns.waypoints.export.clipboard") : "");
		return Texts.tr("autopaterns.waypoints.export.done", p.points.size(), where);
	}

	/** Patterns contenus dans un texte importé (lignes L et F ignorées). */
	static List<WaypointPattern> parseImport(String s) {
		return parse(Arrays.asList(s.split("\n")), null);
	}
}

package fr.autopaterns.performance;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import fr.autopaterns.core.Log;
import fr.autopaterns.core.ModPaths;
import fr.autopaterns.storage.AtomicFiles;

/**
 * Fichier de restauration {@code autopaterns/graphismes-origine.txt} (format 2.x {@code clé=T:valeur}, UTF-8).
 * <ul>
 *   <li>Écrit (atomique) AVANT toute modification ; supprimé après restauration complète.</li>
 *   <li>3.0 : une ligne de commentaire en tête (ignorée par la lecture 2.x : pas de « = »), et deux entrées
 *       supplémentaires : {@code shaders=B:true|false} (état Iris d'origine, bug 2.x n°13) et
 *       {@code packs=L:id1,id2…} (packs de ressources d'origine, identifiants encodés URL).</li>
 * </ul>
 */
final class PerfBackup {
	static final String SHADERS = "shaders";
	static final String PACKS = "packs";
	private static final String HEADER = "# AutoPaterns 3.0 - réglages graphiques d'origine, restaurés automatiquement (ne pas modifier)";

	private PerfBackup() {}

	static Path file() {
		return ModPaths.file("graphismes-origine.txt");
	}

	static boolean exists() {
		return Files.isRegularFile(file());
	}

	/** Lit les entrées (ordre du fichier) ; lignes invalides ignorées comme en 2.x. */
	static LinkedHashMap<String, String> read() throws IOException {
		LinkedHashMap<String, String> out = new LinkedHashMap<>();
		for (String raw : AtomicFiles.readLinesWithBackup(file())) {
			String line = raw.strip();
			if (line.startsWith("#")) continue;
			int eq = line.indexOf('=');
			int col = line.indexOf(':', eq + 1);
			if (eq <= 0 || col < 0) continue;
			out.put(line.substring(0, eq).trim(), line.substring(eq + 1));
		}
		return out;
	}

	/** Sauvegarde existante et lisible avec au moins une entrée, sinon une table vide. */
	static LinkedHashMap<String, String> readValid() {
		if (!exists()) return new LinkedHashMap<>();
		try {
			return read();
		} catch (IOException | RuntimeException e) {
			Log.warn("sauvegarde graphique illisible : " + e);
			return new LinkedHashMap<>();
		}
	}

	/** Écriture atomique ; une table vide n'est jamais écrite (on n'écrase pas une sauvegarde par du vide). */
	static void write(Map<String, String> entries) throws IOException {
		if (entries.isEmpty()) return;
		List<String> lines = new ArrayList<>(entries.size() + 1);
		lines.add(HEADER);
		for (Map.Entry<String, String> e : entries.entrySet()) lines.add(e.getKey() + "=" + e.getValue());
		AtomicFiles.writeLines(file(), lines);
	}

	/** Met le fichier à jour : supprimé si plus rien à restaurer, réécrit sinon. */
	static void sync(Map<String, String> remaining) {
		try {
			if (remaining.isEmpty()) delete();
			else write(remaining);
		} catch (IOException | RuntimeException e) {
			Log.warn("sauvegarde graphique non écrite : " + e);
		}
	}

	static void delete() {
		Path f = file();
		for (Path p : new Path[] {f, f.resolveSibling(f.getFileName() + ".bak"), f.resolveSibling(f.getFileName() + ".tmp")}) {
			try {
				Files.deleteIfExists(p);
			} catch (IOException | RuntimeException e) {
				Log.warn("sauvegarde graphique non supprimée : " + e);
			}
		}
	}

	static String encodePacks(List<String> ids) {
		StringBuilder sb = new StringBuilder("L:");
		for (int i = 0; i < ids.size(); i++) {
			if (i > 0) sb.append(',');
			sb.append(URLEncoder.encode(ids.get(i), StandardCharsets.UTF_8));
		}
		return sb.toString();
	}

	/** Liste de packs, ou null si la valeur n'est pas au format {@code L:}. */
	static List<String> decodePacks(String v) {
		if (v == null || !v.startsWith("L:")) return null;
		List<String> out = new ArrayList<>();
		for (String tok : v.substring(2).split(",")) {
			if (tok.isEmpty()) continue;
			try {
				out.add(URLDecoder.decode(tok, StandardCharsets.UTF_8));
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
		return out;
	}
}

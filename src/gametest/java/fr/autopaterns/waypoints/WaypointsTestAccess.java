package fr.autopaterns.waypoints;

import java.util.List;

/**
 * Accès, pour les tests en jeu uniquement, au lecteur / écrivain du format {@code waypoints.txt}
 * ({@link WaypointIO} et la ligne F de {@link BlockFinder} sont package-private). Même principe que
 * {@code FishingTestAccess} : classe du source set {@code gametest} placée dans le paquet du module.
 * Ne touche ni au fichier réel ni à l'état du module.
 */
public final class WaypointsTestAccess {
	private WaypointsTestAccess() {}

	/** En-tête écrit en première ligne de {@code waypoints.txt}. */
	public static String header() {
		return WaypointIO.HEADER;
	}

	/** Analyse des lignes ; les lignes qui ne sont ni P ni W (L, F…) sont ajoutées à {@code other}. */
	public static List<WaypointPattern> parse(List<String> lines, List<String[]> other) {
		return WaypointIO.parse(lines, other::add);
	}

	/** Ligne P puis lignes W d'un pattern (format d'écriture du module). */
	public static String write(WaypointPattern p) {
		return WaypointIO.write(p);
	}

	/** Lit une ligne F (champs déjà découpés) dans {@code finder}. */
	public static void readFinder(BlockFinder finder, String[] fields) {
		finder.read(fields);
	}

	/** Ligne F écrite par {@code finder} (avec son retour à la ligne). */
	public static String writeFinder(BlockFinder finder) {
		StringBuilder sb = new StringBuilder();
		finder.write(sb);
		return sb.toString();
	}
}

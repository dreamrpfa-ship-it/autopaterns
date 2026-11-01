package fr.autopaterns.waypoints;

import fr.autopaterns.core.Texts;

/**
 * Un marqueur posé sur un bloc (2.x : record {@code Waypoint}).
 *
 * @param ctx  contexte serveur (adresse en minuscules, {@code solo/<dossier du monde>} en solo depuis la 3.0,
 *             {@code solo} pour les points enregistrés en solo par la 2.x)
 * @param dim  identifiant de dimension, ex. {@code minecraft:overworld} ({@code ?} si inconnu)
 * @param type {@link #MINE}, {@link #CLICK}, {@link #HOLD}, {@link #PLACE} ou {@link #SIMILAR}
 * @param time horodatage de création (epoch ms), conservé pour la compatibilité du fichier
 */
public record Waypoint(String ctx, String dim, int x, int y, int z, char type, long time) {
	public static final char MINE = 'M';
	public static final char CLICK = 'C';
	public static final char HOLD = 'T';
	public static final char PLACE = 'P';
	public static final char SIMILAR = 'S';

	public Waypoint {
		ctx = ctx == null ? "" : ctx;
		dim = dim == null ? "" : dim;
	}

	/** Clé d'unicité (le type n'en fait pas partie, comme en 2.x). */
	public String key() {
		return keyOf(ctx, dim, x, y, z);
	}

	public static String keyOf(String ctx, String dim, int x, int y, int z) {
		return ctx + "|" + dim + "|" + x + "|" + y + "|" + z;
	}

	/** Le point appartient-il au monde courant ({@code ctx}/{@code dim}) ? Voir {@link Waypoints#sameContext}. */
	public boolean in(String curCtx, String curDim) {
		return dim.equals(curDim) && Waypoints.sameContext(ctx, curCtx);
	}

	/** Libellé du type : « miné », « clic droit », « clic maintenu », « posé », « identique », « ? ». */
	public static String typeLabel(char c) {
		return switch (c) {
			case MINE -> Texts.tr("autopaterns.waypoints.type.mine");
			case CLICK -> Texts.tr("autopaterns.waypoints.type.click");
			case HOLD -> Texts.tr("autopaterns.waypoints.type.hold");
			case PLACE -> Texts.tr("autopaterns.waypoints.type.place");
			case SIMILAR -> Texts.tr("autopaterns.waypoints.type.similar");
			default -> "?";
		};
	}
}

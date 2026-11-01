package fr.autopaterns.waypoints;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import fr.autopaterns.core.Texts;

/**
 * Pattern de waypoints : ensemble ordonné de marqueurs + options de capture et d'affichage (identique à la 2.x).
 * Les champs sont publics comme en 2.x ; toute modification doit être suivie de {@link Waypoints#markDirty()}.
 */
public final class WaypointPattern {
	/** Couleurs ARGB proposées. */
	public static final int[] COLORS = {0xFF55FFFF, 0xFFFF5555, 0xFF55FF55, 0xFFFFFF55, 0xFFFF55FF, 0xFFFFAA00, 0xFF5555FF, 0xFFFFFFFF};
	/** Clés de traduction des noms de couleurs (Cyan, Rouge, Vert, Jaune, Magenta, Orange, Bleu, Blanc). */
	private static final String[] COLOR_KEYS = {"cyan", "red", "green", "yellow", "magenta", "orange", "blue", "white"};
	/** Distances maximales d'affichage (0 = illimitée). */
	public static final int[] DISTANCES = {32, 64, 128, 256, 512, 0};
	/** Clés des modes de libellé : Aucun, Distance, Type + distance, Nom + distance. */
	private static final String[] LABEL_KEYS = {"none", "distance", "type", "name"};
	/** Rayons « Identiques autour » (0 = désactivé). */
	public static final int[] SIMILAR_RADII = {0, 8, 16, 24, 32};

	public String name;
	public boolean mine = true;
	public boolean click;
	public boolean hold;
	public boolean place;
	public int holdTicks = 10;
	public int colorIndex;
	public int labelMode = 1;
	public int maxDistance = 128;
	public boolean visible = true;
	public boolean dupes;
	public int similarRadius;
	public boolean throughWalls = true;
	public final List<Waypoint> points = new ArrayList<>();
	private final Set<String> keys = new HashSet<>();

	public WaypointPattern(String name) {
		this.name = name;
	}

	public int color() {
		return COLORS[Math.floorMod(colorIndex, COLORS.length)];
	}

	public String colorName() {
		return colorName(colorIndex);
	}

	public static String colorName(int index) {
		return Texts.tr("autopaterns.waypoints.color." + COLOR_KEYS[Math.floorMod(index, COLOR_KEYS.length)]);
	}

	public static String labelModeName(int mode) {
		return Texts.tr("autopaterns.waypoints.label." + LABEL_KEYS[Math.floorMod(mode, LABEL_KEYS.length)]);
	}

	/** Le type d'action est-il capturé par ce pattern ? */
	public boolean accepts(char t) {
		return switch (t) {
			case Waypoint.MINE -> mine;
			case Waypoint.CLICK -> click;
			case Waypoint.HOLD -> hold;
			case Waypoint.PLACE -> place;
			case Waypoint.SIMILAR -> similarRadius > 0;
			default -> false;
		};
	}

	/** Au moins une action directe capturée (S non compté, comme en 2.x). */
	public boolean anyTrigger() {
		return mine || click || hold || place;
	}

	/** Ajoute le point ; refusé si la clé existe déjà, sauf option « Doublons ». */
	public boolean add(Waypoint w) {
		if (!keys.add(w.key()) && !dupes) return false;
		points.add(w);
		return true;
	}

	/** Retire le dernier point (la clé n'est oubliée que si aucun autre point ne la porte). */
	public Waypoint removeLast() {
		if (points.isEmpty()) return null;
		Waypoint w = points.remove(points.size() - 1);
		String k = w.key();
		boolean still = false;
		for (Waypoint o : points) {
			if (o.key().equals(k)) {
				still = true;
				break;
			}
		}
		if (!still) keys.remove(k);
		return w;
	}

	public boolean contains(Waypoint w) {
		return keys.contains(w.key());
	}

	public boolean containsKey(String key) {
		return keys.contains(key);
	}

	public void clear() {
		points.clear();
		keys.clear();
	}

	/** Ex. « Miner, Clic, Identiques(8) », ou « aucune action ». */
	public String triggersSummary() {
		List<String> parts = new ArrayList<>(5);
		if (mine) parts.add(Texts.tr("autopaterns.waypoints.trigger.mine"));
		if (click) parts.add(Texts.tr("autopaterns.waypoints.trigger.click"));
		if (hold) parts.add(Texts.tr("autopaterns.waypoints.trigger.hold"));
		if (place) parts.add(Texts.tr("autopaterns.waypoints.trigger.place"));
		if (similarRadius > 0) parts.add(Texts.tr("autopaterns.waypoints.trigger.similar", similarRadius));
		return parts.isEmpty() ? Texts.tr("autopaterns.waypoints.trigger.none") : String.join(", ", parts);
	}
}

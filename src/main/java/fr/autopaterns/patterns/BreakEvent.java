package fr.autopaterns.patterns;

/**
 * Bloc présent à l'enregistrement (état {@code state}, ex. {@code Block{minecraft:oak_log}[axis=y]}) qui a disparu
 * ou changé au tick {@code tick}. Format de ligne 2.x : {@code tick x y z état} (l'état peut contenir des espaces).
 */
public record BreakEvent(int tick, int x, int y, int z, String state) {
	public String serialize() {
		return tick + " " + x + " " + y + " " + z + " " + state;
	}

	public static BreakEvent parse(String line) {
		String[] p = line.trim().split("\\s+", 5);
		if (p.length != 5 || p[4].isBlank()) throw new IllegalArgumentException("5 valeurs attendues");
		int tick = Integer.parseInt(p[0]);
		if (tick < 0) throw new IllegalArgumentException("tick négatif");
		return new BreakEvent(tick, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]), p[4].trim());
	}
}

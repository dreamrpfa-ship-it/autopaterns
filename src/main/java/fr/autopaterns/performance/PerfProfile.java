package fr.autopaterns.performance;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import fr.autopaterns.core.Texts;

/**
 * Profils du mode performance.
 * <ul>
 *   <li><b>Équilibré</b> : coupe ce qui coûte cher sans changer l'aspect du jeu (nuages, particules réduites, ombres
 *       d'entités, vignette, fondu des chunks), distance de rendu ≤ 10, priorité haute.</li>
 *   <li><b>Performance</b> : Équilibré + particules minimales, occlusion ambiante, transparence améliorée, mélange des
 *       biomes, distance des entités 50 %, distance de rendu ≤ 8, shaders coupés.</li>
 *   <li><b>Maximum</b> : exactement le mode performance 2.x (11 réglages, distance ≤ 6, shaders et packs de
 *       ressources coupés, priorité haute).</li>
 *   <li><b>Personnalisé</b> : choix libre (dès qu'une optimisation est modifiée à la main).</li>
 * </ul>
 * La limite d'images en inactivité est toujours levée, quel que soit le profil (2.x).
 */
public enum PerfProfile {
	BALANCED("balanced",
		EnumSet.of(PerfOpt.CLOUDS, PerfOpt.PARTICLES, PerfOpt.ENTITY_SHADOWS, PerfOpt.VIGNETTE, PerfOpt.CHUNK_FADE,
			PerfOpt.RENDER_DISTANCE, PerfOpt.PRIORITY),
		Map.of(PerfOpt.RENDER_DISTANCE, 10, PerfOpt.PARTICLES, 1)),
	PERFORMANCE("performance",
		EnumSet.of(PerfOpt.CLOUDS, PerfOpt.PARTICLES, PerfOpt.ENTITY_SHADOWS, PerfOpt.VIGNETTE, PerfOpt.CHUNK_FADE,
			PerfOpt.RENDER_DISTANCE, PerfOpt.AMBIENT_OCCLUSION, PerfOpt.IMPROVED_TRANSPARENCY, PerfOpt.BIOME_BLEND,
			PerfOpt.ENTITY_DISTANCE, PerfOpt.SHADERS, PerfOpt.PRIORITY),
		Map.of(PerfOpt.RENDER_DISTANCE, 8, PerfOpt.PARTICLES, 2)),
	MAXIMUM("maximum",
		EnumSet.of(PerfOpt.IMPROVED_TRANSPARENCY, PerfOpt.CLOUDS, PerfOpt.ENTITY_SHADOWS, PerfOpt.PARTICLES,
			PerfOpt.AMBIENT_OCCLUSION, PerfOpt.BIOME_BLEND, PerfOpt.RENDER_DISTANCE, PerfOpt.ENTITY_DISTANCE,
			PerfOpt.VIGNETTE, PerfOpt.CHUNK_FADE, PerfOpt.SHADERS, PerfOpt.PRIORITY, PerfOpt.RESOURCE_PACKS),
		Map.of(PerfOpt.RENDER_DISTANCE, 6, PerfOpt.PARTICLES, 2)),
	CUSTOM("custom", EnumSet.noneOf(PerfOpt.class), Map.of());

	public final String id;
	private final EnumSet<PerfOpt> opts;
	private final Map<PerfOpt, Integer> values;

	PerfProfile(String id, EnumSet<PerfOpt> opts, Map<PerfOpt, Integer> values) {
		this.id = id;
		this.opts = opts;
		this.values = new EnumMap<>(PerfOpt.class);
		this.values.putAll(values);
	}

	public Set<PerfOpt> opts() {
		return opts.clone();
	}

	/** Valeur imposée par le profil, ou null. */
	public Integer value(PerfOpt o) {
		return values.get(o);
	}

	public String label() {
		return Texts.tr("autopaterns.performance.profile." + id);
	}

	public String description() {
		return Texts.tr("autopaterns.performance.profile." + id + ".desc");
	}

	public static PerfProfile parse(String s, PerfProfile def) {
		if (s == null) return def;
		for (PerfProfile p : values()) if (p.name().equalsIgnoreCase(s.trim()) || p.id.equalsIgnoreCase(s.trim())) return p;
		return def;
	}
}

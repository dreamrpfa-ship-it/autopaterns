package fr.autopaterns.performance;

import java.util.List;

import fr.autopaterns.core.Texts;

/**
 * Optimisations du mode performance. Chacune est un réglage activable ; certaines ont une valeur (distance, FPS…).
 * {@code key} = clé dans {@code graphismes-origine.txt} (clés 2.x conservées pour les options déjà gérées en 2.x).
 * Seules des options qui existent réellement dans Options 26.2 sont utilisées (cf. API-26.2 §11).
 */
public enum PerfOpt {
	/** Toujours appliquée (même mode performance désactivé), comme en 2.x : pas d'interrupteur. */
	INACTIVITY("inactivity", "inactivityFpsLimit", Kind.OPTION, true, 0, 0, 0, 0),
	RENDER_DISTANCE("renderDistance", "viewDistance", Kind.OPTION, false, 2, 32, 1, 6),
	SIMULATION_DISTANCE("simulationDistance", "simulationDistance", Kind.OPTION, false, 5, 32, 1, 5),
	GRAPHICS_PRESET("preset", "preset", Kind.PRESET, false, 0, 0, 0, 0),
	PARTICLES("particles", "particles", Kind.OPTION, false, 1, 2, 1, 2),
	CLOUDS("clouds", "cloudRenderMode", Kind.OPTION, false, 0, 0, 0, 0),
	AMBIENT_OCCLUSION("ao", "ao", Kind.OPTION, false, 0, 0, 0, 0),
	ENTITY_SHADOWS("entityShadows", "entityShadows", Kind.OPTION, false, 0, 0, 0, 0),
	IMPROVED_TRANSPARENCY("transparency", "improvedTransparency", Kind.OPTION, false, 0, 0, 0, 0),
	BIOME_BLEND("biomeBlend", "biomeBlendRadius", Kind.OPTION, false, 0, 0, 0, 0),
	ENTITY_DISTANCE("entityDistance", "entityDistanceScaling", Kind.OPTION, false, 0, 0, 0, 0),
	VIGNETTE("vignette", "vignette", Kind.OPTION, false, 0, 0, 0, 0),
	CHUNK_FADE("chunkFade", "chunkFade", Kind.OPTION, false, 0, 0, 0, 0),
	WEATHER("weather", "weatherRadius", Kind.OPTION, false, 0, 0, 0, 0),
	MIPMAPS("mipmaps", "mipmapLevels", Kind.OPTION, false, 0, 0, 0, 0),
	FPS_MAX("fpsMax", "framerateLimit", Kind.OPTION, false, 30, 250, 10, 120),
	RESOURCE_PACKS("packs", "packs", Kind.PACKS, false, 0, 0, 0, 0),
	SHADERS("shaders", "shaders", Kind.SHADERS, false, 0, 0, 0, 0),
	PRIORITY("priority", "priority", Kind.PRIORITY, false, 0, 0, 0, 0);

	public enum Kind { OPTION, PRESET, PACKS, SHADERS, PRIORITY }

	/**
	 * Ordre d'application des options graphiques : ordre 2.x (transparence, nuages, ombres, particules, AO, biomes,
	 * distance de rendu, distance des entités, vignette, fondu), puis les ajouts 3.0.
	 */
	public static final List<PerfOpt> OPTION_ORDER = List.of(
		IMPROVED_TRANSPARENCY, CLOUDS, ENTITY_SHADOWS, PARTICLES, AMBIENT_OCCLUSION, BIOME_BLEND, RENDER_DISTANCE,
		ENTITY_DISTANCE, VIGNETTE, CHUNK_FADE, SIMULATION_DISTANCE, WEATHER, MIPMAPS, FPS_MAX);

	/** Identifiant dans config.txt. */
	public final String id;
	/** Clé de l'option dans graphismes-origine.txt / {@link GraphicsOptions}. */
	public final String key;
	public final Kind kind;
	public final boolean always;
	public final int min, max, step, def;

	PerfOpt(String id, String key, Kind kind, boolean always, int min, int max, int step, int def) {
		this.id = id;
		this.key = key;
		this.kind = kind;
		this.always = always;
		this.min = min;
		this.max = max;
		this.step = step;
		this.def = def;
	}

	public boolean hasValue() {
		return step > 0;
	}

	public String label() {
		return Texts.tr("autopaterns.performance.opt." + id);
	}

	public String tip() {
		return Texts.tr("autopaterns.performance.opt." + id + ".tip");
	}

	/** Texte d'une valeur réglable. */
	public String formatValue(int v) {
		return switch (this) {
			case RENDER_DISTANCE, SIMULATION_DISTANCE -> Texts.tr("autopaterns.performance.value.chunks", v);
			case PARTICLES -> Texts.tr(v <= 1 ? "autopaterns.performance.value.particles.decreased" : "autopaterns.performance.value.particles.minimal");
			case FPS_MAX -> Texts.tr("autopaterns.performance.value.fps", v);
			default -> Integer.toString(v);
		};
	}

	/**
	 * Valeur cible d'une option graphique (convertie ensuite vers le type réel de l'option) :
	 * booléens, nombres ou nom de constante d'énumération.
	 */
	Object target(int value) {
		return switch (this) {
			case INACTIVITY -> "MINIMIZED";
			case IMPROVED_TRANSPARENCY, ENTITY_SHADOWS, AMBIENT_OCCLUSION, VIGNETTE -> Boolean.FALSE;
			case CLOUDS -> "OFF";
			case PARTICLES -> value <= 1 ? "DECREASED" : "MINIMAL";
			case BIOME_BLEND, MIPMAPS -> 0;
			case RENDER_DISTANCE, SIMULATION_DISTANCE, FPS_MAX -> value;
			case ENTITY_DISTANCE -> 0.5;
			case CHUNK_FADE -> 0.0;
			case WEATHER -> 3;
			default -> null;
		};
	}

	/** Option seulement abaissée (jamais augmentée), ex. distance de rendu : appliquée si la valeur actuelle est plus haute. */
	boolean onlyLower() {
		return switch (this) {
			case RENDER_DISTANCE, SIMULATION_DISTANCE, FPS_MAX, WEATHER, MIPMAPS, ENTITY_DISTANCE, BIOME_BLEND, CHUNK_FADE -> true;
			default -> false;
		};
	}
}

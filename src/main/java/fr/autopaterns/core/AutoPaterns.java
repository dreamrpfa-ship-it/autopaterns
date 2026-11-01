package fr.autopaterns.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import fr.autopaterns.config.Config;
import fr.autopaterns.fishing.FishingService;
import fr.autopaterns.hud.HudManager;
import fr.autopaterns.patterns.PatternEngine;
import fr.autopaterns.performance.PerformanceMode;
import fr.autopaterns.selling.SellingService;
import fr.autopaterns.waypoints.WaypointsModule;
import net.minecraft.client.Minecraft;

/**
 * Point d'accès unique aux systèmes du mod (créés une fois dans onInitializeClient).
 * Les modules communiquent uniquement par ces accesseurs et par les API publiques documentées.
 */
public final class AutoPaterns {
	private static AutoPaterns instance;

	private final Config config = new Config();
	private final Automation automation = new Automation();
	private final PerformanceMode performance = new PerformanceMode();
	private final PatternEngine patterns = new PatternEngine();
	private final SellingService selling = new SellingService();
	private final FishingService fishing = new FishingService();
	private final WaypointsModule waypoints = new WaypointsModule();
	private final HudManager hud = new HudManager();
	private final List<ClientModule> modules = List.of(automation, performance, patterns, selling, fishing, waypoints, hud);
	private boolean loaded;

	private AutoPaterns() {}

	static AutoPaterns create() {
		if (instance != null) throw new IllegalStateException("AutoPaterns déjà initialisé");
		instance = new AutoPaterns();
		return instance;
	}

	/** Instance unique ; ne doit pas être appelée avant onInitializeClient. */
	public static AutoPaterns get() {
		if (instance == null) throw new IllegalStateException("AutoPaterns pas encore initialisé");
		return instance;
	}

	static AutoPaterns getOrNull() {
		return instance;
	}

	public Config config() { return config; }
	public Automation automation() { return automation; }
	public PerformanceMode performance() { return performance; }
	public PatternEngine patterns() { return patterns; }
	public SellingService selling() { return selling; }
	public FishingService fishing() { return fishing; }
	public WaypointsModule waypoints() { return waypoints; }
	public HudManager hud() { return hud; }

	/** Tous les modules, dans l'ordre d'initialisation. */
	public List<ClientModule> modules() {
		return modules;
	}

	public boolean isLoaded() {
		return loaded;
	}

	/** Premier END tick : migration de l'ancien dossier, lecture de config.txt, chargement des modules. */
	void loadOnce(Minecraft mc) {
		if (loaded) return;
		loaded = true;
		migrateOldFolder();
		var props = config.load();
		for (ClientModule m : modules) {
			Guard.runAlways("chargement de " + m.name(), () -> m.onLoad(mc, props));
		}
		config.save();
	}

	/** Dossier « autopatern » des toutes premières versions renommé en « autopaterns » (comme en 2.x). */
	private static void migrateOldFolder() {
		Path old = ModPaths.legacyRoot();
		Path neu = ModPaths.root();
		try {
			if (Files.isDirectory(old) && !Files.exists(neu)) {
				Files.move(old, neu);
				Log.info("dossier « autopatern » renommé en « autopaterns » (patterns et réglages conservés)");
				Notifier.hud(Texts.tr("autopaterns.auto.migrated"));
			}
		} catch (Exception e) {
			Log.error("ancien dossier non repris", e);
		}
	}
}

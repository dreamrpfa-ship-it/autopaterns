package fr.autopaterns.performance;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import fr.autopaterns.core.ClientModule;
import fr.autopaterns.core.Log;
import fr.autopaterns.core.Notifier;
import fr.autopaterns.core.Texts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;

/**
 * Mode performance (2.x {@code PerformanceMode}) : pendant un enregistrement / une lecture, abaisse temporairement des
 * réglages graphiques, coupe les shaders Iris, retire les packs de ressources « file/ » et passe le processus en
 * priorité haute (Windows), puis restaure tout à l'arrêt. La limite d'images en inactivité est TOUJOURS levée.
 *
 * <p>Protections 3.0 :
 * <ul>
 *   <li>valeurs d'origine écrites sur disque (atomique) AVANT toute modification ; si l'écriture échoue, rien n'est
 *       modifié ;</li>
 *   <li>une sauvegarde valide déjà présente sur disque (session précédente non restaurée) fait foi : ses valeurs sont
 *       reprises comme valeurs d'origine, jamais écrasées ;</li>
 *   <li>restauration exacte : préréglage graphique restauré en premier, puis toutes les options en ordre inverse
 *       (si le préréglage a été changé, toutes les options qu'il peut toucher ont été mémorisées) ;</li>
 *   <li>état des shaders et liste des packs inclus dans la sauvegarde (récupération après crash complète) ;</li>
 *   <li>une seule activation à la fois ; échecs de restauration signalés et gardés sur disque pour une nouvelle
 *       tentative ; liste des optimisations réellement appliquées ({@link #results()}).</li>
 * </ul>
 */
public final class PerformanceMode implements ClientModule {
	public enum Status { APPLIED, ALREADY, FAILED, UNAVAILABLE }

	/** Résultat d'une optimisation lors de la dernière activation. */
	public record Result(PerfOpt opt, Status status, String detail) {}

	private enum Mode {
		/** Fin d'action normale : FPS max restauré au retour du focus si la fenêtre est en arrière-plan. */
		SESSION,
		/** Bouton « Restaurer maintenant » : tout, tout de suite. */
		IMMEDIATE,
		/** Fermeture du jeu : tout de suite, sans rechargement des packs (la liste d'options.txt n'a pas été modifiée). */
		STOPPING,
		/** Après un crash, au lancement : options tout de suite ; shaders et packs une fois le chargement terminé. */
		RECOVERY
	}

	private enum Outcome { RESTORED, SAME, FAILED, IGNORED }

	private static final String FPS_KEY = "framerateLimit";
	private static final String PRESET_KEY = "preset";
	private static final String FAST = "FAST";

	private final PerfSettings settings = new PerfSettings();
	private boolean enabled = true;
	private boolean active;
	private boolean applying;
	private boolean lastFull;
	private String lastProblem;
	/** Valeurs d'origine de l'activation en cours (clé → T:valeur), ordre d'application. */
	private final LinkedHashMap<String, String> saved = new LinkedHashMap<>();
	private int savedPriority = -1;
	private final List<Result> results = new ArrayList<>();
	private CompletableFuture<?> reload;
	/** Entrées encore à restaurer, gardées sur disque (FPS différé, récupération différée, échecs). */
	private final LinkedHashMap<String, String> pendingDisk = new LinkedHashMap<>();
	private Integer pendingFps;
	private Boolean pendingShaders;
	private List<String> pendingPacks;
	private String pendingNotice;

	public PerformanceMode() {
		// aucun accès au jeu ici
	}

	@Override
	public String name() {
		return "mode performance";
	}

	// ------------------------------------------------------------------ réglages

	public boolean isEnabled() {
		return enabled;
	}

	/** Clé config « performance » ; l'appelant sauvegarde la configuration. */
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public PerfSettings settings() {
		return settings;
	}

	public boolean isActive() {
		return active;
	}

	/** Résultats de la dernière activation (optimisations appliquées, déjà au minimum, impossibles…). */
	public List<Result> results() {
		return Collections.unmodifiableList(results);
	}

	/** La dernière activation était-elle complète (mode performance activé) ? */
	public boolean lastWasFull() {
		return lastFull;
	}

	/** Dernier problème rencontré (sauvegarde impossible, restauration incomplète), ou null. */
	public String lastProblem() {
		return lastProblem;
	}

	/** Des réglages d'origine attendent encore d'être restaurés (hors activation en cours). */
	public boolean hasPendingRestore() {
		return !pendingDisk.isEmpty();
	}

	public int appliedCount() {
		int n = 0;
		for (Result r : results) if (r.status() == Status.APPLIED) n++;
		return n;
	}

	/** Résumé selon les optimisations choisies, ex. « graphismes réduits, packs et shaders coupés, priorité haute ». */
	public String summary() {
		List<String> parts = new ArrayList<>();
		boolean gfx = false;
		for (PerfOpt o : settings.enabledList()) gfx |= o.kind == PerfOpt.Kind.OPTION || o.kind == PerfOpt.Kind.PRESET;
		if (gfx) parts.add(Texts.tr("autopaterns.performance.summary.graphics"));
		boolean packs = settings.on(PerfOpt.RESOURCE_PACKS), shaders = settings.on(PerfOpt.SHADERS);
		if (packs && shaders) parts.add(Texts.tr("autopaterns.performance.summary.packsShaders"));
		else if (packs) parts.add(Texts.tr("autopaterns.performance.summary.packs"));
		else if (shaders) parts.add(Texts.tr("autopaterns.performance.summary.shaders"));
		if (settings.on(PerfOpt.PRIORITY)) parts.add(Texts.tr("autopaterns.performance.summary.priority"));
		if (parts.isEmpty()) return Texts.tr("autopaterns.performance.summary.none");
		return String.join(", ", parts);
	}

	// ------------------------------------------------------------------ cycle de vie

	@Override
	public void onLoad(Minecraft mc, Properties config) {
		enabled = !"false".equals(config.getProperty("performance"));
		settings.load(config);
		recoverAfterCrash(mc);
	}

	@Override
	public void saveConfig(Properties config) {
		config.setProperty("performance", Boolean.toString(enabled));
		settings.save(config);
	}

	@Override
	public void onEndTick(Minecraft mc) {
		if (mc.options == null) return;
		if (pendingFps != null && mc.isWindowActive()) {
			// après le retour du focus (et après la restauration des FPS d'arrière-plan par l'automate)
			int v = pendingFps;
			pendingFps = null;
			if (setFps(mc.options, v)) {
				pendingDisk.remove(FPS_KEY);
				saveOptions(mc.options);
			}
			PerfBackup.sync(pendingDisk);
		}
		if ((pendingShaders != null || pendingPacks != null) && mc.gui.overlay() == null) {
			boolean changed = false;
			if (pendingShaders != null) {
				pendingShaders = null;
				int r = restoreShaders();
				if (r >= 0) pendingDisk.remove(PerfBackup.SHADERS);
				changed |= r > 0;
			}
			if (pendingPacks != null) {
				List<String> ids = pendingPacks;
				pendingPacks = null;
				int r = restorePacks(mc, ids);
				if (r >= 0) pendingDisk.remove(PerfBackup.PACKS);
				changed |= r > 0;
			}
			PerfBackup.sync(pendingDisk);
			if (changed && pendingNotice == null) pendingNotice = crashNotice();
		}
		if (pendingNotice != null && mc.player != null) {
			Notifier.hud(pendingNotice);
			pendingNotice = null;
		}
	}

	@Override
	public void onStopping(Minecraft mc) {
		if (active) {
			active = false;
			LinkedHashMap<String, String> e = new LinkedHashMap<>(saved);
			saved.clear();
			restoreEntries(mc, e, Mode.STOPPING);
		} else if (pendingFps != null && mc != null && mc.options != null) {
			if (setFps(mc.options, pendingFps)) {
				pendingDisk.remove(FPS_KEY);
				saveOptions(mc.options);
			}
			pendingFps = null;
			PerfBackup.sync(pendingDisk);
		}
	}

	// ------------------------------------------------------------------ application

	/**
	 * Applique le mode : toujours la limite d'inactivité ; si {@code full}, le profil / les optimisations choisies.
	 * {@code details} reçoit le résumé 2.x. Renvoie le rechargement des ressources à attendre, ou null.
	 */
	public CompletableFuture<?> apply(Minecraft mc, StringBuilder details, boolean full) {
		if (active || applying || mc == null || mc.options == null) return null;
		applying = true;
		try {
			return doApply(mc, details == null ? new StringBuilder() : details, full);
		} finally {
			applying = false;
		}
	}

	private CompletableFuture<?> doApply(Minecraft mc, StringBuilder sb, boolean full) {
		Options o = mc.options;
		results.clear();
		lastFull = full;
		lastProblem = null;
		savedPriority = -1;

		// 1. Valeurs d'origine : la sauvegarde valide déjà sur disque fait foi, puis les valeurs actuelles.
		LinkedHashMap<String, String> orig = PerfBackup.readValid();
		boolean hadDisk = !orig.isEmpty();
		if (hadDisk) Log.warn("mode performance : sauvegarde précédente non restaurée, ses valeurs restent les valeurs d'origine");

		List<PerfOpt> opts = new ArrayList<>();
		if (full) for (PerfOpt opt : PerfOpt.OPTION_ORDER) if (settings.on(opt)) opts.add(opt);
		boolean preset = full && settings.on(PerfOpt.GRAPHICS_PRESET);

		remember(orig, o, PerfOpt.INACTIVITY.key);
		if (preset) {
			remember(orig, o, PRESET_KEY);
			for (String k : GraphicsOptions.PRESET_SNAPSHOT) remember(orig, o, k);
		}
		for (PerfOpt opt : opts) {
			// FPS max : mémorisé seulement s'il va être abaissé (ne jamais restaurer une limite posée par un autre)
			if (opt == PerfOpt.FPS_MAX && !wouldChange(o, opt)) continue;
			remember(orig, o, opt.key);
		}
		if (full && settings.on(PerfOpt.SHADERS) && !orig.containsKey(PerfBackup.SHADERS)) {
			Boolean s = IrisShaders.enabled();
			if (s != null) orig.put(PerfBackup.SHADERS, "B:" + s);
		}
		if (full && settings.on(PerfOpt.RESOURCE_PACKS) && !orig.containsKey(PerfBackup.PACKS)) {
			try {
				List<String> sel = new ArrayList<>(mc.getResourcePackRepository().getSelectedIds());
				boolean anyFile = false;
				for (String id : sel) anyFile |= id.startsWith("file/");
				if (anyFile) orig.put(PerfBackup.PACKS, PerfBackup.encodePacks(sel));
			} catch (RuntimeException | LinkageError e) {
				Log.error("packs de ressources illisibles", e);
			}
		}

		// 2. Écriture du fichier de restauration AVANT toute modification.
		try {
			PerfBackup.write(orig);
		} catch (IOException | RuntimeException e) {
			Log.error("sauvegarde graphique non écrite : mode performance non appliqué", e);
			lastProblem = Texts.tr("autopaterns.performance.problem.backup");
			sb.append(Texts.tr("autopaterns.performance.detail.backupFailed"));
			return null;
		}
		clearPending(); // les entrées en attente sont reprises dans la nouvelle sauvegarde
		saved.clear();
		saved.putAll(orig);
		active = true;

		// 3. Modifications.
		Result inact = tweak(o, PerfOpt.INACTIVITY, null);
		results.add(inact);
		if (inact.status() == Status.APPLIED) sb.append(Texts.tr("autopaterns.performance.detail.inactivity"));
		if (!full) return null;
		if (!sb.isEmpty()) sb.append(", ");

		int graphics = 0;
		if (preset) {
			Result r = presetStep(o);
			results.add(r);
			if (r.status() == Status.APPLIED) graphics++;
		}
		for (PerfOpt opt : opts) {
			Result r = tweak(o, opt, opt.target(settings.value(opt)));
			results.add(r);
			if (r.status() == Status.APPLIED) graphics++;
		}
		sb.append(Texts.tr("autopaterns.performance.detail.graphics", graphics));

		if (settings.on(PerfOpt.SHADERS)) {
			Result r = shadersStep();
			results.add(r);
			if (r.status() == Status.APPLIED) sb.append(Texts.tr("autopaterns.performance.detail.shaders"));
		}
		if (settings.on(PerfOpt.PRIORITY)) {
			Result r = priorityStep();
			results.add(r);
			if (r.status() == Status.APPLIED) sb.append(Texts.tr("autopaterns.performance.detail.priority"));
		}
		// Un rechargement lancé par la restauration précédente doit se terminer avant le départ (bug 2.x n°16).
		CompletableFuture<?> wait = (reload != null && !reload.isDone()) ? reload : null;
		if (settings.on(PerfOpt.RESOURCE_PACKS)) {
			int[] removed = {0};
			Result r = packsStep(mc, removed);
			results.add(r);
			if (r.status() == Status.APPLIED) {
				sb.append(Texts.tr("autopaterns.performance.detail.packs", removed[0]));
				wait = reload;
			}
		}
		return wait;
	}

	/** Mémorise la valeur actuelle d'une option (si pas déjà connue). */
	private static void remember(Map<String, String> orig, Options o, String key) {
		if (orig.containsKey(key)) return;
		OptionInstance<?> inst = GraphicsOptions.get(o, key);
		if (inst == null) return;
		try {
			String enc = GraphicsOptions.encode(inst.get());
			if (enc != null) orig.put(key, enc);
			else Log.warn("réglage " + key + " non mémorisable (type non pris en charge) : il ne sera pas modifié");
		} catch (RuntimeException e) {
			Log.warn("réglage " + key + " illisible : " + e);
		}
	}

	private boolean wouldChange(Options o, PerfOpt opt) {
		OptionInstance<?> inst = GraphicsOptions.get(o, opt.key);
		if (inst == null) return false;
		try {
			Object cur = inst.get();
			Object v = GraphicsOptions.convert(cur, opt.target(settings.value(opt)));
			return v != null && !v.equals(cur) && !(opt.onlyLower() && notHigher(cur, v));
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static boolean notHigher(Object cur, Object target) {
		return cur instanceof Number c && target instanceof Number t && c.doubleValue() <= t.doubleValue();
	}

	/** Modifie une option (2.x {@code tweak}) : valeur relue après écriture, annulée si le jeu la refuse. */
	private Result tweak(Options o, PerfOpt opt, Object target) {
		OptionInstance<?> inst = GraphicsOptions.get(o, opt.key);
		if (inst == null) return new Result(opt, Status.UNAVAILABLE, Texts.tr("autopaterns.performance.result.missing"));
		Object cur;
		try {
			cur = inst.get();
		} catch (RuntimeException e) {
			return new Result(opt, Status.FAILED, e.getClass().getSimpleName());
		}
		if (cur == null) return new Result(opt, Status.UNAVAILABLE, Texts.tr("autopaterns.performance.result.missing"));
		Object v = opt == PerfOpt.INACTIVITY ? GraphicsOptions.inactivityTarget(cur) : GraphicsOptions.convert(cur, target);
		if (v == null) return new Result(opt, Status.UNAVAILABLE, Texts.tr("autopaterns.performance.result.unsupported"));
		if (v.equals(cur) || (opt.onlyLower() && notHigher(cur, v))) {
			return new Result(opt, Status.ALREADY, GraphicsOptions.display(cur));
		}
		if (!saved.containsKey(opt.key)) {
			// valeur d'origine non sauvegardée : on ne touche à rien (restauration impossible sinon)
			return new Result(opt, Status.UNAVAILABLE, Texts.tr("autopaterns.performance.result.notSaved"));
		}
		try {
			GraphicsOptions.set(inst, v);
			if (v.equals(inst.get())) {
				return new Result(opt, Status.APPLIED, GraphicsOptions.display(cur) + " → " + GraphicsOptions.display(v));
			}
			GraphicsOptions.set(inst, cur); // valeur refusée par le jeu : annulation (2.x)
			return new Result(opt, Status.FAILED, Texts.tr("autopaterns.performance.result.refused"));
		} catch (RuntimeException e) {
			Log.warn("réglage " + opt.key + " non modifié : " + e);
			return new Result(opt, Status.FAILED, e.getClass().getSimpleName());
		}
	}

	private Result presetStep(Options o) {
		PerfOpt opt = PerfOpt.GRAPHICS_PRESET;
		String before = GraphicsOptions.presetName(o);
		if (before == null) return new Result(opt, Status.UNAVAILABLE, Texts.tr("autopaterns.performance.result.missing"));
		if (FAST.equals(before)) return new Result(opt, Status.ALREADY, before.toLowerCase(java.util.Locale.ROOT));
		if (!saved.containsKey(PRESET_KEY)) return new Result(opt, Status.UNAVAILABLE, Texts.tr("autopaterns.performance.result.notSaved"));
		try {
			boolean ok = GraphicsOptions.applyPreset(o, FAST);
			return ok ? new Result(opt, Status.APPLIED, before.toLowerCase(java.util.Locale.ROOT) + " → fast")
				: new Result(opt, Status.FAILED, Texts.tr("autopaterns.performance.result.refused"));
		} catch (IllegalArgumentException e) {
			return new Result(opt, Status.UNAVAILABLE, Texts.tr("autopaterns.performance.result.unsupported"));
		} catch (RuntimeException | LinkageError e) {
			Log.warn("préréglage graphique non modifié : " + e);
			return new Result(opt, Status.FAILED, e.getClass().getSimpleName());
		}
	}

	private Result shadersStep() {
		PerfOpt opt = PerfOpt.SHADERS;
		if (!IrisShaders.present()) return new Result(opt, Status.UNAVAILABLE, Texts.tr("autopaterns.performance.result.noIris"));
		Boolean on = IrisShaders.enabled();
		if (on == null) return new Result(opt, Status.FAILED, Texts.tr("autopaterns.performance.result.irisError"));
		if (!on) return new Result(opt, Status.ALREADY, Texts.tr("autopaterns.performance.result.shadersOff"));
		if (!saved.containsKey(PerfBackup.SHADERS)) return new Result(opt, Status.UNAVAILABLE, Texts.tr("autopaterns.performance.result.notSaved"));
		IrisShaders.setEnabled(false);
		return Boolean.FALSE.equals(IrisShaders.enabled())
			? new Result(opt, Status.APPLIED, Texts.tr("autopaterns.performance.result.shadersCut"))
			: new Result(opt, Status.FAILED, Texts.tr("autopaterns.performance.result.irisError"));
	}

	private Result priorityStep() {
		PerfOpt opt = PerfOpt.PRIORITY;
		if (!WindowsPriority.supported()) return new Result(opt, Status.UNAVAILABLE, Texts.tr("autopaterns.performance.result.windowsOnly"));
		int old = WindowsPriority.set(WindowsPriority.HIGH_PRIORITY_CLASS);
		if (old <= 0) return new Result(opt, Status.FAILED, Texts.tr("autopaterns.performance.result.refused"));
		if (old == WindowsPriority.HIGH_PRIORITY_CLASS) return new Result(opt, Status.ALREADY, Texts.tr("autopaterns.performance.result.priorityHigh"));
		savedPriority = old;
		return new Result(opt, Status.APPLIED, Texts.tr("autopaterns.performance.result.priorityHigh"));
	}

	private Result packsStep(Minecraft mc, int[] removedOut) {
		PerfOpt opt = PerfOpt.RESOURCE_PACKS;
		try {
			var repo = mc.getResourcePackRepository();
			List<String> before = new ArrayList<>(repo.getSelectedIds());
			List<String> kept = new ArrayList<>();
			for (String id : before) if (!id.startsWith("file/")) kept.add(id);
			int removed = before.size() - kept.size();
			if (removed == 0) return new Result(opt, Status.ALREADY, Texts.tr("autopaterns.performance.result.noPacks"));
			if (!saved.containsKey(PerfBackup.PACKS)) return new Result(opt, Status.UNAVAILABLE, Texts.tr("autopaterns.performance.result.notSaved"));
			repo.setSelected(kept);
			reload = mc.reloadResourcePacks();
			removedOut[0] = removed;
			return new Result(opt, Status.APPLIED, Texts.tr("autopaterns.performance.result.packsRemoved", removed));
		} catch (RuntimeException | LinkageError e) {
			Log.error("packs de ressources non retirés", e);
			return new Result(opt, Status.FAILED, e.getClass().getSimpleName());
		}
	}

	// ------------------------------------------------------------------ restauration

	/** Restaure tout ce que l'activation en cours a modifié (2.x {@code restore}). */
	public void restore(Minecraft mc) {
		if (!active) return;
		active = false;
		LinkedHashMap<String, String> e = new LinkedHashMap<>(saved);
		saved.clear();
		restoreEntries(mc, e, Mode.SESSION);
	}

	/** Bouton « Restaurer maintenant » : activation en cours ou réglages encore en attente. Renvoie le message à afficher. */
	public String restoreNow(Minecraft mc) {
		if (active) {
			active = false;
			LinkedHashMap<String, String> e = new LinkedHashMap<>(saved);
			saved.clear();
			restoreEntries(mc, e, Mode.IMMEDIATE);
		} else {
			LinkedHashMap<String, String> e = PerfBackup.readValid();
			if (e.isEmpty()) e = new LinkedHashMap<>(pendingDisk);
			if (e.isEmpty()) return Texts.tr("autopaterns.performance.restore.nothing");
			clearPending();
			restoreEntries(mc, e, Mode.IMMEDIATE);
		}
		return pendingDisk.isEmpty()
			? "§a" + Texts.tr("autopaterns.performance.restore.done")
			: "§e" + Texts.tr("autopaterns.performance.restore.partial");
	}

	/**
	 * Restaure des valeurs d'origine : préréglage d'abord, puis les options en ordre inverse, {@code options.save()},
	 * shaders, priorité, packs. Les entrées non restaurées restent sur disque. Renvoie le nombre d'options remises
	 * à une valeur différente.
	 */
	private int restoreEntries(Minecraft mc, LinkedHashMap<String, String> entries, Mode mode) {
		Options o = mc == null ? null : mc.options;
		LinkedHashMap<String, String> keep = new LinkedHashMap<>();
		List<String> failed = new ArrayList<>();
		int restored = 0;
		if (o == null) {
			for (Map.Entry<String, String> e : entries.entrySet()) {
				if (!PerfBackup.PACKS.equals(e.getKey()) && !PerfBackup.SHADERS.equals(e.getKey())) keep.put(e.getKey(), e.getValue());
			}
		} else {
			String preset = entries.get(PRESET_KEY);
			if (preset != null) restored += restoreOne(o, PRESET_KEY, preset, keep, failed);
			List<String> keys = new ArrayList<>(entries.keySet());
			for (int i = keys.size() - 1; i >= 0; i--) {
				String k = keys.get(i);
				if (k.equals(PRESET_KEY) || k.equals(PerfBackup.SHADERS) || k.equals(PerfBackup.PACKS)) continue;
				String v = entries.get(k);
				if (k.equals(FPS_KEY) && mode == Mode.SESSION && !mc.isWindowActive()) {
					Integer fps = decodeInt(v);
					if (fps != null) {
						// fenêtre en arrière-plan : l'automate limite les FPS et restaurera sa propre valeur au retour
						// du focus ; la nôtre est appliquée juste après (onEndTick), sinon elle serait écrasée.
						pendingFps = fps;
						keep.put(k, v);
						continue;
					}
				}
				restored += restoreOne(o, k, v, keep, failed);
			}
			saveOptions(o);
		}

		String sh = entries.get(PerfBackup.SHADERS);
		if (sh != null && "B:true".equalsIgnoreCase(sh.trim())) {
			if (mode == Mode.RECOVERY) {
				pendingShaders = Boolean.TRUE;
				keep.put(PerfBackup.SHADERS, sh);
			} else if (restoreShaders() < 0) {
				failed.add(PerfBackup.SHADERS);
				keep.put(PerfBackup.SHADERS, sh);
			}
		}
		if (mode != Mode.RECOVERY && savedPriority > 0) {
			if (WindowsPriority.set(savedPriority) < 0) failed.add("priority");
			savedPriority = -1;
		}
		String pk = entries.get(PerfBackup.PACKS);
		List<String> ids = PerfBackup.decodePacks(pk);
		if (ids != null && mode != Mode.STOPPING) {
			if (mode == Mode.RECOVERY) {
				pendingPacks = ids;
				keep.put(PerfBackup.PACKS, pk);
			} else if (restorePacks(mc, ids) < 0) {
				failed.add(PerfBackup.PACKS);
				keep.put(PerfBackup.PACKS, pk);
			}
		}

		pendingDisk.clear();
		pendingDisk.putAll(keep);
		PerfBackup.sync(keep);
		if (!failed.isEmpty()) {
			lastProblem = Texts.tr("autopaterns.performance.problem.restore", String.join(", ", failed));
			Log.warn("mode performance : réglage(s) non restauré(s) : " + String.join(", ", failed));
			if (mode != Mode.STOPPING) {
				Notifier.hud("§e" + Texts.tr("autopaterns.performance.restore.failed", failed.size(), String.join(", ", failed)));
			}
		}
		return restored;
	}

	private int restoreOne(Options o, String key, String value, Map<String, String> keep, List<String> failed) {
		switch (restoreOption(o, key, value)) {
			case RESTORED -> {
				return 1;
			}
			case FAILED -> {
				failed.add(key);
				keep.put(key, value);
				return 0;
			}
			default -> {
				return 0;
			}
		}
	}

	/** 2.x {@code restoreOptions} pour une entrée : décodée selon le type actuel, écrite si différente, vérifiée. */
	private static Outcome restoreOption(Options o, String key, String value) {
		OptionInstance<?> inst = GraphicsOptions.get(o, key);
		if (inst == null) return Outcome.IGNORED; // clé inconnue : ignorée (2.x)
		try {
			Object cur = inst.get();
			Object v = GraphicsOptions.decode(cur, value);
			if (v == null) {
				Log.warn("réglage " + key + " non restauré : valeur « " + value + " » illisible");
				return Outcome.IGNORED;
			}
			if (v.equals(cur)) return Outcome.SAME;
			GraphicsOptions.set(inst, v);
			if (v.equals(inst.get())) return Outcome.RESTORED;
			Log.warn("réglage " + key + " non restauré : valeur refusée par le jeu");
			return Outcome.FAILED;
		} catch (RuntimeException e) {
			Log.warn("réglage " + key + " non restauré : " + e);
			return Outcome.FAILED;
		}
	}

	/** Remet les shaders : -1 échec, 0 rien à faire (déjà actifs ou Iris absent), 1 réactivés. */
	private static int restoreShaders() {
		if (!IrisShaders.present()) return 0;
		Boolean on = IrisShaders.enabled();
		if (Boolean.TRUE.equals(on)) return 0;
		if (!IrisShaders.setEnabled(true)) return -1;
		return Boolean.TRUE.equals(IrisShaders.enabled()) ? 1 : -1;
	}

	/** Remet la liste des packs : -1 échec, 0 déjà identique, 1 changée (rechargement lancé, non attendu comme en 2.x). */
	private int restorePacks(Minecraft mc, List<String> ids) {
		try {
			var repo = mc.getResourcePackRepository();
			List<String> cur = new ArrayList<>(repo.getSelectedIds());
			if (cur.equals(ids)) return 0;
			repo.setSelected(ids);
			reload = mc.reloadResourcePacks();
			return 1;
		} catch (RuntimeException | LinkageError e) {
			Log.error("packs de ressources non restaurés", e);
			return -1;
		}
	}

	private static boolean setFps(Options o, int v) {
		try {
			OptionInstance<Integer> f = o.framerateLimit();
			if (!Integer.valueOf(v).equals(f.get())) f.set(v);
			return Integer.valueOf(v).equals(f.get());
		} catch (RuntimeException e) {
			Log.warn("réglage framerateLimit non restauré : " + e);
			return false;
		}
	}

	private static void saveOptions(Options o) {
		try {
			o.save();
		} catch (RuntimeException e) {
			Log.warn("options non réécrites : " + e);
		}
	}

	private static Integer decodeInt(String v) {
		if (v == null || !v.startsWith("I:")) return null;
		try {
			return Integer.valueOf(v.substring(2).trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private void clearPending() {
		pendingDisk.clear();
		pendingFps = null;
		pendingShaders = null;
		pendingPacks = null;
	}

	// ------------------------------------------------------------------ récupération après crash

	/**
	 * Au premier tick : si graphismes-origine.txt existe, le jeu s'est fermé pendant une action → restauration.
	 * Message seulement si un réglage était réellement différent (2.x l'affichait toujours, bug n°12).
	 */
	private void recoverAfterCrash(Minecraft mc) {
		if (!PerfBackup.exists()) return;
		LinkedHashMap<String, String> entries;
		try {
			entries = PerfBackup.read();
		} catch (IOException | RuntimeException e) {
			Log.warn("sauvegarde graphique illisible : " + e); // fichier conservé
			return;
		}
		if (entries.isEmpty()) {
			PerfBackup.delete();
			return;
		}
		Log.info("mode performance : réglages d'une session interrompue trouvés, restauration");
		int n = restoreEntries(mc, entries, Mode.RECOVERY);
		if (n > 0) pendingNotice = crashNotice();
	}

	private static String crashNotice() {
		return Texts.tr("autopaterns.performance.recovered");
	}
}

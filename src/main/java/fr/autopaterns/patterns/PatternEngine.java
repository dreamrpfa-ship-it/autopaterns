package fr.autopaterns.patterns;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Automation;
import fr.autopaterns.core.ClientModule;
import fr.autopaterns.core.Log;
import fr.autopaterns.core.ModPaths;
import fr.autopaterns.core.Notifier;
import fr.autopaterns.core.Phase;
import fr.autopaterns.core.Texts;
import fr.autopaterns.gui.UiScreen;
import fr.autopaterns.input.KeyDef;
import fr.autopaterns.input.Keys;
import fr.autopaterns.selling.SellOrigin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;

/**
 * Module PATTERNS : pattern courant, enregistrement, lecture (recalage, checkpoints, réparation, remplacement
 * d'items, caméra), stockage et API des écrans. Les phases sont tenues par {@link Automation} ; ce module ne fait
 * que les changements internes à la lecture (APPROACH ↔ PLAYING ↔ REPAIR) et délègue les arrêts à l'automate.
 * Clé de configuration : {@code current} (nom du pattern courant, absente pour un enregistrement non nommé).
 */
public final class PatternEngine implements ClientModule {
	private static final int STATUS_PERIOD_TICKS = 10;

	private PatternStore store;
	/** Pattern courant (null = aucun). */
	private PatternStore.Loaded current;
	/** Nom du pattern courant (null = dernier enregistrement non nommé). */
	private String currentName;
	private final PatternOptions defaultOptions = new PatternOptions();
	private final Recorder recorder = new Recorder();
	private final Playback playback = new Playback(this);
	/** Touches de lancement par pattern (3.1). */
	private final PatternHotkeys hotkeys = new PatternHotkeys();
	private int statusTicks;

	public PatternEngine() {
		// aucun accès au jeu ici : tout est chargé au premier END tick (onLoad)
	}

	@Override
	public String name() {
		return "patterns";
	}

	PatternStore store() {
		if (store == null) store = new PatternStore(ModPaths.root());
		return store;
	}

	PatternStore.Loaded current() {
		return current;
	}

	private static Automation automation() {
		return AutoPaterns.get().automation();
	}

	private static void persistConfig() {
		AutoPaterns.get().config().save();
	}

	// ------------------------------------------------------------------ cycle de vie

	@Override
	public void onLoad(Minecraft mc, Properties config) {
		hotkeys.load(config);
		String raw = config.getProperty("current");
		String name = null;
		if (raw != null) {
			String r = raw.trim();
			if (PatternStore.isValidName(r) && store().exists(r)) {
				name = r; // nom existant tel quel (ex. « x (2) » importé en 2.x, que l'assainissement aurait cassé)
			} else {
				String s = PatternStore.sanitize(raw);
				if (s != null && store().exists(s)) name = s;
			}
		}
		// Pas de sauvegarde de config.txt ici : les autres modules n'ont pas encore lu leurs réglages.
		if (name == null || !loadNamed(name, false)) loadLastPattern();
	}

	@Override
	public void saveConfig(Properties config) {
		if (currentName != null) config.setProperty("current", currentName);
		else config.remove("current");
		hotkeys.save(config);
	}

	/** Fin de chaque tick client : touches de lancement des patterns. */
	@Override
	public void onEndTick(Minecraft mc) {
		String name = hotkeys.poll(mc);
		if (name != null) launchFromHotkey(mc, name);
	}

	/**
	 * Touche de lancement pressée : si rien ne tourne, charge le pattern et le lance (une fois ou en boucle) ;
	 * si une activité tourne déjà, l'arrête (même comportement que les touches Lecture / Boucle).
	 */
	private void launchFromHotkey(Minecraft mc, String name) {
		PatternHotkeys.Binding b = hotkeys.get(name);
		if (b == null) return;
		boolean loop = b.mode() == PatternHotkeys.Mode.LOOP;
		Automation au = automation();
		if (au.isBusy()) {
			au.togglePlayback(mc, loop);
			return;
		}
		if (!name.equals(currentName) || !hasPattern()) {
			String r = uiLoad(name);
			if (!r.startsWith("✔")) {
				Notifier.hud(r);
				return;
			}
		}
		Log.info("touche " + PatternHotkeys.label(b.key()) + " → pattern « " + name + " » " + (loop ? "en boucle" : "une fois"));
		Notifier.hud(Msg.t("hotkey.launch", name, modeLabel(b.mode())));
		au.togglePlayback(mc, loop);
	}

	@Override
	public void onHalt(Minecraft mc) {
		playback.stopped();
		if (recorder.active()) finishRecording(mc); // l'enregistrement en cours n'est pas perdu
	}

	@Override
	public void onStopping(Minecraft mc) {
		if (recorder.active()) finishRecording(mc);
	}

	// ------------------------------------------------------------------ ticks et images

	/** START tick, phase RECORDING. */
	public void recordTick(Minecraft mc, LocalPlayer p) {
		if (!recorder.active()) return;
		if (mc.isPaused() || mc.gui.screen() instanceof UiScreen) return;
		if (recorder.ticks() >= PatternStore.MAX_TICKS) { // au-delà, le fichier ne serait plus relisible
			automation().stopAll(mc, Msg.t("rec.limit"));
			return;
		}
		recorder.tick(mc, p);
	}

	/** START tick, phases APPROACH / PLAYING / REPAIR. */
	public void playTick(Minecraft mc, LocalPlayer p) {
		if (!hasPattern()) {
			automation().stopAll(mc, Msg.t("stop.noPattern"));
			return;
		}
		playback.tick(mc, p);
	}

	/** END tick (joueur présent). */
	public void endTick(Minecraft mc, LocalPlayer p) {
		if (hasPattern()) playback.endTick(mc, p);
		if (++statusTicks >= STATUS_PERIOD_TICKS) {
			statusTicks = 0;
			if (!Notifier.hudOk()) showStatus(p);
		}
	}

	/** Chaque image ; {@code pre} = avant la mise à jour de la caméra (anticipation nulle). */
	public void onFrame(Minecraft mc, boolean pre) {
		playback.frameHookOk = true;
		Phase ph = automation().phase();
		if (ph != Phase.RECORDING && !ph.isPlayback()) return;
		LocalPlayer p = mc.player;
		if (p == null || mc.gui.screen() != null) return;
		if (ph == Phase.RECORDING) {
			if (!mc.isPaused()) recorder.frame(CameraDriver.tickProgress(mc), p.getYRot(), p.getXRot());
			return;
		}
		if (hasPattern()) playback.frame(mc, p, pre);
	}

	/** Statut dans la barre d'action, seulement si le HUD du mod est indisponible (2.x showStatus). */
	private void showStatus(LocalPlayer p) {
		switch (automation().phase()) {
			case RECORDING -> {
				int n = recorder.ticks();
				Notifier.overlay(Msg.t("status.rec", String.format(Locale.FRANCE, "%.1f", n / 20.0), n));
			}
			case APPROACH -> {
				double d = playback.approach.distance(p);
				if (d >= 0) Notifier.overlay(Msg.t("status.approach", Texts.fmt(d)));
			}
			case PLAYING -> {
				int size = Math.max(1, patternSize());
				String mode = playback.loop ? Msg.t("status.loop", playback.repetitions + 1) : Msg.t("status.play");
				Notifier.overlay(Msg.t("status.playing", mode, 100 * playback.index / size, Texts.fmt(playback.repMaxDev)));
			}
			default -> { }
		}
	}

	// ------------------------------------------------------------------ enregistrement / lecture

	public void beginRecording(Minecraft mc, LocalPlayer p) {
		recorder.begin(p);
	}

	public void beginPlayback(Minecraft mc, LocalPlayer p, boolean loop) {
		if (!hasPattern()) {
			automation().toIdle(mc);
			Notifier.hud(Msg.t("play.none", Keys.label(KeyDef.RECORD)));
			return;
		}
		playback.begin(p, loop);
	}

	/** Sauvegarde l'enregistrement (dernier.apt + archive) ; ne change pas la phase. */
	public void finishRecording(Minecraft mc) {
		PatternStore.Loaded l = recorder.finish(mc);
		if (l == null) {
			Notifier.hud(Msg.t("rec.empty"));
			return;
		}
		current = l;
		currentName = null;
		persistConfig(); // retire la clé « current »
		int n = l.frames().size();
		String secs = String.format(Locale.FRANCE, "%.2f", n / 20.0);
		int images = l.camera() == null ? 0 : l.camera().size();
		try {
			String archive = store().save(l);
			Notifier.hud(Msg.t("rec.saved", n, secs, images, l.breaks().size(), Keys.label(KeyDef.MENU)));
			Log.info("pattern enregistré (" + n + " ticks), archive " + archive);
		} catch (IOException | RuntimeException e) {
			Notifier.hud(Msg.t("rec.unsaved", msg(e)));
			Log.error("échec de sauvegarde", e);
		}
	}

	/** Nettoyage après l'arrêt d'une lecture. */
	public void onStopped(Minecraft mc) {
		playback.stopped();
	}

	/** Reprise après une vente / corbeille déclenchée par la lecture (appelé par le module vente). */
	public void resumeAfterJob(Minecraft mc, LocalPlayer p, SellOrigin origin, int resumeIndex, String summary) {
		playback.resumeAfterJob(mc, p, origin, resumeIndex, summary);
	}

	// ------------------------------------------------------------------ accesseurs

	public boolean hasPattern() {
		return current != null && current.frames() != null && !current.frames().isEmpty();
	}

	public String currentName() {
		return currentName;
	}

	public boolean isLoop() {
		return playback.loop;
	}

	public int recordingTicks() {
		return recorder.ticks();
	}

	public int recordingBreaks() {
		return recorder.breakCount();
	}

	public boolean playbackStarted() {
		return playback.started;
	}

	public String stopSummary() {
		return playback.stopSummary();
	}

	/** Options du pattern chargé (jamais null). */
	public PatternOptions options() {
		return current != null && current.options() != null ? current.options() : defaultOptions;
	}

	/** Ajoute une ligne à diagnostic.txt (erreurs d'écriture journalisées, jamais propagées). */
	public void appendDiagnostic(String line) {
		try {
			store().appendDiagnostic(line);
		} catch (IOException | RuntimeException e) {
			Log.warn("diagnostic non écrit : " + msg(e));
		}
	}

	public int index() {
		return playback.index;
	}

	public int patternSize() {
		return hasPattern() ? current.frames().size() : 0;
	}

	// HUD
	public int approachIndex() { return playback.approach.index; }
	public boolean approachResume() { return playback.approach.resume; }

	/** Distance horizontale au point de recalage (en APPROACH), sinon -1. */
	public double approachDistance() {
		if (automation().phase() != Phase.APPROACH || !hasPattern()) return -1;
		return playback.approach.distance(Minecraft.getInstance().player);
	}

	/** Bloc en cours de réparation (phase REPAIR), sinon null. */
	public BlockPos repairPos() {
		return automation().phase() == Phase.REPAIR ? playback.repair.pos() : null;
	}

	public int repairTicks() { return playback.repair.ticks; }
	public int repetitions() { return playback.repetitions; }
	public double lastDev() { return playback.lastDev; }
	public double repMaxDev() { return playback.repMaxDev; }
	public int diagCheckpoints() { return playback.diag.checkpoints; }
	public int diagRepairs() { return playback.diag.repairs; }
	public int diagRefills() { return playback.diag.refills; }
	public int diagWalkBacks() { return playback.diag.walkBacks; }

	// ------------------------------------------------------------------ API des écrans

	/** « « nom » · 3:07 · 2 bloc(s) » (+ « · ancien format »), ou « aucun ». */
	public String patternSummary() {
		if (!hasPattern()) return Msg.t("summary.none");
		String name = currentName != null ? "« " + currentName + " »" : Msg.t("summary.unnamed");
		String suffix = current.frames().get(0).fullState() ? "" : Msg.t("summary.oldFormat");
		return Msg.t("summary", name, Texts.duration(current.frames().size()), current.breaks().size(), suffix);
	}

	public String keysHint() {
		return Msg.t("keys.hint", Keys.label(KeyDef.RECORD), Keys.label(KeyDef.PLAY), Keys.label(KeyDef.LOOP), Keys.label(KeyDef.STOP));
	}

	public List<PatternStore.Info> uiList() {
		try {
			return store().list();
		} catch (RuntimeException e) {
			Log.warn("liste des patterns illisible : " + e);
			return List.of();
		}
	}

	/** Le nom (assaini) désigne-t-il un autre pattern existant que le courant ? (confirmation de remplacement) */
	public boolean uiWouldReplace(String name) {
		String s = PatternStore.sanitize(name);
		return s != null && !s.equals(currentName) && store().exists(s);
	}

	public String uiSave(String name) {
		String s = PatternStore.sanitize(name);
		if (s == null) return Msg.t("ui.save.noName");
		if (!hasPattern()) return Msg.t("ui.save.noPattern", Keys.label(KeyDef.RECORD));
		if (automation().phase() == Phase.RECORDING) return Msg.t("ui.save.recording");
		try {
			boolean existed = store().exists(s);
			store().saveNamed(s, current);
			currentName = s;
			persistConfig();
			Log.info("pattern sauvegardé sous « " + s + " »");
			return Msg.t(existed ? "ui.save.replaced" : "ui.save.ok", s);
		} catch (IOException | RuntimeException e) {
			return Msg.t("ui.save.fail", msg(e));
		}
	}

	public String uiLoad(String name) {
		if (automation().isBusy()) return Msg.t("ui.busy", Keys.label(KeyDef.STOP));
		try {
			PatternStore.Loaded l = store().loadNamed(name);
			if (l.frames().isEmpty()) return Msg.t("ui.load.empty", name);
			setCurrent(name, l, true);
			return Msg.t("ui.load.ok", name, Texts.duration(l.frames().size()), l.breaks().size());
		} catch (IOException | RuntimeException e) {
			return Msg.t("ui.load.fail", name, msg(e));
		}
	}

	private boolean loadNamed(String name, boolean persist) {
		try {
			PatternStore.Loaded l = store().loadNamed(name);
			if (l.frames().isEmpty()) {
				Log.warn("pattern « " + name + " » vide, non rechargé");
				return false;
			}
			setCurrent(name, l, persist);
			return true;
		} catch (IOException | RuntimeException e) {
			Log.warn("pattern « " + name + " » illisible : " + msg(e));
			return false;
		}
	}

	private void setCurrent(String name, PatternStore.Loaded l, boolean persist) {
		current = l;
		currentName = name;
		if (persist) persistConfig();
		Log.info("pattern « " + name + " » chargé : " + l.frames().size() + " ticks");
	}

	private void loadLastPattern() {
		try {
			PatternStore.Loaded l = store().loadLast();
			if (l != null && !l.frames().isEmpty()) {
				current = l;
				currentName = null;
				Log.info("pattern rechargé : " + l.frames().size() + " ticks, "
					+ (l.camera() == null ? 0 : l.camera().size()) + " images de caméra.");
			}
		} catch (IOException | RuntimeException e) {
			Log.warn("dernier pattern illisible : " + msg(e));
		}
	}

	/** Renomme un pattern sauvegardé (nouveau en 3.0). */
	public String uiRename(String from, String to) {
		if (from == null) return Msg.t("ui.select");
		String s = PatternStore.sanitize(to);
		if (s == null) return Msg.t("ui.save.noName");
		if (s.equals(from)) return Msg.t("ui.rename.same");
		try {
			store().rename(from, s);
			if (from.equals(currentName)) currentName = s;
			hotkeys.rename(from, s);
			persistConfig();
			Log.info("pattern « " + from + " » renommé en « " + s + " »");
			return Msg.t("ui.rename.ok", from, s);
		} catch (IOException | RuntimeException e) {
			return Msg.t("ui.rename.fail", msg(e));
		}
	}

	public String uiDelete(String name) {
		if (name == null) return Msg.t("ui.select");
		try {
			store().delete(name);
			if (name.equals(currentName)) currentName = null; // le pattern reste en mémoire, comme en 2.x
			hotkeys.remove(name);
			persistConfig();
			Log.info("pattern « " + name + " » supprimé");
			return Msg.t("ui.delete.ok", name);
		} catch (IOException | RuntimeException e) {
			return Msg.t("ui.delete.fail", msg(e));
		}
	}

	/** Exporte {@code name} (ou le pattern courant si null) en .apt.gz et ouvre le dossier d'export. */
	public String uiExport(String name) {
		String n = name != null ? name : currentName;
		if (n == null) return Msg.t("ui.export.noName");
		try {
			Path p = store().export(n);
			openFolder(p.getParent());
			return Msg.t("ui.export.ok", p.getFileName().toString());
		} catch (IOException | RuntimeException e) {
			return Msg.t("ui.export.fail", msg(e));
		}
	}

	public List<PatternStore.Info> uiImportable() {
		try {
			return store().importable();
		} catch (RuntimeException e) {
			Log.warn("dossier d'import illisible : " + e);
			return List.of();
		}
	}

	public String uiImport(String fileName) {
		try {
			String n = store().importFile(fileName);
			Log.info("« " + fileName + " » importé sous le nom « " + n + " »");
			return Msg.t("ui.import.ok", fileName, n);
		} catch (IOException | RuntimeException e) {
			return Msg.t("ui.import.fail", msg(e));
		}
	}

	public String uiOpenImportFolder() {
		try {
			Path d = store().importDir();
			Files.createDirectories(d);
			openFolder(d);
			return Msg.t("ui.importDir.ok");
		} catch (IOException | RuntimeException e) {
			return Msg.t("ui.importDir.fail", msg(e));
		}
	}

	private static void openFolder(Path path) {
		try {
			Util.getPlatform().openPath(path);
		} catch (RuntimeException | LinkageError e) {
			Log.error("ouverture du dossier impossible", e);
		}
	}

	/** Copie des options du pattern courant. */
	public PatternOptions uiOptions() {
		return options().copy();
	}

	/** Copie des options de {@code name} (null ou nom courant = pattern courant) ; défauts si illisible. */
	public PatternOptions uiOptionsOf(String name) {
		if (name == null || name.equals(currentName)) return uiOptions();
		try {
			return store().loadOptions(name).copy();
		} catch (IOException | RuntimeException e) {
			Log.warn("réglages de « " + name + " » illisibles : " + e);
			return new PatternOptions();
		}
	}

	/**
	 * Enregistre les options du pattern courant (dans son fichier nommé, sinon dans dernier.apt). Les options en
	 * mémoire ne changent qu'après une écriture réussie (2.x les modifiait avant, d'où une divergence possible).
	 */
	public String uiSaveOptions(PatternOptions opts) {
		if (!hasPattern()) return Msg.t("ui.options.noPattern");
		PatternStore.Loaded l = new PatternStore.Loaded(current.frames(), current.camera(), current.breaks(), opts.copy());
		try {
			if (currentName != null) store().saveNamed(currentName, l);
			else store().saveLastOnly(l);
		} catch (IOException | RuntimeException e) {
			return Msg.t("ui.options.fail", msg(e));
		}
		current = l;
		AutoPaterns.get().selling().forgetSession();
		return currentName != null ? Msg.t("ui.options.okIn", currentName) : Msg.t("ui.options.ok");
	}

	/** Enregistre les options d'un pattern sauvegardé ({@code name} null ou courant = {@link #uiSaveOptions}). */
	public String uiSaveOptionsOf(String name, PatternOptions opts) {
		if (name == null || (name.equals(currentName) && hasPattern())) return uiSaveOptions(opts);
		try {
			PatternStore.Loaded l = store().loadNamed(name);
			store().saveNamed(name, new PatternStore.Loaded(l.frames(), l.camera(), l.breaks(), opts.copy()));
			AutoPaterns.get().selling().forgetSession();
			return Msg.t("ui.options.okIn", name);
		} catch (IOException | RuntimeException e) {
			return Msg.t("ui.options.fail", msg(e));
		}
	}

	// ------------------------------------------------------------------ touches de lancement (API des écrans)

	PatternHotkeys.Binding uiHotkey(String name) {
		return hotkeys.get(name);
	}

	/** « G · boucle », ou null si aucune touche. */
	String uiHotkeyLabel(String name) {
		PatternHotkeys.Binding b = hotkeys.get(name);
		return b == null ? null : PatternHotkeys.label(b.key()) + " · " + modeLabel(b.mode());
	}

	java.util.Map<String, PatternHotkeys.Binding> uiHotkeys() {
		return hotkeys.all();
	}

	/** Assigne une touche à un pattern sauvegardé ; renvoie le message à afficher. */
	String uiSetHotkey(String name, int key, PatternHotkeys.Mode mode) {
		if (name == null || !store().exists(name)) return Msg.t("hotkey.unsaved");
		String previous = hotkeys.ownerOf(key, name);
		hotkeys.set(name, key, mode);
		persistConfig();
		String label = PatternHotkeys.label(key);
		Log.info("touche " + label + " assignée au pattern « " + name + " » (" + mode + ")");
		StringBuilder sb = new StringBuilder(Msg.t("hotkey.set", label, name, modeLabel(mode)));
		if (previous != null) sb.append(' ').append(Msg.t("hotkey.moved", previous));
		List<String> c = PatternHotkeys.conflicts(Minecraft.getInstance(), key);
		if (!c.isEmpty()) sb.insert(0, "§e").append(' ').append(Msg.t("hotkey.conflict", String.join(", ", c)));
		return sb.toString();
	}

	String uiSetHotkeyMode(String name, PatternHotkeys.Mode mode) {
		if (hotkeys.get(name) == null) return null;
		hotkeys.setMode(name, mode);
		persistConfig();
		return Msg.t("hotkey.modeSet", name, modeLabel(mode));
	}

	String uiClearHotkey(String name) {
		if (!hotkeys.remove(name)) return Msg.t("hotkey.none");
		persistConfig();
		return Msg.t("hotkey.cleared", name);
	}

	static String modeLabel(PatternHotkeys.Mode mode) {
		return Msg.t(mode == PatternHotkeys.Mode.LOOP ? "hotkey.mode.loop" : "hotkey.mode.once");
	}

	static String msg(Throwable e) {
		String m = e.getMessage();
		return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
	}
}

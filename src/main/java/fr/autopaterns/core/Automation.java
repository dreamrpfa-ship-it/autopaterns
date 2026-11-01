package fr.autopaterns.core;

import java.util.Properties;
import java.util.concurrent.CompletableFuture;

import fr.autopaterns.gui.MainMenuScreen;
import fr.autopaterns.hud.HudEditScreen;
import fr.autopaterns.input.InputSim;
import fr.autopaterns.input.KeyDef;
import fr.autopaterns.input.Keys;
import fr.autopaterns.patterns.PatternEngine;
import fr.autopaterns.patterns.PatternOptions;
import fr.autopaterns.performance.PerformanceMode;
import fr.autopaterns.selling.SellOrigin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Automate central (ex-cœur d'AutoPaternsClient 2.x) : phase courante, raccourcis globaux, préparation
 * (mode performance), arrêts (normal, d'urgence, sur erreur avec action OnError) et réglages d'arrière-plan.
 * Les modules exécutent leur logique propre ; l'automate garantit qu'une seule activité tourne à la fois
 * et que tout arrêt relâche les touches et restaure les réglages.
 */
public final class Automation implements ClientModule {
	/** Délai minimum de préparation (2 s), comme en 2.x. */
	public static final int WARMUP_TICKS = 40;
	/** Au-delà, la préparation démarre même si le rechargement des ressources n'a pas répondu (corrige un blocage 2.x). */
	private static final int PREP_TIMEOUT_TICKS = 600;
	/** Cycle des FPS max en arrière-plan (0 = non limité). */
	public static final int[] BACKGROUND_FPS = {60, 30, 120, 0};

	private Phase phase = Phase.IDLE;
	private Action pendingAction;
	private CompletableFuture<?> prepFuture;
	private int prepTicks;
	private int prepWaitTicks;

	private int backgroundFps = 60;
	private Boolean savedPauseOnLostFocus;
	private Integer savedMaxFps;
	private boolean preFrameHookOk;

	@Override
	public String name() {
		return "automate";
	}

	// ------------------------------------------------------------------ état

	public Phase phase() {
		return phase;
	}

	/** Changement de phase interne à un module (ex. APPROACH → PLAYING, → SELLING, → FISHING). */
	public void setPhase(Phase p) {
		this.phase = p;
	}

	public boolean isBusy() {
		return phase != Phase.IDLE;
	}

	/** Pêche en cours (y compris une vente déclenchée par la pêche). */
	public boolean isFishing() {
		return phase == Phase.FISHING
			|| (phase == Phase.SELLING && AutoPaterns.get().selling().origin() == SellOrigin.FISHING);
	}

	public int prepTicks() {
		return prepTicks;
	}

	public int backgroundFps() {
		return backgroundFps;
	}

	// ------------------------------------------------------------------ cycle de vie

	@Override
	public void onLoad(Minecraft mc, Properties config) {
		int v;
		try {
			v = Integer.parseInt(config.getProperty("backgroundFps", "60").trim());
		} catch (RuntimeException e) {
			v = 60;
		}
		backgroundFps = 60;
		for (int allowed : BACKGROUND_FPS) if (allowed == v) backgroundFps = v; // valeur validée (corrige 2.x)
	}

	@Override
	public void saveConfig(Properties config) {
		config.setProperty("backgroundFps", Integer.toString(backgroundFps));
	}

	@Override
	public void onStopping(Minecraft mc) {
		restoreBackground(mc);
	}

	/** START_CLIENT_TICK. */
	void startTick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) {
			if (phase != Phase.IDLE) stopAll(mc, null);
			return;
		}
		// Arrêt d'urgence traité en tout premier, avant toute action automatisée de ce tick.
		if (Keys.poll(KeyDef.STOP)) {
			stopAll(mc, phase == Phase.IDLE ? Texts.tr("autopaterns.auto.nothing") : Texts.tr("autopaterns.auto.emergency"));
			return;
		}
		AutoPaterns ap = AutoPaterns.get();
		switch (phase) {
			case RECORDING -> ap.patterns().recordTick(mc, p);
			case SELLING -> ap.selling().sellStep(mc, p);
			case CAPTURE -> ap.selling().captureStep(mc, p);
			case FISHING -> ap.fishing().step(mc, p);
			case APPROACH, PLAYING, REPAIR -> ap.patterns().playTick(mc, p);
			default -> { }
		}
	}

	/** END_CLIENT_TICK. */
	void endTick(Minecraft mc) {
		AutoPaterns ap = AutoPaterns.get();
		ap.loadOnce(mc);
		Keys.tick();
		handleHotkeys(mc);
		backgroundSettings(mc);
		for (ClientModule m : ap.modules()) {
			if (m != this) m.onEndTick(mc);
		}
		LocalPlayer p = mc.player;
		if (p == null) return;
		if (phase == Phase.PREPARING) {
			boolean ready = prepFuture == null || prepFuture.isDone();
			if (!ready && ++prepWaitTicks >= PREP_TIMEOUT_TICKS) {
				Log.warn("préparation : rechargement des ressources sans réponse après 30 s, départ quand même");
				ready = true;
				prepFuture = null;
			}
			if (ready && ++prepTicks >= WARMUP_TICKS) begin(mc, pendingAction);
		}
		ap.patterns().endTick(mc, p);
	}

	/**
	 * Image rendue. {@code pre} = appel avant la mise à jour de la caméra (mixin GameRenderer#update) ;
	 * sinon appel de secours en fin de rendu du monde (LevelRenderEvents.END_MAIN), ignoré si le premier fonctionne.
	 */
	void onFrame(Minecraft mc, boolean pre) {
		if (pre) preFrameHookOk = true;
		else if (preFrameHookOk) return;
		AutoPaterns.get().patterns().onFrame(mc, pre);
	}

	// ------------------------------------------------------------------ raccourcis

	private void handleHotkeys(Minecraft mc) {
		AutoPaterns ap = AutoPaterns.get();
		if (Keys.poll(KeyDef.MENU)) {
			if (mc.gui.screen() instanceof MainMenuScreen) mc.gui.setScreen(null);
			else mc.gui.setScreen(new MainMenuScreen(null));
		}
		if (Keys.poll(KeyDef.STOP)) { // si pressée hors monde (START tick sans joueur)
			stopAll(mc, phase == Phase.IDLE ? Texts.tr("autopaterns.auto.nothing") : Texts.tr("autopaterns.auto.emergency"));
		}
		if (Keys.poll(KeyDef.HUD_EDIT)) {
			mc.gui.setScreen(new HudEditScreen(null));
		}
		if (Keys.poll(KeyDef.PERF)) {
			if (isBusy()) {
				Notifier.hud(Texts.tr("autopaterns.auto.perf.busy"));
			} else {
				PerformanceMode perf = ap.performance();
				perf.setEnabled(!perf.isEnabled());
				ap.config().save();
				Notifier.hud(Texts.tr(perf.isEnabled() ? "autopaterns.auto.perf.on" : "autopaterns.auto.perf.off", perf.summary()));
			}
		}
		if (Keys.poll(KeyDef.RECORD)) toggleRecord(mc);
		if (Keys.poll(KeyDef.LOOP)) togglePlayback(mc, true);
		if (Keys.poll(KeyDef.PLAY)) togglePlayback(mc, false);
		if (Keys.poll(KeyDef.FISHING)) toggleFishing(mc);
	}

	/** Raccourci / bouton « Enregistrer ». */
	public void toggleRecord(Minecraft mc) {
		switch (phase) {
			case RECORDING -> {
				AutoPaterns.get().patterns().finishRecording(mc);
				toIdle(mc);
			}
			case PREPARING -> stopAll(mc, Texts.tr("autopaterns.auto.prep.cancel"));
			case IDLE -> {
				if (mc.player != null) prepare(mc, Action.RECORD);
			}
			default -> Notifier.hud(Texts.tr("autopaterns.auto.busy", Keys.label(KeyDef.STOP)));
		}
	}

	/** Raccourci / bouton « Lecture » ou « Boucle » : démarre, ou arrête ce qui tourne. */
	public void togglePlayback(Minecraft mc, boolean loop) {
		PatternEngine pe = AutoPaterns.get().patterns();
		switch (phase) {
			case PREPARING -> stopAll(mc, Texts.tr("autopaterns.auto.prep.cancel"));
			case FISHING -> stopAll(mc, Texts.tr("autopaterns.auto.fishing.stopped"));
			case CAPTURE -> stopAll(mc, Texts.tr("autopaterns.auto.capture.cancel"));
			case APPROACH, PLAYING, REPAIR, SELLING -> stopAll(mc, pe.stopSummary());
			case RECORDING -> Notifier.hud(Texts.tr("autopaterns.auto.recording.busy", Keys.label(KeyDef.RECORD)));
			default -> {
				if (!pe.hasPattern()) {
					Notifier.hud(Texts.tr("autopaterns.auto.nopattern", Keys.label(KeyDef.RECORD)));
					return;
				}
				if (mc.player == null) return;
				prepare(mc, loop ? Action.LOOP : Action.PLAY_ONCE);
			}
		}
	}

	/** Raccourci pêche (K) : lance ou arrête la pêche automatique. */
	public void toggleFishing(Minecraft mc) {
		if (isFishing()) {
			stopAll(mc, Texts.tr("autopaterns.auto.fishing.off"));
		} else {
			String r = AutoPaterns.get().fishing().start(mc);
			if (r != null && !r.startsWith("✔")) Notifier.hud(r);
		}
	}

	// ------------------------------------------------------------------ démarrage

	/** Applique le mode performance (si activé : départ après 2 s) puis démarre l'action. */
	public void prepare(Minecraft mc, Action action) {
		PerformanceMode perf = AutoPaterns.get().performance();
		StringBuilder sb = new StringBuilder();
		prepFuture = perf.apply(mc, sb, perf.isEnabled());
		if (!perf.isEnabled()) {
			begin(mc, action);
			return;
		}
		pendingAction = action;
		prepTicks = 0;
		prepWaitTicks = 0;
		phase = Phase.PREPARING;
		Notifier.hud(Texts.tr("autopaterns.auto.prep", sb.toString()));
	}

	private void begin(Minecraft mc, Action action) {
		prepFuture = null;
		LocalPlayer p = mc.player;
		if (p == null || action == null) {
			toIdle(mc);
			return;
		}
		PatternEngine pe = AutoPaterns.get().patterns();
		if (action == Action.RECORD) {
			pe.beginRecording(mc, p);
			phase = Phase.RECORDING;
			Notifier.hud(Texts.tr("autopaterns.auto.rec.start", Keys.label(KeyDef.RECORD)));
		} else {
			boolean loop = action == Action.LOOP;
			Notifier.hud(Texts.tr(loop ? "autopaterns.auto.loop.start" : "autopaterns.auto.play.start"));
			pe.beginPlayback(mc, p, loop); // passe en APPROACH
		}
	}

	// ------------------------------------------------------------------ arrêts

	/** Retour au repos : phase IDLE et restauration du mode performance. */
	public void toIdle(Minecraft mc) {
		phase = Phase.IDLE;
		prepFuture = null;
		pendingAction = null;
		PerformanceMode perf = AutoPaterns.get().performance();
		if (perf.isActive()) perf.restore(mc);
	}

	/**
	 * Arrêt immédiat de toute activité (touche d'arrêt, bouton, fin normale) sans action OnError.
	 * L'enregistrement en cours est sauvegardé ; les touches sont relâchées ; les menus de vente fermés.
	 */
	public void stopAll(Minecraft mc, String msg) {
		AutoPaterns ap = AutoPaterns.get();
		switch (phase) {
			case RECORDING -> ap.patterns().finishRecording(mc);
			case APPROACH, PLAYING, REPAIR -> {
				InputSim.releaseAll(mc);
				ap.patterns().onStopped(mc);
			}
			case FISHING -> {
				InputSim.releaseAll(mc);
				ap.fishing().stop(mc);
			}
			case SELLING, CAPTURE -> {
				InputSim.releaseAll(mc);
				boolean fromFishing = ap.selling().origin() == SellOrigin.FISHING;
				ap.selling().abort(mc);
				if (fromFishing) ap.fishing().stop(mc);
			}
			default -> { }
		}
		toIdle(mc);
		if (msg != null) Notifier.hud(msg);
	}

	/**
	 * Arrêt sur erreur : {@link #stopAll} puis, si une lecture de pattern était réellement en cours
	 * (ou une vente déclenchée par elle), journal de diagnostic et action OnError du pattern
	 * (rien / déconnexion / commande).
	 */
	public void errorStop(Minecraft mc, String msg) {
		AutoPaterns ap = AutoPaterns.get();
		SellOrigin origin = ap.selling().origin();
		boolean wasPlaying = (phase.isPlayback()
			|| (phase == Phase.SELLING && (origin == SellOrigin.RESUME || origin == SellOrigin.NEXT_REP)))
			&& ap.patterns().playbackStarted();
		stopAll(mc, msg);
		PatternOptions o = ap.patterns().options();
		if (!wasPlaying || o == null) return;
		String plain = Texts.strip(msg);
		ap.patterns().appendDiagnostic("ERREUR · " + plain + " · action : " + o.onError.label);
		ClientPacketListener h = mc.getConnection();
		switch (o.onError) {
			case DISCONNECT -> {
				if (mc.hasSingleplayerServer()) {
					Notifier.hud(Texts.tr("autopaterns.auto.onerror.solo"));
				} else if (h != null) {
					Log.info("erreur → déconnexion : " + plain);
					h.getConnection().disconnect(Component.literal("AutoPaterns : " + plain));
				}
			}
			case COMMAND -> {
				String cmd = o.command == null ? "" : o.command.trim();
				while (cmd.startsWith("/")) cmd = cmd.substring(1);
				if (!cmd.isEmpty() && h != null) {
					h.sendCommand(cmd);
					Notifier.hud(Texts.tr("autopaterns.auto.onerror.cmd", cmd));
					Log.info("erreur → commande /" + cmd);
				}
			}
			default -> { }
		}
	}

	/** Mise en sécurité après une exception (appelé par {@link Guard}). */
	void forceIdle(Minecraft mc) {
		Phase was = phase;
		phase = Phase.IDLE;
		prepFuture = null;
		pendingAction = null;
		if (was == Phase.SELLING || was == Phase.CAPTURE) {
			Guard.runAlways("fermeture du menu de vente", () -> AutoPaterns.get().selling().abort(mc));
		}
		PerformanceMode perf = AutoPaterns.get().performance();
		if (perf.isActive()) Guard.runAlways("restauration du mode performance", () -> perf.restore(mc));
	}

	/** Texte d'état pour les menus. */
	public String statusLine() {
		AutoPaterns ap = AutoPaterns.get();
		return switch (phase) {
			case IDLE -> Texts.tr(ap.performance().isEnabled() ? "autopaterns.auto.status.idle.on" : "autopaterns.auto.status.idle.off");
			case PREPARING -> Texts.tr("autopaterns.auto.status.preparing");
			case RECORDING -> Texts.tr("autopaterns.auto.status.recording", Texts.duration(ap.patterns().recordingTicks()));
			case APPROACH -> Texts.tr("autopaterns.auto.status.approach");
			case PLAYING -> Texts.tr(ap.patterns().isLoop() ? "autopaterns.auto.status.loop" : "autopaterns.auto.status.play");
			case REPAIR -> Texts.tr("autopaterns.auto.status.repair");
			case SELLING -> Texts.tr(ap.selling().isTrashJob() ? "autopaterns.auto.status.trash" : "autopaterns.auto.status.sell");
			case CAPTURE -> Texts.tr("autopaterns.auto.status.capture");
			case FISHING -> Texts.tr("autopaterns.auto.status.fishing");
		};
	}

	// ------------------------------------------------------------------ arrière-plan

	/** Change la limite de FPS quand la fenêtre n'a pas le focus (60 → 30 → 120 → illimité). */
	public String cycleBackgroundFps() {
		int i = BACKGROUND_FPS.length;
		for (int k = 0; k < BACKGROUND_FPS.length; k++) if (BACKGROUND_FPS[k] == backgroundFps) i = k;
		backgroundFps = BACKGROUND_FPS[(i + 1) % BACKGROUND_FPS.length];
		AutoPaterns.get().config().save();
		return backgroundFps == 0 ? Texts.tr("autopaterns.auto.bgfps.unlimited") : Texts.tr("autopaterns.auto.bgfps.n", backgroundFps);
	}

	/**
	 * Pendant une activité : la pause sur perte de focus est désactivée (sinon l'écran de pause stopperait
	 * la lecture). En permanence : FPS limités quand la fenêtre est en arrière-plan. Valeurs d'origine
	 * restaurées au retour au repos / au focus / à la fermeture.
	 */
	private void backgroundSettings(Minecraft mc) {
		if (mc.options == null) return;
		boolean busy = phase != Phase.IDLE;
		if (busy && savedPauseOnLostFocus == null) {
			savedPauseOnLostFocus = mc.options.pauseOnLostFocus;
			mc.options.pauseOnLostFocus = false;
		} else if (!busy && savedPauseOnLostFocus != null) {
			mc.options.pauseOnLostFocus = savedPauseOnLostFocus;
			savedPauseOnLostFocus = null;
		}
		boolean focused = mc.isWindowActive();
		OptionInstance<Integer> fps = mc.options.framerateLimit();
		if (!focused && backgroundFps > 0 && savedMaxFps == null) {
			Integer v = fps.get();
			if (v != null && v > backgroundFps) {
				savedMaxFps = v;
				fps.set(backgroundFps);
			}
		} else if ((focused || backgroundFps == 0) && savedMaxFps != null) {
			fps.set(savedMaxFps);
			savedMaxFps = null;
		}
	}

	void restoreBackground(Minecraft mc) {
		if (mc == null || mc.options == null) return;
		if (savedPauseOnLostFocus != null) {
			mc.options.pauseOnLostFocus = savedPauseOnLostFocus;
			savedPauseOnLostFocus = null;
		}
		if (savedMaxFps != null) {
			mc.options.framerateLimit().set(savedMaxFps);
			savedMaxFps = null;
		}
	}
}

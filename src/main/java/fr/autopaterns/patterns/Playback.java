package fr.autopaterns.patterns;

import java.util.List;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Automation;
import fr.autopaterns.core.Log;
import fr.autopaterns.core.Notifier;
import fr.autopaterns.core.Phase;
import fr.autopaterns.core.Texts;
import fr.autopaterns.gui.UiScreen;
import fr.autopaterns.input.InputSim;
import fr.autopaterns.selling.SellOrigin;
import fr.autopaterns.selling.SellingService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;

/**
 * Lecture d'un pattern (phases APPROACH / PLAYING / REPAIR, 2.x §10-§12) : application des frames, checkpoints
 * et corrections de dérive, retour à pied, contrôle des blocs cassés, répétitions, reprise après vente, caméra.
 */
final class Playback {
	static final double DRIFT_FIX = 0.1;
	static final int CHECKPOINT_TICKS = 100;
	static final double MAX_SNAP = 1.5;
	static final int BREAK_GRACE_TICKS = 10;
	static final double DIVERGENCE = 0.001;

	private final PatternEngine engine;
	final CameraDriver camera = new CameraDriver();
	final Diagnostics diag = new Diagnostics();
	final Refill refill = new Refill();
	final Approach approach = new Approach(this);
	final Repair repair = new Repair(this);

	boolean loop;
	/** Frame à appliquer au prochain tick de lecture. */
	int index;
	/** Dernière frame appliquée (référence de la caméra). */
	int playedTick;
	/** Une répétition a réellement commencé depuis {@link #begin}. */
	boolean started;
	int repetitions;
	double lastDev, repMaxDev, runMaxDev;
	int firstDivergenceTick = -1;
	/** Prochain bloc cassé à contrôler. */
	int breakCursor;
	/** Un crochet d'image a été appelé au moins une fois (sinon caméra posée au tick). */
	boolean frameHookOk;

	Playback(PatternEngine engine) {
		this.engine = engine;
	}

	// ------------------------------------------------------------------ accès

	List<TickFrame> frames() {
		PatternStore.Loaded l = engine.current();
		return l == null || l.frames() == null ? List.of() : l.frames();
	}

	private CameraTrack track() {
		PatternStore.Loaded l = engine.current();
		return l == null ? null : l.camera();
	}

	private List<BreakEvent> breaks() {
		PatternStore.Loaded l = engine.current();
		return l == null || l.breaks() == null ? List.of() : l.breaks();
	}

	TickFrame frameAt(int i) {
		List<TickFrame> fr = frames();
		return fr.get(Math.max(0, Math.min(i, fr.size() - 1)));
	}

	private static Automation automation() {
		return AutoPaterns.get().automation();
	}

	private static Phase phase() {
		return automation().phase();
	}

	void errorStop(Minecraft mc, String msg) {
		automation().errorStop(mc, msg);
	}

	// ------------------------------------------------------------------ démarrage / arrêt

	void begin(LocalPlayer p, boolean loop) {
		this.loop = loop;
		repetitions = 0;
		runMaxDev = 0;
		repMaxDev = 0;
		lastDev = 0;
		started = false;
		index = 0;
		playedTick = 0;
		breakCursor = 0;
		firstDivergenceTick = -1;
		repair.clear();
		repair.count = 0;
		approach.index = 0;
		approach.resume = false;
		approach.begin(p);
	}

	/** Après un arrêt (touches déjà relâchées par l'automate). */
	void stopped() {
		repair.clear();
	}

	String stopSummary() {
		double dev = started ? Math.max(runMaxDev, repMaxDev) : runMaxDev; // inclut la répétition en cours (2.x l'oubliait)
		return Msg.t("stop.summary", repetitions, driftSuffix(dev, -1));
	}

	static String driftSuffix(double dev, int tick) {
		return Msg.t("drift", Texts.fmt(dev)) + (tick >= 0 ? Msg.t("drift.since", tick) : "");
	}

	// ------------------------------------------------------------------ ticks

	/** START tick en APPROACH / PLAYING / REPAIR. */
	void tick(Minecraft mc, LocalPlayer p) {
		Screen screen = mc.gui.screen();
		if (screen != null) {
			screenOpened(mc, screen);
			return;
		}
		if (frames().isEmpty()) {
			automation().stopAll(mc, Msg.t("stop.noPattern"));
			return;
		}
		switch (phase()) {
			case APPROACH -> approach.step(mc, p);
			case REPAIR -> {
				if (refill.run(mc, p, engine.options(), diag)) repair.step(mc, p);
			}
			case PLAYING -> {
				diag.detectCorrection(p);
				if (AutoPaterns.get().selling().playingTick(mc, p)) return; // une vente / corbeille a démarré
				if (phase() != Phase.PLAYING) return;
				if (index >= frames().size()) {
					finishRepetition(mc, p);
					return;
				}
				if (refill.run(mc, p, engine.options(), diag) && checkpoint(mc, p)) applyFrame(mc, p, index);
			}
			default -> { }
		}
	}

	/**
	 * Écran ouvert pendant la lecture. 2.x : arrêt « propre » pour ses 4 écrans, sinon arrêt sur erreur (action
	 * OnError) pour TOUT écran, y compris le chat et la pause. 3.0 : écrans du mod, chat et menu pause → arrêt
	 * propre sans OnError ; autres écrans (mort, interface imposée par le serveur, inventaire…) → arrêt sur erreur.
	 */
	private void screenOpened(Minecraft mc, Screen screen) {
		if (screen instanceof UiScreen) {
			automation().stopAll(mc, Msg.t("stop.menu"));
		} else if (screen instanceof ChatScreen || screen instanceof PauseScreen) {
			automation().stopAll(mc, Msg.t("stop.chat"));
		} else {
			errorStop(mc, Msg.t("stop.screen"));
		}
	}

	/** END tick (toutes phases, joueur présent). */
	void endTick(Minecraft mc, LocalPlayer p) {
		if (phase() == Phase.PLAYING) {
			diag.markEnd(p);
			index++;
			List<TickFrame> fr = frames();
			if (index < fr.size()) {
				if (!frameHookOk) { // pas de crochet d'image : caméra posée au rythme des ticks
					TickFrame f = fr.get(index);
					p.setYRot(f.yaw());
					p.setXRot(f.pitch());
				}
			} else {
				finishRepetition(mc, p);
			}
		}
		Phase ph = phase();
		if (frameHookOk && ph.isPlayback() && mc.gui.screen() == null) {
			computeCamera(ph, CameraDriver.tickProgress(mc), 0.0);
			camera.apply(p);
		}
	}

	/** Image rendue en APPROACH / PLAYING / REPAIR. */
	void frame(Minecraft mc, LocalPlayer p, boolean pre) {
		Phase ph = phase();
		float tp = CameraDriver.tickProgress(mc);
		double la = pre ? 0.0 : camera.frameTicks(mc);
		if (ph == Phase.PLAYING) diag.measureFrame();
		computeCamera(ph, tp, Math.min(CameraDriver.MAX_LOOKAHEAD, Math.max(0.0, la)));
		camera.apply(p);
	}

	private void computeCamera(Phase ph, double tp, double lookahead) {
		if (ph == Phase.PLAYING) camera.playing(frames(), track(), playedTick, tp, lookahead);
		else camera.interpolate(tp, lookahead);
	}

	// ------------------------------------------------------------------ répétitions

	void startRepetition(Minecraft mc, LocalPlayer p) {
		automation().setPhase(Phase.PLAYING);
		index = 0;
		repMaxDev = 0;
		breakCursor = 0;
		repair.count = 0;
		repair.clear();
		SellingService s = AutoPaterns.get().selling();
		diag.reset(s.sales(), s.trash());
		refill.reset();
		CameraTrack t = track();
		if (t != null) t.rewind();
		firstDivergenceTick = -1;
		started = true;
		applyFrame(mc, p, 0);
	}

	/** Fin de recalage d'une reprise : la lecture continue à la frame {@code i}. */
	void resumeAt(Minecraft mc, LocalPlayer p, int i) {
		automation().setPhase(Phase.PLAYING);
		index = i;
		started = true;
		Notifier.hud(Msg.t("approach.resumed", i));
		applyFrame(mc, p, i);
	}

	void applyFrame(Minecraft mc, LocalPlayer p, int i) {
		TickFrame f = frames().get(i);
		playedTick = i;
		PlayerOps.applyKeys(mc, f);
		Inventory inv = p.getInventory();
		if (inv.getSelectedSlot() != f.slot()) inv.setSelectedSlot(f.slot());
		camera.applyRotation(p, f.yaw(), f.pitch());
	}

	/** Contrôles avant d'appliquer la frame {@link #index} ; false = ne pas l'appliquer ce tick. */
	boolean checkpoint(Minecraft mc, LocalPlayer p) {
		int i = index;
		List<BreakEvent> brk = breaks();
		while (breakCursor < brk.size() && brk.get(breakCursor).tick() + BREAK_GRACE_TICKS <= i) {
			BreakEvent ev = brk.get(breakCursor);
			if (PlayerOps.blockStillThere(mc, ev)) {
				repair.begin(mc, p, ev, breakCursor);
				return false;
			}
			breakCursor++;
		}
		TickFrame f = frames().get(i);
		double dx = p.getX() - f.x(), dy = p.getY() - f.y(), dz = p.getZ() - f.z();
		double dev = Math.sqrt(dx * dx + dy * dy + dz * dz);
		lastDev = dev;
		if (dev > repMaxDev) repMaxDev = dev;
		if (firstDivergenceTick < 0 && dev > DIVERGENCE) {
			firstDivergenceTick = i;
			Log.info("premier écart > 1 mm au tick " + i + " : " + dev + " bloc");
		}
		if (dev > Approach.MAX_START_DISTANCE) {
			errorStop(mc, Msg.t("err.safety", Texts.fmt(dev)));
			return false;
		}
		if (dev > MAX_SNAP) {
			walkBack(mc, p, i, dev);
			return false;
		}
		if (dev > DRIFT_FIX || (i > 0 && i % CHECKPOINT_TICKS == 0 && dev > DIVERGENCE)) {
			if (!safeRestore(mc, p, f, dev > DRIFT_FIX)) return false;
			diag.checkpoints++;
		}
		return true;
	}

	/** Replace le joueur sur la frame sauf si un bloc occupe désormais cette place (arrêt sur erreur). */
	boolean safeRestore(Minecraft mc, LocalPlayer p, TickFrame f, boolean snap) {
		if (!PlayerOps.spaceFree(mc, p, f.x(), f.y(), f.z(), f.has(TickFrame.K_SNEAK))) {
			errorStop(mc, Msg.t("err.obstacle", (int) Math.floor(f.x()), (int) Math.floor(f.y()), (int) Math.floor(f.z())));
			return false;
		}
		PlayerOps.restoreState(p, f, snap);
		return true;
	}

	/** Écart trop grand pour un recalage sur place : retour à pied au dernier tick au sol. */
	private void walkBack(Minecraft mc, LocalPlayer p, int i, double dev) {
		int j = groundIndexAtOrBefore(i);
		diag.walkBacks++;
		approach.index = j;
		approach.resume = true;
		breakCursor = firstBreakAtOrAfter(j);
		InputSim.releaseAll(mc); // 2.x gardait les touches de la frame précédente un tick de plus
		Notifier.hud(Msg.t("walkback", Texts.fmt(dev), j));
		approach.begin(p);
	}

	/** Dernière frame au sol à {@code j} ou avant (les frames de l'ancien format comptent comme au sol). */
	int groundIndexAtOrBefore(int j) {
		List<TickFrame> fr = frames();
		j = Math.max(0, Math.min(j, fr.size() - 1));
		while (j > 0 && fr.get(j).state() >= 0 && !fr.get(j).onGround()) j--;
		return j;
	}

	int firstBreakAtOrAfter(int t) {
		List<BreakEvent> brk = breaks();
		for (int k = 0; k < brk.size(); k++) if (brk.get(k).tick() >= t) return k;
		return brk.size();
	}

	private void finishRepetition(Minecraft mc, LocalPlayer p) {
		InputSim.releaseAll(mc);
		repetitions++;
		runMaxDev = Math.max(runMaxDev, repMaxDev);
		SellingService s = AutoPaterns.get().selling();
		String sum = diag.summary(s.sales(), s.trash());
		Log.info("répétition " + repetitions + " : écart max " + repMaxDev + " bloc, premier écart au tick "
			+ firstDivergenceTick + " · " + sum);
		engine.appendDiagnostic(Msg.t("diag.rep", repetitions, frames().size(), Texts.fmt(repMaxDev), firstDivergenceTick, sum));
		if (loop && p != null) {
			Notifier.hud(Msg.t("rep.done", repetitions, driftSuffix(repMaxDev, firstDivergenceTick), sum));
			// 2.x ne remettait pas ces valeurs à zéro : après un retour à pied ou une vente, les répétitions
			// suivantes reprenaient au milieu du pattern au lieu de repartir du début.
			approach.index = 0;
			approach.resume = false;
			if (s.autoClean(mc, p, true, SellOrigin.NEXT_REP)) return; // nettoyage entre deux répétitions
			approach.begin(p);
		} else {
			automation().toIdle(mc);
			Notifier.hud(Msg.t("play.done", driftSuffix(repMaxDev, firstDivergenceTick), sum));
		}
	}

	/**
	 * Fin d'une vente / corbeille déclenchée par la lecture (appelé par le module vente, 2.x §14.2) :
	 * NEXT_REP → nouvelle répétition depuis le départ ; RESUME → recalage sur le dernier tick au sol
	 * avant le déclenchement puis reprise.
	 */
	void resumeAfterJob(Minecraft mc, LocalPlayer p, SellOrigin origin, int resumeIndex, String summary) {
		String sum = summary == null ? "" : summary;
		if (p == null || frames().isEmpty() || (origin != SellOrigin.RESUME && origin != SellOrigin.NEXT_REP)) {
			automation().toIdle(mc);
			if (!sum.isEmpty()) Notifier.hud(sum + ".");
			return;
		}
		if (origin == SellOrigin.NEXT_REP) {
			Notifier.hud(Msg.t("resume.next", sum));
			approach.index = 0;
			approach.resume = false;
		} else {
			int j = groundIndexAtOrBefore(Math.min(resumeIndex, frames().size() - 1));
			approach.index = j;
			approach.resume = true;
			breakCursor = firstBreakAtOrAfter(j);
			Notifier.hud(Msg.t("resume.at", sum, j));
		}
		approach.begin(p);
	}
}

package fr.autopaterns.fishing;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeoutException;

import fr.autopaterns.core.Log;
import fr.autopaterns.core.Notifier;
import fr.autopaterns.core.Texts;
import fr.autopaterns.input.InputSim;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Robot de pêche (reconstruction fidèle de la 2.x) : machine à états CAST → CASTING → WAIT → GAME → AFTER (→ REEL),
 * captures de l'écran planifiées, calibrage et repli de la méthode de capture, décisions du contrôleur.
 * Toutes les méthodes s'exécutent sur le thread client (les callbacks de capture y sont ramenés par
 * {@link ScreenGrab}) : aucun état partagé entre threads.
 */
final class FishingBot {
	// ------------------------------------------------------------------ constantes 2.x
	static final int[] RECAST_TICKS = {10, 20, 30, 40, 60};
	static final int[] WAIT_SECONDS = {30, 45, 60, 90, 120, 300};
	static final int[] LEADS = {2, 4, 6, 8, 10, 12};
	static final int[] CLICK_RATES = {5, 7, 10, 14, 20, 0};

	/** 3.0 : délai maximal d'une capture avant annulation (2.x : échec direct après 100 ticks). */
	static final int CAPTURE_TIMEOUT = 40;
	/** 3.0 : captures sans réponse consécutives (en capture d'écran) avant l'arrêt « ne répond pas ». */
	static final int MAX_TIMEOUTS = 3;
	/** Délai de réponse du calibrage GPU (2.x : 60 ticks = 3 s). */
	static final int CALIB_TIMEOUT = 60;
	static final int MAX_VERIFY = 5;
	static final int MAX_DIAG = 20;
	/** 3.0 : remontées consécutives sans disparition du bouchon avant l'arrêt (corrige la boucle infinie 2.x). */
	static final int MAX_REEL_FAILS = 5;
	private static final int MAX_ERROR_LOG = 200_000;

	enum State { CAST, CASTING, WAIT, GAME, AFTER, REEL }

	enum Status { RUNNING, FAILED, CLEAN }

	/** Dernière image reçue (pour le diagnostic). Le tableau n'est jamais modifié après sa création. */
	private record Frame(int[] px, int w, int h, int ox, int oy, int scale) {}

	// ------------------------------------------------------------------ réglages (config.txt)
	int recastTicks = 20;   // fishRecast
	int waitSeconds = 60;   // fishWait
	int lead = 6;           // fishLead
	int clickRate = 10;     // fishClicks (≤ 0 = clic droit maintenu)
	boolean clean = true;   // fishClean
	boolean diagnostic;     // fishDiag
	boolean gpu = true;     // fishGpu (3.0) : copie GPU autorisée (sinon capture d'écran directement)

	// ------------------------------------------------------------------ exécution
	private int lastClick = -100;
	private int clicks;
	private int requested;
	private State state = State.CAST;
	private int ticks;
	private int tickCount;
	private int retries;
	private int noGauge;
	private int gameTicks;
	private int diagSaved;
	private int catches;
	private int misses;
	private int invBefore;
	private int reelFails;
	private String failure = "";
	private final FishControl control = new FishControl();
	private boolean holding;

	private FishVision.Result latest = FishVision.Result.NONE;
	private int latestStamp = -1;
	private int usedStamp = -1;
	private boolean capturePending;
	private ScreenGrab.Pending pending;
	private int captureSeq;
	private FishVision.Result hint;
	private Frame lastFrame;

	private int captureMode;
	private final StringBuilder captureErrors = new StringBuilder();
	private String lastErrorShort = "";

	private int calib;
	private boolean gpuBottomUp = true;
	private int[] calibShot, calibGpuTop, calibGpuBottom;
	private int calibW, calibH;
	private int calibRequested;
	private boolean calibBusy;
	private boolean verifying;
	private int calibToken;
	private final ScreenGrab.Pending[] calibHandles = new ScreenGrab.Pending[3];
	private int verifyTries;
	private int lastVerify = -100;
	private boolean orientationOk;
	private double calibDiffTop = Double.NaN;
	private double calibDiffBottom = Double.NaN;

	// statistiques de session (3.0, affichées dans l'écran)
	private int captures;
	private int errorCount;
	private int timeouts;
	private int consecutiveTimeouts;
	private int analysisErrors;
	private boolean diagBroken;
	private boolean started;

	// ------------------------------------------------------------------ persistance

	void load(Properties p) {
		recastTicks = clamp(intOf(p, "fishRecast", recastTicks), 1, 1200);
		waitSeconds = clamp(intOf(p, "fishWait", waitSeconds), 1, 3600);
		lead = clamp(intOf(p, "fishLead", lead), 0, 40);
		clickRate = clamp(intOf(p, "fishClicks", clickRate), 0, 20);
		clean = !"false".equals(p.getProperty("fishClean"));
		diagnostic = "true".equals(p.getProperty("fishDiag"));
		gpu = !"false".equals(p.getProperty("fishGpu"));
	}

	void save(Properties p) {
		p.setProperty("fishRecast", Integer.toString(recastTicks));
		p.setProperty("fishWait", Integer.toString(waitSeconds));
		p.setProperty("fishLead", Integer.toString(lead));
		p.setProperty("fishClicks", Integer.toString(clickRate));
		p.setProperty("fishClean", Boolean.toString(clean));
		p.setProperty("fishDiag", Boolean.toString(diagnostic));
		p.setProperty("fishGpu", Boolean.toString(gpu));
	}

	private static int intOf(Properties p, String key, int def) {
		try {
			return Integer.parseInt(p.getProperty(key, Integer.toString(def)).trim());
		} catch (RuntimeException e) {
			return def;
		}
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	// ------------------------------------------------------------------ cycle de vie

	void start() {
		cancelCapture();
		cancelCalibration();
		state = State.CAST;
		noGauge = retries = ticks = 0;
		diagSaved = misses = catches = 0;
		clicks = gameTicks = reelFails = 0;
		failure = "";
		holding = false;
		hint = null;
		lastFrame = null;
		latest = FishVision.Result.NONE;
		usedStamp = latestStamp; // un résultat d'une session précédente n'est jamais « frais »
		captureMode = gpu ? 0 : 1;
		calib = gpu ? 0 : 2;
		orientationOk = false;
		verifyTries = 0;
		lastVerify = -100;
		calibDiffTop = calibDiffBottom = Double.NaN;
		captureErrors.setLength(0);
		lastErrorShort = "";
		captures = errorCount = timeouts = consecutiveTimeouts = analysisErrors = 0;
		diagBroken = false;
		control.reset();
		started = true;
	}

	/** Reprise après une vente / corbeille d'origine pêche. */
	void resume() {
		state = State.CAST;
		ticks = 0;
		reelFails = 0;
		cancelCapture(); // une capture lancée avant la vente montrerait le conteneur
	}

	/** Écran ouvert : aucune capture ne doit montrer l'interface. */
	void pause() {
		if (capturePending) cancelCapture();
	}

	/** Arrêt complet : captures et calibrage annulés (tampons GPU libérés). Les compteurs restent affichables. */
	void stop() {
		cancelCapture();
		cancelCalibration();
		if (calib == 1) calib = 0;
		holding = false;
	}

	private void cancelCapture() {
		captureSeq++;
		capturePending = false;
		if (pending != null) pending.cancel();
		pending = null;
	}

	private void cancelCalibration() {
		calibToken++;
		for (int i = 0; i < calibHandles.length; i++) {
			if (calibHandles[i] != null) calibHandles[i].cancel();
			calibHandles[i] = null;
		}
		calibBusy = false;
		verifying = false;
	}

	// ------------------------------------------------------------------ accesseurs

	String failure() { return failure; }
	int catches() { return catches; }
	int misses() { return misses; }
	int clicks() { return clicks; }
	int captures() { return captures; }
	int errorCount() { return errorCount; }
	int timeouts() { return timeouts; }
	int analysisErrors() { return analysisErrors; }
	String lastErrorShort() { return lastErrorShort; }
	boolean diagBroken() { return diagBroken; }
	State state() { return state; }

	/** Texte du HUD. */
	String detail() {
		String s = switch (state) {
			case CAST, CASTING -> Texts.tr("autopaterns.fishing.hud.cast");
			case WAIT -> Texts.tr("autopaterns.fishing.hud.wait", ticks / 20);
			case GAME -> Texts.tr("autopaterns.fishing.hud.game", holding ? "↑" : "↓", clicks);
			case AFTER -> Texts.tr("autopaterns.fishing.hud.after");
			case REEL -> Texts.tr("autopaterns.fishing.hud.reel");
		};
		return s + Texts.tr("autopaterns.fishing.hud.catches", catches)
			+ (misses > 0 ? Texts.tr("autopaterns.fishing.hud.misses", misses) : "");
	}

	/** Méthode de capture courante (libellés 2.x). */
	String captureMethod() {
		if (captureMode == 0) {
			return calib == 2 ? Texts.tr("autopaterns.fishing.method.gpu") : Texts.tr("autopaterns.fishing.method.gpuCalib");
		}
		if (captureMode == 1) return Texts.tr("autopaterns.fishing.method.reduced");
		if (captureMode == 2) return Texts.tr("autopaterns.fishing.method.full");
		return Texts.tr("autopaterns.fishing.method.none");
	}

	/** État du calibrage de la copie GPU (écran de la pêche). */
	String calibrationState() {
		if (!started) return Texts.tr(gpu ? "autopaterns.fishing.calib.pending" : "autopaterns.fishing.calib.disabled");
		if (captureMode != 0) {
			if (!gpu) return Texts.tr("autopaterns.fishing.calib.disabled");
			return Texts.tr("autopaterns.fishing.calib.abandoned", lastErrorShort.isEmpty() ? "—" : lastErrorShort);
		}
		if (calib == 0) return Texts.tr("autopaterns.fishing.calib.pending");
		if (calib == 1) return Texts.tr("autopaterns.fishing.calib.running");
		String diffs = Double.isNaN(calibDiffTop) ? "—"
			: String.format(Locale.ROOT, "%.1f / %.1f", calibDiffTop, calibDiffBottom);
		String rows = Texts.tr(gpuBottomUp ? "autopaterns.fishing.calib.bottomUp" : "autopaterns.fishing.calib.topDown");
		if (orientationOk) return Texts.tr("autopaterns.fishing.calib.ok", rows, diffs);
		return Texts.tr("autopaterns.fishing.calib.unsure", rows, verifyTries, MAX_VERIFY, diffs);
	}

	// ------------------------------------------------------------------ tick

	/**
	 * Un tick de pêche (START tick, phase FISHING, aucun écran ouvert).
	 * {@code click} : un clic droit bref (clickCount de la touche « utiliser »).
	 */
	Status tick(Minecraft mc, LocalPlayer player, KeyMapping useKey, Runnable click) {
		tickCount++;
		ticks++;
		if (!failure.isEmpty()) return Status.FAILED;
		if (capturePending && tickCount - requested > CAPTURE_TIMEOUT) captureTimedOut();
		if (!failure.isEmpty()) return Status.FAILED;
		if (calibrate(mc) && captureDue() && !capturePending) requestCapture(mc);
		if (!failure.isEmpty()) return Status.FAILED;

		FishVision.Result fresh = null;
		if (latestStamp != usedStamp) {
			usedStamp = latestStamp;
			fresh = latest;
		}

		switch (state) {
			case CAST -> {
				if (!holdsRod(player)) return fail(Texts.tr("autopaterns.fishing.fail.rod"));
				if (player.fishing != null) {
					go(State.WAIT); // ligne déjà lancée
				} else {
					InputSim.setKey(useKey, false);
					click.run();
					go(State.CASTING);
				}
			}
			case CASTING -> {
				if (player.fishing != null) {
					retries = 0;
					reelFails = 0;
					go(State.WAIT);
				} else if (ticks > 30) {
					if (++retries > 3) return fail(Texts.tr("autopaterns.fishing.fail.cast"));
					go(State.CAST);
				}
			}
			case WAIT -> {
				if (fresh != null && fresh.found() && fresh.hasZone()) {
					invBefore = inventoryTotal(player);
					control.reset();
					clicks = 0;
					noGauge = 0;
					gameTicks = 0;
					go(State.GAME);
					saveDiagnostic(fresh, "debut");
				} else if (player.fishing == null && ticks > 40) {
					go(State.AFTER); // bouchon disparu
				} else if (ticks > waitSeconds * 20) {
					go(State.REEL); // rien n'a mordu
				}
			}
			case GAME -> {
				gameTicks++;
				if (fresh != null) {
					if (fresh.found() && fresh.hasZone()) {
						noGauge = 0;
						double zone = fresh.norm(fresh.zoneCenter());
						double fish = fresh.hasFish() ? fresh.norm(fresh.fish()) : Double.NaN;
						holding = control.step(zone, fish, effectiveLead());
						if (gameTicks % 40 == 0) saveDiagnostic(fresh, "jeu");
					} else {
						noGauge++;
						holding = false;
					}
				}
				if (clickRate <= 0) {
					InputSim.setKey(useKey, holding); // clic droit maintenu
				} else {
					InputSim.setKey(useKey, false);
					int interval = Math.max(1, Math.round(20f / clickRate));
					if (holding && tickCount - lastClick >= interval) {
						click.run();
						lastClick = tickCount;
						clicks++;
					}
				}
				if (noGauge >= 6 || gameTicks > 1800) { // fin du combat ou 90 s
					holding = false;
					InputSim.setKey(useKey, false);
					if (inventoryTotal(player) > invBefore) catches++;
					else misses++;
					go(State.AFTER);
				}
			}
			case AFTER -> {
				if (ticks >= recastTicks) {
					if (player.fishing != null) {
						go(State.REEL);
					} else if (clean) {
						go(State.CAST);
						return Status.CLEAN;
					} else {
						go(State.CAST);
					}
				}
			}
			case REEL -> {
				if (ticks == 1) {
					InputSim.setKey(useKey, false);
					click.run(); // un clic pour ramener la ligne
				} else if (player.fishing == null || ticks > 30) {
					if (player.fishing == null) {
						reelFails = 0;
					} else if (++reelFails >= MAX_REEL_FAILS) {
						return fail(Texts.tr("autopaterns.fishing.fail.reel"));
					}
					go(State.AFTER);
					ticks = recastTicks; // relance immédiate au tick suivant
				}
			}
		}
		return Status.RUNNING;
	}

	private void go(State s) {
		state = s;
		ticks = 0;
	}

	private Status fail(String msg) {
		failure = msg;
		return Status.FAILED;
	}

	/**
	 * Horizon d'anticipation exprimé en mesures. {@code lead} est en ticks ; la copie GPU mesure à chaque tick,
	 * la capture d'écran un tick sur deux : l'horizon réel reste ainsi {@code lead} ticks dans les deux cas
	 * (corrige le doublement 2.x en capture d'écran ; inchangé en copie GPU).
	 */
	private double effectiveLead() {
		return captureMode == 0 ? lead : lead / 2.0;
	}

	/** Canne à pêche en main principale (clé de traduction finissant par « fishing_rod », cannes moddées comprises). */
	static boolean holdsRod(LocalPlayer player) {
		if (player == null) return false;
		var inv = player.getInventory();
		ItemStack st = inv.getItem(inv.getSelectedSlot());
		return !st.isEmpty() && st.getItem().getDescriptionId().endsWith("fishing_rod");
	}

	/** Total des items des emplacements 0..35 (armure et main secondaire exclues). */
	private static int inventoryTotal(LocalPlayer player) {
		var inv = player.getInventory();
		int total = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty()) total += s.getCount();
		}
		return total;
	}

	// ------------------------------------------------------------------ captures

	private boolean captureDue() {
		if (state == State.GAME) return captureMode == 0 || tickCount % 2 == 0;
		if (state == State.WAIT) return tickCount % (captureMode == 0 ? 8 : 20) == 0;
		return false;
	}

	private void requestCapture(Minecraft mc) {
		int W = mc.getWindow().getWidth();
		int H = mc.getWindow().getHeight();
		if (W <= 0 || H <= 0) return; // fenêtre réduite : rien à lire
		boolean full = hint == null || !hint.found();
		int x = 0, y = 0, w = W, h = H;
		int step = Math.max(1, Math.round(W / 850f));
		if (!full) {
			int gw = hint.x1() - hint.x0();
			int gh = hint.bottom() - hint.top();
			int mx = Math.max(12, gw * 2);
			int my = Math.max(16, gh / 4);
			x = hint.x0() - mx;
			y = hint.top() - my;
			w = gw + 2 * mx;
			h = gh + 2 * my;
			step = 1;
		}
		final int stamp = tickCount;
		while (captureMode <= 2) {
			try {
				final int seq = ++captureSeq;
				capturePending = true;
				requested = tickCount;
				ScreenGrab.Sink sink = (px, pw, ph, ox, oy, scale) -> onPixels(seq, px, pw, ph, ox, oy, scale, W, H, stamp);
				if (captureMode == 0) {
					pending = ScreenGrab.gpu(mc, x, y, w, h, step, gpuBottomUp, sink, t -> asyncError(seq, t));
				} else {
					int div = captureMode == 1 ? ScreenGrab.divisor(W, H, Math.max(2, step)) : 1;
					if (captureMode == 1 && div == 1) captureMode = 2;
					pending = ScreenGrab.screenshot(mc, div, x, y, w, h, Math.max(step, div), sink, t -> asyncError(seq, t));
				}
				return;
			} catch (VirtualMachineError e) {
				throw e;
			} catch (Throwable t) {
				cancelCapture();
				noteError("méthode " + captureMode, t);
				captureMode++;
			}
		}
		failure = Texts.tr("autopaterns.fishing.fail.read", lastErrorShort);
	}

	/** Capture reçue (thread client). Une erreur d'analyse ne dégrade plus la méthode de capture (corrige 2.x). */
	private void onPixels(int seq, int[] px, int pw, int ph, int ox, int oy, int scale, int W, int H, int stamp) {
		if (seq != captureSeq || !capturePending) return; // capture annulée ou périmée
		capturePending = false;
		pending = null;
		consecutiveTimeouts = 0;
		captures++;
		int s = Math.max(1, scale);
		FishVision.Result r;
		try {
			r = FishVision.analyzePart(px, pw, ph, W / s, H / s).scaled(scale, ox, oy);
		} catch (RuntimeException e) {
			if (analysisErrors++ < 5) Log.error("pêche : analyse de l'image impossible", e);
			r = FishVision.Result.NONE;
		}
		hint = r.found() ? r : null;
		lastFrame = new Frame(px, pw, ph, ox, oy, scale);
		latest = r;
		latestStamp = stamp;
	}

	/** Erreur asynchrone d'une capture : méthode suivante. */
	private void asyncError(int seq, Throwable t) {
		if (seq != captureSeq || !capturePending) return;
		noteError("lecture, méthode " + captureMode, t);
		captureMode++;
		capturePending = false;
		pending = null;
		if (captureMode > 2) failure = Texts.tr("autopaterns.fishing.fail.read", lastErrorShort);
	}

	/** Capture sans réponse après {@link #CAPTURE_TIMEOUT} ticks : annulation, repli ou arrêt. */
	private void captureTimedOut() {
		int mode = captureMode;
		cancelCapture();
		timeouts++;
		consecutiveTimeouts++;
		noteError("délai dépassé, méthode " + mode,
			new TimeoutException("aucune réponse de la capture après " + CAPTURE_TIMEOUT + " ticks"));
		if (mode == 0) {
			captureMode = 1; // copie GPU muette : capture d'écran
			consecutiveTimeouts = 0;
		} else if (consecutiveTimeouts >= MAX_TIMEOUTS) {
			failure = Texts.tr("autopaterns.fishing.fail.noResponse");
		}
	}

	// ------------------------------------------------------------------ calibrage de la copie GPU

	/** Vrai si une capture peut être demandée ce tick. */
	private boolean calibrate(Minecraft mc) {
		if (captureMode != 0) {
			if (calibBusy) cancelCalibration();
			calib = 2;
			return true;
		}
		// (A) vérification d'orientation pendant le combat, sur la zone de la jauge
		if (calib == 2 && !orientationOk && state == State.GAME && hint != null && hint.found()
			&& !calibBusy && verifyTries < MAX_VERIFY && tickCount - lastVerify >= 20) {
			verifyTries++;
			lastVerify = tickCount;
			int gw = hint.x1() - hint.x0();
			int gh = hint.bottom() - hint.top();
			startCalibration(mc, hint.x0() - gw, hint.top() - gh / 8, gw * 3, gh + gh / 4, 1);
			if (calibBusy) verifying = true;
			return true;
		}
		// (B) premier appel : calibrage plein écran
		if (calib == 0) {
			calib = 1;
			int W = mc.getWindow().getWidth();
			startCalibration(mc, 0, 0, W, mc.getWindow().getHeight(), Math.max(1, W / 160));
			return false;
		}
		if (!calibBusy) return calib == 2;
		// (C) calibrage en vol
		if (calibShot != null && calibGpuTop != null && calibGpuBottom != null) {
			finishCalibration();
			return true;
		}
		if (tickCount - calibRequested > CALIB_TIMEOUT) {
			cancelCalibration();
			noteError("copie GPU (calibrage)", new IllegalStateException("aucune réponse de la copie GPU"));
			captureMode = 1;
			calib = 2;
			return true;
		}
		// 2.x : aucune capture pendant tout calibrage. 3.0 : pendant une simple vérification (combat en cours),
		// les captures continuent avec l'orientation courante (corrige le contrôle figé jusqu'à 3 s).
		return verifying;
	}

	private void startCalibration(Minecraft mc, int x, int y, int w, int h, int step) {
		int W = mc.getWindow().getWidth();
		int H = mc.getWindow().getHeight();
		cancelCalibration();
		x = Math.max(0, x);
		y = Math.max(0, y);
		w = Math.max(1, Math.min(W - x, w));
		h = Math.max(1, Math.min(H - y, h));
		calibShot = calibGpuTop = calibGpuBottom = null;
		calibRequested = tickCount;
		final int cw = Math.max(1, w / Math.max(2, step));
		final int ch = Math.max(1, h / Math.max(2, step));
		calibW = cw;
		calibH = ch;
		calibBusy = true;
		final int token = calibToken;
		try {
			int div = ScreenGrab.divisor(W, H, 2);
			calibHandles[0] = ScreenGrab.screenshot(mc, div, x, y, w, h, Math.max(step, div),
				(px, pw, ph, ox, oy, sc) -> { if (token == calibToken) calibShot = fit(px, pw, ph, cw, ch); },
				t -> { if (token == calibToken) calibShot = new int[0]; });
			calibHandles[1] = ScreenGrab.gpu(mc, x, y, w, h, step, false,
				(px, pw, ph, ox, oy, sc) -> { if (token == calibToken) calibGpuTop = fit(px, pw, ph, cw, ch); },
				t -> calibGpuFailed(token, t));
			calibHandles[2] = ScreenGrab.gpu(mc, x, y, w, h, step, true,
				(px, pw, ph, ox, oy, sc) -> { if (token == calibToken) calibGpuBottom = fit(px, pw, ph, cw, ch); },
				t -> calibGpuFailed(token, t));
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable t) {
			cancelCalibration();
			noteError("copie GPU (calibrage)", t);
			captureMode = 1;
			calib = 2;
		}
	}

	private void calibGpuFailed(int token, Throwable t) {
		if (token != calibToken) return;
		cancelCalibration();
		noteError("copie GPU (calibrage)", t);
		captureMode = 1;
		calib = 2;
	}

	private void finishCalibration() {
		int[] shot = calibShot;
		int[] top = calibGpuTop;
		int[] bottom = calibGpuBottom;
		cancelCalibration(); // jeton suivant : plus aucun callback tardif accepté
		calib = 2;
		if (shot.length == 0) return; // capture de référence en échec : on garde la copie GPU telle quelle
		double dTop = diff(shot, top);
		double dBot = diff(shot, bottom);
		calibDiffTop = dTop;
		calibDiffBottom = dBot;
		if (Math.min(dTop, dBot) > 40.0) {
			noteError("copie GPU (calibrage)", new IllegalStateException(
				String.format(Locale.ROOT, "image GPU différente de l'écran (écarts %.1f / %.1f)", dTop, dBot)));
			captureMode = 1;
			return;
		}
		if (Math.abs(dTop - dBot) > 4.0) {
			boolean bu = dBot < dTop;
			if (bu != gpuBottomUp) {
				gpuBottomUp = bu;
				hint = null;
				cancelCapture(); // une capture en vol utilise l'ancienne orientation
			}
			orientationOk = true;
		}
	}

	/** Rééchantillonnage au plus proche vers {@code cw × ch}. */
	private static int[] fit(int[] px, int pw, int ph, int cw, int ch) {
		int[] out = new int[cw * ch];
		for (int r = 0; r < ch; r++) {
			int sr = Math.min(ph - 1, (int) ((long) r * ph / ch));
			for (int c = 0; c < cw; c++) {
				out[r * cw + c] = px[sr * pw + Math.min(pw - 1, (int) ((long) c * pw / cw))];
			}
		}
		return out;
	}

	/** Écart moyen absolu par canal. */
	private static double diff(int[] a, int[] b) {
		int n = Math.min(a.length, b.length);
		if (n == 0) return 255.0;
		long sum = 0L;
		for (int i = 0; i < n; i++) {
			int x = a[i];
			int y = b[i];
			sum += Math.abs(((x >> 16) & 255) - ((y >> 16) & 255))
				+ Math.abs(((x >> 8) & 255) - ((y >> 8) & 255))
				+ Math.abs((x & 255) - (y & 255));
		}
		return sum / (3.0 * n);
	}

	// ------------------------------------------------------------------ journaux et diagnostic

	private void noteError(String ctx, Throwable t) {
		lastErrorShort = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " : " + t.getMessage());
		errorCount++;
		StringWriter sw = new StringWriter();
		t.printStackTrace(new PrintWriter(sw));
		captureErrors.append("── ").append(ctx).append(" ──\n");
		if (captureErrors.length() < MAX_ERROR_LOG) captureErrors.append(sw);
		else captureErrors.append(t).append(" (trace omise : journal trop long)\n");
		captureErrors.append('\n');
		Log.error("capture de l'écran (" + ctx + ") : " + t, null);
		FishFiles.writeErrors(captureErrors.toString());
	}

	private void saveDiagnostic(FishVision.Result r, String tag) {
		if (!diagnostic || diagBroken || diagSaved >= MAX_DIAG || lastFrame == null) return;
		Frame f = lastFrame;
		FishVision.Result local = r.found() ? toLocal(r, f) : r;
		String name = String.format(Locale.ROOT, "peche-%02d-%s.png", ++diagSaved, tag);
		FishFiles.writeDiagnostic(name, f.px(), f.w(), f.h(), local, this::diagFailed);
	}

	/**
	 * Échec d'écriture d'une image : diagnostic coupé pour la session seulement. Corrige la 2.x, qui mettait
	 * le réglage à false (puis le sauvegardait au changement suivant d'un autre réglage).
	 */
	private void diagFailed(Throwable t) {
		if (diagBroken) return;
		diagBroken = true;
		Log.error("pêche : image de diagnostic impossible, diagnostic coupé pour cette session", t);
		Notifier.hud(Texts.tr("autopaterns.fishing.diag.off", t.getClass().getSimpleName()));
	}

	private static FishVision.Result toLocal(FishVision.Result r, Frame f) {
		int s = Math.max(1, f.scale());
		return new FishVision.Result(true,
			(r.x0() - f.ox()) / s, (r.x1() - f.ox()) / s,
			(r.top() - f.oy()) / s, (r.bottom() - f.oy()) / s,
			r.zoneTop() < 0 ? -1 : (r.zoneTop() - f.oy()) / s,
			r.zoneBottom() < 0 ? -1 : (r.zoneBottom() - f.oy()) / s,
			Double.isNaN(r.fish()) ? Double.NaN : (r.fish() - f.oy()) / s);
	}
}

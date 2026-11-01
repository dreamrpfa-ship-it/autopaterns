package fr.autopaterns.selling;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Automation;
import fr.autopaterns.core.ClientModule;
import fr.autopaterns.core.Guard;
import fr.autopaterns.core.Log;
import fr.autopaterns.core.ModPaths;
import fr.autopaterns.core.Notifier;
import fr.autopaterns.core.Phase;
import fr.autopaterns.core.Texts;
import fr.autopaterns.gui.UiScreen;
import fr.autopaterns.input.InputSim;
import fr.autopaterns.input.KeyDef;
import fr.autopaterns.input.Keys;
import fr.autopaterns.patterns.PatternEngine;
import fr.autopaterns.patterns.PatternOptions;
import fr.autopaterns.selling.SellRecipes.Click;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;

/**
 * Vente et corbeille automatiques (module « vente ») : déclenchement pendant la lecture d'un pattern, entre deux
 * répétitions, depuis la pêche ou par le bouton « Tester » ; exécution des jobs ({@link SellRoutine}) ; capture
 * des ventes faites à la main ({@link SellCapture}) et leur persistance ({@link SellRecipes}).
 * Thread client uniquement.
 */
public final class SellingService implements ClientModule {
	/** Ticks avant de pouvoir revendre « pendant le pattern » après un job (2.x). */
	public static final int SELL_COOLDOWN_TICKS = 100;

	private SellRoutine job;
	private SellOrigin jobOrigin;
	private boolean jobTrash;
	private int sellResumeIndex;
	private int cooldown;
	private boolean pendingSale;
	private PatternOptions testOptions;
	private String lastBeginError;
	private int diagSales, diagTrash;

	private SellRecipes sellRecipes, trashRecipes;
	/** Items introuvables en boutique, ignorés pour la vente pendant la session. */
	private final Set<String> notInShop = new HashSet<>();
	/** {@code commande|item} → index de catégorie où l'item a été vu. */
	private final Map<String, Integer> categoryCache = new HashMap<>();
	private final SellCapture capture = new SellCapture();

	public SellingService() {}

	@Override
	public String name() {
		return "vente";
	}

	// ================================================================ cycle de vie

	@Override
	public void onEndTick(Minecraft mc) {
		Phase ph = AutoPaterns.get().automation().phase();
		if (ph != Phase.SELLING && job != null) { // cohérence : un job ne survit pas à un changement de phase externe
			Log.warn("vente : job abandonné (phase " + ph + ")");
			job = null;
			jobOrigin = null;
			pendingSale = false;
			testOptions = null;
		}
		if (ph != Phase.CAPTURE && capture.active) capture.reset();
		if (ph == Phase.IDLE || ph == Phase.PREPARING || ph == Phase.RECORDING) cooldown = 0; // 2.x : remis à 0 au départ d'une lecture
	}

	@Override
	public void onHalt(Minecraft mc) {
		abort(mc);
	}

	@Override
	public void onStopping(Minecraft mc) {
		abort(mc);
	}

	// ================================================================ API publique

	/** START tick, phase SELLING. */
	public void sellStep(Minecraft mc, LocalPlayer p) {
		Automation auto = AutoPaterns.get().automation();
		if (job == null) {
			auto.stopAll(mc, null);
			return;
		}
		if (mc.gui.screen() instanceof UiScreen) {
			auto.stopAll(mc, Texts.tr(jobTrash ? "autopaterns.selling.stopped.menuTrash" : "autopaterns.selling.stopped.menuSell"));
			return;
		}
		SellRoutine.Status st = job.tick(mc, p);
		if (st == SellRoutine.Status.FAILED) {
			auto.errorStop(mc, Texts.tr("autopaterns.selling.failed", label(jobTrash), job.failure()));
			return;
		}
		if (st == SellRoutine.Status.DONE) finishJob(mc, p);
	}

	/** START tick, phase CAPTURE. */
	public void captureStep(Minecraft mc, LocalPlayer p) {
		Automation auto = AutoPaterns.get().automation();
		if (!capture.active) {
			auto.stopAll(mc, null);
			return;
		}
		SellCapture.Outcome o = capture.step(mc, p, mc.gui.screen() instanceof UiScreen);
		switch (o) {
			case CANCEL -> auto.stopAll(mc, capture.cancelMessage);
			case FINISH -> finishCapture(mc, p, capture.finishItems);
			default -> { }
		}
	}

	/** Appelé par patterns à chaque tick PLAYING : décrémente le cooldown puis tente un nettoyage « pendant le pattern ». */
	public boolean playingTick(Minecraft mc, LocalPlayer p) {
		if (cooldown > 0) cooldown--;
		return autoClean(mc, p, false, SellOrigin.RESUME);
	}

	/**
	 * Déclenche la corbeille (prioritaire) ou la vente si les options du pattern chargé le demandent.
	 * {@code boundary} : frontière de répétition / pêche (le test « entre deux répétitions / cooldown / au sol » est sauté).
	 * Vrai si un job a démarré (phase SELLING).
	 */
	public boolean autoClean(Minecraft mc, LocalPlayer p, boolean boundary, SellOrigin origin) {
		if (p == null) return false;
		boolean t = shouldRun(mc, p, boundary, true);
		boolean s = shouldRun(mc, p, boundary, false);
		if (!t && !s) return false;
		pendingSale = t && AutoPaterns.get().patterns().options().sellEnabled;
		return beginJob(mc, p, origin, t);
	}

	/** Arrêt : job et capture abandonnés, conteneur ouvert refermé proprement (pile du curseur reposée). */
	public void abort(Minecraft mc) {
		SellRoutine j = job;
		job = null;
		jobOrigin = null;
		pendingSale = false;
		testOptions = null;
		capture.reset();
		LocalPlayer p = mc == null ? null : mc.player;
		if (p != null && Menus.menuOpen(p)) {
			Screen keep = mc.gui.screen() instanceof UiScreen ui ? ui : null;
			Menus.closeSafely(mc, p, j == null ? null : j.pickedFrom());
			if (keep != null && mc.gui.screen() != keep) mc.gui.setScreen(keep); // ne pas fermer le menu du mod
		}
	}

	public SellOrigin origin() {
		return jobOrigin;
	}

	public boolean isTrashJob() {
		return job != null && jobTrash;
	}

	/** Progression du job en cours (HUD), ou null. */
	public String jobDetail() {
		return job == null ? null : job.progress();
	}

	/** Détail HUD en phase CAPTURE, ou null. */
	public String captureDetail() {
		if (!capture.active) return null;
		String what = capture.target == null ? Texts.tr("autopaterns.selling.theItem") : PatternOptions.displayName(capture.target);
		return Texts.tr(capture.trash ? "autopaterns.selling.capture.detailTrash" : "autopaterns.selling.capture.detailSell",
			what, capture.clicks.size());
	}

	/** Vide la mémoire de session (items introuvables, catégories apprises) : après un changement d'options. */
	public void forgetSession() {
		notInShop.clear();
		categoryCache.clear();
	}

	public int sales() {
		return diagSales;
	}

	public int trash() {
		return diagTrash;
	}

	/** Cases vides de l'inventaire principal (0..35). */
	public static int freeSlots(LocalPlayer p) {
		return Menus.freeSlots(p);
	}

	/** Quantité d'un item (clé de traduction) dans l'inventaire principal. */
	public static int count(LocalPlayer p, String key) {
		return Menus.count(p, key);
	}

	// ================================================================ API des écrans

	/** Bouton « Tester » : lance un job TEST avec les options de l'écran (null : options du pattern chargé). */
	public String uiTest(boolean trash, PatternOptions opts) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		Automation auto = AutoPaterns.get().automation();
		if (auto.isBusy()) return Texts.tr("autopaterns.selling.ui.busy", Keys.label(KeyDef.STOP));
		if (p == null) return Texts.tr("autopaterns.selling.ui.noWorld");
		notInShop.clear();
		pendingSale = false;
		lastBeginError = null;
		testOptions = opts == null ? null : opts.copy();
		if (beginJob(mc, p, SellOrigin.TEST, trash)) {
			mc.gui.setScreen(null); // écran fermé seulement si le test démarre (2.x le fermait toujours)
			return Texts.tr("autopaterns.selling.ui.testStarted");
		}
		testOptions = null;
		if (lastBeginError != null) return "✖ " + Texts.strip(lastBeginError); // vraie cause (2.x : « Rien à vendre »)
		return Texts.tr(trash ? "autopaterns.selling.ui.nothingTrash" : "autopaterns.selling.ui.nothingSell");
	}

	/** Bouton « ● Capturer ». {@code text} : nom de l'item (vide : détection automatique). */
	public String uiStartCapture(String text, boolean trash, String patternName, PatternOptions opts) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		Automation auto = AutoPaterns.get().automation();
		if (auto.isBusy()) return Texts.tr("autopaterns.selling.ui.busy", Keys.label(KeyDef.STOP));
		ClientPacketListener conn = mc.getConnection();
		if (p == null || conn == null) return Texts.tr("autopaterns.selling.ui.noServer");
		PatternOptions o = opts != null ? opts : AutoPaterns.get().patterns().options();
		String target = resolveItem(text, o);
		if (text != null && !text.isBlank() && target == null) return Texts.tr("autopaterns.selling.ui.unknownItem", text.trim());
		if (target != null && Menus.count(p, target) == 0) {
			return Texts.tr(trash ? "autopaterns.selling.ui.needItemTrash" : "autopaterns.selling.ui.needItemSell",
				PatternOptions.displayName(target));
		}
		String cmd = SellRoutine.stripSlashes(o.command(trash));
		if (cmd.isEmpty()) return Texts.tr("autopaterns.selling.ui.emptyCommand");
		capture.begin(trash, target, patternName, p);
		mc.gui.setScreen(null);
		InputSim.releaseAll(mc);
		auto.setPhase(Phase.CAPTURE);
		conn.sendCommand(cmd);
		String what = target == null ? Texts.tr("autopaterns.selling.theItem") : PatternOptions.displayName(target);
		Notifier.hud(Texts.tr(trash ? "autopaterns.selling.capture.startTrash" : "autopaterns.selling.capture.startSell", what));
		return Texts.tr("autopaterns.selling.ui.captureStarted");
	}

	/** Bouton « Oublier » : supprime la capture de l'item nommé. */
	public String uiForgetCapture(String text, boolean trash) {
		String k = resolveItem(text, AutoPaterns.get().patterns().options());
		if (k == null) return Texts.tr("autopaterns.selling.ui.forgetWhich");
		try {
			return recipes(trash).remove(k)
				? Texts.tr("autopaterns.selling.ui.forgotten", PatternOptions.displayName(k))
				: Texts.tr("autopaterns.selling.ui.noCapture", PatternOptions.displayName(k));
		} catch (IOException | RuntimeException e) {
			Log.error("capture non oubliée", e);
			return "✖ " + e.getMessage();
		}
	}

	public boolean uiHasCapture(String key, boolean trash) {
		return recipes(trash).has(key);
	}

	public int uiCaptureCount(boolean trash) {
		return recipes(trash).size();
	}

	/** Clés distinctes des items de l'inventaire principal, dans l'ordre des cases. */
	public List<String> uiInventoryItems() {
		LocalPlayer p = Minecraft.getInstance().player;
		LinkedHashSet<String> out = new LinkedHashSet<>();
		if (p != null) {
			for (int i = 0; i < 36; i++) {
				String k = PatternOptions.itemId(p.getInventory().getItem(i));
				if (k != null) out.add(k);
			}
		}
		return new ArrayList<>(out);
	}

	// ================================================================ déclenchement et jobs

	private boolean shouldRun(Minecraft mc, LocalPlayer p, boolean boundary, boolean trash) {
		PatternEngine pe = AutoPaterns.get().patterns();
		if (!pe.hasPattern()) return false;
		PatternOptions o = pe.options();
		if (o == null || !o.enabled(trash) || o.items(trash).isEmpty() || mc.getConnection() == null) return false;
		if (!boundary && (o.between(trash) || cooldown > 0 || !p.onGround())) return false;
		return Menus.freeSlots(p) <= o.threshold(trash) && !presentItems(p, o, trash).isEmpty();
	}

	private List<String> presentItems(LocalPlayer p, PatternOptions o, boolean trash) {
		List<String> out = new ArrayList<>();
		for (String it : o.items(trash)) {
			if ((trash || !notInShop.contains(it)) && Menus.count(p, it) > 0) out.add(it);
		}
		return out;
	}

	private boolean beginJob(Minecraft mc, LocalPlayer p, SellOrigin origin, boolean trash) {
		PatternOptions opts = (origin == SellOrigin.TEST && testOptions != null) ? testOptions : AutoPaterns.get().patterns().options();
		if (opts == null) opts = new PatternOptions();
		List<String> items = presentItems(p, opts, trash);
		if (items.isEmpty()) {
			if (origin == SellOrigin.TEST) {
				Notifier.hud(Texts.tr(trash ? "autopaterns.selling.noneTrash" : "autopaterns.selling.noneSell"));
			}
			return false;
		}
		SellRoutine r;
		try {
			r = new SellRoutine(opts, items, recipes(trash).all(), trash, categoryCache);
		} catch (RuntimeException e) {
			String msg = Texts.tr("autopaterns.selling.badSteps", label(trash), e.getMessage());
			lastBeginError = msg;
			if (origin == SellOrigin.TEST) Notifier.hud(msg);
			else AutoPaterns.get().automation().errorStop(mc, msg);
			return false;
		}
		InputSim.releaseAll(mc);
		job = r;
		jobTrash = trash;
		jobOrigin = origin;
		sellResumeIndex = AutoPaterns.get().patterns().index();
		AutoPaterns.get().automation().setPhase(Phase.SELLING);
		Notifier.hud(Texts.tr(origin == SellOrigin.TEST ? "autopaterns.selling.startTest" : "autopaterns.selling.start",
			label(trash), items.size()));
		Log.info((trash ? "corbeille : " : "vente : ") + items);
		return true;
	}

	/** Fin normale d'un job : bilan, enchaînement corbeille → vente, puis reprise selon l'origine. */
	private void finishJob(Minecraft mc, LocalPlayer p) {
		AutoPaterns ap = AutoPaterns.get();
		PatternEngine pe = ap.patterns();
		SellRoutine done = job;
		boolean trash = jobTrash;
		SellOrigin origin = jobOrigin;
		int sorts = done.soldTypes();
		List<String> nf = new ArrayList<>(done.notFound());
		job = null;
		if (!nf.isEmpty() && !trash) {
			notInShop.addAll(nf);
			List<String> names = new ArrayList<>();
			for (String k : nf) names.add(PatternOptions.displayName(k));
			String m = "Introuvable(s) dans la boutique, ignoré(s) jusqu'au redémarrage : " + String.join(", ", names);
			Log.info(m);
			diagnostic(pe, "VENTE · " + m);
		}
		if (trash) diagTrash++;
		else diagSales++;
		int free = Menus.freeSlots(p);
		String summary = trash
			? Texts.tr("autopaterns.selling.summary.trash", sorts, free)
			: Texts.tr("autopaterns.selling.summary.sell", sorts, free)
				+ (nf.isEmpty() ? "" : Texts.tr("autopaterns.selling.summary.notFound", nf.size()));
		diagnostic(pe, (trash ? "CORBEILLE · " : "VENTE · ") + Texts.strip(summary));

		// enchaînement corbeille → vente
		if (trash && pendingSale && origin != SellOrigin.TEST) {
			pendingSale = false;
			PatternOptions o = pe.options();
			if (o != null && Menus.freeSlots(p) <= o.sellThreshold && !presentItems(p, o, false).isEmpty()) {
				Notifier.hud(summary + Texts.tr("autopaterns.selling.suffix.thenSell"));
				if (beginJob(mc, p, origin, false)) return;
				if (ap.automation().phase() != Phase.SELLING) return; // échec de démarrage déjà traité (errorStop)
			}
		}
		pendingSale = false;
		cooldown = SELL_COOLDOWN_TICKS;
		testOptions = null;
		jobOrigin = null;
		switch (origin == null ? SellOrigin.TEST : origin) {
			case TEST -> {
				ap.automation().toIdle(mc);
				Notifier.hud(summary + ".");
			}
			case FISHING -> {
				ap.automation().setPhase(Phase.FISHING);
				ap.fishing().resume();
				Notifier.hud(summary + Texts.tr("autopaterns.selling.suffix.fishing"));
			}
			case RESUME, NEXT_REP -> pe.resumeAfterJob(mc, p, origin, sellResumeIndex, summary);
		}
	}

	private static void diagnostic(PatternEngine pe, String line) {
		try {
			pe.appendDiagnostic(line);
		} catch (RuntimeException e) {
			Log.warn("diagnostic non écrit : " + e);
		}
	}

	// ================================================================ capture

	/** Crochet du mixin (HEAD de handleContainerInput) : relève le clic en phase CAPTURE. */
	public static void onContainerInput(int containerId, int slotId, int button, ContainerInput input, Player player) {
		if (Guard.disabled()) return;
		try {
			AutoPaterns ap = AutoPaterns.get();
			if (ap.automation().phase() != Phase.CAPTURE) return;
			ap.selling().capture.record(slotId, button, input, player);
		} catch (RuntimeException e) {
			Log.warn("clic non relevé : " + e);
		}
	}

	private void finishCapture(Minecraft mc, LocalPlayer p, List<String> items) {
		List<Click> clicks = List.copyOf(capture.clicks);
		boolean trash = capture.trash;
		String patternName = capture.patternName;
		if (Menus.menuOpen(p)) Menus.closeSafely(mc, p, null);
		capture.reset();
		AutoPaterns ap = AutoPaterns.get();
		ap.automation().toIdle(mc);

		List<String> saved = new ArrayList<>();
		List<String> names = new ArrayList<>();
		int maxClicks = 0;
		try {
			for (String key : items) {
				List<Click> compact = SellCapture.compactClicks(clicks, key, items.size() > 1);
				if (compact.isEmpty()) continue; // capture vide : non enregistrée (2.x : plantage au rejeu)
				recipes(trash).put(key, compact);
				saved.add(key);
				maxClicks = Math.max(maxClicks, compact.size());
				names.add(PatternOptions.displayName(key));
				if (!trash) notInShop.remove(key);
			}
		} catch (IOException | RuntimeException e) {
			Log.error("capture non sauvegardée", e);
			Notifier.hud(Texts.tr("autopaterns.selling.capture.notSaved", String.valueOf(e.getMessage())));
			return;
		}
		if (saved.isEmpty()) {
			List<String> all = new ArrayList<>();
			for (String k : items) all.add(PatternOptions.displayName(k));
			Notifier.hud(Texts.tr("autopaterns.selling.capture.empty", String.join(", ", all)));
			return;
		}
		String suffix = addToPattern(patternName, trash, saved);
		Log.info((trash ? "corbeille" : "vente") + " capturée pour " + saved + " : " + clicks);
		Notifier.hud(Texts.tr(trash ? "autopaterns.selling.capture.doneTrash" : "autopaterns.selling.capture.doneSell",
			String.join(", ", names), maxClicks, suffix));
	}

	/** Ajoute les items capturés à la liste du pattern (nommé ou courant) et l'enregistre ; renvoie le suffixe du message. */
	private String addToPattern(String patternName, boolean trash, List<String> items) {
		PatternEngine pe = AutoPaterns.get().patterns();
		boolean named = patternName != null && !patternName.equals(pe.currentName());
		if (!named && !pe.hasPattern()) return "";
		String target = named ? patternName : null;
		try {
			PatternOptions o = pe.uiOptionsOf(target);
			if (o == null || !o.items(trash).addAll(items)) return "";
			String r = pe.uiSaveOptionsOf(target, o);
			if (r != null && r.startsWith("✔")) {
				forgetSession();
				return named
					? Texts.tr(trash ? "autopaterns.selling.capture.addedTrashTo" : "autopaterns.selling.capture.addedSellTo", patternName)
					: Texts.tr(trash ? "autopaterns.selling.capture.addedTrash" : "autopaterns.selling.capture.addedSell");
			}
			Log.warn("liste du pattern non sauvegardée : " + r);
		} catch (RuntimeException e) {
			Log.error("liste du pattern non sauvegardée", e);
		}
		return named ? Texts.tr("autopaterns.selling.capture.listNotSavedOf", patternName)
			: Texts.tr("autopaterns.selling.capture.listNotSaved");
	}

	// ================================================================ outils

	/** Captures (chargées une fois, relues si le fichier a été modifié sur le disque). */
	SellRecipes recipes(boolean trash) {
		if (trash) {
			if (trashRecipes == null) trashRecipes = new SellRecipes(ModPaths.file("corbeille.txt"), true);
			else trashRecipes.reloadIfChanged();
			return trashRecipes;
		}
		if (sellRecipes == null) sellRecipes = new SellRecipes(ModPaths.file("ventes.txt"), false);
		else sellRecipes.reloadIfChanged();
		return sellRecipes;
	}

	/** Item désigné par un nom tapé (nom affiché, nom court, identifiant {@code minecraft:x}), ou null. */
	String resolveItem(String text, PatternOptions opts) {
		if (text == null || text.isBlank()) return null;
		String q = SellSteps.normalize(text).replace('_', ' ');
		int c = q.indexOf(':');
		if (c >= 0) q = q.substring(c + 1).trim();
		if (q.isEmpty()) return null;
		LinkedHashSet<String> cands = new LinkedHashSet<>(uiInventoryItems());
		if (opts != null) {
			cands.addAll(opts.sellItems);
			cands.addAll(opts.trashItems);
		}
		cands.addAll(PatternOptions.DEFAULT_SELL);
		cands.addAll(recipes(false).all().keySet());
		cands.addAll(recipes(true).all().keySet());
		for (int pass = 0; pass < 3; pass++) {
			for (String k : cands) {
				String[] vs = {SellSteps.normalize(PatternOptions.displayName(k)),
					SellSteps.normalize(PatternOptions.shortName(k)).replace('_', ' ')};
				for (String v : vs) {
					boolean hit = switch (pass) {
						case 0 -> v.equals(q);
						case 1 -> v.startsWith(q);
						default -> v.contains(q);
					};
					if (hit) return k;
				}
			}
		}
		return null;
	}

	static String label(boolean trash) {
		return Texts.tr(trash ? "autopaterns.selling.label.trash" : "autopaterns.selling.label.sell");
	}
}

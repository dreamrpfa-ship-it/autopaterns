package fr.autopaterns.selling;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import fr.autopaterns.core.Log;
import fr.autopaterns.core.Texts;
import fr.autopaterns.patterns.PatternOptions;
import fr.autopaterns.selling.SellRecipes.Click;
import fr.autopaterns.selling.SellSteps.Step;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Un job de vente ou de corbeille (machine à états 2.x : COMMAND → WAIT_SCREEN → STEPS → VERIFY → CLOSE, par item).
 * <p>Vente : commande, navigation catégories / pages, clic sur l'item puis étapes « nom » / « attendre », vérification
 * que l'inventaire a diminué. Corbeille : commande puis maj+clic de chaque pile. Une capture enregistrée pour l'item
 * est rejouée à la place de l'algorithme générique.</p>
 * <p>Sécurités 3.0 (en plus de la 2.x) :</p>
 * <ul>
 *   <li>chaque clic est vérifié au moment de l'envoi (menu courant, slot du menu, contenu attendu : jamais de clic sur
 *       un slot vide ou différent, sauf « poser » dans un slot vide avec une pile au curseur) ;</li>
 *   <li>un clic n'est envoyé que {@code delay} ticks après le précédent ET après en avoir constaté l'effet
 *       (sinon après {@code max(10, 3×delay)} ticks) ; deux clics consécutifs sur la même cible exigent un vrai
 *       changement côté serveur (la prédiction locale ne suffit pas) ;</li>
 *   <li>plafonds : durée par item, durée totale, commandes et clics par item, clics refusés consécutifs ;</li>
 *   <li>fermeture propre : la pile tenue au curseur est reposée dans l'inventaire avant de refermer le menu.</li>
 * </ul>
 * Thread client uniquement.
 */
final class SellRoutine {
	enum Stage { COMMAND, WAIT_SCREEN, STEPS, VERIFY, CLOSE }

	enum Status { RUNNING, DONE, FAILED }

	// ---------------------------------------------------------------- constantes 2.x
	static final int SCREEN_TIMEOUT = 60;
	static final int STEP_TIMEOUT = 60;
	static final int CHANGE_TIMEOUT = 60;
	static final int SETTLE_MIN = 8;
	static final int CLOSED_GRACE_MIN = 10;
	static final int MAX_PAGES = 10;
	static final int SELL_PASSES = 3;
	static final int TRASH_PASSES = 6;
	static final int VERIFY_TIMEOUT = 40;
	static final int MAX_REPEAT_CLICKS = 40;
	static final int RECLICK_MIN = 10;

	// ---------------------------------------------------------------- plafonds 3.0 (anti-boucle)
	/** Durée maximale consacrée à un item (toutes passes et catégories comprises) : 5 min. */
	static final int ITEM_MAX_TICKS = 6000;
	/** Durée maximale d'un job complet : 20 min. */
	static final int JOB_MAX_TICKS = 24000;
	static final int MAX_COMMANDS_PER_ITEM = 40;
	static final int MAX_CLICKS_PER_ITEM = 400;
	/** Clics refusés d'affilée (contenu du slot inattendu, menu remplacé) avant abandon. */
	static final int MAX_REFUSED_CLICKS = 40;
	/** Taille max du cache des catégories (2.x : non borné). */
	static final int CATEGORY_CACHE_MAX = 512;

	private final PatternOptions options;
	private final boolean trash;
	private final List<Step> steps;
	private final ArrayDeque<String> queue;
	private final Set<String> wanted;
	private final Set<String> sold = new LinkedHashSet<>();
	private final Set<String> notFound = new LinkedHashSet<>();
	private final int delay;
	private final int settle;
	private final String command;
	private final Map<String, List<Click>> recipes;
	private final Map<String, Integer> categoryCache;

	private String item;
	private int itemCountBefore;
	private int passes;
	private Stage stage = Stage.COMMAND;
	private int stepIndex, tick, nextAction, deadline, stepStart, pages, closedTicks;
	private String failure = "";
	private String progress = "";
	private boolean failed;
	private boolean retrySame;
	private boolean inCategory;
	private List<Integer> catOrder;
	private int catTry, catCount;
	private boolean waitChange, navWasPage;
	private int changeDeadline;
	private long navBefore, navAfter;
	private final Set<Long> seenPages = new HashSet<>();
	private List<Click> recipe;
	private int clickIndex;
	private int repeatClicks, repeatBefore;
	private boolean pairHalf;
	private Slot pickedFrom;

	// garde des clics (3.0)
	private int lastClickTick = Integer.MIN_VALUE / 2;
	private long lastPre, lastPost;
	private int itemStart, commandsForItem, clicksForItem, refusedClicks;

	/**
	 * @throws IllegalArgumentException étapes de vente invalides (message affichable)
	 */
	SellRoutine(PatternOptions opts, List<String> items, Map<String, List<Click>> recipes, boolean trash,
				Map<String, Integer> categoryCache) {
		this.options = opts;
		this.trash = trash;
		this.steps = trash ? List.of() : SellSteps.parse(opts.sellSteps);
		this.queue = new ArrayDeque<>(items);
		this.wanted = new HashSet<>(items);
		this.delay = Math.max(1, opts.delay(trash));
		this.settle = Math.max(SETTLE_MIN, 2 * delay);
		this.command = stripSlashes(opts.command(trash));
		Map<String, List<Click>> r = new LinkedHashMap<>();
		if (recipes != null) {
			for (Map.Entry<String, List<Click>> e : recipes.entrySet()) {
				if (e.getValue() != null && !e.getValue().isEmpty()) r.put(e.getKey(), e.getValue()); // capture vide ignorée (bug 2.x)
			}
		}
		this.recipes = r;
		this.categoryCache = categoryCache;
	}

	static String stripSlashes(String cmd) {
		String c = cmd == null ? "" : cmd.trim();
		while (c.startsWith("/")) c = c.substring(1);
		return c.trim();
	}

	String failure() { return failure; }
	String progress() { return progress; }
	int soldTypes() { return sold.size(); }
	Set<String> notFound() { return Collections.unmodifiableSet(notFound); }
	boolean trash() { return trash; }
	/** Slot d'où une pile a été prise (pour la reposer à l'arrêt), ou null. */
	Slot pickedFrom() { return pickedFrom; }

	// ================================================================ tick

	Status tick(Minecraft mc, LocalPlayer p) {
		tick++;
		if (tick > JOB_MAX_TICKS) return fail(Texts.tr("autopaterns.selling.fail.jobTimeout", Texts.duration(JOB_MAX_TICKS)));
		if (item != null && (stage == Stage.WAIT_SCREEN || stage == Stage.STEPS || stage == Stage.VERIFY)
			&& tick - itemStart > ITEM_MAX_TICKS) {
			return fail(Texts.tr("autopaterns.selling.fail.itemTimeout", dn(item), Texts.duration(ITEM_MAX_TICKS)));
		}
		if (tick < nextAction) return Status.RUNNING;
		boolean open = Menus.screenOpen(mc, p);
		AbstractContainerMenu menu = p.containerMenu;
		Status s = switch (stage) {
			case COMMAND -> commandStage(mc, p, open);
			case WAIT_SCREEN -> waitScreenStage(open);
			case STEPS -> stepsStage(mc, p, menu, open);
			case VERIFY -> verifyStage(p, open);
			case CLOSE -> closeStage(mc, p);
		};
		return failed ? Status.FAILED : s;
	}

	private Status commandStage(Minecraft mc, LocalPlayer p, boolean open) {
		if (open) {
			Menus.closeSafely(mc, p, pickedFrom);
			nextAction = tick + delay;
			return Status.RUNNING;
		}
		if (!retrySame) {
			item = queue.poll();
			if (item == null) {
				progress = Texts.tr("autopaterns.selling.progress.done");
				return Status.DONE;
			}
			passes = 0;
			catOrder = null;
			catTry = 0;
			itemStart = tick;
			commandsForItem = 0;
			clicksForItem = 0;
		}
		retrySame = false;
		recipe = recipes.get(item);
		clickIndex = 0;
		repeatClicks = 0;
		pairHalf = false;
		pickedFrom = null;
		itemCountBefore = Menus.count(p, item);
		if (itemCountBefore == 0) return Status.RUNNING; // item absent : sauté (le tick suivant prend le suivant)
		ClientPacketListener conn = mc.getConnection();
		if (conn == null) return fail(Texts.tr("autopaterns.selling.fail.noConnection"));
		if (command.isEmpty()) {
			return fail(Texts.tr(trash ? "autopaterns.selling.fail.emptyTrashCommand" : "autopaterns.selling.fail.emptyCommand"));
		}
		if (++commandsForItem > MAX_COMMANDS_PER_ITEM) {
			return fail(Texts.tr("autopaterns.selling.fail.tooManyCommands", dn(item), MAX_COMMANDS_PER_ITEM));
		}
		conn.sendCommand(command);
		progress = Texts.tr("autopaterns.selling.progress.command", dn(item), command);
		inCategory = false;
		waitChange = false;
		pages = 0;
		seenPages.clear();
		stage = Stage.WAIT_SCREEN;
		deadline = tick + SCREEN_TIMEOUT;
		return Status.RUNNING;
	}

	private Status waitScreenStage(boolean open) {
		if (open) {
			stage = Stage.STEPS;
			stepIndex = 0;
			stepStart = tick;
			closedTicks = 0;
			refusedClicks = 0;
			nextAction = tick + delay;
		} else if (tick > deadline) {
			return fail(Texts.tr("autopaterns.selling.fail.noScreen", command));
		}
		return Status.RUNNING;
	}

	private Status stepsStage(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu, boolean open) {
		if (!open) {
			closedTicks++;
			if (closedTicks <= Math.max(CLOSED_GRACE_MIN, 2 * delay)) return Status.RUNNING;
			if (waitChange && tick <= changeDeadline) return Status.RUNNING; // navigation : menu momentanément fermé
			return fail(Texts.tr("autopaterns.selling.fail.closed"));
		}
		closedTicks = 0;
		if (recipe != null) return replayStep(mc, p, menu);
		if (trash) return trashStep(mc, p, menu);
		Step st = steps.get(stepIndex);
		switch (st.kind()) {
			case SellSteps.WAIT -> {
				progress = dn(item) + " · " + st.describe();
				advance(st.ticks());
			}
			case SellSteps.ITEM -> {
				return itemStep(mc, p, menu, st);
			}
			default -> {
				progress = dn(item) + " · " + st.describe();
				if (!canClick(p)) return Status.RUNNING;
				Slot s = Menus.findByName(menu, st.text(), item);
				if (s != null) {
					if (clickChecked(mc, p, menu, s, st.button(), ContainerInput.PICKUP, nameContains(st.text()))) advance(delay);
				} else if (tick - stepStart > STEP_TIMEOUT) {
					return fail(Texts.tr("autopaterns.selling.fail.buttonNotFound", st.text()));
				}
			}
		}
		return Status.RUNNING;
	}

	private Status verifyStage(LocalPlayer p, boolean open) {
		int n = Menus.count(p, item);
		if (n < itemCountBefore) {
			sold.add(item);
			if (n > 0 && ++passes < (trash ? TRASH_PASSES : SELL_PASSES)) {
				retrySame = true;
				catTry = 0;
				catOrder = null;
			}
			stage = Stage.CLOSE;
			// Corbeille : enchaîner l'item suivant sans refermer le menu (seulement s'il est présent — corrige 2.x).
			if (trash && !retrySame && recipe == null && open) {
				String nx;
				while ((nx = queue.peek()) != null && !recipes.containsKey(nx)) {
					int c = Menus.count(p, nx);
					queue.poll();
					if (c > 0) {
						item = nx;
						itemCountBefore = c;
						passes = 0;
						repeatClicks = 0;
						stage = Stage.STEPS;
						stepStart = tick;
						itemStart = tick;
						commandsForItem = 1;
						clicksForItem = 0;
						break;
					}
				}
			}
			return Status.RUNNING;
		}
		if (trash && recipe == null && n == itemCountBefore && tick > deadline) {
			return fail(Texts.tr("autopaterns.selling.fail.trashRefused", dn(item), command));
		}
		if (tick > deadline) {
			return fail(Texts.tr(trash ? "autopaterns.selling.fail.nothingRemovedTrash" : "autopaterns.selling.fail.nothingRemovedSell", dn(item)));
		}
		return Status.RUNNING;
	}

	private Status closeStage(Minecraft mc, LocalPlayer p) {
		if (Menus.menuOpen(p)) Menus.closeSafely(mc, p, pickedFrom);
		pickedFrom = null;
		stage = Stage.COMMAND;
		nextAction = tick + 1;
		return Status.RUNNING;
	}

	// ================================================================ vente générique : item, catégories, pages

	private Status itemStep(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu, Step step) {
		if (waitChange) {
			long sig = Menus.signature(menu);
			if (sig != navBefore && sig != navAfter) { // vrai changement (la prédiction locale du clic ne compte pas)
				waitChange = false;
				stepStart = tick;
				nextAction = tick + delay;
				return Status.RUNNING;
			}
			if (tick <= changeDeadline) return Status.RUNNING;
			waitChange = false;
			if (!navWasPage) return fail(Texts.tr("autopaterns.selling.fail.categoryNoOpen"));
			return endOfCategory();
		}
		noteCategories(menu);
		Slot s = Menus.findItem(menu, item);
		if (s != null) {
			progress = dn(item) + " · " + step.describe();
			if (!canClick(p)) return Status.RUNNING;
			if (inCategory && catOrder != null) putCategory(cacheKey(item), catOrder.get(catTry));
			if (clickChecked(mc, p, menu, s, step.button(), ContainerInput.PICKUP, Menus.holding(item))) advance(delay);
			return Status.RUNNING;
		}
		List<Slot> cats = inCategory ? List.of() : Menus.categorySlots(menu, options.sellCategory);
		if (!cats.isEmpty()) {
			if (catOrder == null) {
				catCount = cats.size();
				catOrder = new ArrayList<>();
				Integer c = categoryCache.get(cacheKey(item));
				if (c != null && c < catCount) catOrder.add(c);
				for (int i = 0; i < catCount; i++) if (!catOrder.contains(i)) catOrder.add(i);
			}
			if (catTry >= catOrder.size()) return giveUp();
			int idx = catOrder.get(catTry);
			if (idx >= cats.size()) return endOfCategory();
			progress = Texts.tr("autopaterns.selling.progress.category", dn(item), idx + 1, cats.size());
			if (!canClick(p)) return Status.RUNNING;
			Slot cs = cats.get(idx);
			if (navigate(mc, p, menu, cs, false, Menus.holdingExact(Menus.key(Menus.stack(cs))))) inCategory = true;
			return Status.RUNNING;
		}
		// pas de catégorie (ou déjà dedans) : pagination
		if (tick - stepStart < settle) return Status.RUNNING;
		if (!canClick(p)) return Status.RUNNING;
		String catPart = inCategory && catOrder != null
			? Texts.tr("autopaterns.selling.progress.categoryPrefix", catOrder.get(catTry) + 1, catCount) : "";
		progress = Texts.tr("autopaterns.selling.progress.page", dn(item), catPart, pages + 1);
		if (!seenPages.add(Menus.signature(menu))) return endOfCategory(); // page déjà vue : boucle
		String pageText = options.sellPageText == null ? "" : options.sellPageText;
		Slot pg = pageText.isBlank() ? null : Menus.findByName(menu, pageText, item);
		if (pg != null && pages < MAX_PAGES) {
			if (navigate(mc, p, menu, pg, true, nameContains(pageText))) {
				pages++;
			} else {
				seenPages.remove(Menus.signature(menu)); // clic refusé : la page sera réexaminée
			}
			return Status.RUNNING;
		}
		return endOfCategory();
	}

	private boolean navigate(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu, Slot slot, boolean isPage,
							 Predicate<ItemStack> expect) {
		long before = Menus.signature(menu);
		if (!clickChecked(mc, p, menu, slot, 0, ContainerInput.PICKUP, expect)) return false;
		navBefore = before;
		navAfter = Menus.signature(p.containerMenu);
		waitChange = true;
		navWasPage = isPage;
		changeDeadline = tick + CHANGE_TIMEOUT;
		nextAction = tick + 1;
		return true;
	}

	private Status endOfCategory() {
		if (inCategory && catOrder != null && catTry + 1 < catOrder.size()) {
			catTry++;
			retrySame = true;
			stage = Stage.CLOSE; // menu refermé, commande renvoyée pour la catégorie suivante
			return Status.RUNNING;
		}
		return giveUp();
	}

	private Status giveUp() {
		if (!sold.contains(item)) notFound.add(item);
		retrySame = false;
		stage = Stage.CLOSE;
		return Status.RUNNING;
	}

	/** Apprend dans quelle catégorie se trouvent les autres items demandés visibles dans le menu. */
	private void noteCategories(AbstractContainerMenu menu) {
		if (!inCategory || catOrder == null || waitChange) return;
		int idx = catOrder.get(catTry);
		for (Slot s : Menus.slots(menu)) {
			if (Menus.isPlayerSlot(s)) continue;
			ItemStack st = Menus.stack(s);
			if (st.isEmpty()) continue;
			String k = Menus.key(st);
			for (String w : wanted) {
				if (PatternOptions.sameItem(w, k) && !categoryCache.containsKey(cacheKey(w))) putCategory(cacheKey(w), idx);
			}
		}
	}

	private void putCategory(String key, int idx) {
		if (categoryCache.size() >= CATEGORY_CACHE_MAX && !categoryCache.containsKey(key)) categoryCache.clear();
		categoryCache.put(key, idx);
	}

	private String cacheKey(String it) {
		return command + "|" + it;
	}

	// ================================================================ rejeu d'une capture

	private Status replayStep(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu) {
		if (clickIndex >= recipe.size()) {
			toVerify(tick);
			return Status.RUNNING;
		}
		Click c = recipe.get(clickIndex);
		boolean pair = isPair(clickIndex);
		if (c.playerInv() && (c.action() == SellRecipes.QUICK_MOVE || pair)) return repeatStep(mc, p, menu, c, pair);
		String label = c.name().isBlank() ? PatternOptions.shortName(c.key()) : Texts.strip(c.name());
		progress = Texts.tr("autopaterns.selling.progress.replay", dn(item), clickIndex + 1, recipe.size(), label);
		if (!canClick(p)) return Status.RUNNING;
		// anti double-clic : même cible que le clic précédent et aucun changement constaté depuis
		if (clickIndex > 0 && sameTarget(c, recipe.get(clickIndex - 1)) && !changedSinceLastClick(p)
			&& tick - lastClickTick < reclickWait()) {
			return Status.RUNNING;
		}
		Slot s = Menus.findRecorded(menu, c);
		if (s == null) {
			if (tick - stepStart > STEP_TIMEOUT) {
				return fail(Texts.tr("autopaterns.selling.fail.replayNotFound", clickIndex + 1, label));
			}
			return Status.RUNNING;
		}
		if (c.key().isEmpty() && Menus.carried(menu).isEmpty()) {
			// « poser » sans rien au curseur : la prise précédente n'a rien donné, le clic serait inutile
			Log.debug("vente : clic « poser » " + (clickIndex + 1) + " sauté (curseur vide)");
			clickIndex++;
			stepStart = tick;
			if (clickIndex >= recipe.size()) toVerify(tick);
			return Status.RUNNING;
		}
		Predicate<ItemStack> expect = c.key().isEmpty() ? ItemStack::isEmpty : Menus.holdingExact(c.key());
		if (!clickChecked(mc, p, menu, s, c.button(), Menus.input(c.action()), expect)) return Status.RUNNING;
		clickIndex++;
		nextAction = tick + delay;
		stepStart = tick + delay;
		if (clickIndex >= recipe.size()) toVerify(tick);
		return Status.RUNNING;
	}

	/** Prendre une pile dans l'inventaire puis la poser dans un slot vide du menu. */
	private boolean isPair(int i) {
		Click c = recipe.get(i);
		return c.playerInv() && c.action() == SellRecipes.PICKUP && i + 1 < recipe.size()
			&& !recipe.get(i + 1).playerInv() && recipe.get(i + 1).key().isEmpty();
	}

	/** Répète un clic d'inventaire (maj+clic, ou paire prendre/poser) sur chaque pile de l'item jusqu'à épuisement. */
	private Status repeatStep(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu, Click c, boolean pair) {
		int n = Menus.count(p, c.key());
		progress = Texts.tr(trash ? "autopaterns.selling.progress.repeatTrash" : "autopaterns.selling.progress.repeatMenu",
			dn(item), repeatClicks);
		if (pairHalf) { // 2e moitié : poser la pile prise
			ItemStack cur = Menus.carried(menu);
			if (cur.isEmpty()) { // la prise n'a rien donné
				pairHalf = false;
				return endRepeat(pair);
			}
			if (!canClick(p)) return Status.RUNNING;
			Click put = recipe.get(clickIndex + 1);
			Slot t = Menus.emptyMenuSlot(menu, put.slot());
			Slot dest = t != null ? t : Menus.emptyPlayerSlot(menu, pickedFrom); // plus de place : reposer dans l'inventaire
			if (dest == null) {
				pairHalf = false;
				return endRepeat(pair);
			}
			if (clickChecked(mc, p, menu, dest, t != null ? put.button() : 0, ContainerInput.PICKUP, ItemStack::isEmpty)) {
				pairHalf = false;
				nextAction = tick + delay;
				if (t == null) return endRepeat(pair);
			}
			return Status.RUNNING;
		}
		if (repeatClicks > 0 && !canClick(p)) return Status.RUNNING; // effet du clic précédent pas encore constaté
		if ((repeatClicks > 0 && n >= repeatBefore) || n == 0 || repeatClicks >= MAX_REPEAT_CLICKS) return endRepeat(pair);
		Slot s = Menus.findRecorded(menu, c);
		if (s == null) return endRepeat(pair);
		if (!canClick(p)) return Status.RUNNING;
		if (!clickChecked(mc, p, menu, s, c.button(), Menus.input(c.action()), Menus.holdingExact(c.key()))) {
			return Status.RUNNING;
		}
		repeatBefore = n;
		repeatClicks++;
		if (pair) {
			pairHalf = true;
			pickedFrom = s;
		}
		nextAction = tick + delay;
		return Status.RUNNING;
	}

	private Status endRepeat(boolean pair) {
		clickIndex += pair ? 2 : 1;
		repeatClicks = 0;
		pairHalf = false;
		stepStart = tick;
		if (clickIndex >= recipe.size()) toVerify(tick);
		return Status.RUNNING;
	}

	private static boolean sameTarget(Click a, Click b) {
		return a.slot() == b.slot() && a.key().equals(b.key()) && a.name().equals(b.name());
	}

	// ================================================================ corbeille sans capture

	private Status trashStep(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu) {
		int n = Menus.count(p, item);
		progress = Texts.tr("autopaterns.selling.progress.repeatTrash", dn(item), repeatClicks);
		if (repeatClicks > 0 && !canClick(p)) return Status.RUNNING;
		if (n == 0 || (repeatClicks > 0 && n >= repeatBefore) || repeatClicks >= MAX_REPEAT_CLICKS) {
			repeatClicks = 0;
			toVerify(tick);
			return Status.RUNNING;
		}
		if (tick - stepStart < delay && repeatClicks == 0) return Status.RUNNING;
		Slot s = Menus.playerSlotWith(menu, item);
		if (s == null) {
			toVerify(tick);
			return Status.RUNNING;
		}
		if (!canClick(p)) return Status.RUNNING;
		if (!clickChecked(mc, p, menu, s, 0, ContainerInput.QUICK_MOVE, Menus.holding(item))) return Status.RUNNING;
		repeatBefore = n;
		repeatClicks++;
		nextAction = tick + delay;
		return Status.RUNNING;
	}

	// ================================================================ outils

	private void advance(int d) {
		stepIndex++;
		stepStart = tick + d;
		pages = 0;
		nextAction = tick + d;
		if (stepIndex >= steps.size()) {
			stage = Stage.VERIFY;
			deadline = tick + d + VERIFY_TIMEOUT;
		}
	}

	private void toVerify(int from) {
		stage = Stage.VERIFY;
		deadline = from + delay + VERIFY_TIMEOUT;
	}

	private int reclickWait() {
		return Math.max(RECLICK_MIN, 3 * delay);
	}

	/**
	 * Un nouveau clic est permis {@code delay} ticks après le précédent, une fois son effet constaté (l'état du menu,
	 * du curseur ou de l'inventaire a changé depuis l'avant-clic), ou au plus tard après {@link #reclickWait()}.
	 */
	private boolean canClick(LocalPlayer p) {
		int el = tick - lastClickTick;
		if (el < delay) return false;
		if (Menus.stateSignature(p) != lastPre) return true;
		return el >= reclickWait();
	}

	/** Changement réel depuis le dernier clic : différent de l'avant-clic ET de l'état prédit juste après. */
	private boolean changedSinceLastClick(LocalPlayer p) {
		long cur = Menus.stateSignature(p);
		return cur != lastPre && cur != lastPost;
	}

	/** Clic vérifié et comptabilisé ; faux si refusé (rien n'a été envoyé). */
	private boolean clickChecked(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu, Slot slot, int button,
								 ContainerInput in, Predicate<ItemStack> expect) {
		if (clicksForItem >= MAX_CLICKS_PER_ITEM) {
			fail(Texts.tr("autopaterns.selling.fail.tooManyClicks", dn(item), MAX_CLICKS_PER_ITEM));
			return false;
		}
		long pre = Menus.stateSignature(p);
		if (!Menus.click(mc, p, menu, slot, button, in, expect)) {
			if (++refusedClicks > MAX_REFUSED_CLICKS) fail(Texts.tr("autopaterns.selling.fail.clickRefused", dn(item)));
			return false;
		}
		refusedClicks = 0;
		clicksForItem++;
		lastPre = pre;
		lastPost = Menus.stateSignature(p);
		lastClickTick = tick;
		return true;
	}

	private static Predicate<ItemStack> nameContains(String text) {
		String want = SellSteps.normalize(text);
		return st -> !st.isEmpty() && SellSteps.normalize(Menus.name(st, 256)).contains(want);
	}

	private Status fail(String msg) {
		failure = msg;
		failed = true;
		return Status.FAILED;
	}

	private static String dn(String key) {
		return PatternOptions.displayName(key);
	}
}

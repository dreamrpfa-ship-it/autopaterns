package fr.autopaterns.selling;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import fr.autopaterns.core.Log;
import fr.autopaterns.core.Notifier;
import fr.autopaterns.core.Texts;
import fr.autopaterns.patterns.PatternOptions;
import fr.autopaterns.selling.SellRecipes.Click;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Capture d'une vente / corbeille faite à la main : relève les clics (via le mixin sur
 * {@code MultiPlayerGameMode#handleContainerInput}) et détecte la baisse de l'inventaire (2.x §4).
 * L'état est porté ici ; la décision de fin (sauvegarde, arrêt) est prise par {@link SellingService}.
 * Thread client uniquement.
 */
final class SellCapture {
	static final int MAX_CLICKS = 60;
	static final int OPEN_TIMEOUT = 60;
	static final int CLOSED_TICKS = 3;
	static final int MAX_TICKS = 2400;

	/** Issue d'un tick de capture. */
	enum Outcome { CONTINUE, FINISH, CANCEL }

	boolean active;
	boolean trash;
	/** Item visé, ou null : détection automatique. */
	String target;
	/** Pattern auquel ajouter l'item capturé (null : pattern courant). */
	String patternName;
	final List<Click> clicks = new ArrayList<>();
	private Map<String, Integer> start = Map.of();
	private int ticks;
	private int closed;
	private boolean seen;
	private boolean warnedLimit;
	private boolean warnedDrag;

	/** Message d'arrêt (si CANCEL) ou items à enregistrer (si FINISH). */
	String cancelMessage;
	List<String> finishItems = List.of();

	void begin(boolean trash, String target, String patternName, LocalPlayer p) {
		this.active = true;
		this.trash = trash;
		this.target = target;
		this.patternName = patternName;
		this.clicks.clear();
		this.start = inventoryCounts(p);
		this.ticks = 0;
		this.closed = 0;
		this.seen = false;
		this.warnedLimit = false;
		this.warnedDrag = false;
		this.cancelMessage = null;
		this.finishItems = List.of();
	}

	void reset() {
		active = false;
		clicks.clear();
		start = Map.of();
		patternName = null;
		target = null;
	}

	int ticks() {
		return ticks;
	}

	/** Clic relevé (appelé en HEAD de handleContainerInput : l'état lu est celui d'avant le clic). */
	void record(int slotId, int button, ContainerInput input, Player player) {
		if (!active || player == null) return;
		AbstractContainerMenu menu = player.containerMenu;
		if (menu == null || menu == player.inventoryMenu || slotId < 0) return;
		if (clicks.size() >= MAX_CLICKS) {
			if (!warnedLimit) {
				warnedLimit = true;
				Notifier.hud(Texts.tr("autopaterns.selling.capture.limit", MAX_CLICKS));
			}
			return;
		}
		List<Slot> slots = Menus.slots(menu);
		if (slotId >= slots.size()) return;
		int code = Menus.code(input);
		if (code < 0) { // glisser-déposer : non rejouable
			if (!warnedDrag) {
				warnedDrag = true;
				Notifier.hud(Texts.tr("autopaterns.selling.capture.drag"));
			}
			return;
		}
		Slot slot = slots.get(slotId);
		ItemStack st = Menus.stack(slot);
		boolean isP = Menus.isPlayerSlot(slot);
		if (st.isEmpty()) {
			if (isP || Menus.carried(menu).isEmpty()) return; // clic dans le vide sans rien au curseur
			clicks.add(new Click(slot.index, button, code, false, "", "")); // « poser » dans un slot vide du menu
		} else {
			clicks.add(new Click(slot.index, button, code, isP, Menus.key(st), Menus.name(st, 256)));
		}
	}

	/** Un tick de capture (2.x §4.6). {@code modScreen} : un écran du mod est ouvert. */
	Outcome step(Minecraft mc, LocalPlayer p, boolean modScreen) {
		ticks++;
		if (modScreen) return cancel(Texts.tr("autopaterns.selling.capture.cancelled"));
		boolean open = Menus.screenOpen(mc, p);
		if (open) {
			seen = true;
			closed = 0;
		}
		if (!seen) {
			if (ticks > OPEN_TIMEOUT) return cancel(Texts.tr("autopaterns.selling.capture.noMenu"));
			return Outcome.CONTINUE;
		}
		List<String> dec = decreased(p);
		if (!dec.isEmpty() && clicks.isEmpty()) {
			Log.warn("item retiré pendant la capture sans aucun clic relevé : SellingClickMixin non appliqué ?");
			return cancel(Texts.tr("autopaterns.selling.capture.noClicks"));
		}
		if (!trash && !dec.isEmpty()) return finish(List.of(target != null ? target : pick(dec)));
		if (!open) {
			if (++closed > CLOSED_TICKS) {
				if (trash && !dec.isEmpty()) return finish(target != null ? List.of(target) : dec);
				return cancel(clicks.isEmpty() ? Texts.tr("autopaterns.selling.capture.cancelled")
					: Texts.tr(trash ? "autopaterns.selling.capture.closedTrash" : "autopaterns.selling.capture.closedSell"));
			}
		}
		if (ticks > MAX_TICKS) {
			return cancel(Texts.tr(trash ? "autopaterns.selling.capture.timeoutTrash" : "autopaterns.selling.capture.timeoutSell"));
		}
		return Outcome.CONTINUE;
	}

	private Outcome cancel(String msg) {
		cancelMessage = msg;
		return Outcome.CANCEL;
	}

	private Outcome finish(List<String> items) {
		finishItems = List.copyOf(items);
		return Outcome.FINISH;
	}

	/** Parmi plusieurs items diminués, celui qui a été cliqué en premier (2.x : ordre arbitraire d'un HashMap). */
	private String pick(List<String> dec) {
		for (Click c : clicks) {
			if (c.key().isEmpty()) continue;
			for (String k : dec) if (PatternOptions.sameItem(k, c.key())) return k;
		}
		return dec.get(0);
	}

	/** Quantités par item (cases 0..35, ordre des cases) + pile au curseur (une pile tenue ne compte pas comme retirée). */
	static Map<String, Integer> inventoryCounts(LocalPlayer p) {
		Map<String, Integer> m = new LinkedHashMap<>();
		if (p == null) return m;
		for (int i = 0; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (!st.isEmpty()) m.merge(Menus.key(st), st.getCount(), Integer::sum);
		}
		ItemStack cur = Menus.carried(p.containerMenu != null ? p.containerMenu : p.inventoryMenu);
		if (!cur.isEmpty()) m.merge(Menus.key(cur), cur.getCount(), Integer::sum);
		return m;
	}

	/** Items (présents au départ) dont la quantité a baissé, dans l'ordre des cases. */
	private List<String> decreased(LocalPlayer p) {
		Map<String, Integer> now = inventoryCounts(p);
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, Integer> e : start.entrySet()) {
			if (target != null && !PatternOptions.sameItem(target, e.getKey())) continue;
			if (now.getOrDefault(e.getKey(), 0) < e.getValue()) out.add(e.getKey());
		}
		return out;
	}

	/**
	 * Supprime les répétitions (le rejeu répète lui-même jusqu'à épuisement) et, en multi-items ({@code filter}),
	 * les clics d'inventaire portant sur d'autres items (2.x §4.7). Les clics côté menu sont toujours gardés.
	 */
	static List<Click> compactClicks(List<Click> clicks, String target, boolean filter) {
		List<Click> out = new ArrayList<>();
		for (int i = 0; i < clicks.size(); i++) {
			Click c = clicks.get(i);
			boolean pair = c.playerInv() && c.action() == SellRecipes.PICKUP && i + 1 < clicks.size()
				&& !clicks.get(i + 1).playerInv() && clicks.get(i + 1).key().isEmpty();
			if (c.playerInv() && filter && !PatternOptions.sameItem(target, c.key())) {
				if (pair) i++;
				continue;
			}
			if (pair) {
				int n = out.size();
				boolean dup = n >= 2 && out.get(n - 2).playerInv() && out.get(n - 2).key().equals(c.key())
					&& out.get(n - 2).action() == SellRecipes.PICKUP && out.get(n - 1).key().isEmpty();
				if (!dup) {
					out.add(c);
					out.add(clicks.get(i + 1));
				}
				i++;
				continue;
			}
			if (c.playerInv() && !out.isEmpty()) {
				Click last = out.get(out.size() - 1);
				if (last.playerInv() && last.key().equals(c.key()) && last.action() == c.action() && last.button() == c.button()) {
					continue;
				}
			}
			out.add(c);
		}
		return out;
	}
}

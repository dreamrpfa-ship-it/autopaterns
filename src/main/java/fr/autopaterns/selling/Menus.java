package fr.autopaterns.selling;

import java.util.List;
import java.util.function.Predicate;

import fr.autopaterns.core.Log;
import fr.autopaterns.patterns.PatternOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Lecture des menus (coffres de boutique, corbeille) et de l'inventaire, et envoi SÛR des clics.
 * Tous les appels Minecraft du module touchant aux conteneurs passent par ici (méthodes courtes, faciles à
 * corriger à la compilation si un nom a changé). Thread client uniquement.
 */
final class Menus {
	private Menus() {}

	// ------------------------------------------------------------------ appels Minecraft isolés

	/** Le slot appartient à l'inventaire du joueur (API NON CONFIRMÉE en 26.2 : champ {@code Slot.container}). */
	static boolean isPlayerSlot(Slot s) {
		return s.container instanceof Inventory;
	}

	/** Pile du slot (API NON CONFIRMÉE : {@code Slot#getItem()}). */
	static ItemStack stack(Slot s) {
		ItemStack st = s.getItem();
		return st == null ? ItemStack.EMPTY : st;
	}

	/** Pile tenue au curseur (API NON CONFIRMÉE : {@code AbstractContainerMenu#getCarried()}). */
	static ItemStack carried(AbstractContainerMenu menu) {
		if (menu == null) return ItemStack.EMPTY;
		ItemStack st = menu.getCarried();
		return st == null ? ItemStack.EMPTY : st;
	}

	/** Nom affiché d'une pile, tronqué (API NON CONFIRMÉE : {@code ItemStack#getHoverName()}). */
	static String name(ItemStack st, int max) {
		if (st == null || st.isEmpty()) return "";
		String s = st.getHoverName().getString();
		return s.length() <= max ? s : s.substring(0, max);
	}

	/** Ferme le conteneur côté serveur et client (API NON CONFIRMÉE : {@code LocalPlayer#closeContainer()}). */
	private static void closeContainerRaw(LocalPlayer p) {
		p.closeContainer();
	}

	// ------------------------------------------------------------------ état des menus

	/** Un menu de conteneur (autre que l'inventaire) est ouvert ET affiché. */
	static boolean screenOpen(Minecraft mc, LocalPlayer p) {
		return p != null && p.containerMenu != null && p.containerMenu != p.inventoryMenu && mc.gui.screen() != null;
	}

	/** Un menu de conteneur est ouvert côté joueur (même si l'écran a été remplacé). */
	static boolean menuOpen(Player p) {
		return p != null && p.containerMenu != null && p.containerMenu != p.inventoryMenu;
	}

	static List<Slot> slots(AbstractContainerMenu menu) {
		return menu == null ? List.of() : menu.slots;
	}

	/** Empreinte du contenu du menu hors inventaire du joueur (2.x). */
	static long signature(AbstractContainerMenu menu) {
		if (menu == null) return 0L;
		long h = menu.containerId;
		for (Slot s : slots(menu)) {
			if (isPlayerSlot(s)) continue;
			ItemStack st = stack(s);
			h = h * 31 + (st.isEmpty() ? 0 : key(st).hashCode() * 17L + name(st, 128).hashCode() + st.getCount());
		}
		return h;
	}

	/**
	 * Empreinte complète de l'état observable après un clic : menu, pile au curseur et inventaire du joueur.
	 * Sert à constater l'effet d'un clic avant d'en envoyer un autre (anti double-clic).
	 */
	static long stateSignature(LocalPlayer p) {
		AbstractContainerMenu menu = p.containerMenu;
		long h = signature(menu);
		ItemStack cur = carried(menu);
		h = h * 31 + (cur.isEmpty() ? 0 : key(cur).hashCode() * 7L + cur.getCount());
		for (int i = 0; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			h = h * 31 + (st.isEmpty() ? 0 : key(st).hashCode() + st.getCount());
		}
		return h;
	}

	static String key(ItemStack st) {
		String k = PatternOptions.itemId(st);
		return k == null ? "" : k;
	}

	// ------------------------------------------------------------------ inventaire

	/** Quantité de l'item (clé, {@code block.}/{@code item.} confondus) dans les cases 0..35. */
	static int count(LocalPlayer p, String key) {
		if (p == null || key == null) return 0;
		int n = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (!st.isEmpty() && PatternOptions.sameItem(key, key(st))) n += st.getCount();
		}
		return n;
	}

	/** Quantité de l'item tenue au curseur du menu courant. */
	static int carriedCount(LocalPlayer p, String key) {
		ItemStack cur = carried(p.containerMenu != null ? p.containerMenu : p.inventoryMenu);
		return !cur.isEmpty() && PatternOptions.sameItem(key, key(cur)) ? cur.getCount() : 0;
	}

	static int freeSlots(LocalPlayer p) {
		if (p == null) return 0;
		int n = 0;
		for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) n++;
		return n;
	}

	// ------------------------------------------------------------------ recherche de slots

	/** Premier slot du menu (hors joueur) contenant l'item. */
	static Slot findItem(AbstractContainerMenu menu, String key) {
		for (Slot s : slots(menu)) {
			if (isPlayerSlot(s)) continue;
			ItemStack st = stack(s);
			if (!st.isEmpty() && PatternOptions.sameItem(key, key(st))) return s;
		}
		return null;
	}

	/**
	 * Bouton du menu (hors joueur) dont le nom correspond au texte. 3.0 : un nom ÉGAL est préféré à un nom
	 * qui le CONTIENT (2.x prenait la première sous-chaîne, qui pouvait être un item). {@code avoidKey} :
	 * item à ne pas prendre pour un bouton (l'item vendu), sauf s'il est le seul candidat.
	 */
	static Slot findByName(AbstractContainerMenu menu, String text, String avoidKey) {
		String want = SellSteps.normalize(text);
		if (want.isEmpty()) return null;
		Slot exact = null, contains = null, avoided = null;
		for (Slot s : slots(menu)) {
			if (isPlayerSlot(s)) continue;
			ItemStack st = stack(s);
			if (st.isEmpty()) continue;
			String n = SellSteps.normalize(name(st, 256));
			if (!n.contains(want)) continue;
			if (avoidKey != null && PatternOptions.sameItem(avoidKey, key(st))) {
				if (avoided == null) avoided = s;
				continue;
			}
			if (n.equals(want)) {
				if (exact == null) exact = s;
			} else if (contains == null) {
				contains = s;
			}
		}
		return exact != null ? exact : (contains != null ? contains : avoided);
	}

	/** Slots de catégorie : items du menu dont l'identifiant ou le nom correspond à {@code category} (2.x). */
	static List<Slot> categorySlots(AbstractContainerMenu menu, String category) {
		String cat = category == null ? "" : category.trim().replace(':', '.');
		if (cat.isEmpty()) return List.of();
		String n = SellSteps.normalize(cat);
		java.util.ArrayList<Slot> out = new java.util.ArrayList<>();
		for (Slot s : slots(menu)) {
			if (isPlayerSlot(s)) continue;
			ItemStack st = stack(s);
			if (st.isEmpty()) continue;
			String k = key(st).toLowerCase(java.util.Locale.ROOT);
			if (k.endsWith("." + n) || k.equals(n) || SellSteps.normalize(name(st, 256)).equals(n)) out.add(s);
		}
		return out;
	}

	/** Slot correspondant à un clic capturé (algorithme 2.x §2.4 findRecorded). */
	static Slot findRecorded(AbstractContainerMenu menu, SellRecipes.Click c) {
		String want = SellSteps.normalize(c.name());
		Slot byName = null, bySlot = null, last = null;
		int matches = 0;
		for (Slot s : slots(menu)) {
			boolean isP = isPlayerSlot(s);
			if (isP != c.playerInv()) continue;
			ItemStack st = stack(s);
			if (st.isEmpty()) {
				if (c.key().isEmpty() && !isP && (last == null || s.index == c.slot())) {
					last = s;
					matches = 1;
				}
				continue;
			}
			if (c.key().isEmpty()) continue;
			if (!key(st).equals(c.key())) continue;
			if (isP) return s;
			matches++;
			last = s;
			if (SellSteps.normalize(name(st, 256)).equals(want)) {
				if (s.index == c.slot()) return s;
				if (byName == null) byName = s;
			} else if (s.index == c.slot()) {
				bySlot = s;
			}
		}
		if (byName != null) return byName;
		if (bySlot != null) return bySlot;
		return matches == 1 ? last : null;
	}

	/** Slot vide du menu (hors joueur), de préférence d'index {@code preferId}. */
	static Slot emptyMenuSlot(AbstractContainerMenu menu, int preferId) {
		Slot first = null;
		for (Slot s : slots(menu)) {
			if (isPlayerSlot(s) || !stack(s).isEmpty()) continue;
			if (s.index == preferId) return s;
			if (first == null) first = s;
		}
		return first;
	}

	/** Premier slot de l'inventaire du joueur (dans le menu) contenant l'item. */
	static Slot playerSlotWith(AbstractContainerMenu menu, String key) {
		for (Slot s : slots(menu)) {
			if (!isPlayerSlot(s)) continue;
			ItemStack st = stack(s);
			if (!st.isEmpty() && PatternOptions.sameItem(key, key(st))) return s;
		}
		return null;
	}

	/** Slot vide de l'inventaire du joueur dans le menu, de préférence {@code prefer}. */
	static Slot emptyPlayerSlot(AbstractContainerMenu menu, Slot prefer) {
		if (prefer != null && isPlayerSlot(prefer) && stack(prefer).isEmpty() && slotIn(menu, prefer)) return prefer;
		for (Slot s : slots(menu)) {
			if (isPlayerSlot(s) && stack(s).isEmpty()) return s;
		}
		return null;
	}

	static boolean slotIn(AbstractContainerMenu menu, Slot s) {
		List<Slot> l = slots(menu);
		return s != null && s.index >= 0 && s.index < l.size() && l.get(s.index) == s;
	}

	// ------------------------------------------------------------------ clics

	/** Code d'action 2.x → {@link ContainerInput}. */
	static ContainerInput input(int code) {
		return switch (code) {
			case SellRecipes.QUICK_MOVE -> ContainerInput.QUICK_MOVE;
			case SellRecipes.SWAP -> ContainerInput.SWAP;
			case SellRecipes.CLONE -> ContainerInput.CLONE;
			case SellRecipes.THROW -> ContainerInput.THROW;
			case SellRecipes.PICKUP_ALL -> ContainerInput.PICKUP_ALL;
			default -> ContainerInput.PICKUP;
		};
	}

	/** {@link ContainerInput} → code 2.x ; -1 pour le glisser-déposer (QUICK_CRAFT), non rejouable. */
	static int code(ContainerInput in) {
		if (in == null) return -1;
		return switch (in) {
			case PICKUP -> SellRecipes.PICKUP;
			case QUICK_MOVE -> SellRecipes.QUICK_MOVE;
			case SWAP -> SellRecipes.SWAP;
			case CLONE -> SellRecipes.CLONE;
			case THROW -> SellRecipes.THROW;
			case PICKUP_ALL -> SellRecipes.PICKUP_ALL;
			default -> -1;
		};
	}

	/**
	 * Clic vérifié : le menu visé doit toujours être le menu courant, le slot doit lui appartenir et son contenu
	 * doit satisfaire {@code expect} AU MOMENT du clic (sinon aucun clic n'est envoyé). Renvoie vrai si envoyé.
	 */
	static boolean click(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu, Slot slot, int button, ContainerInput in,
						 Predicate<ItemStack> expect) {
		MultiPlayerGameMode gm = mc.gameMode;
		if (gm == null || p == null || menu == null || slot == null) return false;
		if (p.containerMenu != menu || menu == p.inventoryMenu || !slotIn(menu, slot)) return false;
		ItemStack st = stack(slot);
		if (expect != null && !expect.test(st)) {
			Log.debug("vente : clic refusé sur le slot " + slot.index + " (contenu inattendu : " + (st.isEmpty() ? "vide" : key(st)) + ")");
			return false;
		}
		gm.handleContainerInput(menu.containerId, slot.index, button, in, p);
		return true;
	}

	/** Ferme proprement le menu ouvert : la pile éventuellement tenue au curseur est d'abord reposée dans l'inventaire. */
	static void closeSafely(Minecraft mc, LocalPlayer p, Slot preferReturn) {
		if (!menuOpen(p)) return;
		returnCarried(mc, p, preferReturn);
		closeContainerRaw(p);
	}

	/** Repose la pile du curseur dans une case vide de l'inventaire (évite qu'elle tombe au sol à la fermeture). */
	static boolean returnCarried(Minecraft mc, LocalPlayer p, Slot prefer) {
		AbstractContainerMenu menu = p.containerMenu;
		if (!menuOpen(p) || carried(menu).isEmpty()) return false;
		Slot dest = emptyPlayerSlot(menu, prefer);
		if (dest == null) return false;
		return click(mc, p, menu, dest, 0, ContainerInput.PICKUP, ItemStack::isEmpty);
	}

	/** Prédicat « le slot contient cet item ». */
	static Predicate<ItemStack> holding(String key) {
		return st -> !st.isEmpty() && PatternOptions.sameItem(key, key(st));
	}

	/** Prédicat « le slot contient exactement cette clé » (rejeu de capture). */
	static Predicate<ItemStack> holdingExact(String key) {
		return st -> !st.isEmpty() && key(st).equals(key);
	}
}

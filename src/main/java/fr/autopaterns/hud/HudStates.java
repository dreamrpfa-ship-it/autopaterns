package fr.autopaterns.hud;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Automation;
import fr.autopaterns.core.Log;
import fr.autopaterns.core.Notifier;
import fr.autopaterns.core.Phase;
import fr.autopaterns.core.Texts;
import fr.autopaterns.fishing.FishingService;
import fr.autopaterns.input.KeyDef;
import fr.autopaterns.input.Keys;
import fr.autopaterns.patterns.PatternEngine;
import fr.autopaterns.patterns.PatternOptions;
import fr.autopaterns.selling.SellOrigin;
import fr.autopaterns.selling.SellingService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * Construction de l'état du HUD à partir des API publiques des modules (équivalent de {@code hudState} /
 * {@code hudPreviewState} / {@code showStatusInner} 2.x).
 * Chaque lecture d'un autre module est protégée : une erreur dans un module ne coupe pas tout le HUD
 * (journalisée une seule fois par information).
 */
final class HudStates {
	// Couleurs de phase 2.x (§2.10 / §2.13)
	static final int C_OK = 0xFF46C37B;
	static final int C_WAIT = 0xFFF5A524;
	static final int C_REC = 0xFFE5484D;
	static final int C_PLAY = 0xFF4C9AFF;
	static final int C_LOOP = HudState.C_LOOP;
	static final int C_FISH = 0xFF3FD0E0;

	private static final int MAX_LOOT = 6;
	private static final long LOOT_REFRESH_NANOS = 200_000_000L;

	private final Set<String> warned = new HashSet<>();
	private long lootAt;
	private List<ItemStack> loot = List.of();
	private List<Integer> lootCounts = List.of();
	private int freeSlots;

	/** État réel (2.x {@code hudState}). */
	HudState build(Minecraft mc) {
		AutoPaterns ap = AutoPaterns.get();
		Automation au = ap.automation();
		PatternEngine pe = ap.patterns();
		SellingService sell = ap.selling();
		FishingService fish = ap.fishing();
		Phase phase = au.phase();

		String label = tr("autopaterns.hud.phase.idle");
		int accent = C_OK;
		String detail = null;
		boolean loop = safe("isLoop", pe::isLoop, false);
		switch (phase) {
			case PREPARING -> {
				label = tr("autopaterns.hud.phase.preparing");
				accent = C_WAIT;
				int n = Math.max(0, (Automation.WARMUP_TICKS - au.prepTicks() + 19) / 20);
				detail = Texts.tr("autopaterns.hud.detail.preparing", n);
			}
			case RECORDING -> {
				label = tr("autopaterns.hud.phase.recording");
				accent = C_REC;
				int ticks = safe("recordingTicks", pe::recordingTicks, 0);
				int breaks = safe("recordingBreaks", pe::recordingBreaks, 0);
				detail = Texts.tr("autopaterns.hud.detail.recording", Texts.duration(ticks), breaks, Keys.label(KeyDef.RECORD));
			}
			case APPROACH -> {
				label = tr("autopaterns.hud.phase.approach");
				accent = C_WAIT;
				detail = approachDetail(pe);
			}
			case PLAYING -> {
				label = tr(loop ? "autopaterns.hud.phase.loop" : "autopaterns.hud.phase.play");
				accent = loop ? C_LOOP : C_PLAY;
			}
			case REPAIR -> {
				label = tr("autopaterns.hud.phase.repair");
				accent = C_WAIT;
				BlockPos pos = safe("repairPos", pe::repairPos, null);
				if (pos != null) {
					int t = safe("repairTicks", pe::repairTicks, 0);
					detail = Texts.tr("autopaterns.hud.detail.repair", pos.getX(), pos.getY(), pos.getZ(), t / 20);
				}
			}
			case SELLING -> {
				boolean trash = safe("isTrashJob", sell::isTrashJob, false);
				label = tr(trash ? "autopaterns.hud.phase.trash" : "autopaterns.hud.phase.sell");
				accent = C_OK;
				detail = safe("jobDetail", sell::jobDetail, null);
			}
			case CAPTURE -> {
				label = tr("autopaterns.hud.phase.capture");
				accent = C_REC;
				detail = safe("captureDetail", sell::captureDetail, null);
			}
			case FISHING -> {
				label = tr("autopaterns.hud.phase.fishing");
				accent = C_FISH;
				detail = safe("fishingDetail", fish::detail, null);
			}
			default -> { }
		}

		boolean hasPattern = safe("hasPattern", pe::hasPattern, false);
		int current = 0;
		int total = 0;
		if (hasPattern && showsProgress(phase, sell)) {
			int size = safe("patternSize", pe::patternSize, 0);
			int index = safe("index", pe::index, 0);
			current = phase == Phase.PLAYING ? index : Math.min(index, size);
			total = size;
		}
		String name = safe("currentName", pe::currentName, null);
		String pattern = (name != null && !name.isBlank()) ? name : (hasPattern ? tr("autopaterns.hud.unnamed") : null);

		refreshInventory(mc, pe, hasPattern);

		double dev = safe("lastDev", pe::lastDev, 0.0);
		double maxDev = safe("repMaxDev", pe::repMaxDev, 0.0);
		int caught = safe("caught", fish::caught, 0);
		int missed = safe("missed", fish::missed, 0);
		boolean fishingShown = phase == Phase.FISHING || au.isFishing() || caught + missed > 0;

		String toast = Notifier.activeToast();
		return new HudState(
			phase != Phase.IDLE, label, accent, pattern, detail,
			current, total, safe("repetitions", pe::repetitions, 0) + 1, loop,
			dev, maxDev, dev > 0.1, total > 0,
			safe("diagCheckpoints", pe::diagCheckpoints, 0), safe("diagRepairs", pe::diagRepairs, 0),
			safe("diagRefills", pe::diagRefills, 0), safe("sales", sell::sales, 0), safe("trash", sell::trash, 0),
			freeSlots, loot, lootCounts, toast, Notifier.accentOf(toast),
			fishingShown, caught, missed);
	}

	/**
	 * Progression affichée en lecture, recalage, réparation et pendant une vente déclenchée par la lecture.
	 * (2.x l'affichait aussi pendant une vente de la pêche ou un test : corrigé, l'index du pattern n'y a pas de sens.)
	 */
	private boolean showsProgress(Phase phase, SellingService sell) {
		if (phase == Phase.PLAYING || phase == Phase.APPROACH || phase == Phase.REPAIR) return true;
		if (phase != Phase.SELLING) return false;
		SellOrigin o = safe("origin", sell::origin, null);
		return o == SellOrigin.RESUME || o == SellOrigin.NEXT_REP;
	}

	private String approachDetail(PatternEngine pe) {
		double d = safe("approachDistance", pe::approachDistance, -1.0);
		if (d < 0) return null;
		boolean resume = safe("approachResume", pe::approachResume, false);
		String head = resume
			? Texts.tr("autopaterns.hud.detail.approach.resume", safe("approachIndex", pe::approachIndex, 0))
			: tr("autopaterns.hud.detail.approach.start");
		return head + Texts.tr("autopaterns.hud.detail.approach.distance", Texts.fmt(d));
	}

	/** Cases libres et butin (items vendables / jetables présents), recalculés au plus toutes les 200 ms. */
	private void refreshInventory(Minecraft mc, PatternEngine pe, boolean hasPattern) {
		long now = System.nanoTime();
		if (now - lootAt < LOOT_REFRESH_NANOS && lootAt != 0) return;
		lootAt = now;
		LocalPlayer p = mc.player;
		if (p == null) {
			freeSlots = 0;
			loot = List.of();
			lootCounts = List.of();
			return;
		}
		freeSlots = safe("freeSlots", () -> SellingService.freeSlots(p), 0);
		if (!hasPattern) {
			loot = List.of();
			lootCounts = List.of();
			return;
		}
		PatternOptions o = safe("options", pe::options, null);
		if (o == null) {
			loot = List.of();
			lootCounts = List.of();
			return;
		}
		LinkedHashSet<String> ids = new LinkedHashSet<>(o.sellItems);
		ids.addAll(o.trashItems);
		String[] slotIds = new String[36];
		ItemStack[] stacks = new ItemStack[36];
		for (int i = 0; i < 36; i++) {
			ItemStack st = p.getInventory().getItem(i);
			stacks[i] = st;
			slotIds[i] = (st == null || st.isEmpty()) ? null : PatternOptions.itemId(st);
		}
		List<ItemStack> l = new ArrayList<>();
		List<Integer> c = new ArrayList<>();
		for (String id : ids) {
			if (l.size() >= MAX_LOOT) break;
			ItemStack first = null;
			int sum = 0;
			for (int i = 0; i < 36; i++) {
				if (slotIds[i] == null || !PatternOptions.sameItem(id, slotIds[i])) continue;
				if (first == null) first = stacks[i];
				sum += stacks[i].getCount();
			}
			if (first != null) {
				l.add(first.copy());
				c.add(sum);
			}
		}
		loot = List.copyOf(l);
		lootCounts = List.copyOf(c);
	}

	/** Aperçu de l'éditeur (2.x {@code hudPreviewState}) : état réel pendant une action, sinon exemple avec le butin réel. */
	HudState preview(Minecraft mc) {
		HudState s = build(mc);
		return s.active() ? s : HudState.sample(s.loot(), s.lootCounts());
	}

	/**
	 * Ligne d'état de la barre d'action quand le HUD est indisponible (2.x {@code showStatusInner}),
	 * ou null s'il n'y a rien à afficher. Recalage : distance réelle à la cible (bug 2.x n°10 corrigé).
	 */
	String fallbackStatus(Minecraft mc) {
		AutoPaterns ap = AutoPaterns.get();
		Phase phase = ap.automation().phase();
		PatternEngine pe = ap.patterns();
		switch (phase) {
			case IDLE -> {
				return null;
			}
			case RECORDING -> {
				int size = safe("recordingTicks", pe::recordingTicks, 0);
				return Texts.tr("autopaterns.hud.fallback.recording", String.format(Locale.FRANCE, "%.1f", size / 20.0), size);
			}
			case APPROACH -> {
				double d = safe("approachDistance", pe::approachDistance, -1.0);
				if (d < 0) return null;
				return safe("approachResume", pe::approachResume, false)
					? Texts.tr("autopaterns.hud.fallback.approach.resume", Texts.fmt(d), safe("approachIndex", pe::approachIndex, 0))
					: Texts.tr("autopaterns.hud.fallback.approach", Texts.fmt(d));
			}
			case PLAYING -> {
				boolean loop = safe("isLoop", pe::isLoop, false);
				String mode = loop
					? Texts.tr("autopaterns.hud.fallback.loop", safe("repetitions", pe::repetitions, 0) + 1)
					: tr("autopaterns.hud.fallback.play");
				int pct = 100 * safe("index", pe::index, 0) / Math.max(1, safe("patternSize", pe::patternSize, 0));
				return Texts.tr("autopaterns.hud.fallback.playing", mode, pct, Texts.fmt(safe("repMaxDev", pe::repMaxDev, 0.0)));
			}
			default -> {
				// 3.0 : les autres phases (préparation, réparation, vente, capture, pêche) ont aussi leur ligne.
				HudState s = build(mc);
				return s.detail() == null
					? Texts.tr("autopaterns.hud.fallback.other", s.label(), "")
					: Texts.tr("autopaterns.hud.fallback.other", s.label(), s.detail());
			}
		}
	}

	private static String tr(String key) {
		return Texts.tr(key);
	}

	/** Lit une information d'un autre module ; en cas d'erreur, valeur par défaut et journal (une fois). */
	private <T> T safe(String what, Supplier<T> s, T def) {
		try {
			T v = s.get();
			return v == null ? def : v;
		} catch (RuntimeException | LinkageError e) {
			if (warned.add(what)) Log.error("HUD : information « " + what + " » indisponible", e);
			return def;
		}
	}
}

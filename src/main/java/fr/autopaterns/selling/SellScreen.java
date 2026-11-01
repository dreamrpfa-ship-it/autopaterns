package fr.autopaterns.selling;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Texts;
import fr.autopaterns.gui.Theme;
import fr.autopaterns.gui.UiScreen;
import fr.autopaterns.gui.widget.UiButton;
import fr.autopaterns.gui.widget.UiText;
import fr.autopaterns.gui.widget.UiToggle;
import fr.autopaterns.patterns.PatternEngine;
import fr.autopaterns.patterns.PatternOptions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Réglages de vente et de corbeille automatiques d'un pattern (ex-SellScreen 2.x, les deux modes réunis dans un
 * seul écran à onglets) : activation, moment, seuil, délai, commande, page, catégorie, étapes, capture, items.
 * Les modifications portent sur une copie des options ; « Enregistrer » les écrit dans le pattern.
 */
public final class SellScreen extends UiScreen {
	private static final int ROW = 20;
	private static final int GAP = 4;

	private final String patternName;
	private final PatternOptions options;
	private boolean trash;
	private String captureText = "";
	private String status = "";
	private int statusColor = Theme.TEXT_DIM;
	private Set<String> captured = Set.of();

	// cache de validation des étapes (évite d'analyser à chaque image)
	private String stepsSpecCache;
	private String stepsErrorCache;
	private String stepsSummaryCache;

	/** @param patternName pattern dont on édite les options ; null = pattern courant */
	public SellScreen(Screen parent, String patternName) {
		super(titleOf(patternName), parent);
		this.patternName = patternName;
		PatternEngine pe = AutoPaterns.get().patterns();
		PatternOptions o = patternName == null ? pe.options() : pe.uiOptionsOf(patternName);
		this.options = o == null ? new PatternOptions() : o.copy();
	}

	private static Component titleOf(String patternName) {
		PatternEngine pe = AutoPaterns.get().patterns();
		String n = patternName != null ? patternName : (pe.hasPattern() ? pe.currentName() : null);
		String t = Texts.tr("autopaterns.selling.screen.title");
		return Texts.lit(n == null || n.isBlank() ? t : t + " — « " + n + " »");
	}

	@Override
	protected int preferredWidth() {
		return 440;
	}

	@Override
	protected int preferredHeight() {
		return 340;
	}

	@Override
	protected int footerHeight() {
		return ROW;
	}

	@Override
	protected String subtitle() {
		return Texts.tr(trash ? "autopaterns.selling.screen.flowTrash" : "autopaterns.selling.screen.flowSell");
	}

	private SellingService selling() {
		return AutoPaterns.get().selling();
	}

	private static boolean busy() {
		return AutoPaterns.get().automation().isBusy();
	}

	// ================================================================ construction

	@Override
	protected void build() {
		SellingService sv = selling();
		Set<String> cap = new HashSet<>(sv.recipes(trash).all().keySet());
		captured = cap;

		int x = left(), y = top(), w = contentWidth();
		int half = (w - GAP) / 2;
		int x2 = x + half + GAP, w2 = w - half - GAP;

		// onglets (avec l'état activé de chaque mode)
		content(new UiButton(x, y, half, ROW, Texts.tr("autopaterns.selling.tab.sell"), () -> switchTab(false))
			.style(trash ? UiButton.Style.NORMAL : UiButton.Style.PRIMARY).indicator(() -> options.sellEnabled));
		content(new UiButton(x2, y, w2, ROW, Texts.tr("autopaterns.selling.tab.trash"), () -> switchTab(true))
			.style(trash ? UiButton.Style.PRIMARY : UiButton.Style.NORMAL).indicator(() -> options.trashEnabled));
		y += ROW + 6;

		// statut (ou aide)
		if (!status.isEmpty()) {
			content(new UiText(x, y, w, 20, status).color(statusColor));
			y += font.width(status) > w ? 24 : 14;
		} else {
			content(new UiText(x, y, w, 10, Texts.tr(trash ? "autopaterns.selling.screen.helpTrash" : "autopaterns.selling.screen.helpSell")));
			y += 14;
		}

		// ---------------------------------------------------------------- déclenchement
		content(new UiText(x, y, w, 10, Texts.tr("autopaterns.selling.section.trigger")).section());
		y += 14;
		final boolean t = trash;
		content(new UiToggle(x, y, half, ROW, Texts.tr(t ? "autopaterns.selling.toggle.trash" : "autopaterns.selling.toggle.sell"),
			() -> options.enabled(t), v -> options.setEnabled(t, v)));
		content(new UiButton(x2, y, w2, ROW, () -> Texts.tr(options.between(t)
			? "autopaterns.selling.moment.between" : "autopaterns.selling.moment.during"),
			() -> options.setBetween(t, !options.between(t)))
			.tooltip(Texts.tr("autopaterns.selling.moment.tip")));
		y += ROW + GAP;
		content(new UiButton(x, y, half, ROW, () -> thresholdLabel(t),
			() -> options.setThreshold(t, PatternOptions.nextIn(PatternOptions.THRESHOLDS, options.threshold(t))))
			.tooltip(Texts.tr("autopaterns.selling.threshold.tip")));
		content(new UiButton(x2, y, w2, ROW, () -> Texts.tr("autopaterns.selling.delay", options.delay(t)),
			() -> options.setDelay(t, PatternOptions.nextIn(PatternOptions.DELAYS, options.delay(t)))));
		y += ROW + 8;

		// ---------------------------------------------------------------- commande, page, catégorie, étapes
		content(new UiText(x, y, w, 10, Texts.tr("autopaterns.selling.section.menu")).section());
		y += 14;
		if (!t) {
			int c3 = (w - 2 * GAP) / 3;
			int cx2 = x + c3 + GAP, cx3 = x + 2 * (c3 + GAP);
			content(new UiText(x, y, c3, 10, Texts.tr("autopaterns.selling.label.command")));
			content(new UiText(cx2, y, c3, 10, Texts.tr("autopaterns.selling.label.page")));
			content(new UiText(cx3, y, w - 2 * (c3 + GAP), 10, Texts.tr("autopaterns.selling.label.category")));
			y += 11;
			field(x, y, c3, options.sellCommand, 256, "/shop", v -> options.setCommand(false, v));
			field(cx2, y, c3, options.sellPageText, 64, Texts.tr("autopaterns.selling.hint.page"),
				v -> options.sellPageText = cut(v, 64));
			field(cx3, y, w - 2 * (c3 + GAP), options.sellCategory, 64, Texts.tr("autopaterns.selling.hint.category"),
				v -> options.sellCategory = cut(v.trim(), 64));
			y += ROW + GAP;
			content(new UiText(x, y, w, 10, Texts.tr("autopaterns.selling.label.steps")));
			y += 11;
			field(x, y, w, options.sellSteps, 256, PatternOptions.DEFAULT_STEPS, v -> options.sellSteps = cut(v, 256));
			y += ROW + 2;
			content(new UiText(x, y, w, 10, this::stepsError).color(Theme.BAD));
			content(new UiText(x, y, w, 10, this::stepsSummary).color(Theme.TEXT_DIM));
			y += 14;
		} else {
			content(new UiText(x, y, half, 10, Texts.tr("autopaterns.selling.label.command")));
			y += 11;
			field(x, y, half, options.trashCommand, 256, "/trash", v -> options.setCommand(true, v));
			content(new UiText(x2, y - 6, w2, 22, Texts.tr("autopaterns.selling.screen.trashHow")).color(Theme.TEXT_MUTED));
			y += ROW + 8;
		}

		// ---------------------------------------------------------------- capture
		content(new UiText(x, y, w, 10, Texts.tr("autopaterns.selling.section.capture")).section());
		y += 14;
		int bCap = 84, bForget = 60;
		int fw = w - bCap - bForget - 2 * GAP;
		field(x, y, fw, captureText, 64, Texts.tr("autopaterns.selling.hint.capture"), v -> captureText = v);
		content(new UiButton(x + fw + GAP, y, bCap, ROW, Texts.tr("autopaterns.selling.btn.capture"), this::capture)
			.style(UiButton.Style.DANGER).enabledWhen(() -> !busy())
			.tooltip(Texts.tr(t ? "autopaterns.selling.capture.tipTrash" : "autopaterns.selling.capture.tipSell")));
		content(new UiButton(x + w - bForget, y, bForget, ROW, Texts.tr("autopaterns.selling.btn.forget"), this::forget));
		y += ROW + 3;
		content(new UiText(x, y, w, 10, Texts.tr("autopaterns.selling.capture.help", cap.size())).color(Theme.TEXT_MUTED));
		y += 14;

		// ---------------------------------------------------------------- items
		content(new UiText(x, y, w, 10, Texts.tr(t ? "autopaterns.selling.section.itemsTrash" : "autopaterns.selling.section.itemsSell")).section());
		y += 14;
		content(new UiText(x, y, w, 10, this::itemsHeader).color(Theme.CYAN));
		y += 13;
		List<String> list = itemList();
		if (list.isEmpty()) {
			content(new UiText(x, y, w, 10, Texts.tr("autopaterns.selling.items.empty")));
			y += 14;
		}
		for (int i = 0; i < list.size(); i++) {
			String key = list.get(i);
			int bx = (i % 2 == 0) ? x : x2;
			int bw = (i % 2 == 0) ? half : w2;
			int by = y + (i / 2) * (ROW + 2);
			content(new UiButton(bx, by, bw, ROW, () -> itemLabel(key), () -> toggleItem(key))
				.indicator(() -> options.items(trash).contains(key))
				.tooltip(key));
		}

		// ---------------------------------------------------------------- pied
		int fy = footerY();
		int bw = (w - 3 * GAP) / 4;
		footer(new UiButton(x, fy, bw, ROW, Texts.tr("autopaterns.selling.btn.test"), this::test)
			.style(UiButton.Style.PRIMARY).enabledWhen(() -> !busy())
			.tooltip(Texts.tr("autopaterns.selling.btn.testTip")));
		footer(new UiButton(x + bw + GAP, fy, bw, ROW,
			Texts.tr(t ? "autopaterns.selling.btn.uncheckAll" : "autopaterns.selling.btn.defaultList"), this::listAction));
		footer(new UiButton(x + 2 * (bw + GAP), fy, bw, ROW, Texts.tr("autopaterns.selling.btn.save"), this::saveAndClose)
			.style(UiButton.Style.PRIMARY));
		footer(new UiButton(x + 3 * (bw + GAP), fy, w - 3 * (bw + GAP), ROW, Texts.tr("autopaterns.selling.btn.cancel"), this::onClose)
			.style(UiButton.Style.GHOST));
	}

	private EditBox field(int x, int y, int w, String value, int max, String hint, Consumer<String> onChange) {
		EditBox e = new EditBox(font, x, y, w, ROW, Texts.lit(hint));
		e.setMaxLength(max);
		e.setValue(value == null ? "" : value);
		setHint(e, hint);
		e.setResponder(onChange);
		return content(e);
	}

	/** Texte gris affiché dans un champ vide (API NON CONFIRMÉE en 26.2 : {@code EditBox#setHint(Component)}). */
	private static void setHint(EditBox e, String hint) {
		e.setHint(Texts.lit(hint));
	}

	// ================================================================ libellés

	private String thresholdLabel(boolean t) {
		int v = options.threshold(t);
		String verb = Texts.tr(t ? "autopaterns.selling.verb.trash" : "autopaterns.selling.verb.sell");
		if (v >= 36) return Texts.tr("autopaterns.selling.threshold.always", verb);
		if (v == 0) return Texts.tr("autopaterns.selling.threshold.full", verb);
		return Texts.tr(v > 1 ? "autopaterns.selling.threshold.many" : "autopaterns.selling.threshold.one", verb, v);
	}

	private String itemLabel(String key) {
		return PatternOptions.displayName(key) + (captured.contains(key) ? " ●" : "");
	}

	private String itemsHeader() {
		int n = options.items(trash).size();
		return Texts.tr(trash ? "autopaterns.selling.items.headerTrash" : "autopaterns.selling.items.headerSell",
			n, n > 1 ? "s" : "", captured.size());
	}

	private void refreshStepsCache() {
		String spec = options.sellSteps == null ? "" : options.sellSteps;
		if (spec.equals(stepsSpecCache)) return;
		stepsSpecCache = spec;
		try {
			List<SellSteps.Step> steps = SellSteps.parse(spec);
			List<String> parts = new ArrayList<>();
			for (SellSteps.Step s : steps) parts.add(s.describe());
			stepsErrorCache = null;
			stepsSummaryCache = "✔ " + String.join(" → ", parts);
		} catch (IllegalArgumentException e) {
			stepsErrorCache = Texts.tr("autopaterns.selling.status.steps", e.getMessage());
			stepsSummaryCache = null;
		}
	}

	private String stepsError() {
		refreshStepsCache();
		return stepsErrorCache;
	}

	private String stepsSummary() {
		refreshStepsCache();
		return stepsSummaryCache;
	}

	/** Items cochés (ordre de la liste) puis items de l'inventaire non cochés. */
	private List<String> itemList() {
		List<String> out = new ArrayList<>(options.items(trash));
		for (String k : selling().uiInventoryItems()) if (!out.contains(k)) out.add(k);
		return out;
	}

	// ================================================================ actions

	private void switchTab(boolean toTrash) {
		if (trash == toTrash) return;
		trash = toTrash;
		status = "";
		refresh();
	}

	private void setStatus(String msg, int color) {
		status = msg == null ? "" : msg;
		statusColor = color;
	}

	private void toggleItem(String key) {
		var set = options.items(trash);
		if (!set.remove(key)) {
			if (set.size() >= PatternOptions.MAX_ITEMS) {
				setStatus(Texts.tr("autopaterns.selling.status.tooMany", PatternOptions.MAX_ITEMS), Theme.BAD);
				refresh();
				return;
			}
			set.add(key);
		}
	}

	/** Vérifie les options avant enregistrement ; {@code requireItems} : un mode activé doit avoir des items. */
	private boolean validate(boolean requireItems) {
		if (!trash || options.sellEnabled) {
			try {
				SellSteps.parse(options.sellSteps);
			} catch (IllegalArgumentException e) {
				setStatus(Texts.tr("autopaterns.selling.status.steps", e.getMessage()), Theme.BAD);
				return false;
			}
		}
		if (requireItems) {
			for (boolean m : new boolean[] {trash, !trash}) {
				if (options.enabled(m) && options.items(m).isEmpty()) {
					setStatus(Texts.tr(m ? "autopaterns.selling.status.checkTrash" : "autopaterns.selling.status.checkSell"), Theme.BAD);
					return false;
				}
			}
		}
		return true;
	}

	/** Un pattern peut recevoir ces options (nommé, ou pattern courant chargé). */
	private boolean canSave() {
		return patternName != null || AutoPaterns.get().patterns().hasPattern();
	}

	/** Enregistre dans le pattern ; vrai si réussi. Le statut est mis à jour. */
	private boolean save(boolean requireItems) {
		if (!validate(requireItems)) return false;
		String r = AutoPaterns.get().patterns().uiSaveOptionsOf(patternName, options);
		boolean ok = r != null && r.startsWith("✔");
		if (ok) selling().forgetSession();
		setStatus(r == null ? "✖" : r, ok ? Theme.GOOD : Theme.BAD);
		return ok;
	}

	/** Avant Tester / Capturer : enregistre si possible ; sans pattern, les options de l'écran servent telles quelles. */
	private boolean saveIfPossible(boolean requireItems) {
		if (canSave()) return save(requireItems);
		return validate(requireItems);
	}

	private void saveAndClose() {
		if (save(true)) onClose();
		else refresh();
	}

	private void test() {
		if (options.items(trash).isEmpty()) {
			setStatus(Texts.tr(trash ? "autopaterns.selling.status.checkTrash" : "autopaterns.selling.status.checkSell"), Theme.BAD);
			refresh();
			return;
		}
		if (!saveIfPossible(true)) {
			refresh();
			return;
		}
		String r = selling().uiTest(trash, options);
		if (r == null || !r.startsWith("✔")) {
			setStatus(r, Theme.BAD);
			refresh();
		}
	}

	private void capture() {
		if (!saveIfPossible(false)) {
			refresh();
			return;
		}
		String r = selling().uiStartCapture(captureText, trash, patternName, options);
		if (r == null || !r.startsWith("✔")) {
			setStatus(r, Theme.BAD);
			refresh();
		}
	}

	private void forget() {
		String r = selling().uiForgetCapture(captureText, trash);
		setStatus(r, r != null && r.startsWith("✔") ? Theme.GOOD : Theme.BAD);
		refresh();
	}

	private void listAction() {
		if (trash) {
			options.trashItems.clear();
			setStatus(Texts.tr("autopaterns.selling.status.uncheckedAll"), Theme.GOOD);
		} else {
			int before = options.sellItems.size();
			for (String k : PatternOptions.DEFAULT_SELL) {
				if (options.sellItems.size() >= PatternOptions.MAX_ITEMS) break;
				options.sellItems.add(k);
			}
			setStatus(Texts.tr("autopaterns.selling.status.defaultList", PatternOptions.DEFAULT_SELL.size(),
				options.sellItems.size() - before), Theme.GOOD);
		}
		refresh();
	}

	private static String cut(String s, int max) {
		if (s == null) return "";
		return s.length() <= max ? s : s.substring(0, max);
	}
}

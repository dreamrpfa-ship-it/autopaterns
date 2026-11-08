package fr.autopaterns.patterns;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Automation;
import fr.autopaterns.core.Phase;
import fr.autopaterns.core.Texts;
import fr.autopaterns.gui.Theme;
import fr.autopaterns.gui.UiScreen;
import fr.autopaterns.gui.widget.UiButton;
import fr.autopaterns.gui.widget.UiList;
import fr.autopaterns.gui.widget.UiText;
import fr.autopaterns.selling.SellScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Menu PATTERNS (partie patterns de l'ancien ManagementScreen 2.x) : état du pattern courant, enregistrement et
 * lecture, patterns sauvegardés (charger, sauvegarder sous un nom, renommer, supprimer avec confirmation),
 * export / import, options du pattern, vente et corbeille.
 * Les actions « Options » et « Vente et corbeille » visent le pattern sélectionné dans la liste, sinon le courant.
 */
public final class PatternsScreen extends UiScreen {
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM HH:mm");
	private static final long LIST_REFRESH_NANOS = 2_000_000_000L;
	private static final int ROW_H = 22;
	private static final int VISIBLE_ROWS = 5;

	private List<PatternStore.Info> infos;
	private long listAt;
	private String selected;
	private String nameText;
	private String pendingDelete;
	private String pendingReplace;
	private EditBox nameBox;

	public PatternsScreen(Screen parent) {
		super(Texts.c("autopaterns.patterns.screen.title"), parent);
	}

	private static PatternEngine pe() {
		return AutoPaterns.get().patterns();
	}

	private static Automation au() {
		return AutoPaterns.get().automation();
	}

	@Override
	protected int preferredWidth() { return 400; }

	@Override
	protected int preferredHeight() { return 364; }

	@Override
	protected String subtitle() {
		return au().statusLine();
	}

	/** Relit la liste des patterns sauvegardés. */
	void reloadList() {
		infos = pe().uiList();
		listAt = System.nanoTime();
		if (selected != null && infos.stream().noneMatch(i -> i.name().equals(selected))) selected = null;
	}

	@Override
	protected void build() {
		PatternEngine pe = pe();
		if (infos == null || System.nanoTime() - listAt > LIST_REFRESH_NANOS) reloadList();
		if (nameText == null) nameText = pe.currentName() != null ? pe.currentName() : "";
		int x = left(), w = contentWidth(), y = top(), gap = 4, bh = 20;

		// --- pattern courant
		content(new UiText(x, y, w, 10, Msg.t("screen.current")).section());
		y += 14;
		content(new UiText(x, y, w, 20, pe::patternSummary).color(Theme.TEXT));
		y += 20;
		content(new UiText(x, y, w, 10, pe::keysHint));
		y += 14;
		int b4 = (w - 3 * gap) / 4;
		content(new UiButton(x, y, b4, bh,
			() -> au().phase() == Phase.RECORDING ? Msg.t("screen.recStop") : Msg.t("screen.rec"), this::record)
			.icon("●").enabledWhen(() -> {
				Phase ph = au().phase();
				return ph == Phase.IDLE || ph == Phase.RECORDING || ph == Phase.PREPARING;
			}).tooltip(Msg.t("screen.rec.tip")));
		content(new UiButton(x + (b4 + gap), y, b4, bh, () -> Msg.t("screen.play"), () -> play(false))
			.icon("▶").enabledWhen(() -> pe().hasPattern() && !au().isBusy()));
		content(new UiButton(x + 2 * (b4 + gap), y, b4, bh, () -> Msg.t("screen.loop"), () -> play(true))
			.icon("⟳").enabledWhen(() -> pe().hasPattern() && !au().isBusy()));
		content(new UiButton(x + 3 * (b4 + gap), y, w - 3 * (b4 + gap), bh, () -> Msg.t("screen.stop"), this::stop)
			.icon("■").style(UiButton.Style.DANGER).enabledWhen(() -> au().isBusy()));
		y += bh + gap;
		int b2 = (w - gap) / 2;
		content(new UiButton(x, y, b2, bh, () -> Msg.t("screen.options"), this::openOptions)
			.icon("⚙").enabledWhen(() -> !au().isBusy() && (selected != null || pe().hasPattern()))
			.tooltip(Msg.t("screen.target.tip")));
		content(new UiButton(x + b2 + gap, y, w - b2 - gap, bh, () -> Msg.t("screen.sell"), this::openSell)
			.icon("$").enabledWhen(() -> !au().isBusy() && (selected != null || pe().hasPattern()))
			.tooltip(Msg.t("screen.target.tip")));
		y += bh + 10;

		// --- patterns sauvegardés
		content(new UiText(x, y, w, 10, Msg.t("screen.saved", infos.size())).section());
		y += 14;
		UiList<String> list = new UiList<String>(x, y, w, VISIBLE_ROWS * ROW_H + 4, ROW_H)
			.emptyText(Msg.t("screen.list.empty"))
			.onSelect(this::select)
			.onOpen(n -> {
				select(n);
				load();
			});
		list.setRows(rows(pe.currentName()));
		if (selected != null) list.select(selected);
		content(list);
		y += VISIBLE_ROWS * ROW_H + 4 + gap;

		int saveW = 110;
		nameBox = new EditBox(font, x + 1, y + 1, w - saveW - gap - 2, bh - 2, Component.literal(Msg.t("screen.name")));
		nameBox.setMaxLength(40);
		nameBox.setValue(nameText);
		nameBox.setResponder(s -> {
			nameText = s;
			if (pendingReplace != null && !pendingReplace.equals(PatternStore.sanitize(s))) pendingReplace = null;
		});
		content(nameBox);
		content(new UiButton(x + w - saveW, y, saveW, bh,
			() -> isPendingReplace() ? Msg.t("screen.replace") : Msg.t("screen.save"), this::save)
			.style(UiButton.Style.PRIMARY)
			.enabledWhen(() -> pe().hasPattern() && au().phase() != Phase.RECORDING)
			.tooltip(Msg.t("screen.save.tip")));
		y += bh + gap;
		int b3 = (w - 2 * gap) / 3;
		content(new UiButton(x, y, b3, bh, () -> Msg.t("screen.load"), this::load)
			.enabledWhen(() -> selected != null && !au().isBusy()));
		content(new UiButton(x + b3 + gap, y, b3, bh, () -> Msg.t("screen.rename"), this::rename)
			.enabledWhen(() -> selected != null && PatternStore.sanitize(nameText) != null
				&& !PatternStore.sanitize(nameText).equals(selected))
			.tooltip(Msg.t("screen.rename.tip")));
		content(new UiButton(x + 2 * (b3 + gap), y, w - 2 * (b3 + gap), bh,
			() -> selected != null && selected.equals(pendingDelete) ? Msg.t("screen.deleteConfirm") : Msg.t("screen.delete"),
			this::delete).style(UiButton.Style.DANGER).enabledWhen(() -> selected != null));
		y += bh + gap;
		content(new UiButton(x, y, w, bh, this::hotkeyLabel, this::openHotkey)
			.icon("⌨").enabledWhen(() -> hotkeyTarget() != null && au().phase() != Phase.RECORDING)
			.tooltip(Msg.t("screen.hotkey.tip")));
		y += bh + 10;

		// --- partage
		content(new UiText(x, y, w, 10, Msg.t("screen.share")).section());
		y += 14;
		content(new UiButton(x, y, b3, bh, () -> Msg.t("screen.export"), this::export)
			.enabledWhen(() -> selected != null || pe().currentName() != null)
			.tooltip(Msg.t("screen.export.tip")));
		content(new UiButton(x + b3 + gap, y, b3, bh, () -> Msg.t("screen.import"),
			() -> minecraft.gui.setScreen(new ImportScreen(this))));
		content(new UiButton(x + 2 * (b3 + gap), y, w - 2 * (b3 + gap), bh, () -> Msg.t("screen.importDir"),
			() -> notify(pe().uiOpenImportFolder())));
	}

	private List<UiList.Row<String>> rows(String current) {
		List<UiList.Row<String>> out = new ArrayList<>(infos.size());
		for (PatternStore.Info i : infos) {
			boolean cur = i.name().equals(current);
			String dur = i.ticks() >= 0 ? Texts.duration(i.ticks()) : "?";
			String date = DATE.format(Instant.ofEpochMilli(i.modifiedMillis()).atZone(ZoneId.systemDefault()));
			String sub = Msg.t("screen.row", dur, date, i.bytes() / 1024);
			String hk = pe().uiHotkeyLabel(i.name());
			if (hk != null) sub += " · ⌨ " + hk;
			out.add(new UiList.Row<>(i.name(), (cur ? "▶ " : "") + i.name(), sub, cur ? Theme.CYAN : 0));
		}
		return out;
	}

	private boolean isPendingReplace() {
		return pendingReplace != null && pendingReplace.equals(PatternStore.sanitize(nameText));
	}

	private void select(String name) {
		if (!Objects.equals(name, selected)) {
			pendingDelete = null;
			pendingReplace = null;
		}
		selected = name;
		if (name != null) {
			nameText = name;
			if (nameBox != null) nameBox.setValue(name);
		}
	}

	// ------------------------------------------------------------------ actions

	private void record() {
		Automation au = au();
		if (au.phase() == Phase.IDLE) {
			closeAll(); // un écran du mod ouvert suspendrait l'enregistrement
			au.toggleRecord(minecraft);
		} else {
			au.toggleRecord(minecraft);
			reloadList();
			refresh();
		}
	}

	private void play(boolean loop) {
		closeAll(); // un écran du mod ouvert arrêterait la lecture
		au().togglePlayback(minecraft, loop);
	}

	private void stop() {
		au().stopAll(minecraft, Msg.t("screen.stopped"));
		reloadList();
		refresh();
	}

	/** Pattern visé par « Touche de lancement » : sélection, sinon pattern courant s'il est sauvegardé. */
	private String hotkeyTarget() {
		return selected != null ? selected : pe().currentName();
	}

	private String hotkeyLabel() {
		String t = hotkeyTarget();
		String hk = t == null ? null : pe().uiHotkeyLabel(t);
		return hk == null ? Msg.t("screen.hotkey") : Msg.t("screen.hotkey.with", hk);
	}

	private void openHotkey() {
		String t = hotkeyTarget();
		if (t != null) minecraft.gui.setScreen(new PatternHotkeyScreen(this, t));
	}

	private void openOptions() {
		minecraft.gui.setScreen(new PatternOptionsScreen(this, selected));
	}

	private void openSell() {
		minecraft.gui.setScreen(new SellScreen(this, selected));
	}

	private void save() {
		String s = PatternStore.sanitize(nameText);
		if (s != null && pe().uiWouldReplace(nameText) && !isPendingReplace()) {
			pendingReplace = s;
			notify("§e" + Msg.t("screen.confirmReplace", s));
			return;
		}
		pendingReplace = null;
		String r = pe().uiSave(nameText);
		notify(r);
		if (r.startsWith("✔")) {
			selected = pe().currentName();
			nameText = selected;
		}
		reloadList();
		refresh();
	}

	private void load() {
		if (selected == null) return;
		pendingDelete = null;
		notify(pe().uiLoad(selected));
		refresh();
	}

	private void rename() {
		if (selected == null) return;
		pendingDelete = null;
		String r = pe().uiRename(selected, nameText);
		notify(r);
		if (r.startsWith("✔")) {
			selected = PatternStore.sanitize(nameText);
			nameText = selected;
		}
		reloadList();
		refresh();
	}

	private void delete() {
		if (selected == null) return;
		if (!selected.equals(pendingDelete)) {
			pendingDelete = selected;
			notify("§e" + Msg.t("screen.confirmDelete", selected));
			return;
		}
		pendingDelete = null;
		String r = pe().uiDelete(selected);
		notify(r);
		if (r.startsWith("✔")) {
			selected = null;
			nameText = pe().currentName() != null ? pe().currentName() : "";
		}
		reloadList();
		refresh();
	}

	private void export() {
		notify(pe().uiExport(selected));
	}

	@Override
	protected void drawOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		EditBox b = nameBox;
		if (b != null && b.visible && !b.isFocused() && b.getValue().isEmpty()) {
			Theme.text(g, font, Msg.t("screen.name.hint"), b.getX() + 4, b.getY() + (b.getHeight() - 8) / 2, Theme.TEXT_MUTED);
		}
	}
}

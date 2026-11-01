package fr.autopaterns.patterns;

import java.util.ArrayList;
import java.util.List;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Texts;
import fr.autopaterns.gui.UiScreen;
import fr.autopaterns.gui.widget.UiButton;
import fr.autopaterns.gui.widget.UiList;
import fr.autopaterns.gui.widget.UiText;
import net.minecraft.client.gui.screens.Screen;

/**
 * Import de patterns (2.x ImportScreen) : fichiers .apt / .apt.gz déposés dans {@code autopaterns/import/}.
 * Double-clic ou « Importer » ; le fichier importé est rangé dans {@code import/deja_importes/}.
 */
public final class ImportScreen extends UiScreen {
	private List<PatternStore.Info> files;
	private String selected;

	public ImportScreen(Screen parent) {
		super(Texts.c("autopaterns.patterns.import.title"), parent);
	}

	@Override
	protected int preferredWidth() { return 380; }

	@Override
	protected int preferredHeight() { return 260; }

	@Override
	protected int footerHeight() { return 22; }

	@Override
	protected String subtitle() {
		return Msg.t("import.hint");
	}

	@Override
	protected void build() {
		PatternEngine pe = AutoPaterns.get().patterns();
		if (files == null) files = pe.uiImportable();
		if (selected != null && files.stream().noneMatch(i -> i.name().equals(selected))) selected = null;
		int x = left(), w = contentWidth(), y = top(), gap = 4;

		content(new UiText(x, y, w, 10, Msg.t("import.files", files.size())).section());
		y += 14;
		int h = Math.max(48, contentBottom() - y - 2);
		UiList<String> list = new UiList<String>(x, y, w, h, 22)
			.emptyText(Msg.t("import.empty"))
			.onSelect(n -> selected = n)
			.onOpen(n -> {
				selected = n;
				doImport();
			});
		List<UiList.Row<String>> rows = new ArrayList<>(files.size());
		for (PatternStore.Info i : files) {
			String dur = i.ticks() >= 0 ? Texts.duration(i.ticks()) : "?";
			rows.add(new UiList.Row<>(i.name(), i.name(), Msg.t("import.row", dur, i.bytes() / 1024), 0));
		}
		list.setRows(rows);
		if (selected != null) list.select(selected);
		content(list);

		int fy = footerY();
		int third = (w - 2 * gap) / 3;
		footer(new UiButton(x, fy, third, 18, () -> Msg.t("import.do"), this::doImport)
			.style(UiButton.Style.PRIMARY).enabledWhen(() -> selected != null));
		footer(new UiButton(x + third + gap, fy, third, 18, () -> Msg.t("import.openDir"),
			() -> notify(AutoPaterns.get().patterns().uiOpenImportFolder())));
		footer(new UiButton(x + 2 * (third + gap), fy, w - 2 * (third + gap), 18, () -> Msg.t("import.refresh"), () -> {
			files = null;
			refresh();
		}));
	}

	private void doImport() {
		if (selected == null) return;
		String r = AutoPaterns.get().patterns().uiImport(selected);
		notify(r);
		if (r.startsWith("✔")) {
			selected = null;
			if (parent instanceof PatternsScreen ps) ps.reloadList();
		}
		files = null;
		refresh();
	}
}

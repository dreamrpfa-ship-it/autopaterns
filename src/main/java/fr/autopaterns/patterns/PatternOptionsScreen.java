package fr.autopaterns.patterns;

import java.util.List;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Texts;
import fr.autopaterns.gui.UiScreen;
import fr.autopaterns.gui.widget.UiButton;
import fr.autopaterns.gui.widget.UiCycle;
import fr.autopaterns.gui.widget.UiText;
import fr.autopaterns.gui.widget.UiToggle;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Options d'un pattern (2.x PatternOptionsScreen) : action en cas d'erreur (rien / déconnexion / commande) et
 * remplacement automatique des items de la barre d'action. Les réglages de vente et corbeille sont dans
 * l'écran « Vente et corbeille ». Échap / Annuler : retour sans enregistrer.
 */
public final class PatternOptionsScreen extends UiScreen {
	private final String patternName;
	private final PatternOptions options;
	private String commandText;

	/** Options du pattern courant. */
	public PatternOptionsScreen(Screen parent) {
		this(parent, null);
	}

	/** Options du pattern sauvegardé {@code patternName} (null = pattern courant). */
	public PatternOptionsScreen(Screen parent, String patternName) {
		super(patternName == null ? Texts.c("autopaterns.patterns.options.title")
			: Texts.c("autopaterns.patterns.options.titleOf", patternName), parent);
		this.patternName = patternName;
		this.options = AutoPaterns.get().patterns().uiOptionsOf(patternName);
		this.commandText = options.command == null ? "" : options.command;
	}

	@Override
	protected int preferredWidth() { return 380; }

	@Override
	protected int preferredHeight() { return 300; }

	@Override
	protected int footerHeight() { return 22; }

	@Override
	protected String subtitle() {
		PatternEngine pe = AutoPaterns.get().patterns();
		return patternName == null || patternName.equals(pe.currentName()) ? pe.patternSummary() : Msg.t("options.inFile");
	}

	@Override
	protected void build() {
		int x = left(), w = contentWidth(), y = top(), gap = 4, bh = 20;

		content(new UiText(x, y, w, 10, Msg.t("options.errors")).section());
		y += 14;
		content(new UiCycle<PatternOptions.OnError>(x, y, w, bh, Msg.t("options.onError"), List.of(PatternOptions.OnError.values()),
			() -> options.onError, v -> {
				options.onError = v;
				refresh();
			}, e -> Msg.t("onerror." + e.name())));
		y += bh + gap;
		EditBox cmd = new EditBox(font, x + 1, y + 1, w - 2, bh - 2, Component.literal(Msg.t("options.command")));
		cmd.setMaxLength(256);
		cmd.setValue(commandText);
		cmd.setResponder(s -> commandText = s);
		cmd.active = options.onError == PatternOptions.OnError.COMMAND;
		content(cmd);
		y += bh + 2;
		content(new UiText(x, y, w, 10, () -> options.onError == PatternOptions.OnError.COMMAND
			? Msg.t("options.command.hint") : ""));
		y += 12;
		content(new UiText(x, y, w, 20, Msg.t("options.help")));
		y += 26;

		content(new UiText(x, y, w, 10, Msg.t("options.refill")).section());
		y += 14;
		int cellW = (w - 2 * gap) / 3;
		for (int i = 0; i < 9; i++) {
			final int idx = i;
			String item = options.slotItems[i];
			String label = (i + 1) + " · " + (item == null ? Msg.t("options.empty") : PatternOptions.displayName(item));
			UiToggle t = new UiToggle(x + (i % 3) * (cellW + gap), y + (i / 3) * (bh + 2), i % 3 == 2 ? w - 2 * (cellW + gap) : cellW, bh,
				label, () -> options.refill[idx], v -> options.refill[idx] = v);
			t.active = item != null;
			if (item != null) t.tooltip(item);
			content(t);
		}

		int fy = footerY();
		int half = (w - gap) / 2;
		footer(new UiButton(x, fy, half, 18, () -> Msg.t("options.save"), this::save).style(UiButton.Style.PRIMARY));
		footer(new UiButton(x + half + gap, fy, w - half - gap, 18, () -> Msg.t("options.cancel"), this::onClose));
	}

	private void save() {
		options.command = commandText == null ? "" : commandText;
		String r = AutoPaterns.get().patterns().uiSaveOptionsOf(patternName, options);
		notify(r);
		if (r.startsWith("✔")) onClose();
	}
}

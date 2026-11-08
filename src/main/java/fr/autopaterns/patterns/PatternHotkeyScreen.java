package fr.autopaterns.patterns;

import java.util.List;
import java.util.Map;

import org.lwjgl.glfw.GLFW;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.gui.Theme;
import fr.autopaterns.gui.UiScreen;
import fr.autopaterns.gui.widget.UiButton;
import fr.autopaterns.gui.widget.UiCycle;
import fr.autopaterns.gui.widget.UiText;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Écran « Touche de lancement » d'un pattern sauvegardé (3.1) : choix de la touche (capture du prochain appui,
 * Échap annule, Suppr / Retour arrière retire), mode « une fois » ou « en boucle », conflits avec les autres
 * raccourcis, et récapitulatif de toutes les touches de lancement. Sauvegarde immédiate dans config.txt.
 */
public final class PatternHotkeyScreen extends UiScreen {
	private final String pattern;
	private boolean capturing;
	/** Mode choisi avant qu'une touche soit assignée. */
	private PatternHotkeys.Mode pendingMode = PatternHotkeys.Mode.ONCE;

	public PatternHotkeyScreen(Screen parent, String pattern) {
		super(Component.literal(Msg.t("hotkey.title")), parent);
		this.pattern = pattern;
	}

	private static PatternEngine pe() {
		return AutoPaterns.get().patterns();
	}

	@Override
	protected int preferredWidth() { return 340; }

	@Override
	protected int preferredHeight() { return 280; }

	@Override
	protected String subtitle() {
		return capturing ? "§b" + Msg.t("hotkey.capture") : Msg.t("hotkey.subtitle", pattern);
	}

	private PatternHotkeys.Binding binding() {
		return pe().uiHotkey(pattern);
	}

	private PatternHotkeys.Mode mode() {
		PatternHotkeys.Binding b = binding();
		return b != null ? b.mode() : pendingMode;
	}

	@Override
	protected void build() {
		int x = left(), w = contentWidth(), y = top(), gap = 4, bh = 20;

		content(new UiText(x, y, w, 10, Msg.t("hotkey.key")).section());
		y += 14;
		content(new UiButton(x, y, w, bh + 4, this::keyButtonLabel, this::startCapture)
			.style(UiButton.Style.PRIMARY).tooltip(Msg.t("hotkey.key.tip")));
		y += bh + 4 + gap;
		content(new UiCycle<>(x, y, w, bh, Msg.t("hotkey.mode"), List.of(PatternHotkeys.Mode.values()),
			this::mode, this::setMode, PatternEngine::modeLabel));
		y += bh + gap;
		content(new UiButton(x, y, w, bh, () -> Msg.t("hotkey.clear"), this::clear)
			.style(UiButton.Style.DANGER).enabledWhen(() -> binding() != null));
		y += bh + gap + 2;
		content(new UiText(x, y, w, 20, this::conflictLine));
		y += 22;
		content(new UiText(x, y, w, 20, Msg.t("hotkey.help")).color(Theme.TEXT_DIM));
		y += 26;

		Map<String, PatternHotkeys.Binding> all = pe().uiHotkeys();
		content(new UiText(x, y, w, 10, Msg.t("hotkey.all", all.size())).section());
		y += 14;
		if (all.isEmpty()) {
			content(new UiText(x, y, w, 10, Msg.t("hotkey.all.empty")).color(Theme.TEXT_MUTED));
		}
		for (Map.Entry<String, PatternHotkeys.Binding> e : all.entrySet()) {
			String line = PatternHotkeys.label(e.getValue().key()) + "  →  « " + e.getKey() + " » · "
				+ PatternEngine.modeLabel(e.getValue().mode());
			content(new UiText(x, y, w, 10, line).color(e.getKey().equals(pattern) ? Theme.CYAN : Theme.TEXT));
			y += 12;
		}
	}

	private String keyButtonLabel() {
		if (capturing) return "> ? <";
		PatternHotkeys.Binding b = binding();
		return b == null ? Msg.t("hotkey.key.none") : Msg.t("hotkey.key.current", PatternHotkeys.label(b.key()));
	}

	private String conflictLine() {
		PatternHotkeys.Binding b = binding();
		if (b == null) return "";
		List<String> c = PatternHotkeys.conflicts(minecraft, b.key());
		return c.isEmpty() ? "§a" + Msg.t("hotkey.noConflict") : "§e" + Msg.t("hotkey.conflict", String.join(", ", c));
	}

	private void startCapture() {
		capturing = true;
	}

	private void setMode(PatternHotkeys.Mode m) {
		pendingMode = m;
		String r = pe().uiSetHotkeyMode(pattern, m);
		if (r != null) notify(r);
	}

	private void clear() {
		notify(pe().uiClearHotkey(pattern));
		refresh();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (capturing) {
			capturing = false;
			int k = event.key();
			if (k == GLFW.GLFW_KEY_ESCAPE) {
				notify(Msg.t("hotkey.cancel"));
			} else if (k == GLFW.GLFW_KEY_DELETE || k == GLFW.GLFW_KEY_BACKSPACE) {
				notify(pe().uiClearHotkey(pattern));
			} else if (k > 0) {
				notify(pe().uiSetHotkey(pattern, k, mode()));
			}
			refresh();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (capturing) {
			// Les boutons de souris ne sont pas pris en charge comme touche de lancement.
			capturing = false;
			notify(Msg.t("hotkey.mouse"));
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}
}

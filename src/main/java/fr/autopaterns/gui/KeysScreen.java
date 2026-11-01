package fr.autopaterns.gui;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import fr.autopaterns.core.Texts;
import fr.autopaterns.gui.widget.UiButton;
import fr.autopaterns.gui.widget.UiText;
import fr.autopaterns.gui.widget.UiWidget;
import fr.autopaterns.input.KeyDef;
import fr.autopaterns.input.Keys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Configuration centralisée des raccourcis du mod : touche actuelle, conflits (avec toutes les touches du jeu),
 * réassignation (clavier ou souris), désassignation (Suppr / Retour arrière), réinitialisation individuelle
 * ou globale. Sauvegarde automatique dans options.txt. La touche du menu elle-même est modifiable.
 */
public final class KeysScreen extends UiScreen {
	private KeyDef capturing;
	private KeyDef selected;

	public KeysScreen(Screen parent) {
		super(Texts.c("autopaterns.keys.title"), parent);
	}

	@Override
	protected int preferredWidth() { return 360; }

	@Override
	protected int preferredHeight() { return 300; }

	@Override
	protected int footerHeight() { return 22; }

	@Override
	protected String subtitle() {
		if (capturing != null) return "§b" + Texts.tr("autopaterns.keys.capture", Texts.tr(capturing.translationKey));
		return Texts.tr("autopaterns.keys.help");
	}

	@Override
	protected void build() {
		int x = left(), w = contentWidth(), y = top();
		KeyDef.Cat cat = null;
		for (KeyDef d : KeyDef.values()) {
			if (d.category != cat) {
				cat = d.category;
				content(new UiText(x, y + 3, w, 10, Texts.tr("key.category.autopaterns." + cat.id)).section());
				y += 16;
			}
			content(new KeyRow(x, y, w, 20, d));
			y += 22;
		}
		int fy = footerY();
		footer(new UiButton(left(), fy, 130, 18, () -> Texts.tr("autopaterns.keys.reset.one"), () -> {
			if (selected != null) {
				Keys.reset(selected);
				notify("§a" + Texts.tr("autopaterns.keys.reset.done", Texts.tr(selected.translationKey), Keys.label(selected)));
			}
		}).enabledWhen(() -> selected != null));
		footer(new UiButton(right() - 130, fy, 130, 18, () -> Texts.tr("autopaterns.keys.reset.all"), () -> {
			Keys.resetAll();
			notify("§a" + Texts.tr("autopaterns.keys.reset.alldone"));
		}).style(UiButton.Style.DANGER));
	}

	private void assign(KeyDef d, InputConstants.Key key) {
		KeyMapping km = Keys.mapping(d);
		if (km == null) return;
		Keys.rebind(km, key);
		Keys.drain(d);
		List<KeyMapping> c = Keys.conflicts(km);
		if (key == InputConstants.UNKNOWN) {
			notify(Texts.tr("autopaterns.keys.unbound", Texts.tr(d.translationKey)));
		} else if (c.isEmpty()) {
			notify("§a" + Texts.tr("autopaterns.keys.bound", Texts.tr(d.translationKey), Keys.label(d)));
		} else {
			notify("§e" + Texts.tr("autopaterns.keys.conflict", Keys.label(d), names(c)));
		}
	}

	private static String names(List<KeyMapping> list) {
		List<String> out = new ArrayList<>();
		for (KeyMapping k : list) out.add(Component.translatable(k.getName()).getString());
		return String.join(", ", out);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (capturing != null) {
			KeyDef d = capturing;
			capturing = null;
			int k = event.key();
			if (k == GLFW.GLFW_KEY_ESCAPE) {
				notify(Texts.tr("autopaterns.keys.cancel"));
			} else if (k == GLFW.GLFW_KEY_DELETE || k == GLFW.GLFW_KEY_BACKSPACE) {
				assign(d, InputConstants.UNKNOWN);
			} else {
				assign(d, InputConstants.getKey(event));
			}
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (capturing != null) {
			KeyDef d = capturing;
			capturing = null;
			assign(d, InputConstants.Type.MOUSE.getOrCreate(event.button()));
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	/** Ligne : nom de l'action, touche assignée (rouge si conflit), état de capture. */
	private final class KeyRow extends UiWidget {
		private final KeyDef def;

		KeyRow(int x, int y, int w, int h, KeyDef def) {
			super(x, y, w, h, Component.translatable(def.translationKey));
			this.def = def;
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {
			activate();
		}

		@Override
		protected void activate() {
			selected = def;
			capturing = def;
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			animate();
			int x = getX(), y = getY(), w = getWidth(), h = getHeight();
			boolean cap = capturing == def;
			boolean sel = selected == def;
			KeyMapping km = Keys.mapping(def);
			List<KeyMapping> conflicts = Keys.conflicts(km);
			int border = cap ? Theme.BORDER_FOCUS : (sel ? Theme.BORDER_HOVER : Theme.mix(Theme.BORDER, Theme.BORDER_HOVER, hover));
			Theme.tile(g, x, y, w, h, Theme.mix(Theme.SURFACE, Theme.SURFACE_HOVER, hover), border);
			String name = Texts.tr(def.translationKey);
			String key = cap ? "> ? <" : Keys.label(def);
			int kw = Math.max(54, font().width(key) + 14);
			int kx = x + w - kw - 4;
			int keyColor = cap ? Theme.CYAN : (km == null || km.isUnbound() ? Theme.TEXT_MUTED : (conflicts.isEmpty() ? Theme.TEXT : Theme.BAD));
			Theme.tile(g, kx, y + 3, kw, h - 6, 0xCC070B13, cap ? Theme.CYAN : (conflicts.isEmpty() ? Theme.BORDER : Theme.alpha(Theme.BAD, 0.8f)));
			Theme.centered(g, font(), key, kx + kw / 2, y + (h - 8) / 2, keyColor);
			int maxW = kx - x - 16;
			Theme.textShadow(g, font(), Theme.clip(font(), name, maxW), x + 8, y + (h - 8) / 2, Theme.TEXT);
			if (!conflicts.isEmpty() && isHovered()) {
				setTooltip(net.minecraft.client.gui.components.Tooltip.create(
					Component.literal(Texts.tr("autopaterns.keys.conflict.tip", names(conflicts)))));
			} else if (conflicts.isEmpty()) {
				setTooltip(null);
			}
		}
	}
}

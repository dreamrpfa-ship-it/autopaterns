package fr.autopaterns.gui.widget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.lwjgl.glfw.GLFW;

import fr.autopaterns.gui.Theme;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Liste défilante à sélection unique. Chaque ligne : titre, sous-titre optionnel (texte gris à droite
 * ou en dessous si la hauteur de ligne ≥ 22), pastille de couleur optionnelle.
 * Molette pour défiler, clic pour sélectionner, double-clic ou Entrée pour « ouvrir ».
 */
public class UiList<T> extends UiWidget {
	public record Row<T>(T value, String title, String subtitle, int accent) {
		public Row(T value, String title) {
			this(value, title, null, 0);
		}
	}

	private final List<Row<T>> rows = new ArrayList<>();
	private final int rowH;
	private int selected = -1;
	private double scroll;
	private Consumer<T> onSelect = v -> {};
	private Consumer<T> onOpen = v -> {};
	private String emptyText = "—";

	public UiList(int x, int y, int w, int h, int rowH) {
		super(x, y, w, h, Component.empty());
		this.rowH = Math.max(12, rowH);
	}

	public UiList<T> onSelect(Consumer<T> c) { this.onSelect = c; return this; }
	public UiList<T> onOpen(Consumer<T> c) { this.onOpen = c; return this; }
	public UiList<T> emptyText(String s) { this.emptyText = s; return this; }

	public void setRows(List<Row<T>> newRows) {
		T keep = getSelected();
		rows.clear();
		rows.addAll(newRows);
		selected = -1;
		if (keep != null) select(keep);
		clampScroll();
	}

	public List<Row<T>> rows() {
		return rows;
	}

	public T getSelected() {
		return selected >= 0 && selected < rows.size() ? rows.get(selected).value() : null;
	}

	public void select(T value) {
		for (int i = 0; i < rows.size(); i++) {
			if (java.util.Objects.equals(rows.get(i).value(), value)) {
				selected = i;
				ensureVisible(i);
				return;
			}
		}
	}

	public void clearSelection() {
		selected = -1;
	}

	private int contentH() {
		return rows.size() * rowH;
	}

	private void clampScroll() {
		double max = Math.max(0, contentH() - getHeight() + 4);
		scroll = Math.max(0, Math.min(max, scroll));
	}

	private void ensureVisible(int i) {
		int top = i * rowH;
		if (top < scroll) scroll = top;
		else if (top + rowH > scroll + getHeight() - 4) scroll = top + rowH - getHeight() + 4;
		clampScroll();
	}

	private int indexAt(double my) {
		int i = (int) Math.floor((my - getY() - 2 + scroll) / rowH);
		return i >= 0 && i < rows.size() ? i : -1;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		int i = indexAt(event.y());
		if (i < 0) return;
		selected = i;
		T v = rows.get(i).value();
		onSelect.accept(v);
		if (doubleClick) onOpen.accept(v);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		if (!isMouseOver(mx, my)) return false;
		scroll -= sy * rowH;
		clampScroll();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!isFocused() || rows.isEmpty()) return super.keyPressed(event);
		int k = event.key();
		if (k == GLFW.GLFW_KEY_DOWN || k == GLFW.GLFW_KEY_UP) {
			selected = Math.max(0, Math.min(rows.size() - 1, selected + (k == GLFW.GLFW_KEY_DOWN ? 1 : -1)));
			ensureVisible(selected);
			onSelect.accept(rows.get(selected).value());
			return true;
		}
		if ((k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) && selected >= 0) {
			onOpen.accept(rows.get(selected).value());
			return true;
		}
		return false;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		animate();
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();
		Theme.tile(g, x, y, w, h, 0x99070B13, isFocused() ? Theme.BORDER_HOVER : Theme.BORDER);
		if (rows.isEmpty()) {
			Theme.centered(g, font(), emptyText, x + w / 2, y + h / 2 - 4, Theme.TEXT_MUTED);
			return;
		}
		clampScroll();
		g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		int hoverIdx = isMouseOver(mouseX, mouseY) ? indexAt(mouseY) : -1;
		boolean twoLines = rowH >= 22;
		int first = Math.max(0, (int) (scroll / rowH));
		int barW = contentH() > h - 4 ? 4 : 0;
		for (int i = first; i < rows.size(); i++) {
			int ry = y + 2 + i * rowH - (int) scroll;
			if (ry > y + h) break;
			Row<T> r = rows.get(i);
			int rx = x + 2, rw = w - 4 - barW;
			if (i == selected) {
				g.fill(rx, ry, rx + rw, ry + rowH - 1, 0x3322D3EE);
				g.fill(rx, ry, rx + 2, ry + rowH - 1, Theme.CYAN);
			} else if (i == hoverIdx) {
				g.fill(rx, ry, rx + rw, ry + rowH - 1, 0x22FFFFFF);
			}
			int tx = rx + 7;
			if (r.accent() != 0) {
				Theme.dot(g, tx, ry + (twoLines ? 5 : (rowH - 4) / 2), r.accent());
				tx += 9;
			}
			int maxW = rx + rw - 6 - tx;
			if (twoLines) {
				Theme.textShadow(g, font(), Theme.clip(font(), r.title(), maxW), tx, ry + 3, Theme.TEXT);
				if (r.subtitle() != null) Theme.text(g, font(), Theme.clip(font(), r.subtitle(), maxW), tx, ry + 13, Theme.TEXT_DIM);
			} else {
				int ty = ry + (rowH - 8) / 2;
				String sub = r.subtitle();
				int subW = sub == null ? 0 : Math.min(maxW / 2, font().width(sub));
				Theme.textShadow(g, font(), Theme.clip(font(), r.title(), maxW - subW - (sub == null ? 0 : 8)), tx, ty, Theme.TEXT);
				if (sub != null) {
					String s2 = Theme.clip(font(), sub, subW);
					Theme.text(g, font(), s2, rx + rw - 6 - font().width(s2), ty, Theme.TEXT_DIM);
				}
			}
		}
		g.disableScissor();
		if (barW > 0) {
			int track = h - 4;
			int thumb = Math.max(12, Math.round(track * (h - 4f) / contentH()));
			double max = Math.max(1, contentH() - h + 4);
			int ty = y + 2 + (int) Math.round((track - thumb) * (scroll / max));
			g.fill(x + w - 5, y + 2, x + w - 2, y + h - 2, 0x33FFFFFF);
			g.fill(x + w - 5, ty, x + w - 2, ty + thumb, Theme.alpha(Theme.CYAN, 0.8f));
		}
	}
}

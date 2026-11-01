package fr.autopaterns.performance;

import java.util.List;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Texts;
import fr.autopaterns.gui.Theme;
import fr.autopaterns.gui.UiScreen;
import fr.autopaterns.gui.widget.UiButton;
import fr.autopaterns.gui.widget.UiCycle;
import fr.autopaterns.gui.widget.UiSlider;
import fr.autopaterns.gui.widget.UiText;
import fr.autopaterns.gui.widget.UiToggle;
import fr.autopaterns.gui.widget.UiWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Écran MODE PERFORMANCE : activation générale, profil, optimisations individuelles (interrupteurs et valeurs), état
 * actuel avec la liste des optimisations réellement appliquées, restauration immédiate et FPS en arrière-plan.
 */
public final class PerformanceScreen extends UiScreen {
	private static final List<PerfProfile> PROFILES = List.of(PerfProfile.BALANCED, PerfProfile.PERFORMANCE, PerfProfile.MAXIMUM);

	private final PerformanceMode perf;

	public PerformanceScreen(Screen parent) {
		super(Texts.c("autopaterns.performance.title"), parent);
		this.perf = AutoPaterns.get().performance();
	}

	@Override
	protected int preferredWidth() {
		return 400;
	}

	@Override
	protected int preferredHeight() {
		return 320;
	}

	@Override
	protected int footerHeight() {
		return 22;
	}

	@Override
	protected String subtitle() {
		if (perf.isActive()) return "§a" + Texts.tr("autopaterns.performance.state.active", perf.appliedCount());
		if (perf.hasPendingRestore()) return "§e" + Texts.tr("autopaterns.performance.state.pending");
		return perf.isEnabled()
			? Texts.tr("autopaterns.performance.state.ready")
			: Texts.tr("autopaterns.performance.state.off");
	}

	@Override
	protected void build() {
		int x = left(), w = contentWidth(), y = top();
		PerfSettings s = perf.settings();

		content(new UiToggle(x, y, w, 22, Texts.tr("autopaterns.performance.enabled"), perf::isEnabled, this::setEnabled)
			.tooltip(Texts.tr("autopaterns.performance.enabled.tip")));
		y += 26;
		content(new UiCycle<>(x, y, w, 20, Texts.tr("autopaterns.performance.profile"), PROFILES, s::profile, p -> {
			s.applyProfile(p);
			changed();
		}, PerfProfile::label));
		y += 22;
		content(new UiText(x + 2, y, w - 4, 20, () -> s.profile().description()));
		y += 24;

		content(new UiText(x, y, w, 10, Texts.tr("autopaterns.performance.section.state")).section());
		y += 14;
		int lines = Math.max(1, perf.results().size()) + 1 + (perf.lastProblem() != null ? 1 : 0);
		content(new ResultsView(x, y, w, lines * 10 + 2));
		y += lines * 10 + 6;

		content(new UiText(x, y, w, 10, Texts.tr("autopaterns.performance.section.opts")).section());
		y += 14;
		content(new UiText(x + 2, y, w - 4, 20, Texts.tr("autopaterns.performance.always")));
		y += 24;
		int half = (w - 4) / 2;
		int col = 0;
		for (PerfOpt o : PerfOpt.values()) {
			if (o.always) continue;
			if (o.hasValue()) {
				if (col == 1) {
					y += 22;
					col = 0;
				}
				content(new UiToggle(x, y, half, 20, o.label(), () -> s.on(o), v -> {
					s.set(o, v);
					changed();
				}).tooltip(o.tip()));
				content(new UiSlider(x + half + 4, y - 1, w - half - 4, 22, Texts.tr("autopaterns.performance.value"),
					o.min, o.max, o.step, () -> s.value(o), v -> {
						s.setValue(o, v);
						changed();
					}, o::formatValue).tooltip(o.tip()));
				y += 24;
				continue;
			}
			content(new UiToggle(col == 0 ? x : x + half + 4, y, col == 0 ? half : w - half - 4, 20, o.label(), () -> s.on(o), v -> {
				s.set(o, v);
				changed();
			}).tooltip(o.tip()));
			if (col == 0) {
				col = 1;
			} else {
				col = 0;
				y += 22;
			}
		}
		if (col == 1) y += 22;
		content(new UiText(x + 2, y + 2, w - 4, 20, () -> perf.isActive() ? "§e" + Texts.tr("autopaterns.performance.nextTime") : ""));

		int fy = footerY();
		int fw = (w - 4) / 2;
		footer(new UiButton(x, fy, fw, 18, Texts.tr("autopaterns.performance.restore"), () -> {
			notify(perf.restoreNow(minecraft));
			refresh();
		}).style(UiButton.Style.PRIMARY).enabledWhen(() -> perf.isActive() || perf.hasPendingRestore())
			.tooltip(Texts.tr("autopaterns.performance.restore.tip")));
		footer(new UiButton(x + fw + 4, fy, w - fw - 4, 18, this::backgroundLabel,
			() -> notify(AutoPaterns.get().automation().cycleBackgroundFps()))
			.tooltip(Texts.tr("autopaterns.performance.background.tip")));
	}

	private void setEnabled(boolean v) {
		AutoPaterns ap = AutoPaterns.get();
		if (ap.automation().isBusy()) {
			notify("§e" + Texts.tr("autopaterns.performance.busy"));
			return;
		}
		perf.setEnabled(v);
		ap.config().save();
		notify(Texts.tr(v ? "autopaterns.performance.toggled.on" : "autopaterns.performance.toggled.off"));
	}

	private void changed() {
		AutoPaterns.get().config().save();
	}

	private String backgroundLabel() {
		int fps = AutoPaterns.get().automation().backgroundFps();
		return Texts.tr("autopaterns.performance.background",
			fps == 0 ? Texts.tr("autopaterns.performance.background.unlimited") : Integer.toString(fps));
	}

	/** Liste colorée des résultats de la dernière activation. */
	private final class ResultsView extends UiWidget {
		ResultsView(int x, int y, int w, int h) {
			super(x, y, w, h, Component.empty());
			this.active = false;
		}

		@Override
		public boolean isMouseOver(double mx, double my) {
			return false;
		}

		@Override
		public void onClick(MouseButtonEvent event, boolean doubleClick) {}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
			int x = getX() + 2, y = getY(), w = getWidth() - 4;
			int bottom = getY() + getHeight();
			List<PerformanceMode.Result> res = perf.results();
			String head;
			int headColor;
			if (perf.isActive()) {
				head = Texts.tr("autopaterns.performance.results.now", perf.appliedCount());
				headColor = Theme.GOOD;
			} else if (res.isEmpty()) {
				head = Texts.tr("autopaterns.performance.results.never");
				headColor = Theme.TEXT_DIM;
			} else {
				head = Texts.tr("autopaterns.performance.results.last", perf.appliedCount());
				headColor = Theme.TEXT_DIM;
			}
			Theme.text(g, font(), Theme.clip(font(), head, w), x, y, headColor);
			y += 10;
			String problem = perf.lastProblem();
			if (problem != null && y + 9 <= bottom) {
				Theme.text(g, font(), Theme.clip(font(), "⚠ " + problem, w), x, y, Theme.WARN);
				y += 10;
			}
			for (PerformanceMode.Result r : res) {
				if (y + 9 > bottom) break;
				String icon;
				int color;
				switch (r.status()) {
					case APPLIED -> { icon = "✔"; color = Theme.GOOD; }
					case ALREADY -> { icon = "–"; color = Theme.TEXT_DIM; }
					case FAILED -> { icon = "✖"; color = Theme.BAD; }
					default -> { icon = "○"; color = Theme.TEXT_MUTED; }
				}
				Theme.text(g, font(), icon, x + 4, y, color);
				String label = r.opt().label();
				int lw = Math.min(w / 2, font().width(label));
				Theme.text(g, font(), Theme.clip(font(), label, w / 2), x + 16, y, r.status() == PerformanceMode.Status.APPLIED ? Theme.TEXT : Theme.TEXT_DIM);
				String d = r.detail() == null ? "" : r.detail();
				Theme.text(g, font(), Theme.clip(font(), d, w - 24 - lw), x + 24 + lw, y, color);
				y += 10;
			}
		}
	}
}

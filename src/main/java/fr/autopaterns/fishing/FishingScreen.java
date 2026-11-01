package fr.autopaterns.fishing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.Automation;
import fr.autopaterns.core.Log;
import fr.autopaterns.core.Texts;
import fr.autopaterns.gui.Theme;
import fr.autopaterns.gui.UiScreen;
import fr.autopaterns.gui.widget.UiButton;
import fr.autopaterns.gui.widget.UiCycle;
import fr.autopaterns.gui.widget.UiText;
import fr.autopaterns.gui.widget.UiToggle;
import fr.autopaterns.input.KeyDef;
import fr.autopaterns.input.Keys;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;

/**
 * Écran « Pêche automatique » : marche / arrêt (indicateur ON/OFF), tous les réglages 2.x (relance, attente,
 * anticipation, clic droit, corbeille/vente, diagnostic), méthode de capture préférée, état de la capture et du
 * calibrage, compteurs de la session, accès au dossier des images de diagnostic et au journal des erreurs.
 * Chaque changement de réglage est sauvegardé aussitôt dans config.txt. Comme tout écran, il met la pêche en pause.
 */
public final class FishingScreen extends UiScreen {
	private static final List<Integer> RECAST = boxed(FishingBot.RECAST_TICKS);
	private static final List<Integer> WAIT = boxed(FishingBot.WAIT_SECONDS);
	private static final List<Integer> LEADS = boxed(FishingBot.LEADS);
	private static final List<Integer> CLICKS = boxed(FishingBot.CLICK_RATES);
	private static final List<Boolean> METHODS = List.of(Boolean.TRUE, Boolean.FALSE);

	public FishingScreen(Screen parent) {
		super(Texts.c("autopaterns.fishing.title"), parent);
	}

	private static List<Integer> boxed(int[] a) {
		return Arrays.stream(a).boxed().toList();
	}

	private static String tr(String key, Object... args) {
		return Texts.tr("autopaterns.fishing." + key, args);
	}

	private static FishingBot bot() {
		return AutoPaterns.get().fishing().bot();
	}

	private static Automation automation() {
		return AutoPaterns.get().automation();
	}

	private static boolean isOn() {
		return automation().isFishing();
	}

	@Override
	protected int preferredWidth() {
		return 400;
	}

	@Override
	protected int preferredHeight() {
		return 330;
	}

	@Override
	protected String subtitle() {
		String s = automation().statusLine();
		return isOn() ? s + " · " + bot().captureMethod() : s;
	}

	@Override
	protected void build() {
		FishingBot b = bot();
		boolean on = isOn();
		int x = left();
		int y = top();
		int w = contentWidth();
		int half = (w - 6) / 2;
		int x2 = x + half + 6;
		int w2 = w - half - 6;

		// --- marche / arrêt
		content(new UiButton(x, y, w, 34, () -> tr(isOn() ? "stop" : "start"), this::toggle)
			.style(on ? UiButton.Style.DANGER : UiButton.Style.PRIMARY)
			.icon(on ? "■" : "▶")
			.subtitle(this::cardSubtitle)
			.indicator(FishingScreen::isOn)
			.enabledWhen(() -> isOn() || !automation().isBusy()));
		y += 40;

		// --- réglages
		content(new UiText(x, y, w, 10, tr("section.settings")).section());
		y += 14;
		Locale loc = numberLocale();
		UiCycle<Integer> recast = content(new UiCycle<Integer>(x, y, half, 20, tr("recast"), RECAST,
			() -> b.recastTicks, v -> { b.recastTicks = v; changed(); },
			v -> String.format(loc, "%.1f s", v / 20.0)));
		recast.tooltip(tr("recast.tip"));
		UiCycle<Integer> wait = content(new UiCycle<Integer>(x2, y, w2, 20, tr("wait"), WAIT,
			() -> b.waitSeconds, v -> { b.waitSeconds = v; changed(); },
			v -> v + " s"));
		wait.tooltip(tr("wait.tip"));
		y += 24;
		UiCycle<Integer> lead = content(new UiCycle<Integer>(x, y, half, 20, tr("lead"), LEADS,
			() -> b.lead, v -> { b.lead = v; changed(); },
			v -> tr("lead.value", v)));
		lead.tooltip(tr("lead.tip"));
		UiCycle<Integer> clicks = content(new UiCycle<Integer>(x2, y, w2, 20, tr("clicks"), CLICKS,
			() -> b.clickRate, v -> { b.clickRate = v; changed(); },
			v -> v > 0 ? tr("clicks.spam", v) : tr("clicks.hold")));
		clicks.tooltip(tr("clicks.tip"));
		y += 24;
		UiToggle clean = content(new UiToggle(x, y, half, 20, tr("clean"),
			() -> b.clean, v -> { b.clean = v; changed(); }));
		clean.tooltip(tr("clean.tip"));
		UiToggle diag = content(new UiToggle(x2, y, w2, 20, tr("diag"),
			() -> b.diagnostic, v -> { b.diagnostic = v; changed(); }));
		diag.tooltip(tr("diag.tip"));
		y += 28;

		// --- capture de l'écran
		content(new UiText(x, y, w, 10, tr("section.capture")).section());
		y += 14;
		content(new UiText(x, y, w, 30, this::captureInfo));
		y += 34;
		UiCycle<Boolean> method = content(new UiCycle<Boolean>(x, y, w, 20, tr("prefer"), METHODS,
			() -> b.gpu, v -> { b.gpu = v; changed(); },
			v -> v ? tr("prefer.gpu") : tr("prefer.screenshot")));
		method.tooltip(tr("prefer.tip"));
		y += 24;
		UiButton diagDir = content(new UiButton(x, y, half, 20, tr("openDiag"), () -> open(FishFiles.diagDir(), true)));
		diagDir.icon("⌂");
		diagDir.tooltip(tr("openDiag.tip"));
		UiButton errFile = content(new UiButton(x2, y, w2, 20, tr("openErrors"), () -> open(FishFiles.errorFile(), false)));
		errFile.icon("!");
		errFile.enabledWhen(() -> Files.isRegularFile(FishFiles.errorFile()));
		errFile.tooltip(tr("openErrors.tip"));
		y += 28;

		// --- session
		content(new UiText(x, y, w, 10, tr("section.session")).section());
		y += 14;
		content(new UiText(x, y, w, 20, this::sessionInfo).color(Theme.TEXT));
		y += 24;

		// --- aide (texte 2.x)
		content(new UiText(x, y, w, 10, tr("section.help")).section());
		y += 14;
		content(new UiText(x, y, w, 80, () -> tr("help")).color(Theme.TEXT_MUTED));
	}

	// ----------------------------------------------------------------- textes dynamiques

	private String cardSubtitle() {
		FishingBot b = bot();
		if (isOn()) return Texts.strip(b.detail());
		if (!b.failure().isEmpty()) return tr("card.failed", Texts.strip(b.failure()));
		return tr("card.off", b.catches(), b.misses(), Keys.label(KeyDef.FISHING));
	}

	private String captureInfo() {
		FishingBot b = bot();
		String method = isOn() ? b.captureMethod() : (b.gpu ? tr("prefer.gpu") : tr("prefer.screenshot"));
		StringBuilder sb = new StringBuilder();
		sb.append(tr("info.method", method)).append('\n');
		sb.append(tr("info.calib", b.calibrationState())).append('\n');
		sb.append(tr("info.stats", b.captures(), b.errorCount(), b.timeouts()));
		if (!b.lastErrorShort().isEmpty()) sb.append(tr("info.lastError", Texts.cut(b.lastErrorShort(), 60)));
		return sb.toString();
	}

	private String sessionInfo() {
		FishingBot b = bot();
		String s = tr("session", b.catches(), b.misses(), b.clicks());
		if (b.diagBroken()) s += "\n" + tr("session.diagOff");
		return s;
	}

	private static Locale numberLocale() {
		Locale l = Locale.forLanguageTag(Texts.tr("autopaterns.fishing.numberLocale"));
		return l.getLanguage().isEmpty() ? Locale.FRANCE : l;
	}

	// ----------------------------------------------------------------- actions

	private void toggle() {
		safe(() -> {
			Automation a = automation();
			if (a.isFishing()) {
				a.stopAll(minecraft, tr("stoppedMenu"));
				refresh();
			} else {
				String r = AutoPaterns.get().fishing().start(minecraft); // ferme l'écran en cas de succès
				if (r != null && !r.startsWith("✔")) notify(r);
			}
		});
	}

	private void changed() {
		AutoPaterns.get().config().save();
	}

	private void open(Path p, boolean dir) {
		safe(() -> {
			if (dir) {
				try {
					Files.createDirectories(p);
				} catch (IOException e) {
					throw new IllegalStateException(e);
				}
			}
			Util.getPlatform().openPath(p);
		});
	}

	private void safe(Runnable r) {
		try {
			r.run();
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable t) {
			Log.error("écran de la pêche : action impossible", t);
			notify(tr("error", t.getClass().getSimpleName()));
		}
	}
}

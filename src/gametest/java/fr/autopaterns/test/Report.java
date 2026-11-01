package fr.autopaterns.test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Rapport des tests en jeu : chaque vérification est enregistrée (PASS / FAIL + détail), écrite dans le journal du jeu
 * et dans {@code <dossier de jeu>/autopaterns/test-report.txt} (réécrit après chaque ligne : un plantage laisse un
 * rapport à jour). Un échec n'interrompt jamais la suite des tests.
 */
final class Report {
	/** Action de test pouvant lever n'importe quelle exception (enregistrée comme FAIL). */
	@FunctionalInterface
	interface Step {
		void run() throws Exception;
	}

	private static final Logger LOG = LoggerFactory.getLogger("AutoPaterns-GameTest");
	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final Path file;
	private final List<String> lines = new ArrayList<>();
	private final List<String> failures = new ArrayList<>();
	private String section = "-";
	private int pass;
	private boolean fileBroken;

	Report() {
		this.file = FabricLoader.getInstance().getGameDir().resolve("autopaterns").resolve("test-report.txt");
		lines.add("# AutoPaterns 3.0 - rapport des tests en jeu (" + LocalDateTime.now().format(TS) + ")");
		lines.add("# Format : PASS|FAIL|INFO  [section] vérification - détail");
		flush();
	}

	Path file() {
		return file;
	}

	/** Début d'une section (numéro de la liste de l'utilisateur + titre). */
	void section(String name) {
		section = name;
		lines.add("");
		lines.add("== " + name + " ==");
		LOG.info("[test] ===== {} =====", name);
		flush();
	}

	/** Enregistre une vérification ; renvoie {@code ok}. */
	boolean check(String what, boolean ok, String detail) {
		String d = detail == null || detail.isEmpty() ? "" : " - " + detail;
		String line = (ok ? "PASS" : "FAIL") + "  [" + section + "] " + what + d;
		lines.add(line);
		if (ok) {
			pass++;
			LOG.info("[test] {}", line);
		} else {
			failures.add(line);
			LOG.error("[test] {}", line);
		}
		flush();
		return ok;
	}

	boolean check(String what, boolean ok) {
		return check(what, ok, null);
	}

	/** Égalité exacte (détail « attendu / obtenu » en cas d'échec). */
	boolean equal(String what, Object expected, Object actual) {
		boolean ok = java.util.Objects.equals(expected, actual);
		return check(what, ok, ok ? String.valueOf(actual) : "attendu « " + expected + " », obtenu « " + actual + " »");
	}

	/** Information (ni succès ni échec). */
	void info(String text) {
		String line = "INFO  [" + section + "] " + text;
		lines.add(line);
		LOG.info("[test] {}", line);
		flush();
	}

	/** Exécute une étape ; toute exception (y compris AssertionError) devient un FAIL et le test continue. */
	void run(String what, Step step) {
		try {
			step.run();
		} catch (VirtualMachineError e) {
			throw e;
		} catch (Throwable t) {
			fail(what, t);
		}
	}

	/** Échec sur exception (trace complète dans le rapport). */
	void fail(String what, Throwable t) {
		StringWriter sw = new StringWriter();
		t.printStackTrace(new PrintWriter(sw));
		check(what, false, "exception " + t);
		for (String s : sw.toString().split("\\R")) {
			if (!s.isBlank()) lines.add("      " + s);
		}
		LOG.error("[test] exception dans « " + what + " »", t);
		flush();
	}

	int failureCount() {
		return failures.size();
	}

	int passCount() {
		return pass;
	}

	/** Ligne finale + liste des échecs. */
	void finish() {
		lines.add("");
		lines.add("== BILAN ==");
		lines.add("PASS " + pass + " / FAIL " + failures.size());
		if (!failures.isEmpty()) {
			lines.add("Échecs :");
			lines.addAll(failures);
		}
		LOG.info("[test] bilan : {} PASS, {} FAIL (rapport : {})", pass, failures.size(), file);
		flush();
	}

	private void flush() {
		if (fileBroken) return;
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			fileBroken = true;
			LOG.error("[test] rapport non écrit : " + file, e);
		}
	}
}

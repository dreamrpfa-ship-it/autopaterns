package fr.autopaterns.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Emplacements des fichiers du mod. Tout vit sous {@code <dossier de jeu>/autopaterns/}
 * (même dossier que la version 2.x : les données existantes sont reprises telles quelles).
 */
public final class ModPaths {
	private ModPaths() {}

	/** Dossier de jeu (.minecraft ou dossier d'instance). */
	public static Path gameDir() {
		return FabricLoader.getInstance().getGameDir();
	}

	/** {@code <jeu>/autopaterns}. */
	public static Path root() {
		return gameDir().resolve("autopaterns");
	}

	/** Ancien dossier des toutes premières versions (« autopatern », sans s). */
	public static Path legacyRoot() {
		return gameDir().resolve("autopatern");
	}

	public static Path file(String name) {
		return root().resolve(name);
	}

	/** {@code <jeu>/autopaterns/journal} : journaux de diagnostic du mod (3.0). */
	public static Path logDir() {
		return root().resolve("journal");
	}

	public static Path ensureRoot() throws IOException {
		Path r = root();
		Files.createDirectories(r);
		return r;
	}
}

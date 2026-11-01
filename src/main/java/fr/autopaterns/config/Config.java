package fr.autopaterns.config;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import fr.autopaterns.core.AutoPaterns;
import fr.autopaterns.core.ClientModule;
import fr.autopaterns.core.Log;
import fr.autopaterns.core.ModPaths;
import fr.autopaterns.storage.AtomicFiles;

/**
 * {@code autopaterns/config.txt} (format java.util.Properties, UTF-8), compatible 2.x.
 * Différence avec 2.x : les clés inconnues sont conservées (le fichier n'est plus reconstruit de zéro)
 * et l'écriture est atomique. Chaque module lit ses clés dans {@link ClientModule#onLoad} et les écrit
 * dans {@link ClientModule#saveConfig}. La sauvegarde est automatique : appeler {@link #save()} après
 * chaque modification de réglage.
 */
public final class Config {
	private final Properties props = new Properties();
	private boolean loaded;

	public Path file() {
		return ModPaths.file("config.txt");
	}

	public Properties load() {
		props.clear();
		Path f = file();
		if (Files.isRegularFile(f)) {
			try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
				props.load(r);
			} catch (IOException | RuntimeException e) {
				Log.warn("config.txt illisible, réglages par défaut : " + e);
			}
		}
		loaded = true;
		return props;
	}

	public Properties props() {
		return props;
	}

	public boolean isLoaded() {
		return loaded;
	}

	/** Collecte les réglages de tous les modules et écrit le fichier (atomique). */
	public void save() {
		if (!loaded) return; // ne jamais écraser un fichier existant avant de l'avoir lu
		try {
			for (ClientModule m : AutoPaterns.get().modules()) {
				try {
					m.saveConfig(props);
				} catch (RuntimeException e) {
					Log.error("réglages de " + m.name() + " non collectés", e);
				}
			}
			StringWriter w = new StringWriter();
			props.store(w, "AutoPaterns");
			AtomicFiles.writeString(file(), w.toString());
		} catch (IOException | RuntimeException e) {
			Log.error("configuration non sauvegardée", e);
		}
	}

	public String get(String key, String def) {
		String v = props.getProperty(key);
		return v == null ? def : v;
	}

	public int getInt(String key, int def) {
		try {
			return Integer.parseInt(get(key, Integer.toString(def)).trim());
		} catch (RuntimeException e) {
			return def;
		}
	}

	public boolean getBool(String key, boolean def) {
		String v = props.getProperty(key);
		return v == null ? def : !"false".equalsIgnoreCase(v.trim());
	}

	public void put(String key, String value) {
		if (value == null) props.remove(key);
		else props.setProperty(key, value);
	}
}

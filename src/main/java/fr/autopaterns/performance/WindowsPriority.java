package fr.autopaterns.performance;

import java.util.Locale;

import com.sun.jna.Function;
import com.sun.jna.Pointer;

import fr.autopaterns.core.Log;

/**
 * Priorité du processus du jeu sous Windows (kernel32 via JNA, bibliothèque livrée avec Minecraft).
 * Appels directs à l'API JNA (plus de réflexion comme en 2.x). Hors Windows : rien.
 * La priorité ne survit pas au processus : rien à restaurer après un crash.
 */
final class WindowsPriority {
	static final int NORMAL_PRIORITY_CLASS = 0x20;
	static final int HIGH_PRIORITY_CLASS = 0x80;

	private WindowsPriority() {}

	static boolean supported() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
	}

	/** Change la classe de priorité ; renvoie l'ancienne classe, ou -1 en cas d'échec / hors Windows. */
	static int set(int cls) {
		if (!supported()) return -1;
		try {
			Pointer h = (Pointer) Function.getFunction("kernel32", "GetCurrentProcess").invoke(Pointer.class, new Object[0]);
			int old = Function.getFunction("kernel32", "GetPriorityClass").invokeInt(new Object[] {h});
			int ok = Function.getFunction("kernel32", "SetPriorityClass").invokeInt(new Object[] {h, cls});
			return (ok != 0 && old != 0) ? old : -1;
		} catch (RuntimeException | LinkageError e) {
			Log.warn("priorité du processus non modifiée : " + e);
			return -1;
		}
	}
}

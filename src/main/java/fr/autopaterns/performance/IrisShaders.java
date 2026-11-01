package fr.autopaterns.performance;

import java.lang.reflect.Method;

import fr.autopaterns.core.Log;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Shaders du mod tiers Iris, via son API publique {@code net.irisshaders.iris.api.v0.IrisApi}.
 *
 * <p><b>Réflexion</b> : Iris est un mod optionnel absent à la compilation ; c'est le SEUL usage de réflexion du module
 * (aucune réflexion sur Minecraft). Méthodes appelées (API publique stable v0) : {@code IrisApi.getInstance()},
 * {@code IrisApi#getConfig()}, {@code IrisApiConfig#areShadersEnabled()},
 * {@code IrisApiConfig#setShadersEnabledAndApply(boolean)}. Iris absent → rien n'est tenté (pas d'exception).
 */
final class IrisShaders {
	private static final String API = "net.irisshaders.iris.api.v0.IrisApi";
	private static final String CONFIG = "net.irisshaders.iris.api.v0.IrisApiConfig";
	private static boolean warned;

	private IrisShaders() {}

	static boolean present() {
		try {
			return FabricLoader.getInstance().isModLoaded("iris");
		} catch (RuntimeException | LinkageError e) {
			return false;
		}
	}

	/** Shaders actifs ? null si Iris est absent ou ne répond pas. */
	static Boolean enabled() {
		if (!present()) return null;
		try {
			Object cfg = config();
			Method m = Class.forName(CONFIG).getMethod("areShadersEnabled");
			Object v = m.invoke(cfg);
			return v instanceof Boolean b ? b : null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			warn(e);
			return null;
		}
	}

	/** Active / coupe les shaders ; true si l'appel a abouti. */
	static boolean setEnabled(boolean on) {
		if (!present()) return false;
		try {
			Object cfg = config();
			Method m = Class.forName(CONFIG).getMethod("setShadersEnabledAndApply", boolean.class);
			m.invoke(cfg, on);
			return true;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			warn(e);
			return false;
		}
	}

	private static Object config() throws ReflectiveOperationException {
		Class<?> api = Class.forName(API);
		Object inst = api.getMethod("getInstance").invoke(null);
		return api.getMethod("getConfig").invoke(inst);
	}

	private static void warn(Throwable e) {
		Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
		if (!warned) {
			warned = true;
			Log.error("shaders Iris non modifiés", cause);
		} else {
			Log.warn("shaders Iris non modifiés : " + cause);
		}
	}
}

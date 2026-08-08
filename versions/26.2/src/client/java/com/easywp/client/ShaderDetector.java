package com.easywp.client;

import java.lang.reflect.Method;

/**
 * Reflection-based shaderpack detector for Iris, Oculus, and OptiFine.
 */
public final class ShaderDetector {

    private static volatile boolean cachedResult = false;
    private static long lastCheckTimeMs = 0;
    private static final long CACHE_DURATION_MS = 1000;

    private static Boolean irisAvailable = null;
    private static Method irisGetInstance = null;
    private static Method irisIsShaderPackInUse = null;
    private static Method irisIsRenderingShadowPass = null;

    private static Boolean optiFineAvailable = null;
    private static Method optiFineIsShaders = null;

    private ShaderDetector() { }

    /**
     * Checks if a shaderpack is active (cached for 1 second).
     */
    public static boolean isShaderPackActive() {
        long now = System.currentTimeMillis();
        if (now - lastCheckTimeMs < CACHE_DURATION_MS) {
            return cachedResult;
        }
        lastCheckTimeMs = now;
        cachedResult = checkIrisOrOculus() || checkOptiFine();
        return cachedResult;
    }

    /**
     * Checks if Iris/Oculus is currently rendering the shadow pass.
     * Prevents rendering 3D waypoint billboards into the shadow map.
     */
    public static boolean isRenderingShadowPass() {
        if (!isShaderPackActive()) return false;
        if (irisAvailable != null && irisAvailable && irisIsRenderingShadowPass != null) {
            try {
                Object instance = irisGetInstance.invoke(null);
                return (Boolean) irisIsRenderingShadowPass.invoke(instance);
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private static boolean checkIrisOrOculus() {
        if (irisAvailable == null) {
            irisAvailable = tryLoadIrisApi("net.irisshaders.iris.api.v0.IrisApi");
            if (!irisAvailable) {
                irisAvailable = tryLoadIrisApi("net.coderbot.iris.api.v0.IrisApi");
            }
        }
        if (!irisAvailable) return false;

        try {
            Object instance = irisGetInstance.invoke(null);
            return (Boolean) irisIsShaderPackInUse.invoke(instance);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean tryLoadIrisApi(String className) {
        try {
            Class<?> apiClass = Class.forName(className);
            irisGetInstance           = apiClass.getMethod("getInstance");
            irisIsShaderPackInUse     = apiClass.getMethod("isShaderPackInUse");
            try {
                irisIsRenderingShadowPass = apiClass.getMethod("isRenderingShadowPass");
            } catch (NoSuchMethodException e) {
                irisIsRenderingShadowPass = null;
            }
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return false;
        }
    }

    private static boolean checkOptiFine() {
        if (optiFineAvailable == null) {
            try {
                Class<?> configClass = Class.forName("net.optifine.Config");
                optiFineIsShaders = configClass.getMethod("isShaders");
                optiFineAvailable = true;
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                optiFineAvailable = false;
            }
        }
        if (!optiFineAvailable) return false;

        try {
            return (Boolean) optiFineIsShaders.invoke(null);
        } catch (Exception e) {
            return false;
        }
    }
}

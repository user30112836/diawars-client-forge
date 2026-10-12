package de.davidsw.diawarsclient;

import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Forge port of the Fabric {@code ShaderInfoCollector}.
 *
 * <p>The Iris shader API is accessed reflectively so the mod works with and
 * without a shader loader present. On Forge the Iris fork is called
 * <b>Oculus</b>, but it exposes the same {@code IrisApi} class, so both mod
 * ids are accepted.</p>
 */
public class ShaderInfoCollector {

    public record ShaderInfo(boolean installed, boolean enabled, String activePackName) {}

    public static ShaderInfo collect() {
        boolean installed = ModList.isLoaded("iris") || ModList.isLoaded("oculus");
        if (!installed) return new ShaderInfo(false, false, null);

        try {
            Class<?> irisApiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object apiInstance = irisApiClass.getMethod("getInstance").invoke(null);

            Method isInUseMethod = irisApiClass.getMethod("isShaderPackInUse");
            boolean enabled = (boolean) isInUseMethod.invoke(apiInstance);

            String packName = null;
            if (enabled) {
                Method getPackNameMethod = irisApiClass.getMethod("getCurrentPackName");
                Object result = getPackNameMethod.invoke(apiInstance);
                if (result instanceof Optional<?> optional) {
                    packName = optional.map(Object::toString).orElse(null);
                }
            }

            return new ShaderInfo(true, enabled, packName);
        } catch (Exception | LinkageError e) {
            return new ShaderInfo(true, false, null);
        }
    }
}

package com.safenetinc.luna.provider;

import java.security.Provider;

/**
 * Local development stand-in for the real SafeNet Luna JCE provider.
 * <p>
 * NOT A REAL HSM. NOT AFFILIATED WITH OR ENDORSED BY SAFENET/THALES.
 * <p>
 * This class exists under the same fully-qualified name the real Luna client uses so that a
 * consuming application's own Luna-loading component can load it via
 * {@code Class.forName("com.safenetinc.luna.provider.LunaProvider")} with no code changes -
 * only its Luna jar path configuration value needs to point here instead of at the real
 * LunaProvider.jar. See this project's README for setup.
 */
public class LunaProvider extends Provider {

    public static final String KEYSTORE_TYPE = "Luna";

    public LunaProvider() {
        super("SimulatedLuna", "1.0", "HSM simulator for local development - not a real HSM");
        put("KeyStore." + KEYSTORE_TYPE, SimulatedLunaKeyStoreSpi.class.getName());
    }
}

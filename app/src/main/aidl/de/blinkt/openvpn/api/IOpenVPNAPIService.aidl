// Interfaz pública de "OpenVPN for Android" (de.blinkt.openvpn).
// IMPORTANTE: el ORDEN de los métodos tiene que ser idéntico al original,
// porque Android identifica cada llamada por su posición.
package de.blinkt.openvpn.api;

import de.blinkt.openvpn.api.IOpenVPNStatusCallback;
import android.content.Intent;
import android.os.ParcelFileDescriptor;

interface IOpenVPNAPIService {
    // En el original devuelve List<APIVpnProfile>. Locky no lo usa:
    // está solo para mantener la numeración de los métodos.
    List<String> getProfiles();

    void startProfile(String profileUUID);

    boolean addVPNProfile(String name, String config);

    /** Arranca la VPN con una configuración .ovpn completa en texto. */
    void startVPN(in String inlineconfig);

    /** Devuelve null si Locky ya tiene permiso; si no, un Intent para pedirlo. */
    Intent prepare(in String packagename);

    /** Devuelve null si ya está aceptado el permiso de VPN de Android. */
    Intent prepareVPNService();

    void disconnect();

    void pause();

    void resume();

    void registerStatusCallback(in IOpenVPNStatusCallback cb);

    void unregisterStatusCallback(in IOpenVPNStatusCallback cb);
}

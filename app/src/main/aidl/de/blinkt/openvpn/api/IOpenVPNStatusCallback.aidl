package de.blinkt.openvpn.api;

/** OpenVPN for Android nos avisa por acá cada cambio de estado (ej. "CONNECTED"). */
interface IOpenVPNStatusCallback {
    oneway void newStatus(in String uuid, in String state, in String message, in String level);
}

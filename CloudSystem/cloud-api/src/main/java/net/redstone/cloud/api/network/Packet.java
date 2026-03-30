package net.redstone.cloud.api.network;

import java.io.Serializable;

/**
 * Basisklasse für alle Pakete, die über das Cloud-Netzwerk gesendet werden.
 * Wichtig: Es muss Serializable implementieren, um via ObjectOutputStream gesendet zu werden.
 */
public abstract class Packet implements Serializable {
    private static final long serialVersionUID = 1L;

}

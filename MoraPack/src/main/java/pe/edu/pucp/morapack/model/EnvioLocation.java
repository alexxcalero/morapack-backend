// src/main/java/pe/edu/pucp/morapack/model/EnvioLocation.java
package pe.edu.pucp.morapack.model;

import java.time.LocalDateTime;

public class EnvioLocation {

    private final EnvioPosicionTipo tipo;
    private final Aeropuerto aeropuerto;    // null si está en vuelo
    private final Vuelo vuelo;              // null si está en aeropuerto
    private final LocalDateTime salidaTramo;
    private final LocalDateTime llegadaTramo;

    public EnvioLocation(
            EnvioPosicionTipo tipo,
            Aeropuerto aeropuerto,
            Vuelo vuelo,
            LocalDateTime salidaTramo,
            LocalDateTime llegadaTramo
    ) {
        this.tipo = tipo;
        this.aeropuerto = aeropuerto;
        this.vuelo = vuelo;
        this.salidaTramo = salidaTramo;
        this.llegadaTramo = llegadaTramo;
    }

    public EnvioPosicionTipo getTipo() {
        return tipo;
    }

    public Aeropuerto getAeropuerto() {
        return aeropuerto;
    }

    public Vuelo getVuelo() {
        return vuelo;
    }

    public LocalDateTime getSalidaTramo() {
        return salidaTramo;
    }

    public LocalDateTime getLlegadaTramo() {
        return llegadaTramo;
    }

    public boolean estaEnAeropuerto() {
        return tipo == EnvioPosicionTipo.EN_AEROPUERTO;
    }

    public boolean estaEnVuelo() {
        return tipo == EnvioPosicionTipo.EN_VUELO;
    }
}

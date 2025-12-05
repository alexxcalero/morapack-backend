// src/main/java/pe/edu/pucp/morapack/service/grasp/EstadoAlmacen.java
package pe.edu.pucp.morapack.service.grasp;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class EstadoAlmacen {

    private Integer idAeropuerto;      // id_aeropuerto
    private int capacidadMaxima;       // capacidad_maxima
    private int ocupacionActual;       // ocupacion_actual

    public int capacidadLibre() {
        return Math.max(0, capacidadMaxima - ocupacionActual);
    }

    /** Llega carga al almacén (aumenta la ocupación) */
    public void recibir(int cantidad) {
        this.ocupacionActual = Math.min(capacidadMaxima, ocupacionActual + cantidad);
    }

    /** Sale carga del almacén (disminuye la ocupación) */
    public void despachar(int cantidad) {
        this.ocupacionActual = Math.max(0, ocupacionActual - cantidad);
    }
}

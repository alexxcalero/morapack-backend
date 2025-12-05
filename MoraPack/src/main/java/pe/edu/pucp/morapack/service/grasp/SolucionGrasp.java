// src/main/java/pe/edu/pucp/morapack/service/grasp/SolucionGrasp.java
package pe.edu.pucp.morapack.service.grasp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import pe.edu.pucp.morapack.model.Envio;
import pe.edu.pucp.morapack.model.Vuelo;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SolucionGrasp {

    @Builder.Default
    private List<AsignacionEnvioVuelo> asignaciones = new ArrayList<>();

    /**
     * Número de envíos que logramos planificar (completos, no divisibles).
     */
    private int cantidadEnviosAsignados;

    /**
     * Momento (fecha límite) del primer envío que no se pudo asignar
     * dentro de la ventana → colapso logístico.
     * Si no hay colapso, puede ser null.
     */
    private LocalDateTime momentoColapso;

    private Map<Integer, EstadoAlmacen> estadoAlmacenesFinal;

    @Data
    @AllArgsConstructor
    public static class AsignacionEnvioVuelo {
        private Envio envio;
        private Vuelo vuelo;
        // cantidad = envio.getCantidad(), pero guardamos el valor por claridad
        private int cantidad;
    }
}

package pe.edu.pucp.morapack.models;

import lombok.*;

import java.time.ZonedDateTime;
import java.util.ArrayList;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PathState {
    private Aeropuerto ubicacion;
    private ZonedDateTime llegadaUltimoVuelo;
    private ArrayList<PlanDeVuelo> tramos;
    private PlanDeVuelo ultimoVuelo;
    private Integer capacidadRuta;  // Minima capacidad libre hasta ahora
}

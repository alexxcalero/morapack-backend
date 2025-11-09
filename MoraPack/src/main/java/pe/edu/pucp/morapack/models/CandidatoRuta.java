package pe.edu.pucp.morapack.models;

import lombok.*;

import java.time.ZonedDateTime;
import java.util.ArrayList;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidatoRuta {
    private ArrayList<PlanDeVuelo> tramos;
    private ZonedDateTime llegada;
    private Long score;
    private Integer capacidadRuta;  // Minima capacidad libre a lo largo de la ruta
    private Aeropuerto origen;
}

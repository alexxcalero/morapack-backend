package pe.edu.pucp.morapack.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
public class SimulacionTickDTO {

    private Long idSimulacion;
    private LocalDateTime tiempoSimuladoActual;
    private List<VueloSimDTO> vuelos;
    // más adelante podrías agregar envíos, KPIs, etc.
}

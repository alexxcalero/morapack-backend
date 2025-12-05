package pe.edu.pucp.morapack.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import pe.edu.pucp.morapack.model.Envio;
import pe.edu.pucp.morapack.model.Vuelo;

import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
public class SimulacionContext {

    private Long idSimulacion;
    private LocalDateTime tiempoInicio;
    private LocalDateTime tiempoFin;

    private List<Envio> envios;
    private List<Vuelo> vuelos;

    // aquí más adelante agregas:
    // - estado dinámico de vuelos (posiciones, ocupación, etc.)
    // - asignaciones GRASP
}

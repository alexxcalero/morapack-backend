package pe.edu.pucp.morapack.dto;

import java.time.LocalDateTime;

public record SimulacionSemanalRequest(
        LocalDateTime tiempoSimuladoInicio,
        LocalDateTime tiempoSimuladoFin
) {}
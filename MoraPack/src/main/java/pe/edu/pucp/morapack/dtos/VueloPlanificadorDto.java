package pe.edu.pucp.morapack.dtos;

import lombok.Data;

import java.util.List;

@Data
public class VueloPlanificadorDto {

    private Long id;

    // Horas en el mismo formato que ya usas en /api/planificador/vuelos-ultimo-ciclo
    // Ej: "2025-01-01 03:34 (UTC-05:00)"
    private String horaSalida;
    private String horaLlegada;

    private AeropuertoDto origen;
    private AeropuertoDto destino;

    private List<EnvioAsignadoDto> enviosAsignados;

    @Data
    public static class AeropuertoDto {
        private Long id;
        private String ciudad;
        private String codigo;  // opcional
    }

    @Data
    public static class EnvioAsignadoDto {
        private Long envioId;
        private Integer cantidad;        // cantidad asignada
    }
}

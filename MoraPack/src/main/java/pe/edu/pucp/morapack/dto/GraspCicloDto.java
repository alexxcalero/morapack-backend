// src/main/java/pe/edu/pucp/morapack/dto/GraspCicloDto.java
package pe.edu.pucp.morapack.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Data
@Builder
public class GraspCicloDto {

    private Long idSimulacion;
    private String tipoSimulacion;
    private LocalDateTime tSim;
    private LocalDateTime tInicioVentana;
    private LocalDateTime tFinVentana;
    private int kFactor;

    private int totalEnviosCandidatos;
    private int totalEnviosAsignados;
    private LocalDateTime momentoColapso;

    // ya los tenías:
    private List<AeropuertoSnapshotDto> aeropuertos;
    private List<MovimientoEnvioDto> movimientos;
    private List<AsignacionDto> asignaciones;

    // 🔹 NUEVO: las 4 listas “crudas”
    private List<AeropuertoDto> listaAeropuertos;
    private List<VueloDto> listaVuelos;
    private List<EnvioDto> listaEnvios;
    private List<EnvioVueloDto> listaEnviosVuelo;

    // --- DTOs simples ---

    @Data
    @Builder
    public static class AeropuertoSnapshotDto {
        private Integer id;
        private String codigoIata;
        private String nombre;
        private double lat;
        private double lng;
        private int capacidadMaxima;
        private int ocupacionActual;
    }

    @Data
    @Builder
    public static class MovimientoEnvioDto {
        private Long idEnvio;
        private String codigoEnvio;
        private Integer idOrigen;
        private String codigoOrigen;
        private double origenLat;
        private double origenLng;
        private Integer idDestino;
        private String codigoDestino;
        private double destinoLat;
        private double destinoLng;
    }

    @Data
    @Builder
    public static class AsignacionDto {
        private Long idEnvio;
        private String codigoEnvio;
        private Long idVuelo;
        private String codigoVuelo;
        private int cantidad;
    }

    @Data
    @Builder
    public static class AeropuertoDto {
        private Integer id;
        private String codigoIata;
        private String nombre;
        private double lat;
        private double lng;
        private Integer capacidadMaxima;
        private Integer ocupacionActual;
    }

    @Data
    @Builder
    public static class VueloDto {
        private Long id;
        private String codigoVuelo;
        private Integer idOrigen;
        private Integer idDestino;
        private LocalTime horaSalida;
        private LocalTime horaLlegadaEstimada;
        private Integer capacidadMaxima;
    }

    @Data
    @Builder
    public static class EnvioDto {
        private Long id;
        private String codigoEnvio;
        private Integer idOrigen;
        private Integer idDestino;
        private Integer cantidad;
        private LocalDateTime fechaLimiteEntrega;
        private String estado; // PLANIFICADO, PENDIENTE, etc.
    }

    @Data
    @Builder
    public static class EnvioVueloDto {
        private Long idEnvio;
        private Long idVuelo;
        private Integer ordenTramo;
        private Integer cantidad;
    }
}

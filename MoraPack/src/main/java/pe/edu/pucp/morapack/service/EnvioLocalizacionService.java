// src/main/java/pe/edu/pucp/morapack/service/EnvioLocalizacionService.java
package pe.edu.pucp.morapack.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.pucp.morapack.model.*;
import pe.edu.pucp.morapack.repo.EnvioVueloRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EnvioLocalizacionService {

    private final EnvioVueloRepository envioVueloRepository;

    /**
     * Localiza un envío en el tiempo simulado tSim, dentro del contexto
     * de una simulación dada.
     *
     * - Si no tiene ruta -> está en su aeropuerto origen.
     * - Si su ruta fue completada antes de tSim -> está en el último aeropuerto destino.
     * - Si tSim está entre la salida y llegada de un tramo -> está EN_VUELO.
     * - En caso contrario -> EN_AEROPUERTO en el último destino alcanzado.
     */
    @Transactional(readOnly = true)
    public EnvioLocation calcularLocalizacion(Envio envio,
                                              Simulacion simulacion,
                                              LocalDateTime tSim) {

        // 1) Tomar el tiempo simulado de inicio de ESA simulación
        LocalDateTime tInicioSim = simulacion.getTiempoSimuladoInicio();

        // 2) Recuperar la ruta asignada al envío
        List<EnvioVuelo> tramos =
                envioVueloRepository.findByEnvioOrderByOrdenTramoAsc(envio);

        // Sin ruta: está en su aeropuerto de origen (o null si no tiene)
        if (tramos.isEmpty()) {
            return new EnvioLocation(
                    EnvioPosicionTipo.EN_AEROPUERTO,
                    envio.getAeropuertoOrigen(),
                    null,
                    null,
                    null
            );
        }

        // 3) Construir cronograma de tramos (salida/llegada concretas) suponiendo vuelos diarios
        LocalDateTime cursor = max(tInicioSim, envio.getFechaCreacion());
        Aeropuerto aeropuertoActual = envio.getAeropuertoOrigen();

        EnvioLocation ultimaPosicion = null;

        for (EnvioVuelo ev : tramos) {
            Vuelo vuelo = ev.getVuelo();

            LocalTime horaSalida = vuelo.getHoraSalida();
            LocalTime horaLlegadaEstimada = vuelo.getHoraLlegadaEstimada();

            // fecha de salida más temprana >= cursor
            LocalDate fechaBase = cursor.toLocalDate();
            LocalDateTime salida = LocalDateTime.of(fechaBase, horaSalida);
            if (salida.isBefore(cursor)) {
                salida = salida.plusDays(1);
            }

            // llegada estimada (puede ser al día siguiente)
            LocalDateTime llegada = LocalDateTime.of(salida.toLocalDate(), horaLlegadaEstimada);
            if (!llegada.isAfter(salida)) {
                llegada = llegada.plusDays(1);
            }

            // ▶️ Analizar posición respecto a tSim
            if (tSim.isBefore(salida)) {
                // Todavía no embarcó en este tramo: está en el aeropuertoActual
                return new EnvioLocation(
                        EnvioPosicionTipo.EN_AEROPUERTO,
                        aeropuertoActual,
                        null,
                        null,
                        null
                );
            } else if (!tSim.isAfter(llegada)) {
                // Está volando en este tramo
                return new EnvioLocation(
                        EnvioPosicionTipo.EN_VUELO,
                        null,
                        vuelo,
                        salida,
                        llegada
                );
            }

            // Ya pasó este tramo -> ahora está en el aeropuerto destino del vuelo
            aeropuertoActual = vuelo.getAeropuertoDestino();
            cursor = llegada;

            ultimaPosicion = new EnvioLocation(
                    EnvioPosicionTipo.EN_AEROPUERTO,
                    aeropuertoActual,
                    null,
                    null,
                    null
            );
        }

        // 4) Si llegó aquí, todos los tramos programados ya terminaron antes de tSim
        if (ultimaPosicion != null) {
            return ultimaPosicion;
        }

        // Caso de seguridad (no debería suceder)
        return new EnvioLocation(
                EnvioPosicionTipo.EN_AEROPUERTO,
                aeropuertoActual,
                null,
                null,
                null
        );
    }

    private static LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        return (a.isAfter(b) ? a : b);
    }

    /**
     * Helper para el planificador:
     * Sólo se puede reprogramar si el envío está en un aeropuerto
     * (y no ha llegado a destino final / ENTREGADO).
     */
    @Transactional(readOnly = true)
    public boolean puedeReprogramarse(Envio envio,
                                      Simulacion simulacion,
                                      LocalDateTime tSim) {

        // No se reprograman los que ya llegaron al destino final o están ENTREGADO
        if (envio.getEstado() == EstadoEnvio.EN_DESTINO_FINAL ||
            envio.getEstado() == EstadoEnvio.ENTREGADO) {
            return false;
        }

        EnvioLocation loc = calcularLocalizacion(envio, simulacion, tSim);
        return loc.estaEnAeropuerto();
    }
}

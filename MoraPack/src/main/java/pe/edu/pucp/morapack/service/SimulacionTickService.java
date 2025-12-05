package pe.edu.pucp.morapack.service;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import pe.edu.pucp.morapack.dto.SimulacionTickDTO;
import pe.edu.pucp.morapack.dto.VueloSimDTO;
import pe.edu.pucp.morapack.model.EstadoSimulacion;
import pe.edu.pucp.morapack.model.Simulacion;
import pe.edu.pucp.morapack.model.TipoSimulacion;
import pe.edu.pucp.morapack.model.Vuelo;
import pe.edu.pucp.morapack.repo.SimulacionRepository;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SimulacionTickService {

    private final SimulacionRepository simulacionRepository;
    private final SimulacionContextHolder contextHolder;
    private final SimpMessagingTemplate messagingTemplate;

    // factor de velocidad (ej: 60 => 1 seg real = 1 min sim)
    private static final int VELOCIDAD_SIMULACION = 60;

    // tick cada 1 segundo real
    @Scheduled(fixedRate = 1000L)
    public void tick() {
        // Buscar simulación semanal activa
        Simulacion sim = simulacionRepository
                .findFirstByTipoSimulacionAndEsActivaOrderByIdDesc(TipoSimulacion.SEMANAL, true)
                .orElse(null);

        if (sim == null) return;

        LocalDateTime ahoraReal = LocalDateTime.now();

        // Si fechaInicio es null o estado no es EN_EJECUCION, no avanzamos
        if (sim.getFechaInicio() == null) return;
        if (sim.getEstado() != EstadoSimulacion.EN_EJECUCION) {
            return;
        }

        // Calcular tiempo simulado actual
        long secondsReal = java.time.Duration.between(sim.getFechaInicio(), ahoraReal).getSeconds();
        long minutesSim = secondsReal * VELOCIDAD_SIMULACION; // 1s -> 1min sim
        LocalDateTime tiempoSimActual = sim.getTiempoSimuladoInicio().plusMinutes(minutesSim);

        if (tiempoSimActual.isAfter(sim.getTiempoSimuladoFin())) {
            // podrías marcar la simulación como FINALIZADA aquí
            return;
        }

        // Obtener contexto de simulación (vuelos + envíos del rango)
        SimulacionContext ctx = contextHolder.get(sim.getId()).orElse(null);
        if (ctx == null) return;

        List<VueloSimDTO> vuelosDTO = new ArrayList<>();

        for (Vuelo v : ctx.getVuelos()) {
            VueloSimDTO dto = calcularEstadoVueloEnTiempoSimulado(v, tiempoSimActual);
            if (dto != null) {
                vuelosDTO.add(dto);
            }
        }

        SimulacionTickDTO tick = new SimulacionTickDTO(
                sim.getId(),
                tiempoSimActual,
                vuelosDTO
        );

        // Publicar en el topic compartido de simulación semanal
        messagingTemplate.convertAndSend("/topic/simulacion/semanal", tick);
    }

    /**
     * Calcula posición aproximada de un vuelo en el tiempo simulado.
     * Suposición: cada vuelo corre TODOS los días con la misma hora_salida / llegada.
     */
    private VueloSimDTO calcularEstadoVueloEnTiempoSimulado(Vuelo vuelo, LocalDateTime tSim) {

        // Tomamos la fecha "simulada" y construimos las horas de salida/llegada de ESE día
        LocalTime horaSalida = vuelo.getHoraSalida();
        LocalTime horaLlegada = vuelo.getHoraLlegadaEstimada();

        LocalDateTime salidaSim = tSim.toLocalDate().atTime(horaSalida);
        LocalDateTime llegadaSim = tSim.toLocalDate().atTime(horaLlegada);

        // si el vuelo cruza medianoche
        if (llegadaSim.isBefore(salidaSim)) {
            llegadaSim = llegadaSim.plusDays(1);
        }

        if (tSim.isBefore(salidaSim)) {
            // aún no sale
            return new VueloSimDTO(
                    vuelo.getId(),
                    vuelo.getCodigoVuelo(),
                    vuelo.getLatitud().doubleValue(),
                    vuelo.getLongitud().doubleValue(),
                    "EN_ORIGEN"
            );
        } else if (tSim.isAfter(llegadaSim)) {
            // ya llegó
            return new VueloSimDTO(
                    vuelo.getId(),
                    vuelo.getCodigoVuelo(),
                    vuelo.getAeropuertoDestino().getLatitud().doubleValue(),
                    vuelo.getAeropuertoDestino().getLongitud().doubleValue(),
                    "EN_DESTINO"
            );
        } else {
            // Está en vuelo -> interpolamos entre origen y destino
            double frac = (double) java.time.Duration.between(salidaSim, tSim).toMinutes()
                    / (double) java.time.Duration.between(salidaSim, llegadaSim).toMinutes();

            double latO = vuelo.getAeropuertoOrigen().getLatitud().doubleValue();
            double lonO = vuelo.getAeropuertoOrigen().getLongitud().doubleValue();
            double latD = vuelo.getAeropuertoDestino().getLatitud().doubleValue();
            double lonD = vuelo.getAeropuertoDestino().getLongitud().doubleValue();

            double lat = latO + (latD - latO) * frac;
            double lon = lonO + (lonD - lonO) * frac;

            return new VueloSimDTO(
                    vuelo.getId(),
                    vuelo.getCodigoVuelo(),
                    lat,
                    lon,
                    "EN_VUELO"
            );
        }
    }
}

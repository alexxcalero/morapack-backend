// src/main/java/pe/edu/pucp/morapack/service/SimulacionService.java
package pe.edu.pucp.morapack.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.pucp.morapack.model.EstadoSimulacion;
import pe.edu.pucp.morapack.model.Simulacion;
import pe.edu.pucp.morapack.model.TipoSimulacion;
import pe.edu.pucp.morapack.repo.SimulacionRepository;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SimulacionService {

    private final SimulacionRepository simulacionRepository;
    private final SimulacionDataLoader simulacionDataLoader;

    /**
     * Crear una nueva simulación SEMANAL:
     * - esActiva = true
     * - estado = EN_PAUSA
     * - sin rango de tiempo simulado aún (tiempoSimuladoInicio/Fin = null)
     *
     * Se desactivan simulaciones semanales anteriores.
     */
    @Transactional
    public Simulacion crearNuevaSimulacionSemanalPorDefecto() {

        // 1) Desactivar TODAS las simulaciones semanales existentes
        simulacionRepository.desactivarSimulacionesPorTipo(TipoSimulacion.SEMANAL);

        // 2) Crear nueva simulación EN_PAUSA, sin rango definido todavía
        Simulacion sim = Simulacion.builder()
                .tipoSimulacion(TipoSimulacion.SEMANAL)
                .esActiva(true)
                .estado(EstadoSimulacion.EN_PAUSA)
                .fechaInicio(LocalDateTime.now()) // real: se setea al iniciar
                .fechaFin(null)
                .tiempoSimuladoInicio(LocalDateTime.now()) // se definirá en /iniciar
                .tiempoSimuladoFin(null)
                .entradaJson(null)
                .salidaJson(null)
                .build();

        return simulacionRepository.save(sim);
    }

    /**
     * Define el rango de tiempo simulado y coloca la simulación SEMANAL
     * activa en EN_EJECUCION, además de cargar datos iniciales.
     *
     * Asumir tiempoSimuladoInicio + 7 días.
     */
    @Transactional
    public Simulacion iniciarSimulacionSemanal(LocalDateTime tiempoSimuladoInicio,
            LocalDateTime tiempoSimuladoFin) {

        if (tiempoSimuladoInicio == null) {
            throw new IllegalArgumentException("El tiempoSimuladoInicio no puede ser null");
        }

        // Normalizamos inicio y fin en variables locales
        LocalDateTime inicio = tiempoSimuladoInicio;
        LocalDateTime fin = tiempoSimuladoFin;

        if (fin == null) {
            fin = inicio.plusDays(7);
        }

        if (fin.isBefore(inicio)) {
            throw new IllegalArgumentException("El tiempoSimuladoFin no puede ser anterior al inicio");
        }

        // Buscar simulación SEMANAL activa
        Simulacion sim = simulacionRepository
                .findFirstByTipoSimulacionAndEsActivaOrderByIdDesc(TipoSimulacion.SEMANAL, true)
                .orElse(null);

        // Si no existe, desactivar anteriores y crear una nueva
        if (sim == null) {
            simulacionRepository.desactivarSimulacionesPorTipo(TipoSimulacion.SEMANAL);

            sim = Simulacion.builder()
                    .tipoSimulacion(TipoSimulacion.SEMANAL)
                    .esActiva(true)
                    .estado(EstadoSimulacion.EN_PAUSA)
                    .tiempoSimuladoInicio(inicio)
                    .tiempoSimuladoFin(fin)
                    .build();
        } else {
            // Si ya existía, actualizamos el rango
            sim.setTiempoSimuladoInicio(inicio);
            sim.setTiempoSimuladoFin(fin);
        }

        // Marcar como en ejecución y fijar fecha real de inicio
        sim.setFechaInicio(LocalDateTime.now());
        sim.setEstado(EstadoSimulacion.EN_EJECUCION);

        Simulacion guardada = simulacionRepository.save(sim);

        // Cargar datos base (envíos + vuelos) de ese rango
        simulacionDataLoader.cargarDatosInicialesSimulacionSemanal(guardada);

        return guardada;
    }

    /**
     * Marcar la simulación como FINALIZADA cuando el tiempo simulado actual
     * ha alcanzado o sobrepasado el tiempoSimuladoFin.
     */
    @Transactional
    public void finalizarSiLlegoAlFin(Simulacion sim, LocalDateTime tiempoSimuladoActual) {
        LocalDateTime fin = sim.getTiempoSimuladoFin();
        if (fin == null || tiempoSimuladoActual == null) {
            return;
        }

        if (!tiempoSimuladoActual.isBefore(fin)) { // actual >= fin
            sim.setEstado(EstadoSimulacion.FINALIZADA);
            sim.setEsActiva(false);
            // si quieres, aseguras el tiempoSimuladoFin al valor final alcanzado
            sim.setTiempoSimuladoFin(fin);
            simulacionRepository.save(sim);
        }
    }

}

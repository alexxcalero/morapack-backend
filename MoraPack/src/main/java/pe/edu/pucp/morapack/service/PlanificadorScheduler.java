// src/main/java/pe/edu/pucp/morapack/service/PlanificadorScheduler.java
package pe.edu.pucp.morapack.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pe.edu.pucp.morapack.model.Simulacion;
import pe.edu.pucp.morapack.model.TipoSimulacion;
import pe.edu.pucp.morapack.model.EstadoSimulacion;
import pe.edu.pucp.morapack.repo.SimulacionRepository;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlanificadorScheduler {

    private final SimulacionRepository simulacionRepository;
    private final PlanificadorGraspService planificadorGraspService;
    private final SimulacionService simulacionService;

    /**
     * Ejecución periódica del GRASP.
     */
    @Scheduled(fixedRate = 120_000L) // por ejemplo cada 2 minutos
    public void ejecutarCicloPlanificador() {

        // 1. Buscar simulación SEMANAL en estado EN_EJECUCION
        Optional<Simulacion> optSim = simulacionRepository
                .findFirstByTipoSimulacionAndEstadoOrderByIdDesc(
                        TipoSimulacion.SEMANAL,
                        EstadoSimulacion.EN_EJECUCION);

        if (optSim.isEmpty()) {
            log.debug("[PLANIFICADOR] No hay simulación semanal EN_EJECUCION, skip.");
            return;
        }

        Simulacion sim = optSim.get();

        int k = calcularK(sim);

        // Tiempo simulado actual: puedes tener un campo específico;
        // mientras tanto, tomamos el inicio si es la primera vez.
        LocalDateTime tiempoActual = sim.getTiempoSimuladoInicio();
        if (tiempoActual == null) {
            log.warn("[PLANIFICADOR] Simulación {} sin tiempoSimuladoInicio", sim.getId());
            return;
        }

        log.info("[PLANIFICADOR] Tick GRASP en {} (K={})", tiempoActual, k);

        // 2. Ejecutar GRASP y obtener nuevo tiempo simulado
        LocalDateTime nuevoTiempo = planificadorGraspService.ejecutarCiclo(sim, tiempoActual, k);

        // 3. Verificar si ya se alcanzó / superó el tiempoSimuladoFin y marcar
        // FINALIZADA
        if (nuevoTiempo != null) {
            simulacionService.finalizarSiLlegoAlFin(sim, nuevoTiempo);
        }
    }

    private int calcularK(Simulacion sim) {
        if (sim.getTipoSimulacion() == TipoSimulacion.SEMANAL) {
            return 12; // tu valor actual
        }
        return 1;
    }
}

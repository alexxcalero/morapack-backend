package pe.edu.pucp.morapack.services;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import pe.edu.pucp.morapack.models.Planificador;
import pe.edu.pucp.morapack.services.servicesImp.PlanificacionWebSocketServiceImp;
import pe.edu.pucp.morapack.models.Solucion;
import pe.edu.pucp.morapack.dtos.VueloPlanificadorDto;
import pe.edu.pucp.morapack.dtos.AeropuertoEstadoDto;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RelojSimulacionDiaService {

    private static final long TICK_PERIOD_SECONDS = 1L;

    private final SimpMessagingTemplate messagingTemplate;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final Object lock = new Object();

    private volatile Instant simStartInstant;
    private volatile Instant realStartInstant;
    private volatile Instant ultimoSimInstant = null;

    private final AtomicLong simMillis = new AtomicLong(0L);
    private volatile boolean running = false;

    public RelojSimulacionDiaService(
            SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @PostConstruct
    public void init() {
        // Arranca la simulación diaria alineada al "ahora"
        resetTo(Instant.now());
        start();
    }

    public void start() {
        synchronized (lock) {
            if (running)
                return;
            running = true;
        }

        scheduler.scheduleAtFixedRate(() -> {
            try {
                tick();
            } catch (Exception e) {
                System.err.println("❌ Error en tick de RelojSimulacionDia: " + e.getMessage());
            }
        }, 0, TICK_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    public void stop() {
        synchronized (lock) {
            running = false;
        }
    }

    @PreDestroy
    public void shutdown() {
        stop();
        scheduler.shutdownNow();
    }

    /**
     * Reinicia el reloj a un instante de simulación específico.
     * También dispara un broadcast inicial (tiempo + estado, si hay solución).
     */
    public void resetTo(Instant newSimTime) {
        synchronized (lock) {
            this.simStartInstant = newSimTime;
            this.realStartInstant = Instant.now();
        }
        broadcastState(); // envía estado inicial
    }

    /**
     * Devuelve el instante actual de simulación, calculado a partir del
     * tiempo real transcurrido (velocidad 1x).
     */
    public Instant getCurrentSimInstant() {
        synchronized (lock) {
            if (simStartInstant == null || realStartInstant == null) {
                return simStartInstant != null ? simStartInstant : Instant.now();
            }
            long realElapsedMs = Duration.between(realStartInstant, Instant.now()).toMillis();
            return simStartInstant.plusMillis(realElapsedMs);
        }
    }

    public boolean isRunning() {
        return running;
    }

    private void tick() {
        if (!running)
            return;
        broadcastState();
    }

    /**
     * Envía:
     * 1) Tiempo simulado (a /topic/sim-time-dia)
     * 2) Vuelos + aeropuertos dinámicos del día (a /topic/simulacion-dia),
     * usando planificacionWs.enviarEstadoVuelosYAeropuertosDia(..., simMs)
     */
    private void broadcastState() {
        // 1) Tiempo simulado
        Instant simInstant = getCurrentSimInstant();
        if (simInstant == null)
            return;

        this.ultimoSimInstant = simInstant;
        long ms = simInstant.toEpochMilli();
        simMillis.set(ms);

        Map<String, Object> timePayload = new HashMap<>();
        timePayload.put("tipo", "sim_time_dia");
        timePayload.put("simMs", ms);
        timePayload.put("isoUtc", simInstant.toString());

        // ⚠️ Esto lo usa SimulationControlsDia: se mantiene el topic y el formato
        messagingTemplate.convertAndSend("/topic/sim-time-dia", timePayload);
    }

    /**
     * Devuelve el simMs actual, para que otros servicios (como WebSocket) lo
     * puedan reutilizar sin recalcular el instante.
     */
    public long getSimMillis() {
        return simMillis.get();
    }

    public Instant getUltimoSimInstant() {
        return ultimoSimInstant;
    }
}

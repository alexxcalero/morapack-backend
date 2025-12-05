package pe.edu.pucp.morapack.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SimulacionContextHolder {

    private final Map<Long, SimulacionContext> simulaciones = new ConcurrentHashMap<>();

    public void put(Long idSimulacion, SimulacionContext ctx) {
        simulaciones.put(idSimulacion, ctx);
    }

    public Optional<SimulacionContext> get(Long idSimulacion) {
        return Optional.ofNullable(simulaciones.get(idSimulacion));
    }
}

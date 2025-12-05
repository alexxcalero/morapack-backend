package pe.edu.pucp.morapack.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.pucp.morapack.model.Envio;
import pe.edu.pucp.morapack.model.Simulacion;
import pe.edu.pucp.morapack.model.Vuelo;
import pe.edu.pucp.morapack.repo.EnvioRepository;
import pe.edu.pucp.morapack.repo.VueloRepository;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SimulacionDataLoader {

    private final EnvioRepository envioRepository;
    private final VueloRepository vueloRepository;
    private final SimulacionContextHolder contextHolder;

    public void cargarDatosInicialesSimulacionSemanal(Simulacion sim) {
        LocalDateTime ini = sim.getTiempoSimuladoInicio();
        LocalDateTime fin = sim.getTiempoSimuladoFin();

        // 1) Envíos en el rango [ini, fin)
        List<Envio> envios = envioRepository
                .findByFechaCreacionBetween(ini, fin);

        // 2) Vuelos programados base (se repiten todos los días)
        List<Vuelo> vuelos = vueloRepository.findAll();

        // 3) Crear contexto de simulación en memoria
        SimulacionContext ctx = new SimulacionContext(sim.getId(), ini, fin, envios, vuelos);

        contextHolder.put(sim.getId(), ctx);
    }
}

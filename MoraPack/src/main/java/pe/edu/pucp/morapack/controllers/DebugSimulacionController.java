package pe.edu.pucp.morapack.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import pe.edu.pucp.morapack.models.Aeropuerto;
import pe.edu.pucp.morapack.models.PlanDeVuelo;
import pe.edu.pucp.morapack.models.Envio;
import pe.edu.pucp.morapack.models.Envio.EstadoEnvio;
import pe.edu.pucp.morapack.repository.EnvioRepository;
import pe.edu.pucp.morapack.repository.PlanDeVueloRepository;
import pe.edu.pucp.morapack.services.servicesImp.AeropuertoServiceImp;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@CrossOrigin(origins = "*")
@RestController
@RequiredArgsConstructor
@RequestMapping("api/debug")
public class DebugSimulacionController {

    private final AeropuertoServiceImp aeropuertoService;
    private final PlanDeVueloRepository planDeVueloRepository;
    private final EnvioRepository envioRepository;

    @GetMapping("estado-simulacion")
    public Map<String, Object> getEstadoSimulacion() {
        Map<String, Object> response = new HashMap<>();

        // ⏱️ Marca de tiempo
        response.put("timestamp", LocalDateTime.now().toString());

        // ============================================================
        // 1) AEROPUERTOS
        // ============================================================
        List<Aeropuerto> aeropuertos = aeropuertoService.obtenerTodosAeropuertos();
        List<Map<String, Object>> aeropuertosDto = aeropuertos.stream()
                .map(a -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", a.getId());
                    m.put("codigo", a.getCodigo());
                    m.put("ciudad", a.getCiudad());
                    m.put("pais", a.getPais() != null ? a.getPais().getNombre() : null);
                    m.put("capacidadMaxima", a.getCapacidadMaxima());
                    m.put("capacidadOcupada", a.getCapacidadOcupada() != null ? a.getCapacidadOcupada() : 0);
                    return m;
                })
                .collect(Collectors.toList());

        long aeropuertosConOcupacion = aeropuertosDto.stream()
                .filter(a -> {
                    Object occ = a.get("capacidadOcupada");
                    return (occ instanceof Number) && ((Number) occ).intValue() > 0;
                })
                .count();

        Map<String, Object> resumenAeropuertos = new HashMap<>();
        resumenAeropuertos.put("totalAeropuertos", aeropuertos.size());
        resumenAeropuertos.put("aeropuertosConCapacidadOcupada", aeropuertosConOcupacion);

        response.put("aeropuertosResumen", resumenAeropuertos);
        response.put("aeropuertos", aeropuertosDto);

        // ============================================================
        // 2) VUELOS (muestra)
        // ============================================================
        List<PlanDeVuelo> vuelosSample = planDeVueloRepository.findTop100ByOrderByHoraOrigenDesc();

        List<Map<String, Object>> vuelosDto = vuelosSample.stream()
                .map(v -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", v.getId());
                    m.put("ciudadOrigenId", v.getCiudadOrigen());
                    m.put("ciudadDestinoId", v.getCiudadDestino());
                    m.put("horaOrigen", v.getHoraOrigen());
                    m.put("horaDestino", v.getHoraDestino());
                    m.put("capacidadMaxima", v.getCapacidadMaxima());
                    m.put("capacidadOcupada", v.getCapacidadOcupada() != null ? v.getCapacidadOcupada() : 0);
                    return m;
                })
                .collect(Collectors.toList());

        long vuelosConOcupacion = vuelosDto.stream()
                .filter(v -> {
                    Object occ = v.get("capacidadOcupada");
                    return (occ instanceof Number) && ((Number) occ).intValue() > 0;
                })
                .count();

        Map<String, Object> resumenVuelos = new HashMap<>();
        resumenVuelos.put("totalMuestraVuelos", vuelosDto.size());
        resumenVuelos.put("vuelosConCapacidadOcupadaEnMuestra", vuelosConOcupacion);

        response.put("vuelosResumen", resumenVuelos);
        response.put("vuelosSample", vuelosDto);

        // ============================================================
        // 3) ENVÍOS (muestra + resumen por estado)
        // ============================================================

        // 3.1 Resumen por estado
        long totalEnvios = envioRepository.count();
        long planificados = envioRepository.countByEstado(EstadoEnvio.PLANIFICADO);
        long enRuta = envioRepository.countByEstado(EstadoEnvio.EN_RUTA);
        long finalizados = envioRepository.countByEstado(EstadoEnvio.FINALIZADO);
        long entregados = envioRepository.countByEstado(EstadoEnvio.ENTREGADO);
        long estadoNull = envioRepository.countByEstadoIsNull();

        Map<String, Object> resumenEnvios = new HashMap<>();
        resumenEnvios.put("total", totalEnvios);
        resumenEnvios.put("planificados", planificados);
        resumenEnvios.put("enRuta", enRuta);
        resumenEnvios.put("finalizados", finalizados);
        resumenEnvios.put("entregados", entregados);
        resumenEnvios.put("estadoNull", estadoNull);

        response.put("enviosResumen", resumenEnvios);

        // 3.2 Muestra de envíos PLANIFICADO / EN_RUTA (los que importan para la simulación)
        List<EstadoEnvio> estadosInteres = Arrays.asList(
                EstadoEnvio.PLANIFICADO,
                EstadoEnvio.EN_RUTA
        );

        List<Envio> enviosSample = envioRepository
                .findTop100ByEstadoInOrderByIdDesc(estadosInteres);

        List<Map<String, Object>> enviosDto = enviosSample.stream()
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", e.getId());
                    m.put("idEnvioPorAeropuerto", e.getIdEnvioPorAeropuerto());
                    m.put("numProductos", e.getNumProductos());
                    m.put("estado", e.getEstado());
                    m.put("fechaIngreso", e.getFechaIngreso());
                    m.put("fechaLlegadaMax", e.getFechaLlegadaMax());
                    m.put("origen", e.getAeropuertoOrigen() != null ? e.getAeropuertoOrigen().getCodigo() : null);
                    m.put("destino", e.getAeropuertoDestino() != null ? e.getAeropuertoDestino().getCodigo() : null);
                    return m;
                })
                .collect(Collectors.toList());

        response.put("enviosSample", enviosDto);
        response.put("enviosSampleTotal", enviosDto.size());

        return response;
    }
}

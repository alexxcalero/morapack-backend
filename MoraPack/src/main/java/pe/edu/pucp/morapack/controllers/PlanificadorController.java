package pe.edu.pucp.morapack.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import pe.edu.pucp.morapack.models.*;
import pe.edu.pucp.morapack.services.servicesImp.*;
import pe.edu.pucp.morapack.models.Planificador;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@CrossOrigin(origins = "*")
@RestController
@RequiredArgsConstructor
@RequestMapping("api/planificador")
public class PlanificadorController {
    private final AeropuertoServiceImp aeropuertoService;
    private final ContinenteServiceImp continenteService;
    private final PaisServiceImp paisService;
    private final EnvioServiceImp envioService;
    private final PlanDeVueloServiceImp planDeVueloService;
    private final PlanificacionWebSocketServiceImp webSocketService;

    private Planificador planificador;
    private boolean planificadorIniciado = false;

    // Endpoint para iniciar el planificador programado
    @PostMapping("/iniciar")
    public Map<String, Object> iniciarPlanificadorProgramado() {
        Map<String, Object> response = new HashMap<>();

        try {
            if(planificadorIniciado) {
                response.put("estado", "error");
                response.put("mensaje", "El planificador ya está en ejecución");
                return response;
            }

            // Cargar datos necesarios
            ArrayList<Aeropuerto> aeropuertos = aeropuertoService.obtenerTodosAeropuertos();
            ArrayList<Continente> continentes = continenteService.obtenerTodosContinentes();
            ArrayList<Pais> paises = paisService.obtenerTodosPaises();
            ArrayList<Envio> envios = envioService.obtenerEnvios();
            ArrayList<PlanDeVuelo> planes = planDeVueloService.obtenerListaPlanesDeVuelo();

            System.out.println("🚀 INICIANDO PLANIFICADOR PROGRAMADO");
            System.out.println("DEBUG: aeropuertos=" + aeropuertos.size() +
                    ", planes=" + planes.size() + ", envios=" + envios.size());

            // Configurar GRASP
            Grasp grasp = new Grasp();
            grasp.setAeropuertos(aeropuertos);
            grasp.setContinentes(continentes);
            grasp.setPaises(paises);
            grasp.setEnvios(envios);
            grasp.setPlanesOriginales(planes);
            grasp.setHubsPropio();

            // Configurar hubs para los envíos
            ArrayList<Aeropuerto> hubs = grasp.getHubs();
            if(hubs != null && !hubs.isEmpty()) {
                ArrayList<Aeropuerto> uniqHubs = new ArrayList<>(new LinkedHashSet<>(hubs));
                for(Envio e : grasp.getEnvios()) {
                    e.setAeropuertosOrigen(new ArrayList<>(uniqHubs));
                }
            }

            grasp.setEnviosPorDiaPropio();

            // Crear e iniciar el planificador
            planificador = new Planificador(grasp, webSocketService);
            planificador.iniciarPlanificacionProgramada();

            planificadorIniciado = true;

            response.put("estado", "éxito");
            response.put("mensaje", "Planificador programado iniciado correctamente");
            response.put("configuracion", Map.of(
                    "sa_minutos", 5,
                    "k_factor", 24,
                    "ta_segundos", 150,
                    "sc_minutos", 120
            ));
            response.put("timestamp", LocalDateTime.now().toString());

        } catch(Exception e) {
            response.put("estado", "error");
            response.put("mensaje", "Error al iniciar planificador: " + e.getMessage());
            e.printStackTrace();
        }

        return response;
    }

    // Endpoint para detener el planificador
    @PostMapping("/detener")
    public Map<String, Object> detenerPlanificador() {
        Map<String, Object> response = new HashMap<>();

        try {
            if(planificador != null && planificadorIniciado) {
                planificador.detenerPlanificacion();
                planificadorIniciado = false;

                response.put("estado", "éxito");
                response.put("mensaje", "Planificador detenido correctamente");
            } else {
                response.put("estado", "error");
                response.put("mensaje", "No hay planificador en ejecución");
            }

        } catch(Exception e) {
            response.put("estado", "error");
            response.put("mensaje", "Error al detener planificador: " + e.getMessage());
        }

        return response;
    }

    // Endpoint para obtener estado actual del planificador
    @GetMapping("/estado")
    public Map<String, Object> obtenerEstado() {
        Map<String, Object> response = new HashMap<>();

        response.put("planificadorActivo", planificadorIniciado);
        response.put("ultimaActualizacion", LocalDateTime.now().toString());

        if (planificador != null && planificadorIniciado) {
            response.put("cicloActual", planificador.getCicloActual());
            response.put("proximoCiclo", planificador.getProximoCiclo());
            response.put("estadisticas", planificador.getEstadisticasActuales());
        }

        return response;
    }

    // Endpoint para obtener resultados del último ciclo
    @GetMapping("/ultimo-ciclo")
    public Map<String, Object> obtenerUltimoCiclo() {
        Map<String, Object> response = new HashMap<>();

        if(planificador != null && planificadorIniciado) {
            Solucion ultimaSolucion = planificador.getUltimaSolucion();
            if (ultimaSolucion != null) {
                response.put("ciclo", planificador.getCicloActual());
                response.put("solucion", convertirSolucionParaFrontend(ultimaSolucion));
                response.put("timestamp", LocalDateTime.now().toString());
            } else {
                response.put("estado", "no_hay_datos");
                response.put("mensaje", "Aún no hay resultados de ciclos completados");
            }
        } else {
            response.put("estado", "inactivo");
            response.put("mensaje", "El planificador no está activo");
        }

        return response;
    }

    // Metodo auxiliar para convertir la solucion al formato del frontend
    private Map<String, Object> convertirSolucionParaFrontend(Solucion solucion) {
        Map<String, Object> solucionFrontend = new HashMap<>();

        if (solucion == null || solucion.getEnvios() == null) {
            solucionFrontend.put("totalEnvios", 0);
            solucionFrontend.put("enviosCompletados", 0);
            solucionFrontend.put("llegadaMediaPonderada", "N/A");
            solucionFrontend.put("rutas", new ArrayList<>());
            return solucionFrontend;
        }

        solucionFrontend.put("totalEnvios", solucion.getEnvios().size());
        solucionFrontend.put("enviosCompletados", solucion.getEnviosCompletados());
        solucionFrontend.put("llegadaMediaPonderada", solucion.getLlegadaMediaPonderada().toString());

        // Agrupar por envío, no por parte
        List<Map<String, Object>> enviosFrontend = new ArrayList<>();

        for (Envio envio : solucion.getEnvios()) {
            Map<String, Object> envioFrontend = new HashMap<>();
            envioFrontend.put("envioId", envio.getId());
            envioFrontend.put("destino", envio.getAeropuertoDestino().getCodigo());
            envioFrontend.put("cantidadTotal", envio.getNumProductos());
            envioFrontend.put("cantidadAsignada", envio.cantidadAsignada());
            envioFrontend.put("completo", envio.estaCompleto());
            envioFrontend.put("origenesPosibles", envio.getAeropuertosOrigen().stream()
                    .map(Aeropuerto::getCodigo)
                    .collect(Collectors.toList()));
            envioFrontend.put("aparicion", envio.getZonedFechaIngreso().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

            // ✅ PARTES como array dentro del mismo envío
            List<Map<String, Object>> partesFrontend = new ArrayList<>();

            if (envio.getParteAsignadas() != null) {
                for (ParteAsignada parte : envio.getParteAsignadas()) {
                    Map<String, Object> parteFrontend = new HashMap<>();
                    parteFrontend.put("cantidad", parte.getCantidad());
                    parteFrontend.put("origen", parte.getAeropuertoOrigen().getCodigo());
                    parteFrontend.put("llegadaFinal", parte.getLlegadaFinal().format(
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

                    // ✅ TRAMOS como array dentro de cada parte
                    List<Map<String, Object>> tramosFrontend = new ArrayList<>();

                    if (parte.getRuta() != null) {
                        for (VueloInstanciado vuelo : parte.getRuta()) {
                            Map<String, Object> tramoFrontend = new HashMap<>();
                            tramoFrontend.put("vueloBaseId", vuelo.getVueloBase().getId());
                            tramoFrontend.put("origen", aeropuertoService.obtenerAeropuertoPorId(vuelo.getVueloBase().getCiudadOrigen()).get().getCodigo());
                            tramoFrontend.put("destino", aeropuertoService.obtenerAeropuertoPorId(vuelo.getVueloBase().getCiudadDestino()).get().getCodigo());
                            tramoFrontend.put("salida", vuelo.getZonedHoraOrigen().format(
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                            tramoFrontend.put("llegada", vuelo.getZonedHoraDestino().format(
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                            tramoFrontend.put("capacidadOcupada", vuelo.getCapacidadOcupada());
                            tramoFrontend.put("capacidadMaxima", vuelo.getVueloBase().getCapacidadMaxima());

                            tramosFrontend.add(tramoFrontend);
                        }
                    }

                    parteFrontend.put("tramos", tramosFrontend);
                    partesFrontend.add(parteFrontend);
                }
            }

            envioFrontend.put("partes", partesFrontend);
            enviosFrontend.add(envioFrontend);
        }

        solucionFrontend.put("envios", enviosFrontend);
        return solucionFrontend;
    }

    @GetMapping("/estado-horizonte")
    public Map<String, Object> obtenerEstadoHorizonte() {
        Map<String, Object> response = new HashMap<>();

        if (planificador != null && planificadorIniciado) {
            response.put("planificadorActivo", true);
            response.putAll(planificador.getEstadoHorizonte());
        } else {
            response.put("planificadorActivo", false);
            response.put("mensaje", "Planificador no activo");
        }

        return response;
    }
}

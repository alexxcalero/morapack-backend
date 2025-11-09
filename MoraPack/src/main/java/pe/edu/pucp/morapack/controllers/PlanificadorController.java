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
            grasp.setPlanesDeVuelo(planes);
            grasp.setHubsPropio();

            // Configurar hubs para los envíos
            ArrayList<Aeropuerto> hubs = grasp.getHubs();
            if(hubs != null && !hubs.isEmpty()) {
                ArrayList<Aeropuerto> uniqHubs = new ArrayList<>(new LinkedHashSet<>(hubs));
                for(Envio e : grasp.getEnvios()) {
                    e.setAeropuertosOrigen(new ArrayList<>(uniqHubs));
                }
            }

            //grasp.setEnviosPorDiaPropio();

            // Crear e iniciar el planificador
            planificador = new Planificador(grasp, webSocketService, envioService, planDeVueloService, aeropuertoService);
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

        if(planificador != null && planificadorIniciado) {
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

        if(solucion == null || solucion.getEnvios() == null) {
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

        for(Envio envio : solucion.getEnvios()) {
            Map<String, Object> envioFrontend = new HashMap<>();
            envioFrontend.put("envioId", envio.getId());
            envioFrontend.put("destino", envio.getAeropuertoDestino().getCodigo());
            envioFrontend.put("cantidadTotal", envio.getNumProductos());
            envioFrontend.put("cantidadAsignada", envio.cantidadAsignada());
            envioFrontend.put("completo", envio.estaCompleto());
            envioFrontend.put("origenesPosibles", envio.getAeropuertosOrigen().stream()
                    .map(Aeropuerto::getCodigo)
                    .collect(Collectors.toList()));
            envioFrontend.put("aparicion", formatFechaConOffset(
                    envio.getZonedFechaIngreso(),
                    envio.getFechaIngreso(),
                    envio.getHusoHorarioDestino(),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

            // ✅ PARTES como array dentro del mismo envío
            List<Map<String, Object>> partesFrontend = new ArrayList<>();

            if(envio.getParteAsignadas() != null) {
                for(ParteAsignada parte : envio.getParteAsignadas()) {
                    Map<String, Object> parteFrontend = new HashMap<>();
                    parteFrontend.put("cantidad", parte.getCantidad());
                    parteFrontend.put("origen", parte.getAeropuertoOrigen().getCodigo());
                    parteFrontend.put("llegadaFinal", formatFechaConOffset(
                            parte.getLlegadaFinal(),
                            null,
                            null,
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

                    // ✅ TRAMOS como array dentro de cada parte
                    List<Map<String, Object>> tramosFrontend = new ArrayList<>();

                    if(parte.getRuta() != null) {
                        for(PlanDeVuelo vuelo : parte.getRuta()) {
                            Map<String, Object> tramoFrontend = new HashMap<>();
                            tramoFrontend.put("vueloBaseId", vuelo.getId());
                            tramoFrontend.put("origen", aeropuertoService.obtenerAeropuertoPorId(vuelo.getCiudadOrigen()).get().getCodigo());
                            tramoFrontend.put("destino", aeropuertoService.obtenerAeropuertoPorId(vuelo.getCiudadDestino()).get().getCodigo());
                            tramoFrontend.put("salida", formatFechaConOffset(
                                    vuelo.getZonedHoraOrigen(),
                                    vuelo.getHoraOrigen(),
                                    vuelo.getHusoHorarioOrigen(),
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                            tramoFrontend.put("llegada", formatFechaConOffset(
                                    vuelo.getZonedHoraDestino(),
                                    vuelo.getHoraDestino(),
                                    vuelo.getHusoHorarioDestino(),
                                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                            tramoFrontend.put("capacidadOcupada", vuelo.getCapacidadOcupada());
                            tramoFrontend.put("capacidadMaxima", vuelo.getCapacidadMaxima());

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

    @GetMapping("/vuelos-ultimo-ciclo")
    public Map<String, Object> obtenerVuelosUltimoCiclo() {
        Map<String, Object> response = new HashMap<>();

        if(planificador == null || !planificadorIniciado) {
            response.put("estado", "inactivo");
            response.put("mensaje", "El planificador no está activo");
            return response;
        }

        List<PlanDeVuelo> vuelos = planificador.getVuelosUltimoCiclo();
        List<Map<String, Object>> asignaciones = planificador.getAsignacionesUltimoCiclo();
        Map<Integer, List<Map<String, Object>>> asignacionesPorVuelo = asignaciones.stream()
                .filter(a -> a.get("vueloId") != null)
                .collect(Collectors.groupingBy(a -> (Integer) a.get("vueloId")));

        if(vuelos == null || vuelos.isEmpty()) {
            response.put("estado", "sin_datos");
            response.put("mensaje", "Aún no hay vuelos procesados en el último ciclo");
            return response;
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        LocalDateTime inicio = planificador.getInicioHorizonteUltimoCiclo();
        LocalDateTime fin = planificador.getFinHorizonteUltimoCiclo();

        response.put("estado", "exito");
        response.put("cantidadVuelos", vuelos.size());
        response.put("horizonte", Map.of(
                "inicio", inicio != null ? formatFechaConOffset(null, inicio, "0", formatter) : "N/A",
                "fin", fin != null ? formatFechaConOffset(null, fin, "0", formatter) : "N/A"
        ));

        List<Map<String, Object>> vuelosFrontend = vuelos.stream()
                .map(v -> convertirVueloParaFrontend(v, asignacionesPorVuelo))
                .collect(Collectors.toList());

        response.put("vuelos", vuelosFrontend);
        response.put("timestamp", formatFechaConOffset(null, LocalDateTime.now(), "0", formatter));
        return response;
    }

    private Map<String, Object> convertirVueloParaFrontend(PlanDeVuelo vuelo, Map<Integer, List<Map<String, Object>>> asignacionesPorVuelo) {
        Map<String, Object> vueloFrontend = new HashMap<>();
        vueloFrontend.put("id", vuelo.getId());
        vueloFrontend.put("capacidadMaxima", vuelo.getCapacidadMaxima());
        vueloFrontend.put("capacidadOcupada", vuelo.getCapacidadOcupada());
        vueloFrontend.put("capacidadLibre", vuelo.getCapacidadMaxima() != null && vuelo.getCapacidadOcupada() != null
                ? vuelo.getCapacidadMaxima() - vuelo.getCapacidadOcupada() : null);
        vueloFrontend.put("mismoContinente", vuelo.getMismoContinente());
        vueloFrontend.put("estado", vuelo.getEstado());

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        vueloFrontend.put("horaSalida", formatFechaConOffset(
                vuelo.getZonedHoraOrigen(),
                vuelo.getHoraOrigen(),
                vuelo.getHusoHorarioOrigen(),
                formatter));

        vueloFrontend.put("horaLlegada", formatFechaConOffset(
                vuelo.getZonedHoraDestino(),
                vuelo.getHoraDestino(),
                vuelo.getHusoHorarioDestino(),
                formatter));

        aeropuertoService.obtenerAeropuertoPorId(vuelo.getCiudadOrigen())
                .ifPresent(a -> vueloFrontend.put("origen", Map.of(
                        "id", a.getId(),
                        "codigo", a.getCodigo(),
                        "ciudad", a.getCiudad(),
                        "pais", a.getPais() != null ? a.getPais().getNombre() : null
                )));

        aeropuertoService.obtenerAeropuertoPorId(vuelo.getCiudadDestino())
                .ifPresent(a -> vueloFrontend.put("destino", Map.of(
                        "id", a.getId(),
                        "codigo", a.getCodigo(),
                        "ciudad", a.getCiudad(),
                        "pais", a.getPais() != null ? a.getPais().getNombre() : null
                )));

        List<Map<String, Object>> asignaciones = vuelo.getId() != null
                ? asignacionesPorVuelo.getOrDefault(vuelo.getId(), Collections.emptyList())
                : Collections.emptyList();
        vueloFrontend.put("enviosAsignados", asignaciones);

        return vueloFrontend;
    }

    private String formatFechaConOffset(ZonedDateTime zoned, LocalDateTime local, String husoHorario, DateTimeFormatter formatter) {
        if(zoned != null) {
            ZoneOffset offset = zoned.getOffset();
            String offsetStr = offset.getId().equals("Z") ? "+00:00" : offset.getId();
            return String.format("%s (UTC%s)", zoned.format(formatter), offsetStr);
        }

        if(local != null && husoHorario != null) {
            int offsetHoras;
            try {
                offsetHoras = Integer.parseInt(husoHorario);
            } catch(NumberFormatException e) {
                offsetHoras = 0;
            }
            return String.format("%s (UTC%+03d:00)", local.format(formatter), offsetHoras);
        }

        if(local != null) {
            return String.format("%s (UTC%+03d:00)", local.format(formatter), 0);
        }

        return "N/A";
    }
}

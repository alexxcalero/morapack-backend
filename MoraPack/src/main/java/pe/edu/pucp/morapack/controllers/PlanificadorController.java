package pe.edu.pucp.morapack.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import pe.edu.pucp.morapack.models.*;
import pe.edu.pucp.morapack.services.servicesImp.*;
import pe.edu.pucp.morapack.models.Planificador;
import pe.edu.pucp.morapack.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

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
    private final AeropuertoRepository aeropuertoRepository;
    private final PlanDeVueloRepository planDeVueloRepository;
    private final EnvioRepository envioRepository;
    private final EntityManager entityManager;

    private Planificador planificador;
    private boolean planificadorIniciado = false;

    // Endpoint para iniciar el planificador programado (modo normal)
    @PostMapping("/iniciar")
    public Map<String, Object> iniciarPlanificadorProgramado() {
        Map<String, Object> response = new HashMap<>();

        try {
            // Sincronizar el flag con el estado real del planificador
            if(planificador != null && planificador.estaEnEjecucion()) {
                planificadorIniciado = true;
                response.put("estado", "error");
                response.put("mensaje", "El planificador ya está en ejecución");
                return response;
            } else {
                planificadorIniciado = false;
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

    // Endpoint para iniciar simulación semanal
    @PostMapping("/iniciar-simulacion-semanal")
    public Map<String, Object> iniciarSimulacionSemanal(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        try {
            // Sincronizar el flag con el estado real del planificador
            if(planificador != null && planificador.estaEnEjecucion()) {
                planificadorIniciado = true;
                response.put("estado", "error");
                response.put("mensaje", "El planificador ya está en ejecución");
                return response;
            } else {
                planificadorIniciado = false;
            }

            // Validar parámetros
            String fechaInicioStr = request.get("fechaInicio");
            String fechaFinStr = request.get("fechaFin");

            if(fechaInicioStr == null || fechaFinStr == null) {
                response.put("estado", "error");
                response.put("mensaje", "Se requieren los parámetros 'fechaInicio' y 'fechaFin' en formato 'yyyy-MM-ddTHH:mm:ss'");
                return response;
            }

            // Parsear fechas
            LocalDateTime fechaInicio = LocalDateTime.parse(fechaInicioStr);
            LocalDateTime fechaFin = LocalDateTime.parse(fechaFinStr);

            if(fechaInicio.isAfter(fechaFin)) {
                response.put("estado", "error");
                response.put("mensaje", "La fecha de inicio debe ser anterior a la fecha de fin");
                return response;
            }

            // Cargar datos necesarios
            ArrayList<Aeropuerto> aeropuertos = aeropuertoService.obtenerTodosAeropuertos();
            ArrayList<Continente> continentes = continenteService.obtenerTodosContinentes();
            ArrayList<Pais> paises = paisService.obtenerTodosPaises();
            ArrayList<Envio> envios = envioService.obtenerEnvios();
            ArrayList<PlanDeVuelo> planes = planDeVueloService.obtenerListaPlanesDeVuelo();

            System.out.println("🚀 INICIANDO SIMULACIÓN SEMANAL");
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

            // Crear e iniciar el planificador en modo SEMANAL
            planificador = new Planificador(grasp, webSocketService, envioService, planDeVueloService, aeropuertoService);
            planificador.iniciarPlanificacionProgramada(Planificador.ModoSimulacion.SEMANAL, fechaInicio, fechaFin);

            planificadorIniciado = true;

            response.put("estado", "éxito");
            response.put("mensaje", "Simulación semanal iniciada correctamente");
            response.put("configuracion", Map.of(
                    "modo", "SEMANAL",
                    "fechaInicio", fechaInicio.toString(),
                    "fechaFin", fechaFin.toString(),
                    "sa_minutos", 5,
                    "k_factor", 24,
                    "ta_segundos", 150,
                    "sc_minutos", 120
            ));
            response.put("timestamp", LocalDateTime.now().toString());

        } catch(Exception e) {
            response.put("estado", "error");
            response.put("mensaje", "Error al iniciar simulación semanal: " + e.getMessage());
            e.printStackTrace();
        }

        return response;
    }

    // Endpoint para iniciar simulación de colapso
    @PostMapping("/iniciar-simulacion-colapso")
    public Map<String, Object> iniciarSimulacionColapso(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        try {
            // Sincronizar el flag con el estado real del planificador
            if(planificador != null && planificador.estaEnEjecucion()) {
                planificadorIniciado = true;
                response.put("estado", "error");
                response.put("mensaje", "El planificador ya está en ejecución");
                return response;
            } else {
                planificadorIniciado = false;
            }

            // Validar parámetros
            String fechaInicioStr = request.get("fechaInicio");

            if(fechaInicioStr == null) {
                response.put("estado", "error");
                response.put("mensaje", "Se requiere el parámetro 'fechaInicio' en formato 'yyyy-MM-ddTHH:mm:ss'");
                return response;
            }

            // Parsear fecha
            LocalDateTime fechaInicio = LocalDateTime.parse(fechaInicioStr);

            // Cargar datos necesarios
            ArrayList<Aeropuerto> aeropuertos = aeropuertoService.obtenerTodosAeropuertos();
            ArrayList<Continente> continentes = continenteService.obtenerTodosContinentes();
            ArrayList<Pais> paises = paisService.obtenerTodosPaises();
            ArrayList<Envio> envios = envioService.obtenerEnvios();
            ArrayList<PlanDeVuelo> planes = planDeVueloService.obtenerListaPlanesDeVuelo();

            System.out.println("🚀 INICIANDO SIMULACIÓN DE COLAPSO");
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

            // Crear e iniciar el planificador en modo COLAPSO
            planificador = new Planificador(grasp, webSocketService, envioService, planDeVueloService, aeropuertoService);
            planificador.iniciarPlanificacionProgramada(Planificador.ModoSimulacion.COLAPSO, fechaInicio, null);

            planificadorIniciado = true;

            response.put("estado", "éxito");
            response.put("mensaje", "Simulación de colapso iniciada correctamente");
            response.put("configuracion", Map.of(
                    "modo", "COLAPSO",
                    "fechaInicio", fechaInicio.toString(),
                    "sa_minutos", 5,
                    "k_factor", 24,
                    "ta_segundos", 150,
                    "sc_minutos", 120
            ));
            response.put("timestamp", LocalDateTime.now().toString());

        } catch(Exception e) {
            response.put("estado", "error");
            response.put("mensaje", "Error al iniciar simulación de colapso: " + e.getMessage());
            e.printStackTrace();
        }

        return response;
    }

    // Endpoint para detener el planificador
    @PostMapping("/detener")
    public Map<String, Object> detenerPlanificador() {
        Map<String, Object> response = new HashMap<>();

        try {
            // Sincronizar el flag con el estado real del planificador
            if(planificador != null && planificador.estaEnEjecucion()) {
                planificador.detenerPlanificacion();
                planificadorIniciado = false;

                response.put("estado", "éxito");
                response.put("mensaje", "Planificador detenido correctamente");
            } else {
                planificadorIniciado = false; // Asegurar que el flag esté sincronizado
                response.put("estado", "error");
                response.put("mensaje", "No hay planificador en ejecución");
            }

        } catch(Exception e) {
            response.put("estado", "error");
            response.put("mensaje", "Error al detener planificador: " + e.getMessage());
        }

        return response;
    }

    // Endpoint para limpiar todas las planificaciones anteriores
    @PostMapping("/limpiar-planificacion")
    @Transactional
    public Map<String, Object> limpiarPlanificacion() {
        Map<String, Object> response = new HashMap<>();

        try {
            // Verificar si el planificador está activo
            if(planificadorIniciado) {
                response.put("estado", "error");
                response.put("mensaje", "No se puede limpiar la planificación mientras el planificador está activo. Detén el planificador primero.");
                return response;
            }

            int aeropuertosActualizados = 0;
            int planesActualizados = 0;
            int relacionesVuelosEliminadas = 0;
            int partesEliminadas = 0;
            int enviosActualizados = 0;

            // 1. Vaciar capacidades ocupadas de todos los aeropuertos
            List<Aeropuerto> aeropuertos = new ArrayList<>();
            for(Aeropuerto aeropuerto : aeropuertoRepository.findAll()) {
                aeropuerto.setCapacidadOcupada(0);
                aeropuertos.add(aeropuerto);
            }
            if(!aeropuertos.isEmpty()) {
                aeropuertoRepository.saveAll(aeropuertos);
                aeropuertosActualizados = aeropuertos.size();
            }

            // 2. Vaciar capacidades ocupadas de todos los planes de vuelo
            List<PlanDeVuelo> planesDeVuelo = planDeVueloRepository.findAll();
            for(PlanDeVuelo plan : planesDeVuelo) {
                plan.setCapacidadOcupada(0);
            }
            if(!planesDeVuelo.isEmpty()) {
                planDeVueloRepository.saveAll(planesDeVuelo);
                planesActualizados = planesDeVuelo.size();
            }

            // 3. Limpiar las referencias de partes asignadas en los envíos
            List<Envio> envios = envioRepository.findAll();
            List<Envio> enviosParaActualizar = new ArrayList<>();
            for(Envio envio : envios) {
                if(envio.getParteAsignadas() != null && !envio.getParteAsignadas().isEmpty()) {
                    envio.setParteAsignadas(new ArrayList<>());
                    enviosParaActualizar.add(envio);
                    enviosActualizados++;
                }
            }
            if(!enviosParaActualizar.isEmpty()) {
                envioRepository.saveAll(enviosParaActualizar);
            }

            // 4. Eliminar primero las relaciones ParteAsignadaPlanDeVuelo usando SQL nativo
            Query queryRelaciones = entityManager.createNativeQuery("DELETE FROM parte_asignada_plan_de_vuelo");
            relacionesVuelosEliminadas = queryRelaciones.executeUpdate();

            // 5. Eliminar todas las partes asignadas usando SQL nativo
            Query queryPartes = entityManager.createNativeQuery("DELETE FROM parte_asignada");
            partesEliminadas = queryPartes.executeUpdate();

            // Hacer flush para asegurar que los cambios se apliquen
            entityManager.flush();

            response.put("estado", "exito");
            response.put("mensaje", "Planificación limpiada correctamente");
            response.put("detalles", Map.of(
                    "aeropuertosActualizados", aeropuertosActualizados,
                    "planesActualizados", planesActualizados,
                    "relacionesVuelosEliminadas", relacionesVuelosEliminadas,
                    "partesEliminadas", partesEliminadas,
                    "enviosActualizados", enviosActualizados
            ));
            response.put("timestamp", LocalDateTime.now().toString());

        } catch(Exception e) {
            response.put("estado", "error");
            response.put("mensaje", "Error al limpiar planificación: " + e.getMessage());
            e.printStackTrace();
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

        // Agregar información de pedidos clasificados por estado
        Map<String, Object> pedidosConEstado = envioService.obtenerPedidosConEstado();
        response.put("pedidosClasificados", pedidosConEstado);

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

    // Endpoint para obtener el resumen de la última simulación
    @GetMapping("/resumen-planificacion")
    public Map<String, Object> obtenerResumenPlanificacion() {
        Map<String, Object> response = new HashMap<>();

        try {
            // Si no hay planificador en memoria, crear uno temporal para acceder al método
            // o cargar desde BD directamente
            if(planificador == null) {
                // Crear un planificador temporal para usar sus métodos de servicio
                // Esto permite obtener el resumen incluso después de reiniciar la aplicación
                Grasp grasp = new Grasp();
                planificador = new Planificador(grasp, webSocketService, envioService,
                    planDeVueloService, aeropuertoService);
            }

            Map<String, Object> resumen = planificador.obtenerResumenUltimaSimulacion();

            response.put("estado", "éxito");
            response.putAll(resumen);

        } catch(Exception e) {
            response.put("estado", "error");
            response.put("mensaje", "Error al obtener resumen de planificación: " + e.getMessage());
            e.printStackTrace();
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

        // Agregar lista de envíos planificados con sus partes y vuelos
        Solucion ultimaSolucion = planificador.getUltimaSolucion();
        if(ultimaSolucion != null && ultimaSolucion.getEnvios() != null) {
            List<Map<String, Object>> enviosPlanificados = convertirEnviosPlanificadosParaFrontend(ultimaSolucion.getEnvios(), formatter);
            response.put("enviosPlanificados", enviosPlanificados);
            response.put("cantidadEnvios", enviosPlanificados.size());
        } else {
            response.put("enviosPlanificados", Collections.emptyList());
            response.put("cantidadEnvios", 0);
        }

        // Agregar lista de aeropuertos con sus capacidades
        List<Map<String, Object>> aeropuertosFrontend = convertirAeropuertosParaFrontend();
        response.put("aeropuertos", aeropuertosFrontend);
        response.put("cantidadAeropuertos", aeropuertosFrontend.size());

        response.put("timestamp", formatFechaConOffset(null, LocalDateTime.now(), "0", formatter));
        return response;
    }

    private List<Map<String, Object>> convertirAeropuertosParaFrontend() {
        ArrayList<Aeropuerto> aeropuertos = aeropuertoService.obtenerTodosAeropuertos();

        return aeropuertos.stream()
                .map(a -> {
                    Map<String, Object> aeropuertoMap = new HashMap<>();
                    aeropuertoMap.put("id", a.getId());
                    aeropuertoMap.put("codigo", a.getCodigo());
                    aeropuertoMap.put("ciudad", a.getCiudad());
                    aeropuertoMap.put("pais", a.getPais());
                    aeropuertoMap.put("capacidadOcupada", a.getCapacidadOcupada() != null ? a.getCapacidadOcupada() : 0);
                    aeropuertoMap.put("capacidadMaxima", a.getCapacidadMaxima());
                    return aeropuertoMap;
                })
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> convertirEnviosPlanificadosParaFrontend(List<Envio> envios, DateTimeFormatter formatter) {
        List<Map<String, Object>> enviosFrontend = new ArrayList<>();

        for(Envio envio : envios) {
            // Solo incluir envíos que tengan partes asignadas
            if(envio.getParteAsignadas() == null || envio.getParteAsignadas().isEmpty()) {
                continue;
            }

            Map<String, Object> envioMap = new HashMap<>();
            envioMap.put("envioId", envio.getId());
            envioMap.put("envioIdPorAeropuerto", envio.getIdEnvioPorAeropuerto());
            envioMap.put("cliente", envio.getCliente());
            envioMap.put("cantidadTotal", envio.getNumProductos());
            envioMap.put("cantidadAsignada", envio.cantidadAsignada());
            envioMap.put("completo", envio.estaCompleto());

            // Información del destino
            if(envio.getAeropuertoDestino() != null) {
                envioMap.put("destino", Map.of(
                        "id", envio.getAeropuertoDestino().getId(),
                        "codigo", envio.getAeropuertoDestino().getCodigo(),
                        "ciudad", envio.getAeropuertoDestino().getCiudad()
                ));
            }

            // Información de aparición
            envioMap.put("aparicion", formatFechaConOffset(
                    envio.getZonedFechaIngreso(),
                    envio.getFechaIngreso(),
                    envio.getHusoHorarioDestino(),
                    formatter));

            // Lista de partes (si el pedido está dividido)
            List<Map<String, Object>> partesFrontend = new ArrayList<>();

            for(ParteAsignada parte : envio.getParteAsignadas()) {
                Map<String, Object> parteMap = new HashMap<>();
                parteMap.put("cantidad", parte.getCantidad());

                // Aeropuerto origen de esta parte
                if(parte.getAeropuertoOrigen() != null) {
                    parteMap.put("aeropuertoOrigen", Map.of(
                            "id", parte.getAeropuertoOrigen().getId(),
                            "codigo", parte.getAeropuertoOrigen().getCodigo(),
                            "ciudad", parte.getAeropuertoOrigen().getCiudad()
                    ));
                }

                // Llegada final de esta parte
                parteMap.put("llegadaFinal", formatFechaConOffset(
                        parte.getLlegadaFinal(),
                        null,
                        null,
                        formatter));

                // Lista de vuelos por los que pasa esta parte (ruta completa)
                List<Map<String, Object>> vuelosRuta = new ArrayList<>();

                if(parte.getRuta() != null && !parte.getRuta().isEmpty()) {
                    for(int i = 0; i < parte.getRuta().size(); i++) {
                        PlanDeVuelo vuelo = parte.getRuta().get(i);
                        Map<String, Object> vueloRutaMap = new HashMap<>();
                        vueloRutaMap.put("orden", i + 1); // Orden en la ruta (1, 2, 3...)
                        vueloRutaMap.put("vueloId", vuelo.getId());

                        // Origen del vuelo
                        aeropuertoService.obtenerAeropuertoPorId(vuelo.getCiudadOrigen())
                                .ifPresent(a -> vueloRutaMap.put("origen", Map.of(
                                        "id", a.getId(),
                                        "codigo", a.getCodigo(),
                                        "ciudad", a.getCiudad()
                                )));

                        // Destino del vuelo
                        aeropuertoService.obtenerAeropuertoPorId(vuelo.getCiudadDestino())
                                .ifPresent(a -> vueloRutaMap.put("destino", Map.of(
                                        "id", a.getId(),
                                        "codigo", a.getCodigo(),
                                        "ciudad", a.getCiudad()
                                )));

                        vueloRutaMap.put("horaSalida", formatFechaConOffset(
                                vuelo.getZonedHoraOrigen(),
                                vuelo.getHoraOrigen(),
                                vuelo.getHusoHorarioOrigen(),
                                formatter));

                        vueloRutaMap.put("horaLlegada", formatFechaConOffset(
                                vuelo.getZonedHoraDestino(),
                                vuelo.getHoraDestino(),
                                vuelo.getHusoHorarioDestino(),
                                formatter));

                        vueloRutaMap.put("capacidadOcupada", vuelo.getCapacidadOcupada());
                        vueloRutaMap.put("capacidadMaxima", vuelo.getCapacidadMaxima());

                        vuelosRuta.add(vueloRutaMap);
                    }
                }

                parteMap.put("vuelos", vuelosRuta);
                partesFrontend.add(parteMap);
            }

            envioMap.put("partes", partesFrontend);
            envioMap.put("cantidadPartes", partesFrontend.size());

            enviosFrontend.add(envioMap);
        }

        return enviosFrontend;
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

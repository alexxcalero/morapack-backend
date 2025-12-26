package pe.edu.pucp.morapack.controllers;

import lombok.RequiredArgsConstructor;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import pe.edu.pucp.morapack.models.*;
import pe.edu.pucp.morapack.services.RelojSimulacionDiaService;
import pe.edu.pucp.morapack.services.LiberacionCapacidadService;
import pe.edu.pucp.morapack.services.servicesImp.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

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
    private final EntityManager entityManager;
    private final RelojSimulacionDiaService relojSimulacionDiaService;
    private final LiberacionCapacidadService liberacionCapacidadService;
    // Nota: Se eliminaron los repositorios directos - ahora usamos SQL nativo vía
    // EntityManager

    private Planificador planificador;
    private boolean planificadorIniciado = false;

    // ⚡ Guardar fechas de simulación para el resumen
    private LocalDateTime fechaInicioSimulacion;
    private LocalDateTime fechaFinSimulacion;

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Helper para configurar el planificador después de crearlo
     */
    private void configurarPlanificador(Planificador planificador) {
        if (planificador != null) {
            liberacionCapacidadService.setPlanificador(planificador);
        }
    }

    /**
     * Limpia el estado de la simulación día a día (usado por el botón "Limpiar
     * Mapa").
     *
     * - Resetea estado de envíos a NULL
     * - Resetea capacidad_ocupada de aeropuertos a 0
     * - Resetea capacidad_ocupada de planes de vuelo a 0
     * - Elimina partes asignadas y sus relaciones con vuelos
     *
     * IMPORTANTE: No borra envíos ni vuelos; solo limpia la planificación /
     * ocupación.
     */
    @PostMapping("/limpiar-simulacion-dia")
    @Transactional
    public Map<String, Object> limpiarSimulacionDia() {
        Map<String, Object> response = new HashMap<>();
        long startTime = System.currentTimeMillis();
        System.out.println("🧹 [LIMPIAR DIA] Iniciando limpieza de simulación día a día...");

        boolean broadcastStarted = false;

        try {
            // 🔔 Avisar a TODOS los clientes que se inicia el bloqueo/limpieza
            try {
                messagingTemplate.convertAndSend(
                        "/topic/simulacion-control",
                        Map.of(
                                "tipo", "clear_map_start",
                                "timestamp", LocalDateTime.now().toString()));
                broadcastStarted = true;
                System.out.println("📡 [LIMPIAR DIA] Notificado clear_map_start a /topic/simulacion-control");
            } catch (Exception wsEx) {
                System.err.println("⚠️ [LIMPIAR DIA] Error enviando clear_map_start: " + wsEx.getMessage());
            }

            int enviosActualizados = 0;
            int aeropuertosActualizados = 0;
            int planesActualizados = 0;
            int relacionesVuelosEliminadas = 0;
            int partesEliminadas = 0;

            // 1) Resetea estado de envíos
            System.out.println("🧹 [LIMPIAR DIA] Reseteando estados de envíos a NULL...");
            Query queryEstados = entityManager.createNativeQuery("UPDATE envio SET estado = NULL");
            enviosActualizados = queryEstados.executeUpdate();
            System.out.println("✅ Estados de envíos reseteados: " + enviosActualizados);

            // 2) Resetea capacidades de aeropuertos
            System.out.println("🧹 [LIMPIAR DIA] Reseteando capacidades de aeropuertos...");
            Query queryAeropuertos = entityManager.createNativeQuery(
                    "UPDATE aeropuerto SET capacidad_ocupada = 0");
            aeropuertosActualizados = queryAeropuertos.executeUpdate();
            System.out.println("✅ Aeropuertos actualizados: " + aeropuertosActualizados);

            // 3) Resetea capacidades de planes de vuelo
            System.out.println("🧹 [LIMPIAR DIA] Reseteando capacidades de vuelos...");
            Query queryPlanes = entityManager.createNativeQuery(
                    "UPDATE plan_de_vuelo SET capacidad_ocupada = 0");
            planesActualizados = queryPlanes.executeUpdate();
            System.out.println("✅ Planes actualizados: " + planesActualizados);

            // 4) Elimina relaciones vuelo–parte
            System.out.println("🧹 [LIMPIAR DIA] Eliminando relaciones vuelo-parte...");
            Query queryRelaciones = entityManager.createNativeQuery(
                    "DELETE FROM parte_asignada_plan_de_vuelo");
            relacionesVuelosEliminadas = queryRelaciones.executeUpdate();
            System.out.println("✅ Relaciones eliminadas: " + relacionesVuelosEliminadas);

            // 5) Elimina partes asignadas
            System.out.println("🧹 [LIMPIAR DIA] Eliminando partes asignadas...");
            Query queryPartes = entityManager.createNativeQuery(
                    "DELETE FROM parte_asignada");
            partesEliminadas = queryPartes.executeUpdate();
            System.out.println("✅ Partes eliminadas: " + partesEliminadas);

            entityManager.flush();

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("🧹 [LIMPIAR DIA] ✅ Limpieza de simulación día completada en " + elapsed + "ms");

            response.put("estado", "exito");
            response.put("mensaje", "Simulación día limpiada correctamente");
            response.put("detalles", Map.of(
                    "enviosActualizados", enviosActualizados,
                    "aeropuertosActualizados", aeropuertosActualizados,
                    "planesActualizados", planesActualizados,
                    "relacionesVuelosEliminadas", relacionesVuelosEliminadas,
                    "partesEliminadas", partesEliminadas,
                    "tiempoEjecucionMs", elapsed));
            response.put("timestamp", LocalDateTime.now().toString());

        } catch (Exception e) {
            System.err.println("❌ [LIMPIAR DIA] Error al limpiar simulación día: " + e.getMessage());
            e.printStackTrace();

            response.put("estado", "error");
            response.put("mensaje", "Error al limpiar simulación día: " + e.getMessage());
        } finally {
            // 🔔 Avisar siempre que terminó el proceso (éxito o error)
            if (broadcastStarted) {
                try {
                    messagingTemplate.convertAndSend(
                            "/topic/simulacion-control",
                            Map.of(
                                    "tipo", "clear_map_end",
                                    "timestamp", LocalDateTime.now().toString()));
                    System.out.println("📡 [LIMPIAR DIA] Notificado clear_map_end a /topic/simulacion-control");
                } catch (Exception wsEx) {
                    System.err.println("⚠️ [LIMPIAR DIA] Error enviando clear_map_end: " + wsEx.getMessage());
                }
            }
        }

        return response;
    }

    // Endpoint para iniciar el planificador programado (modo normal)
    @PostMapping("/iniciar")
    public Map<String, Object> iniciarPlanificadorProgramado() {
        Map<String, Object> response = new HashMap<>();

        try {
            // Sincronizar el flag con el estado real del planificador
            if (planificador != null && planificador.estaEnEjecucion()) {
                planificadorIniciado = true;
                response.put("estado", "error");
                response.put("mensaje", "El planificador ya está en ejecución");
                return response;
            } else {
                planificadorIniciado = false;
            }

            // ⚡ OPTIMIZACIÓN CRÍTICA: Solo cargar datos básicos (aeropuertos, continentes,
            // países)
            // NO cargar todos los envíos ni vuelos (se cargarán por ciclo desde BD)
            ArrayList<Aeropuerto> aeropuertos = aeropuertoService.obtenerTodosAeropuertos();
            ArrayList<Continente> continentes = continenteService.obtenerTodosContinentes();
            ArrayList<Pais> paises = paisService.obtenerTodosPaises();

            System.out.println("🚀 INICIANDO PLANIFICADOR PROGRAMADO (modo optimizado)");
            System.out.println("📊 DEBUG: aeropuertos=" + aeropuertos.size() +
                    " (envíos y vuelos se cargarán por ciclo desde BD)");

            // Configurar GRASP con datos básicos solamente
            Grasp grasp = new Grasp();
            grasp.setAeropuertos(aeropuertos);
            grasp.setContinentes(continentes);
            grasp.setPaises(paises);
            // ⚡ NO cargar envíos ni vuelos aquí - se cargarán por ciclo
            grasp.setEnvios(new ArrayList<>()); // Lista vacía inicial
            grasp.setPlanesDeVuelo(new ArrayList<>()); // Lista vacía inicial
            grasp.setHubsPropio();

            // ⚡ Los hubs se configurarán cuando se carguen los envíos por ciclo
            // No es necesario configurarlos aquí ya que no hay envíos cargados

            // grasp.setEnviosPorDiaPropio();

            // Crear e iniciar el planificador
            planificador = new Planificador(grasp, webSocketService, envioService, planDeVueloService,
                    aeropuertoService, relojSimulacionDiaService);
            planificador.iniciarPlanificacionProgramada();

            // ⚡ Configurar el planificador en el servicio de liberación de capacidad
            configurarPlanificador(planificador);

            planificadorIniciado = true;

            response.put("estado", "éxito");
            response.put("mensaje", "Planificador programado iniciado correctamente");
            response.put("configuracion", Map.of(
                    "sa_minutos", 5,
                    "k_factor", 24,
                    "ta_segundos", 150,
                    "sc_minutos", 120));
            response.put("timestamp", LocalDateTime.now().toString());

        } catch (Exception e) {
            response.put("estado", "error");
            response.put("mensaje", "Error al iniciar planificador: " + e.getMessage());
            e.printStackTrace();
        }

        return response;
    }

    // Endpoint para iniciar simulación semanal (sin generar vuelos)
    @PostMapping("/iniciar-simulacion-semanal")
    public Map<String, Object> iniciarSimulacionSemanal(@RequestBody Map<String, String> request) {
        System.out.println("🎯 [ENDPOINT] iniciar-simulacion-semanal - PETICIÓN RECIBIDA a las " + LocalDateTime.now());
        Map<String, Object> response = new HashMap<>();

        try {
            // Sincronizar el flag con el estado real del planificador
            if (planificador != null && planificador.estaEnEjecucion()) {
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

            if (fechaInicioStr == null || fechaFinStr == null) {
                response.put("estado", "error");
                response.put("mensaje",
                        "Se requieren los parámetros 'fechaInicio' y 'fechaFin' en formato 'yyyy-MM-ddTHH:mm:ss'");
                return response;
            }

            // Parsear fechas (el frontend ya envía las fechas en UTC)
            LocalDateTime fechaInicio = LocalDateTime.parse(fechaInicioStr);
            LocalDateTime fechaFin = LocalDateTime.parse(fechaFinStr);

            if (fechaInicio.isAfter(fechaFin)) {
                response.put("estado", "error");
                response.put("mensaje", "La fecha de inicio debe ser anterior a la fecha de fin");
                return response;
            }

            // ⚡ Guardar fechas para el resumen
            this.fechaInicioSimulacion = fechaInicio;
            this.fechaFinSimulacion = fechaFin;

            // Cargar datos necesarios
            System.out.println("📂 Cargando aeropuertos...");
            ArrayList<Aeropuerto> aeropuertos = aeropuertoService.obtenerTodosAeropuertos();
            System.out.println("✅ Aeropuertos cargados: " + aeropuertos.size());

            System.out.println("📂 Cargando continentes...");
            ArrayList<Continente> continentes = continenteService.obtenerTodosContinentes();
            System.out.println("✅ Continentes cargados: " + continentes.size());

            System.out.println("📂 Cargando países...");
            ArrayList<Pais> paises = paisService.obtenerTodosPaises();
            System.out.println("✅ Países cargados: " + paises.size());

            // ⚡ OPTIMIZACIÓN: Cargar solo vuelos y envíos dentro del rango de simulación +
            // margen
            LocalDateTime fechaInicioVuelos = fechaInicio.minusDays(1);
            LocalDateTime fechaFinVuelos = fechaFin.plusDays(1);

            System.out.println("📂 Cargando vuelos en rango " + fechaInicioVuelos + " a " + fechaFinVuelos + "...");
            ArrayList<PlanDeVuelo> planes = planDeVueloService.obtenerVuelosEnRango(
                    fechaInicioVuelos, "0", fechaFinVuelos, "0");
            System.out.println("✅ Vuelos cargados: " + planes.size());

            System.out.println("📂 Cargando envíos en rango...");
            ArrayList<Envio> envios = envioService.obtenerEnviosEnRango(
                    fechaInicioVuelos, "0", fechaFinVuelos, "0");
            System.out.println("✅ Envíos cargados: " + envios.size());

            System.out.println("🚀 INICIANDO SIMULACIÓN SEMANAL");
            System.out.println("DEBUG: aeropuertos=" + aeropuertos.size() +
                    ", planes=" + planes.size() + " (rango: " + fechaInicioVuelos + " a " + fechaFinVuelos + ")" +
                    ", envios=" + envios.size());

            // Configurar GRASP
            System.out.println("⚙️ Configurando GRASP...");
            Grasp grasp = new Grasp();
            grasp.setAeropuertos(aeropuertos);
            grasp.setContinentes(continentes);
            grasp.setPaises(paises);
            grasp.setEnvios(envios);
            grasp.setPlanesDeVuelo(planes);
            grasp.setHubsPropio();
            System.out.println("✅ GRASP configurado");

            // Configurar hubs para los envíos
            System.out.println("⚙️ Configurando hubs para " + envios.size() + " envíos...");
            ArrayList<Aeropuerto> hubs = grasp.getHubs();
            if (hubs != null && !hubs.isEmpty()) {
                ArrayList<Aeropuerto> uniqHubs = new ArrayList<>(new LinkedHashSet<>(hubs));
                for (Envio e : grasp.getEnvios()) {
                    e.setAeropuertosOrigen(new ArrayList<>(uniqHubs));
                }
            }
            System.out.println("✅ Hubs configurados");

            // Crear e iniciar el planificador en modo SEMANAL
            System.out.println("⚙️ Creando planificador...");
            planificador = new Planificador(grasp, webSocketService, envioService, planDeVueloService,
                    aeropuertoService, relojSimulacionDiaService);
            planificador.iniciarPlanificacionProgramada(Planificador.ModoSimulacion.SEMANAL, fechaInicio, fechaFin);
            configurarPlanificador(planificador);

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
                    "sc_minutos", 120));
            response.put("timestamp", LocalDateTime.now().toString());

        } catch (Exception e) {
            response.put("estado", "error");
            response.put("mensaje", "Error al iniciar simulación semanal: " + e.getMessage());
            e.printStackTrace();
        }

        return response;
    }

    // Endpoint para iniciar simulación semanal (sin generar vuelos)
    @PostMapping("/reiniciar-simulacion-dia")
    public Map<String, Object> reiniciarSimulacionDia(@RequestBody Map<String, String> request) {
        System.out.println("🎯 [ENDPOINT] reiniciar-simulacion-dia - PETICIÓN RECIBIDA a las " + LocalDateTime.now());
        Map<String, Object> response = new HashMap<>();

        try {
            // Sincronizar el flag con el estado real del planificador
            if (planificador != null && planificador.estaEnEjecucion()) {
                planificadorIniciado = true;
                response.put("estado", "error");
                response.put("mensaje", "El planificador ya está en ejecución");
                return response;
            } else {
                planificadorIniciado = false;
            }

            Object fechaInicioObj = request.get("fechaInicio"); // "2025-12-11T10:00:00"

            if (fechaInicioObj == null) {
                response.put("estado", "error");
                response.put("mensaje", "Se requiere 'fechaInicio' en formato ISO: yyyy-MM-ddTHH:mm:ss");
                return response;
            }

            String fechaInicioStr = fechaInicioObj.toString().trim();

            LocalDateTime ldt = LocalDateTime.parse(fechaInicioStr);
            Instant simInstant = ldt.toInstant(ZoneOffset.UTC);

            relojSimulacionDiaService.resetTo(simInstant);

            LocalDateTime fechaAparicionUTC = LocalDateTime.ofInstant(simInstant, ZoneOffset.UTC);

            // Primera vez: iniciar el planificador
            System.out.println("🚀 Iniciando planificador en modo OPERACIONES_DIARIAS...");

            // Cargar datos necesarios
            System.out.println("📂 Cargando aeropuertos...");
            ArrayList<Aeropuerto> aeropuertos = aeropuertoService.obtenerTodosAeropuertos();
            System.out.println("✅ Aeropuertos cargados: " + aeropuertos.size());

            System.out.println("📂 Cargando continentes...");
            ArrayList<Continente> continentes = continenteService.obtenerTodosContinentes();
            System.out.println("✅ Continentes cargados: " + continentes.size());

            System.out.println("📂 Cargando países...");
            ArrayList<Pais> paises = paisService.obtenerTodosPaises();
            System.out.println("✅ Países cargados: " + paises.size());

            // ⚡ OPTIMIZACIÓN CRÍTICA: NO cargar todos los vuelos al inicio en modo
            // OPERACIONES_DIARIAS
            System.out.println(
                    "📂 [OPTIMIZACIÓN] Vuelos se cargarán por ciclo desde BD (solo horizonte actual + 3 días)");

            // Configurar GRASP
            System.out.println("⚙️ Configurando GRASP...");
            Grasp grasp = new Grasp();
            grasp.setAeropuertos(aeropuertos);
            grasp.setContinentes(continentes);
            grasp.setPaises(paises);
            grasp.setEnvios(new ArrayList<>()); // Lista vacía inicial - se cargarán por ciclo
            grasp.setPlanesDeVuelo(new ArrayList<>()); // Lista vacía inicial - se cargarán por ciclo
            grasp.setHubsPropio();
            System.out.println("✅ GRASP configurado (vuelos y envíos se cargarán por ciclo)");

            // Crear e iniciar el planificador en modo OPERACIONES_DIARIAS
            // Usar la fecha en UTC del reloj de simulación para el inicio del planificador
            System.out.println("⚙️ Creando planificador en modo OPERACIONES_DIARIAS...");
            planificador = new Planificador(grasp, webSocketService, envioService, planDeVueloService,
                    aeropuertoService, relojSimulacionDiaService);
            planificador.iniciarPlanificacionProgramada(
                    Planificador.ModoSimulacion.OPERACIONES_DIARIAS,
                    fechaAparicionUTC.minusHours(5),
                    null); // Sin fecha fin, usar fecha en UTC
            configurarPlanificador(planificador);

            planificadorIniciado = true;

            response.put("estado", "éxito");
            response.put("mensaje", "Operaciones diarias iniciadas correctamente");
            response.put("planificadorIniciado", true);
            response.put("configuracion", Map.of(
                    "modo", "OPERACIONES_DIARIAS",
                    "fechaInicio", fechaAparicionUTC.toString(),
                    "k_factor", 1,
                    "sa_minutos", 2,
                    "ta_segundos", 70));

            response.put("timestamp", LocalDateTime.now().toString());

        } catch (Exception e) {
            response.put("estado", "error");
            response.put("mensaje", "Error al iniciar operaciones diarias: " + e.getMessage());
            e.printStackTrace();
        }

        return response;
    }

    // Endpoint para iniciar simulación semanal v2 (con generación de vuelos)
    @PostMapping("/iniciar-simulacion-semanal-v2")
    public Map<String, Object> iniciarSimulacionSemanalV2(@RequestBody Map<String, String> request) {
        Map<String, Object> response = new HashMap<>();

        try {
            // Sincronizar el flag con el estado real del planificador
            if (planificador != null && planificador.estaEnEjecucion()) {
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

            if (fechaInicioStr == null || fechaFinStr == null) {
                response.put("estado", "error");
                response.put("mensaje",
                        "Se requieren los parámetros 'fechaInicio' y 'fechaFin' en formato 'yyyy-MM-ddTHH:mm:ss'");
                return response;
            }

            // Parsear fechas (el frontend ya envía las fechas en UTC)
            LocalDateTime fechaInicio = LocalDateTime.parse(fechaInicioStr);
            LocalDateTime fechaFin = LocalDateTime.parse(fechaFinStr);

            if (fechaInicio.isAfter(fechaFin)) {
                response.put("estado", "error");
                response.put("mensaje", "La fecha de inicio debe ser anterior a la fecha de fin");
                return response;
            }

            // ⚡ Guardar fechas para el resumen
            this.fechaInicioSimulacion = fechaInicio;
            this.fechaFinSimulacion = fechaFin;

            // Cargar vuelos para la semana desde el archivo
            System.out.println("📂 Cargando vuelos para la semana desde archivo...");
            LocalDate fechaBase = fechaInicio.toLocalDate();
            ArrayList<PlanDeVuelo> planesCargados = cargarVuelosParaSemanaDesdeArchivo(fechaBase);

            if (planesCargados.isEmpty()) {
                response.put("estado", "error");
                response.put("mensaje",
                        "No se pudieron cargar vuelos desde el archivo. Verifique que el archivo existe en src/main/resources/planes/vuelos.txt");
                return response;
            }

            System.out.println("✅ Vuelos cargados: " + planesCargados.size() + " vuelos para 7 días");

            // Cargar datos necesarios
            ArrayList<Aeropuerto> aeropuertos = aeropuertoService.obtenerTodosAeropuertos();
            ArrayList<Continente> continentes = continenteService.obtenerTodosContinentes();
            ArrayList<Pais> paises = paisService.obtenerTodosPaises();

            // ⚡ OPTIMIZACIÓN: Cargar solo vuelos y envíos dentro del rango de simulación +
            // margen
            LocalDateTime fechaInicioVuelos = fechaInicio.minusDays(1);
            LocalDateTime fechaFinVuelos = fechaFin.plusDays(1);
            ArrayList<PlanDeVuelo> planes = planDeVueloService.obtenerVuelosEnRango(
                    fechaInicioVuelos, "0", fechaFinVuelos, "0");
            // Cargar envíos CON parteAsignadas porque el planificador las necesita
            ArrayList<Envio> envios = envioService.obtenerEnviosEnRangoConPartes(
                    fechaInicioVuelos, "0", fechaFinVuelos, "0");

            System.out.println("🚀 INICIANDO SIMULACIÓN SEMANAL V2 (con generación de vuelos)");
            System.out.println("DEBUG: aeropuertos=" + aeropuertos.size() +
                    ", planes=" + planes.size() + " (rango: " + fechaInicioVuelos + " a " + fechaFinVuelos + ")" +
                    ", envios=" + envios.size());

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
            if (hubs != null && !hubs.isEmpty()) {
                ArrayList<Aeropuerto> uniqHubs = new ArrayList<>(new LinkedHashSet<>(hubs));
                for (Envio e : grasp.getEnvios()) {
                    e.setAeropuertosOrigen(new ArrayList<>(uniqHubs));
                }
            }

            // Crear e iniciar el planificador en modo SEMANAL
            planificador = new Planificador(grasp, webSocketService, envioService, planDeVueloService,
                    aeropuertoService, relojSimulacionDiaService);
            planificador.iniciarPlanificacionProgramada(Planificador.ModoSimulacion.SEMANAL, fechaInicio, fechaFin);
            configurarPlanificador(planificador);

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
                    "sc_minutos", 120));
            response.put("timestamp", LocalDateTime.now().toString());

        } catch (Exception e) {
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
            if (planificador != null && planificador.estaEnEjecucion()) {
                planificadorIniciado = true;
                response.put("estado", "error");
                response.put("mensaje", "El planificador ya está en ejecución");
                return response;
            } else {
                planificadorIniciado = false;
            }

            // Validar parámetros
            String fechaInicioStr = request.get("fechaInicio");

            if (fechaInicioStr == null) {
                response.put("estado", "error");
                response.put("mensaje", "Se requiere el parámetro 'fechaInicio' en formato 'yyyy-MM-ddTHH:mm:ss'");
                return response;
            }

            // Parsear fecha (el frontend ya envía la fecha en UTC)
            LocalDateTime fechaInicio = LocalDateTime.parse(fechaInicioStr);

            // ⚡ Guardar fechas para el resumen (colapso: sin fecha fin definida)
            this.fechaInicioSimulacion = fechaInicio;
            this.fechaFinSimulacion = null; // Sin límite para simulación colapso

            // Cargar datos necesarios (solo básicos - envíos y vuelos se cargarán por ciclo desde BD)
            System.out.println("📂 Cargando aeropuertos...");
            ArrayList<Aeropuerto> aeropuertos = aeropuertoService.obtenerTodosAeropuertos();
            System.out.println("✅ Aeropuertos cargados: " + aeropuertos.size());

            System.out.println("📂 Cargando continentes...");
            ArrayList<Continente> continentes = continenteService.obtenerTodosContinentes();
            System.out.println("✅ Continentes cargados: " + continentes.size());

            System.out.println("📂 Cargando países...");
            ArrayList<Pais> paises = paisService.obtenerTodosPaises();
            System.out.println("✅ Países cargados: " + paises.size());

            System.out.println("🚀 INICIANDO SIMULACIÓN DE COLAPSO");
            System.out.println("📊 DEBUG: aeropuertos=" + aeropuertos.size() +
                    " (envíos y vuelos se cargarán por ciclo desde BD según horizonte K)");

            // Configurar GRASP con datos básicos solamente
            // ⚡ OPTIMIZACIÓN: No cargar envíos ni vuelos aquí - se cargarán por ciclo desde BD
            Grasp grasp = new Grasp();
            grasp.setAeropuertos(aeropuertos);
            grasp.setContinentes(continentes);
            grasp.setPaises(paises);
            grasp.setEnvios(new ArrayList<>()); // Lista vacía inicial - se cargarán por ciclo
            grasp.setPlanesDeVuelo(new ArrayList<>()); // Lista vacía inicial - se cargarán por ciclo
            grasp.setHubsPropio();

            // Crear e iniciar el planificador en modo COLAPSO
            planificador = new Planificador(grasp, webSocketService, envioService, planDeVueloService,
                    aeropuertoService, relojSimulacionDiaService);
            planificador.iniciarPlanificacionProgramada(Planificador.ModoSimulacion.COLAPSO, fechaInicio, null);
            configurarPlanificador(planificador);

            planificadorIniciado = true;

            response.put("estado", "éxito");
            response.put("mensaje", "Simulación de colapso iniciada correctamente");
            response.put("configuracion", Map.of(
                    "modo", "COLAPSO",
                    "fechaInicio", fechaInicio.toString(),
                    "sa_minutos", 5,
                    "k_factor", 24,
                    "ta_segundos", 150,
                    "sc_minutos", 120));
            response.put("timestamp", LocalDateTime.now().toString());

        } catch (Exception e) {
            response.put("estado", "error");
            response.put("mensaje", "Error al iniciar simulación de colapso: " + e.getMessage());
            e.printStackTrace();
        }

        return response;
    }

    // Endpoint para AUTOSTART de la simulación día a día (sin crear envío)
    @PostMapping("/autostart-simulacion-dia")
    public Map<String, Object> autostartSimulacionDia() {
        System.out.println("🎯 [ENDPOINT] autostart-simulacion-dia - " + LocalDateTime.now());
        Map<String, Object> response = new HashMap<>();

        try {
            // (Opcional) reloj solo para obtener "fecha actual simulada".
            if (!relojSimulacionDiaService.isRunning()) {
                relojSimulacionDiaService.init();
                relojSimulacionDiaService.start();
            }

            Instant simInstant = relojSimulacionDiaService.getCurrentSimInstant();
            LocalDateTime fechaInicioUTC = LocalDateTime.ofInstant(simInstant, ZoneOffset.UTC);

            List<PlanDeVuelo> vuelos = planDeVueloService.obtenerVuelosIniciales(fechaInicioUTC, 350);

            List<Map<String, Object>> vuelosFrontend = vuelos.stream()
                    .map(v -> convertirVueloParaFrontend(v, Collections.emptyMap()))
                    .collect(Collectors.toList());

            response.put("estado", "exito");
            response.put("mensaje", "Vuelos iniciales cargados");
            response.put("timestamp", LocalDateTime.now().toString());
            response.put("fechaInicioUTC", fechaInicioUTC.toString());
            response.put("cantidadVuelos", vuelosFrontend.size());
            response.put("vuelos", vuelosFrontend);

            // ✅ explícito: NO se inicia planificador aquí
            response.put("planificadorIniciado", false);

        } catch (Exception e) {
            response.put("estado", "error");
            response.put("mensaje", "Error al cargar vuelos iniciales: " + e.getMessage());
            e.printStackTrace();
        }

        return response;
    }

    // Endpoint para iniciar operaciones diarias (tiempo real)
    @PostMapping("/iniciar-operaciones-diarias")
    public Map<String, Object> iniciarOperacionesDiarias(@RequestBody Map<String, Object> request) {
        System.out
                .println("🎯 [ENDPOINT] iniciar-operaciones-diarias - PETICIÓN RECIBIDA a las " + LocalDateTime.now());
        Map<String, Object> response = new HashMap<>();

        try {
            // Validar parámetros (ya NO pedimos fechaAparicion)
            Object codigoAeropuertoDestinoObj = request.get("codigoAeropuertoDestino");
            Object numProductosObj = request.get("numProductos");
            Object clienteObj = request.get("cliente");

            if (codigoAeropuertoDestinoObj == null || numProductosObj == null || clienteObj == null) {
                response.put("estado", "error");
                response.put("mensaje",
                        "Se requieren los parámetros: 'codigoAeropuertoDestino', 'numProductos' y 'cliente'");
                return response;
            }

            String codigoAeropuertoDestino = codigoAeropuertoDestinoObj.toString().trim().toUpperCase();
            Integer numProductos = Integer.parseInt(numProductosObj.toString());
            String cliente = clienteObj.toString();

            // ⏱️ La fecha de aparición viene del reloj de simulación día a día (UTC)
            Instant simInstant = relojSimulacionDiaService.getCurrentSimInstant();
            LocalDateTime fechaAparicionUTC = LocalDateTime.ofInstant(simInstant, ZoneOffset.UTC);

            // Obtener aeropuerto destino por código
            Optional<Aeropuerto> aeropuertoDestinoOpt = aeropuertoService
                    .obtenerAeropuertoPorCodigo(codigoAeropuertoDestino);
            if (aeropuertoDestinoOpt.isEmpty()) {
                response.put("estado", "error");
                response.put("mensaje", "Aeropuerto destino no encontrado con código: " + codigoAeropuertoDestino);
                return response;
            }

            Aeropuerto aeropuertoDestino = aeropuertoDestinoOpt.get();
            String husoHorarioDestino = aeropuertoDestino.getHusoHorario();
            if (husoHorarioDestino == null) {
                response.put("estado", "error");
                response.put("mensaje", "El aeropuerto destino no tiene huso horario configurado");
                return response;
            }

            // Convertir la fecha de aparición de UTC al huso horario del destino
            Integer offsetDestino = Integer.parseInt(husoHorarioDestino);
            ZoneOffset zoneDestino = ZoneOffset.ofHours(offsetDestino);

            // Crear ZonedDateTime en UTC y convertir al huso horario del destino
            ZonedDateTime fechaAparicionZonedUTC = fechaAparicionUTC.atZone(ZoneOffset.UTC);
            ZonedDateTime fechaAparicionZonedDestino = fechaAparicionZonedUTC.withZoneSameInstant(zoneDestino);
            LocalDateTime fechaAparicionDestino = fechaAparicionZonedDestino.toLocalDateTime();

            System.out.printf("🕒 RelojSimDia: UTC=%s -> Destino (UTC%+d)=%s%n",
                    fechaAparicionUTC.toString(), offsetDestino, fechaAparicionDestino.toString());

            // Crear el envío con la fecha convertida al huso horario del destino
            Envio nuevoEnvio = new Envio();
            nuevoEnvio.setAeropuertoDestino(aeropuertoDestino);
            nuevoEnvio.setNumProductos(numProductos);
            nuevoEnvio.setCliente(cliente);
            nuevoEnvio.setFechaIngreso(fechaAparicionDestino); // Fecha en huso horario del destino
            nuevoEnvio.setHusoHorarioDestino(husoHorarioDestino);
            nuevoEnvio.setEstado(null); // Estado NULL para operaciones diarias
            nuevoEnvio.setParteAsignadas(new ArrayList<>());

            // Guardar el envío en BD
            Envio envioGuardado = envioService.insertarEnvio(nuevoEnvio);

            // ⚡ Inicializar zonedFechaIngreso manualmente (ya que @PostLoad solo se ejecuta
            // al cargar desde BD)
            if (envioGuardado.getZonedFechaIngreso() == null
                    && envioGuardado.getFechaIngreso() != null
                    && envioGuardado.getHusoHorarioDestino() != null) {
                envioGuardado.setZonedFechaIngreso(envioGuardado.getFechaIngreso().atZone(zoneDestino));
            }

            System.out.println("✅ Envío creado con ID: " + envioGuardado.getId());

            // Verificar si el planificador ya está en ejecución
            boolean planificadorYaActivo = planificador != null && planificador.estaEnEjecucion();

            if (!planificadorYaActivo) {
                // Primera vez: iniciar el planificador
                System.out.println("🚀 Iniciando planificador en modo OPERACIONES_DIARIAS...");

                // Cargar datos necesarios
                System.out.println("📂 Cargando aeropuertos...");
                ArrayList<Aeropuerto> aeropuertos = aeropuertoService.obtenerTodosAeropuertos();
                System.out.println("✅ Aeropuertos cargados: " + aeropuertos.size());

                System.out.println("📂 Cargando continentes...");
                ArrayList<Continente> continentes = continenteService.obtenerTodosContinentes();
                System.out.println("✅ Continentes cargados: " + continentes.size());

                System.out.println("📂 Cargando países...");
                ArrayList<Pais> paises = paisService.obtenerTodosPaises();
                System.out.println("✅ Países cargados: " + paises.size());

                // ⚡ OPTIMIZACIÓN CRÍTICA: NO cargar todos los vuelos al inicio en modo
                // OPERACIONES_DIARIAS
                System.out.println(
                        "📂 [OPTIMIZACIÓN] Vuelos se cargarán por ciclo desde BD (solo horizonte actual + 3 días)");

                // Configurar GRASP
                System.out.println("⚙️ Configurando GRASP...");
                Grasp grasp = new Grasp();
                grasp.setAeropuertos(aeropuertos);
                grasp.setContinentes(continentes);
                grasp.setPaises(paises);
                grasp.setEnvios(new ArrayList<>()); // Lista vacía inicial - se cargarán por ciclo
                grasp.setPlanesDeVuelo(new ArrayList<>()); // Lista vacía inicial - se cargarán por ciclo
                grasp.setHubsPropio();
                System.out.println("✅ GRASP configurado (vuelos y envíos se cargarán por ciclo)");

                // Crear e iniciar el planificador en modo OPERACIONES_DIARIAS
                // Usar la fecha en UTC del reloj de simulación para el inicio del planificador
                System.out.println("⚙️ Creando planificador en modo OPERACIONES_DIARIAS...");
                planificador = new Planificador(grasp, webSocketService, envioService, planDeVueloService,
                        aeropuertoService, relojSimulacionDiaService);
                planificador.iniciarPlanificacionProgramada(
                        Planificador.ModoSimulacion.OPERACIONES_DIARIAS,
                        fechaAparicionUTC.minusHours(5),
                        null); // Sin fecha fin, usar fecha en UTC
                configurarPlanificador(planificador);

                planificadorIniciado = true;

                response.put("estado", "éxito");
                response.put("mensaje", "Operaciones diarias iniciadas y envío creado correctamente");
                response.put("envioCreado", Map.of(
                        "id", envioGuardado.getId(),
                        "cliente", envioGuardado.getCliente(),
                        "numProductos", envioGuardado.getNumProductos(),
                        "fechaAparicion", fechaAparicionUTC.toString(),
                        "fechaAparicionDestino", fechaAparicionDestino.toString()));
                response.put("planificadorIniciado", true);
                response.put("configuracion", Map.of(
                        "modo", "OPERACIONES_DIARIAS",
                        "fechaInicio", fechaAparicionUTC.toString(),
                        "k_factor", 1,
                        "sa_minutos", 2,
                        "ta_segundos", 70));
            } else {
                // Planificador ya activo: solo crear el envío
                System.out.println("ℹ️ Planificador ya en ejecución, solo se creó el envío");

                response.put("estado", "éxito");
                response.put("mensaje", "Envío creado correctamente (planificador ya en ejecución)");
                response.put("envioCreado", Map.of(
                        "id", envioGuardado.getId(),
                        "cliente", envioGuardado.getCliente(),
                        "numProductos", envioGuardado.getNumProductos(),
                        "fechaAparicion", fechaAparicionUTC.toString(),
                        "fechaAparicionDestino", fechaAparicionDestino.toString()));
                response.put("planificadorIniciado", true);
            }

            response.put("timestamp", LocalDateTime.now().toString());

        } catch (Exception e) {
            response.put("estado", "error");
            response.put("mensaje", "Error al iniciar operaciones diarias: " + e.getMessage());
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
            if (planificador != null && planificador.estaEnEjecucion()) {
                planificador.detenerPlanificacion();
                planificadorIniciado = false;

                // Liberar referencia para que GC recupere memoria
                planificador = null;
                System.gc(); // Sugerencia al GC para recuperar memoria

                response.put("estado", "éxito");
                response.put("mensaje", "Planificador detenido correctamente");
            } else {
                planificadorIniciado = false; // Asegurar que el flag esté sincronizado
                planificador = null; // Nullear por si quedó instancia zombie
                response.put("estado", "error");
                response.put("mensaje", "No hay planificador en ejecución");
            }

        } catch (Exception e) {
            response.put("estado", "error");
            response.put("mensaje", "Error al detener planificador: " + e.getMessage());
        }

        return response;
    }

    // Endpoint para limpiar todas las planificaciones anteriores
    // ⚡ OPTIMIZADO: Usa solo SQL nativo para evitar cargar 43K+ envíos en memoria
    @PostMapping("/limpiar-planificacion")
    @Transactional
    public Map<String, Object> limpiarPlanificacion() {
        Map<String, Object> response = new HashMap<>();
        long startTime = System.currentTimeMillis();
        System.out.println("🧹 [LIMPIAR] Iniciando limpieza de planificación...");

        try {
            // Verificar si el planificador está activo
            if (planificadorIniciado) {
                response.put("estado", "error");
                response.put("mensaje",
                        "No se puede limpiar la planificación mientras el planificador está activo. Detén el planificador primero.");
                return response;
            }

            int enviosActualizados = 0;
            int aeropuertosActualizados = 0;
            int planesActualizados = 0;
            int relacionesVuelosEliminadas = 0;
            int partesEliminadas = 0;

            // ⚡ OPTIMIZACIÓN: Usar SQL nativo para TODO, evitar cargar entidades en memoria

            // 1. Resetear estados de envíos a null con SQL nativo
            System.out.println("🧹 [LIMPIAR] Reseteando estados de envíos a NULL...");
            Query queryEstados = entityManager.createNativeQuery("UPDATE envio SET estado = NULL");
            enviosActualizados = queryEstados.executeUpdate();
            System.out.println("✅ Estados de envíos reseteados: " + enviosActualizados + " envíos");

            // 2. Resetear capacidades de aeropuertos con SQL nativo
            System.out.println("🧹 [LIMPIAR] Reseteando capacidades de aeropuertos...");
            Query queryAeropuertos = entityManager.createNativeQuery("UPDATE aeropuerto SET capacidad_ocupada = 0");
            aeropuertosActualizados = queryAeropuertos.executeUpdate();
            System.out.println("✅ Aeropuertos actualizados: " + aeropuertosActualizados);

            // 3. Resetear capacidades de planes de vuelo con SQL nativo
            System.out.println("🧹 [LIMPIAR] Reseteando capacidades de vuelos...");
            Query queryPlanes = entityManager.createNativeQuery("UPDATE plan_de_vuelo SET capacidad_ocupada = 0");
            planesActualizados = queryPlanes.executeUpdate();
            System.out.println("✅ Planes actualizados: " + planesActualizados);

            // 4. Eliminar relaciones ParteAsignadaPlanDeVuelo (tabla intermedia)
            System.out.println("🧹 [LIMPIAR] Eliminando relaciones vuelo-parte...");
            Query queryRelaciones = entityManager.createNativeQuery("DELETE FROM parte_asignada_plan_de_vuelo");
            relacionesVuelosEliminadas = queryRelaciones.executeUpdate();
            System.out.println("✅ Relaciones eliminadas: " + relacionesVuelosEliminadas);

            // 5. Eliminar todas las partes asignadas
            System.out.println("🧹 [LIMPIAR] Eliminando partes asignadas...");
            Query queryPartes = entityManager.createNativeQuery("DELETE FROM parte_asignada");
            partesEliminadas = queryPartes.executeUpdate();
            System.out.println("✅ Partes eliminadas: " + partesEliminadas);

            // Hacer flush para asegurar que los cambios se apliquen
            entityManager.flush();

            long elapsed = System.currentTimeMillis() - startTime;
            System.out.println("🧹 [LIMPIAR] ✅ Limpieza completada en " + elapsed + "ms");

            response.put("estado", "exito");
            response.put("mensaje", "Planificación limpiada correctamente");
            response.put("detalles", Map.of(
                    "enviosActualizados", enviosActualizados,
                    "aeropuertosActualizados", aeropuertosActualizados,
                    "planesActualizados", planesActualizados,
                    "relacionesVuelosEliminadas", relacionesVuelosEliminadas,
                    "partesEliminadas", partesEliminadas,
                    "tiempoEjecucionMs", elapsed));
            response.put("timestamp", LocalDateTime.now().toString());

        } catch (Exception e) {
            response.put("estado", "error");
            response.put("mensaje", "Error al limpiar planificación: " + e.getMessage());
            e.printStackTrace();
        }

        return response;
    }

    /**
     * ⚡ OPTIMIZADO: Endpoint ligero para polling frecuente desde el frontend.
     * Solo devuelve si el planificador está activo, sin cargar envíos.
     * Usado por HoraActual.jsx y SimulationControls.jsx cada 5-10 segundos.
     */
    @GetMapping("/estado-simple")
    public Map<String, Object> obtenerEstadoSimple() {
        Map<String, Object> response = new HashMap<>();
        response.put("planificadorActivo", planificadorIniciado);
        response.put("ultimaActualizacion", LocalDateTime.now().toString());

        if (planificador != null && planificadorIniciado) {
            response.put("cicloActual", planificador.getCicloActual());
            response.put("proximoCiclo", planificador.getProximoCiclo());
            // Solo estadísticas básicas, sin cargar todos los envíos
            response.put("estadisticas", planificador.getEstadisticasActuales());
        }
        return response;
    }

    /**
     * Endpoint completo para obtener estado con pedidos clasificados.
     * ⚠️ NOTA: Este endpoint es pesado (carga 43K+ envíos). Usar solo cuando se
     * necesiten
     * los detalles de pedidos, no para polling frecuente.
     */
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

        // Agregar información de pedidos clasificados por estado
        Map<String, Object> pedidosConEstado = envioService.obtenerPedidosConEstado();
        response.put("pedidosClasificados", pedidosConEstado);

        return response;
    }

    // Endpoint para obtener resultados del último ciclo
    @GetMapping("/ultimo-ciclo")
    public Map<String, Object> obtenerUltimoCiclo() {
        Map<String, Object> response = new HashMap<>();

        if (planificador != null && planificadorIniciado) {
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
            envioFrontend.put("aparicion", formatFechaConOffset(
                    envio.getZonedFechaIngreso(),
                    envio.getFechaIngreso(),
                    envio.getHusoHorarioDestino(),
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

            // ✅ PARTES como array dentro del mismo envío
            List<Map<String, Object>> partesFrontend = new ArrayList<>();

            if (envio.getParteAsignadas() != null) {
                for (ParteAsignada parte : envio.getParteAsignadas()) {
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

                    if (parte.getRuta() != null) {
                        for (PlanDeVuelo vuelo : parte.getRuta()) {
                            Map<String, Object> tramoFrontend = new HashMap<>();
                            tramoFrontend.put("vueloBaseId", vuelo.getId());
                            tramoFrontend.put("origen", aeropuertoService
                                    .obtenerAeropuertoPorId(vuelo.getCiudadOrigen()).get().getCodigo());
                            tramoFrontend.put("destino", aeropuertoService
                                    .obtenerAeropuertoPorId(vuelo.getCiudadDestino()).get().getCodigo());
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

    /**
     * ⚡ OPTIMIZADO: Endpoint para obtener resumen de simulación.
     * Usa consultas COUNT directas a BD (rápido, no carga todos los envíos).
     * Filtra solo los envíos del rango de fechas de la simulación actual.
     */
    @GetMapping("/resumen-planificacion")
    @Transactional(readOnly = true)
    public Map<String, Object> obtenerResumenPlanificacion() {
        Map<String, Object> response = new HashMap<>();

        try {
            // ⚡ Obtener conteos directamente de BD con consulta COUNT (muy rápido)
            // Filtra por las fechas de la simulación actual
            Map<String, Long> conteosPorEstado = obtenerConteosPorEstadoDB(
                    fechaInicioSimulacion, fechaFinSimulacion);

            long totalEnvios = conteosPorEstado.values().stream().mapToLong(Long::longValue).sum();
            long enviosPlanificados = conteosPorEstado.getOrDefault("PLANIFICADO", 0L);
            long enviosEnRuta = conteosPorEstado.getOrDefault("EN_RUTA", 0L);
            long enviosFinalizados = conteosPorEstado.getOrDefault("FINALIZADO", 0L);
            long enviosEntregados = conteosPorEstado.getOrDefault("ENTREGADO", 0L);
            long enviosRegistrados = conteosPorEstado.getOrDefault("REGISTRADO", 0L);
            // Envíos sin estado (NULL) = no procesados aún
            long enviosSinEstado = conteosPorEstado.getOrDefault("NULL", 0L);

            // Estadísticas de pedidos
            Map<String, Object> statsPedidos = new HashMap<>();
            statsPedidos.put("totalPedidos", totalEnvios);
            statsPedidos.put("pedidosCompletados", enviosEntregados);
            statsPedidos.put("pedidosParciales", enviosFinalizados);
            statsPedidos.put("pedidosSinAsignar", enviosRegistrados + enviosSinEstado);
            statsPedidos.put("tasaExito", totalEnvios > 0
                    ? (enviosEntregados * 100.0 / totalEnvios)
                    : 0.0);
            response.put("estadisticasPedidos", statsPedidos);

            // Estadísticas por estado
            Map<String, Object> statsPorEstado = new HashMap<>();
            statsPorEstado.put("enviosPlanificados", enviosPlanificados);
            statsPorEstado.put("enviosEnRuta", enviosEnRuta);
            statsPorEstado.put("enviosFinalizados", enviosFinalizados);
            statsPorEstado.put("enviosEntregados", enviosEntregados);
            statsPorEstado.put("enviosRegistrados", enviosRegistrados + enviosSinEstado);
            response.put("estadisticasPorEstado", statsPorEstado);

            // Información general
            Map<String, Object> infoGeneral = new HashMap<>();
            infoGeneral.put("cicloActual", planificador != null ? planificador.getCicloActual() : 0);
            infoGeneral.put("enEjecucion", planificadorIniciado);
            infoGeneral.put("totalEnviosBD", totalEnvios);
            infoGeneral.put("fechaInicio", fechaInicioSimulacion != null ? fechaInicioSimulacion.toString() : null);
            infoGeneral.put("fechaFin", fechaFinSimulacion != null ? fechaFinSimulacion.toString() : null);

            // Obtener estadísticas en memoria si están disponibles
            if (planificador != null) {
                Map<String, Object> stats = planificador.getEstadisticasActuales();
                infoGeneral.put("ultimaEjecucion", stats.get("ultimaEjecucion"));
                infoGeneral.put("totalCiclosCompletados", stats.get("totalCiclosCompletados"));
            }
            response.put("informacionGeneral", infoGeneral);

            response.put("estado", "éxito");
            response.put("timestamp", LocalDateTime.now().toString());

        } catch (Exception e) {
            response.put("estado", "error");
            response.put("mensaje", "Error al obtener resumen: " + e.getMessage());
            // Devolver estructura vacía para que el frontend no falle
            response.put("estadisticasPedidos", Map.of("totalPedidos", 0, "tasaExito", 0.0));
            response.put("estadisticasPorEstado", Map.of("enviosEntregados", 0, "enviosEnRuta", 0));
            response.put("informacionGeneral", Map.of("cicloActual", 0));
        }

        return response;
    }

    /**
     * ⚡ Consulta COUNT agrupada por estado - muy rápida incluso con millones de
     * registros
     * Para el resumen, incluye todos los envíos con estados actualizados, independientemente
     * de su fecha de ingreso, ya que los estados se actualizan en tiempo real via WebSocket.
     */
    private Map<String, Long> obtenerConteosPorEstadoDB(LocalDateTime fechaInicio, LocalDateTime fechaFin) {
        Map<String, Long> conteos = new HashMap<>();
        try {
            String sql;
            Query query;

            // ⚡ SOLUCIÓN: Contar TODOS los envíos con estados actualizados
            // El problema es que la comparación SQL de LocalDateTime no considera zonas horarias,
            // mientras que la planificación sí las considera. Esto causa que envíos planificados
            // (que tienen estados actualizados) no aparezcan en el resumen cuando se filtra por fecha.
            //
            // La solución más simple y correcta es contar todos los envíos con estados,
            // ya que el estado es lo que realmente importa para el resumen, no la fecha de creación.
            // Si un envío tiene estado PLANIFICADO, EN_RUTA, FINALIZADO o ENTREGADO,
            // significa que fue procesado por el planificador y debe aparecer en el resumen.
            sql = "SELECT COALESCE(estado, 'NULL') as estado, COUNT(*) as cantidad " +
                    "FROM envio " +
                    "GROUP BY estado";
            query = entityManager.createNativeQuery(sql);

            @SuppressWarnings("unchecked")
            List<Object[]> resultados = query.getResultList();

            for (Object[] fila : resultados) {
                String estado = (String) fila[0];
                Long cantidad = ((Number) fila[1]).longValue();
                conteos.put(estado, cantidad);
            }
        } catch (Exception e) {
            System.err.println("⚠️ Error al obtener conteos por estado: " + e.getMessage());
            e.printStackTrace();
        }
        return conteos;
    }

    private static ZoneOffset parseOffset(String huso) {
        try {
            // tu huso parece venir como "-5", "0", "3"...
            int h = Integer.parseInt(huso.trim());
            return ZoneOffset.ofHours(h);
        } catch (Exception e) {
            return ZoneOffset.UTC;
        }
    }

    private boolean estaCirculando(PlanDeVuelo v, Instant simNow) {
        if (v.getHoraOrigen() == null || v.getHoraDestino() == null)
            return false;

        ZoneOffset offO = parseOffset(v.getHusoHorarioOrigen());
        ZoneOffset offD = parseOffset(v.getHusoHorarioDestino());

        Instant dep = v.getHoraOrigen().atOffset(offO).toInstant();
        Instant arr = v.getHoraDestino().atOffset(offD).toInstant();

        // circulando: dep <= simNow < arr
        return !simNow.isBefore(dep) && simNow.isBefore(arr);
    }

    @GetMapping("/vuelos-circulando")
    public Map<String, Object> vuelosCirculando(
            @RequestParam(defaultValue = "12") int ventanaHoras) {
        Map<String, Object> resp = new HashMap<>();

        // 1) Tiempo simulado actual (UTC)
        Instant simNow = relojSimulacionDiaService.getCurrentSimInstant();
        LocalDateTime simNowUTC = LocalDateTime.ofInstant(simNow, ZoneOffset.UTC);

        // 2) Traer candidatos por ventana (prefiltro rápido)
        // Nota: obtenerVuelosEnRango recibe (inicio, husoInicio, fin, husoFin)
        LocalDateTime ini = simNowUTC.minusHours(ventanaHoras);
        LocalDateTime fin = simNowUTC.plusHours(ventanaHoras);

        List<PlanDeVuelo> candidatos = planDeVueloService.obtenerVuelosEnRango(ini, "0", fin, "0");

        // 3) Filtrar “circulando” con Instant real
        List<Map<String, Object>> circulando = candidatos.stream()
                .filter(v -> estaCirculando(v, simNow))
                .map(v -> convertirVueloParaFrontend(v, Collections.emptyMap()))
                .collect(Collectors.toList());

        resp.put("estado", "exito");
        resp.put("simInstant", simNow.toString());
        resp.put("simNowUTC", simNowUTC.toString());
        resp.put("ventanaHoras", ventanaHoras);
        resp.put("cantidad", circulando.size());
        resp.put("vuelos", circulando);
        return resp;
    }

    @GetMapping("/vuelos-ultimo-ciclo")
    public Map<String, Object> obtenerVuelosUltimoCiclo() {
        Map<String, Object> response = new HashMap<>();

        if (planificador == null || !planificadorIniciado) {
            response.put("estado", "inactivo");
            response.put("mensaje", "El planificador no está activo");
            return response;
        }

        List<PlanDeVuelo> vuelos = planificador.getVuelosUltimoCiclo();
        List<Map<String, Object>> asignaciones = planificador.getAsignacionesUltimoCiclo();
        Map<Integer, List<Map<String, Object>>> asignacionesPorVuelo = asignaciones.stream()
                .filter(a -> a.get("vueloId") != null)
                .collect(Collectors.groupingBy(a -> (Integer) a.get("vueloId")));

        if (vuelos == null || vuelos.isEmpty()) {
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
                "fin", fin != null ? formatFechaConOffset(null, fin, "0", formatter) : "N/A"));

        List<Map<String, Object>> vuelosFrontend = vuelos.stream()
                .map(v -> convertirVueloParaFrontend(v, asignacionesPorVuelo))
                .collect(Collectors.toList());

        response.put("vuelos", vuelosFrontend);

        // Agregar lista de envíos planificados con sus partes y vuelos
        Solucion ultimaSolucion = planificador.getUltimaSolucion();
        if (ultimaSolucion != null && ultimaSolucion.getEnvios() != null) {
            List<Map<String, Object>> enviosPlanificados = convertirEnviosPlanificadosParaFrontend(
                    ultimaSolucion.getEnvios(), formatter);
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
                    aeropuertoMap.put("capacidadOcupada",
                            a.getCapacidadOcupada() != null ? a.getCapacidadOcupada() : 0);
                    aeropuertoMap.put("capacidadMaxima", a.getCapacidadMaxima());
                    return aeropuertoMap;
                })
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> convertirEnviosPlanificadosParaFrontend(List<Envio> envios,
            DateTimeFormatter formatter) {
        List<Map<String, Object>> enviosFrontend = new ArrayList<>();

        for (Envio envio : envios) {
            // Solo incluir envíos que tengan partes asignadas
            if (envio.getParteAsignadas() == null || envio.getParteAsignadas().isEmpty()) {
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
            if (envio.getAeropuertoDestino() != null) {
                envioMap.put("destino", Map.of(
                        "id", envio.getAeropuertoDestino().getId(),
                        "codigo", envio.getAeropuertoDestino().getCodigo(),
                        "ciudad", envio.getAeropuertoDestino().getCiudad()));
            }

            // Información de aparición
            envioMap.put("aparicion", formatFechaConOffset(
                    envio.getZonedFechaIngreso(),
                    envio.getFechaIngreso(),
                    envio.getHusoHorarioDestino(),
                    formatter));

            // Lista de partes (si el pedido está dividido)
            List<Map<String, Object>> partesFrontend = new ArrayList<>();

            for (ParteAsignada parte : envio.getParteAsignadas()) {
                Map<String, Object> parteMap = new HashMap<>();
                parteMap.put("cantidad", parte.getCantidad());

                // Aeropuerto origen de esta parte
                if (parte.getAeropuertoOrigen() != null) {
                    parteMap.put("aeropuertoOrigen", Map.of(
                            "id", parte.getAeropuertoOrigen().getId(),
                            "codigo", parte.getAeropuertoOrigen().getCodigo(),
                            "ciudad", parte.getAeropuertoOrigen().getCiudad()));
                }

                // Llegada final de esta parte
                parteMap.put("llegadaFinal", formatFechaConOffset(
                        parte.getLlegadaFinal(),
                        null,
                        null,
                        formatter));

                // Lista de vuelos por los que pasa esta parte (ruta completa)
                List<Map<String, Object>> vuelosRuta = new ArrayList<>();

                if (parte.getRuta() != null && !parte.getRuta().isEmpty()) {
                    for (int i = 0; i < parte.getRuta().size(); i++) {
                        PlanDeVuelo vuelo = parte.getRuta().get(i);
                        Map<String, Object> vueloRutaMap = new HashMap<>();
                        vueloRutaMap.put("orden", i + 1); // Orden en la ruta (1, 2, 3...)
                        vueloRutaMap.put("vueloId", vuelo.getId());

                        // Origen del vuelo
                        aeropuertoService.obtenerAeropuertoPorId(vuelo.getCiudadOrigen())
                                .ifPresent(a -> vueloRutaMap.put("origen", Map.of(
                                        "id", a.getId(),
                                        "codigo", a.getCodigo(),
                                        "ciudad", a.getCiudad())));

                        // Destino del vuelo
                        aeropuertoService.obtenerAeropuertoPorId(vuelo.getCiudadDestino())
                                .ifPresent(a -> vueloRutaMap.put("destino", Map.of(
                                        "id", a.getId(),
                                        "codigo", a.getCodigo(),
                                        "ciudad", a.getCiudad())));

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

    private Map<String, Object> convertirVueloParaFrontend(PlanDeVuelo vuelo,
            Map<Integer, List<Map<String, Object>>> asignacionesPorVuelo) {
        Map<String, Object> vueloFrontend = new HashMap<>();
        vueloFrontend.put("id", vuelo.getId());
        vueloFrontend.put("capacidadMaxima", vuelo.getCapacidadMaxima());
        vueloFrontend.put("capacidadOcupada", vuelo.getCapacidadOcupada());
        vueloFrontend.put("capacidadLibre", vuelo.getCapacidadMaxima() != null && vuelo.getCapacidadOcupada() != null
                ? vuelo.getCapacidadMaxima() - vuelo.getCapacidadOcupada()
                : null);
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
                        "pais", a.getPais() != null ? a.getPais().getNombre() : null)));

        aeropuertoService.obtenerAeropuertoPorId(vuelo.getCiudadDestino())
                .ifPresent(a -> vueloFrontend.put("destino", Map.of(
                        "id", a.getId(),
                        "codigo", a.getCodigo(),
                        "ciudad", a.getCiudad(),
                        "pais", a.getPais() != null ? a.getPais().getNombre() : null)));

        List<Map<String, Object>> asignaciones = vuelo.getId() != null
                ? asignacionesPorVuelo.getOrDefault(vuelo.getId(), Collections.emptyList())
                : Collections.emptyList();
        vueloFrontend.put("enviosAsignados", asignaciones);

        return vueloFrontend;
    }

    private String formatFechaConOffset(ZonedDateTime zoned, LocalDateTime local, String husoHorario,
            DateTimeFormatter formatter) {
        if (zoned != null) {
            ZoneOffset offset = zoned.getOffset();
            String offsetStr = offset.getId().equals("Z") ? "+00:00" : offset.getId();
            return String.format("%s (UTC%s)", zoned.format(formatter), offsetStr);
        }

        if (local != null && husoHorario != null) {
            int offsetHoras;
            try {
                offsetHoras = Integer.parseInt(husoHorario);
            } catch (NumberFormatException e) {
                offsetHoras = 0;
            }
            return String.format("%s (UTC%+03d:00)", local.format(formatter), offsetHoras);
        }

        if (local != null) {
            return String.format("%s (UTC%+03d:00)", local.format(formatter), 0);
        }

        return "N/A";
    }

    /**
     * Carga vuelos desde el archivo vuelos.txt para los 7 días de la semana
     *
     * @param fechaBase Fecha base (primer día de la semana)
     * @return Lista de planes de vuelo generados para 7 días
     */
    private ArrayList<PlanDeVuelo> cargarVuelosParaSemanaDesdeArchivo(LocalDate fechaBase) {
        ArrayList<PlanDeVuelo> planes = new ArrayList<>();
        Scanner scanner = null;
        InputStream inputStream = null;

        try {
            // Intentar leer desde el classpath primero (funciona en JAR y en desarrollo)
            inputStream = getClass().getClassLoader().getResourceAsStream("planes/vuelos.txt");

            if (inputStream != null) {
                System.out.println("📂 Leyendo archivo desde classpath: planes/vuelos.txt");
                scanner = new Scanner(inputStream, "UTF-8");
            } else {
                // Si no se encuentra en el classpath, intentar como archivo del sistema
                File planesFile = new File("src/main/resources/planes/vuelos.txt");

                if (!planesFile.exists()) {
                    // También intentar desde la raíz del proyecto
                    planesFile = new File("planes/vuelos.txt");

                    if (!planesFile.exists()) {
                        // Intentar con ruta absoluta relativa al directorio de trabajo
                        String workingDir = System.getProperty("user.dir");
                        planesFile = new File(workingDir + "/src/main/resources/planes/vuelos.txt");
                    }
                }

                if (planesFile.exists()) {
                    System.out.println("📂 Leyendo archivo desde sistema de archivos: " + planesFile.getAbsolutePath());
                    scanner = new Scanner(planesFile, "UTF-8");
                } else {
                    System.err.println("❌ Archivo no encontrado. Buscado en:");
                    System.err.println("  - classpath:planes/vuelos.txt");
                    System.err.println("  - src/main/resources/planes/vuelos.txt");
                    System.err.println("  - planes/vuelos.txt");
                    System.err
                            .println("  - " + System.getProperty("user.dir") + "/src/main/resources/planes/vuelos.txt");
                    return planes;
                }
            }

            // Procesar el archivo
            planes = procesarArchivoVuelos(scanner, fechaBase);

            System.out.println("📊 Vuelos procesados del archivo: " + planes.size());

            // Guardar todos los vuelos en la base de datos
            if (!planes.isEmpty()) {
                planDeVueloService.insertarListaPlanesDeVuelo(planes);
                System.out.println("✅ Se generaron " + planes.size() + " vuelos para 7 días (desde " + fechaBase
                        + " hasta " + fechaBase.plusDays(6) + ")");
            } else {
                System.err.println(
                        "⚠️  El archivo se leyó pero no se generaron vuelos. Verifique el formato del archivo.");
            }

        } catch (FileNotFoundException e) {
            System.err.println("❌ Archivo de vuelos no encontrado: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("❌ Error al cargar vuelos desde archivo: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Cerrar recursos
            if (scanner != null) {
                scanner.close();
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception e) {
                    System.err.println("Error al cerrar inputStream: " + e.getMessage());
                }
            }
        }

        return planes;
    }

    /**
     * Procesa el archivo de vuelos y genera planes de vuelo para 7 días
     *
     * @param scanner   Scanner del archivo
     * @param fechaBase Fecha base (primer día de la semana)
     * @return Lista de planes de vuelo generados
     */
    private ArrayList<PlanDeVuelo> procesarArchivoVuelos(Scanner scanner, LocalDate fechaBase) {
        ArrayList<PlanDeVuelo> planes = new ArrayList<>();

        while (scanner.hasNextLine()) {
            String row = scanner.nextLine().trim();

            if (row.isEmpty()) {
                continue;
            }

            String[] data = row.split("-");

            // Formato: ORIGEN-DESTINO-HORA_ORIGEN-HORA_DESTINO-CAPACIDAD
            if (data.length >= 5) {
                Optional<Aeropuerto> aeropuertoOptionalOrig = aeropuertoService.obtenerAeropuertoPorCodigo(data[0]);
                Optional<Aeropuerto> aeropuertoOptionalDest = aeropuertoService.obtenerAeropuertoPorCodigo(data[1]);

                if (aeropuertoOptionalOrig.isPresent() && aeropuertoOptionalDest.isPresent()) {
                    Aeropuerto aeropuertoOrigen = aeropuertoOptionalOrig.get();
                    Aeropuerto aeropuertoDest = aeropuertoOptionalDest.get();

                    Integer ciudadOrigen = aeropuertoOrigen.getId();
                    Integer ciudadDestino = aeropuertoDest.getId();
                    String husoOrigen = aeropuertoOrigen.getHusoHorario();
                    String husoDestino = aeropuertoDest.getHusoHorario();

                    LocalTime hI = LocalTime.parse(data[2]);
                    LocalTime hF = LocalTime.parse(data[3]);
                    Integer capacidad = Integer.parseInt(data[4]);

                    // Generar vuelos para los 7 días de la semana
                    for (int diaOffset = 0; diaOffset < 7; diaOffset++) {
                        LocalDate fechaVuelo = fechaBase.plusDays(diaOffset);

                        LocalDateTime fechaInicio = LocalDateTime.of(fechaVuelo, hI);
                        LocalDateTime fechaFin;

                        // Calcular si el vuelo acaba en el mismo o diferente día
                        Integer cantDias = planDeVueloService.planAcabaAlSiguienteDia(
                                data[2], data[3], husoOrigen, husoDestino,
                                fechaVuelo.getYear(), fechaVuelo.getMonthValue(), fechaVuelo.getDayOfMonth());

                        fechaFin = LocalDateTime.of(fechaVuelo, hF).plusDays(cantDias);

                        // Verificar si mismo continente
                        Integer contOrig = aeropuertoOrigen.getPais() != null
                                ? aeropuertoOrigen.getPais().getIdContinente()
                                : null;
                        Integer contDest = aeropuertoDest.getPais() != null
                                ? aeropuertoDest.getPais().getIdContinente()
                                : null;
                        Boolean mismoContinente = (contOrig != null && contDest != null)
                                ? contOrig.equals(contDest)
                                : null;

                        PlanDeVuelo plan = PlanDeVuelo.builder()
                                .ciudadOrigen(ciudadOrigen)
                                .ciudadDestino(ciudadDestino)
                                .horaOrigen(fechaInicio)
                                .horaDestino(fechaFin)
                                .husoHorarioOrigen(husoOrigen)
                                .husoHorarioDestino(husoDestino)
                                .capacidadMaxima(capacidad)
                                .mismoContinente(mismoContinente)
                                .capacidadOcupada(0)
                                .estado(1)
                                .build();

                        planes.add(plan);
                    }
                }
            }
        }

        return planes;
    }

    /**
     * Endpoint para descargar el reporte de la última planificación
     *
     * @return ResponseEntity con el archivo de reporte
     */
    @GetMapping("/descargar-reporte")
    public ResponseEntity<Resource> descargarReporte() {
        try {
            // Ruta del archivo de reporte (a nivel de morapack-backend)
            // Usa directorio de trabajo para funcionar en desarrollo y producción (JAR)
            String userDir = System.getProperty("user.dir");
            Path archivoReporte = Paths.get(userDir, "reporte", "reporte-ultima-planificacion.txt");

            // Verificar si el archivo existe
            if (!Files.exists(archivoReporte)) {
                return ResponseEntity.notFound().build();
            }

            // Crear recurso desde el archivo
            Resource resource = new UrlResource(archivoReporte.toUri());

            // Verificar que el recurso existe y es legible
            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.notFound().build();
            }

            // Configurar headers para la descarga
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"reporte-ultima-planificacion.txt\"");
            headers.add(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE);

            return ResponseEntity.ok()
                    .headers(headers)
                    .contentLength(Files.size(archivoReporte))
                    .contentType(MediaType.parseMediaType("text/plain; charset=UTF-8"))
                    .body(resource);

        } catch (Exception e) {
            System.err.printf("❌ Error al descargar reporte: %s%n", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.internalServerError().build();
        }
    }
}

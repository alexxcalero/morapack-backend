package pe.edu.pucp.morapack.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import pe.edu.pucp.morapack.models.*;
import pe.edu.pucp.morapack.services.servicesImp.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
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
    private final EntityManager entityManager;
    // Nota: Se eliminaron los repositorios directos - ahora usamos SQL nativo vía
    // EntityManager

    private Planificador planificador;
    private boolean planificadorIniciado = false;

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

            // ⚡ OPTIMIZACIÓN CRÍTICA: Solo cargar datos básicos (aeropuertos, continentes, países)
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
                    aeropuertoService);
            planificador.iniciarPlanificacionProgramada();

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
                    aeropuertoService);
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
                    "sc_minutos", 120));
            response.put("timestamp", LocalDateTime.now().toString());

        } catch (Exception e) {
            response.put("estado", "error");
            response.put("mensaje", "Error al iniciar simulación semanal: " + e.getMessage());
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
                    aeropuertoService);
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

            // Cargar datos necesarios
            ArrayList<Aeropuerto> aeropuertos = aeropuertoService.obtenerTodosAeropuertos();
            ArrayList<Continente> continentes = continenteService.obtenerTodosContinentes();
            ArrayList<Pais> paises = paisService.obtenerTodosPaises();

            // ⚡ OPTIMIZACIÓN: Cargar solo vuelos y envíos desde la fecha de inicio
            // (simulación colapso sin límite)
            LocalDateTime fechaInicioVuelos = fechaInicio.minusDays(1);
            ArrayList<PlanDeVuelo> planes = planDeVueloService.obtenerVuelosDesdeFecha(fechaInicioVuelos, "0");
            ArrayList<Envio> envios = envioService.obtenerEnviosDesdeFechaConPartes(fechaInicioVuelos, "0");

            System.out.println("🚀 INICIANDO SIMULACIÓN DE COLAPSO");
            System.out.println("DEBUG: aeropuertos=" + aeropuertos.size() +
                    ", planes=" + planes.size() + " (desde: " + fechaInicioVuelos + ")" +
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

            // Crear e iniciar el planificador en modo COLAPSO
            planificador = new Planificador(grasp, webSocketService, envioService, planDeVueloService,
                    aeropuertoService);
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
                    "sc_minutos", 120));
            response.put("timestamp", LocalDateTime.now().toString());

        } catch (Exception e) {
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

            int aeropuertosActualizados = 0;
            int planesActualizados = 0;
            int relacionesVuelosEliminadas = 0;
            int partesEliminadas = 0;

            // ⚡ OPTIMIZACIÓN: Usar SQL nativo para TODO, evitar cargar entidades en memoria

            // 1. Resetear capacidades de aeropuertos con SQL nativo
            System.out.println("🧹 [LIMPIAR] Reseteando capacidades de aeropuertos...");
            Query queryAeropuertos = entityManager.createNativeQuery("UPDATE aeropuerto SET capacidad_ocupada = 0");
            aeropuertosActualizados = queryAeropuertos.executeUpdate();
            System.out.println("✅ Aeropuertos actualizados: " + aeropuertosActualizados);

            // 2. Resetear capacidades de planes de vuelo con SQL nativo
            System.out.println("🧹 [LIMPIAR] Reseteando capacidades de vuelos...");
            Query queryPlanes = entityManager.createNativeQuery("UPDATE plan_de_vuelo SET capacidad_ocupada = 0");
            planesActualizados = queryPlanes.executeUpdate();
            System.out.println("✅ Planes actualizados: " + planesActualizados);

            // 3. Eliminar relaciones ParteAsignadaPlanDeVuelo (tabla intermedia)
            System.out.println("🧹 [LIMPIAR] Eliminando relaciones vuelo-parte...");
            Query queryRelaciones = entityManager.createNativeQuery("DELETE FROM parte_asignada_plan_de_vuelo");
            relacionesVuelosEliminadas = queryRelaciones.executeUpdate();
            System.out.println("✅ Relaciones eliminadas: " + relacionesVuelosEliminadas);

            // 4. Eliminar todas las partes asignadas
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

    // Endpoint para obtener el resumen de la última simulación
    @GetMapping("/resumen-planificacion")
    public Map<String, Object> obtenerResumenPlanificacion() {
        Map<String, Object> response = new HashMap<>();

        try {
            // Si no hay planificador en memoria, crear uno temporal para acceder al método
            // o cargar desde BD directamente
            if (planificador == null) {
                // Crear un planificador temporal para usar sus métodos de servicio
                // Esto permite obtener el resumen incluso después de reiniciar la aplicación
                Grasp grasp = new Grasp();
                planificador = new Planificador(grasp, webSocketService, envioService,
                        planDeVueloService, aeropuertoService);
            }

            Map<String, Object> resumen = planificador.obtenerResumenUltimaSimulacion();

            response.put("estado", "éxito");
            response.putAll(resumen);

        } catch (Exception e) {
            response.put("estado", "error");
            response.put("mensaje", "Error al obtener resumen de planificación: " + e.getMessage());
            e.printStackTrace();
        }

        return response;
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
}

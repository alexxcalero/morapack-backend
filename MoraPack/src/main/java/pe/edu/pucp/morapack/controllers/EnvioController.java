package pe.edu.pucp.morapack.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import pe.edu.pucp.morapack.models.*;
import pe.edu.pucp.morapack.services.servicesImp.AeropuertoServiceImp;
import pe.edu.pucp.morapack.services.servicesImp.EnvioServiceImp;
import pe.edu.pucp.morapack.services.servicesImp.PaisServiceImp;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;

@CrossOrigin(origins = "*")
@RestController
@RequiredArgsConstructor
@RequestMapping("api/envios")
public class EnvioController {
    private final EnvioServiceImp envioService;
    private final AeropuertoServiceImp aeropuertoService;
    private final PaisServiceImp paisService;

    @PostMapping("insertar")
    public Envio insertarEnvio(Envio envio) {
        // Resolver aeropuerto destino si viene solo el id
        if (envio.getAeropuertoDestino() != null &&
                envio.getAeropuertoDestino().getId() != null) {

            Integer idDest = envio.getAeropuertoDestino().getId();

            Aeropuerto destinoReal = aeropuertoService
                    .obtenerAeropuertoPorId(idDest) // ver siguiente punto
                    .orElseThrow(() -> new IllegalArgumentException("Aeropuerto destino no encontrado: " + idDest));

            envio.setAeropuertoDestino(destinoReal);
        }

        // Guardar y devolver el envío
        return envioService.insertarEnvio(envio);
    }

    @PostMapping("insertarTodos")
    public ArrayList<Envio> insertarTodos(ArrayList<Envio> envios) {
        return envioService.insertarListaEnvios(envios);
    }

    @GetMapping("obtenerTodos")
    public ArrayList<Envio> obtenerTodos() {
        return envioService.obtenerEnvios();
    }

    @GetMapping("obtenerPorId/{id}")
    public Optional<Envio> obtenerEnvioPorId(@PathVariable Integer id) {
        return envioService.obtenerEnvioPorId(id);
    }

    /**
     * 🔍 BÚSQUEDA DE ENVÍOS: Permite buscar envíos por ID (completo o parcial)
     * incluyendo sus rutas de vuelos. Útil para encontrar envíos específicos
     * que están en aviones volando.
     * 
     * @param query Texto de búsqueda (puede ser ID completo o parte del ID)
     * @param limit Límite de resultados (por defecto 50, máximo 100)
     */
    @GetMapping("buscar")
    @Transactional(readOnly = true)
    public Map<String, Object> buscarEnvios(
            @RequestParam String query,
            @RequestParam(defaultValue = "50") int limit) {

        Map<String, Object> response = new HashMap<>();
        long start = System.currentTimeMillis();

        try {
            int maxLimit = Math.min(limit, 100);
            String queryTrimmed = query.trim();

            if (queryTrimmed.isEmpty()) {
                response.put("estado", "error");
                response.put("mensaje", "Se requiere un término de búsqueda");
                return response;
            }

            System.out.println("🔍 [buscarEnvios] Buscando: '" + queryTrimmed + "' (limit=" + maxLimit + ")");

            // Buscar envíos cuyo ID contenga el término de búsqueda
            List<Envio> enviosEncontrados = envioService.buscarEnviosPorIdConRutas(queryTrimmed, maxLimit);

            // Convertir a formato de respuesta
            List<Map<String, Object>> enviosFormateados = new ArrayList<>();

            for (Envio envio : enviosEncontrados) {
                Map<String, Object> envioMap = new HashMap<>();
                envioMap.put("id", envio.getId());
                envioMap.put("idEnvioPorAeropuerto", envio.getIdEnvioPorAeropuerto());
                envioMap.put("numProductos", envio.getNumProductos());
                envioMap.put("cliente", envio.getCliente());
                envioMap.put("fechaIngreso", envio.getFechaIngreso());
                envioMap.put("estado", envio.getEstado() != null ? envio.getEstado().name() : null);

                // Aeropuerto destino
                if (envio.getAeropuertoDestino() != null) {
                    Map<String, Object> destino = new HashMap<>();
                    destino.put("id", envio.getAeropuertoDestino().getId());
                    destino.put("codigo", envio.getAeropuertoDestino().getCodigo());
                    destino.put("ciudad", envio.getAeropuertoDestino().getCiudad());
                    envioMap.put("aeropuertoDestino", destino);
                }

                // Partes asignadas con vuelos
                List<Map<String, Object>> partesFormateadas = new ArrayList<>();
                if (envio.getParteAsignadas() != null) {
                    for (ParteAsignada parte : envio.getParteAsignadas()) {
                        Map<String, Object> parteMap = new HashMap<>();
                        parteMap.put("id", parte.getId());
                        parteMap.put("cantidad", parte.getCantidad());
                        parteMap.put("entregado", parte.getEntregado());

                        // Aeropuerto origen de la parte
                        if (parte.getAeropuertoOrigen() != null) {
                            Map<String, Object> origen = new HashMap<>();
                            origen.put("id", parte.getAeropuertoOrigen().getId());
                            origen.put("codigo", parte.getAeropuertoOrigen().getCodigo());
                            origen.put("ciudad", parte.getAeropuertoOrigen().getCiudad());
                            parteMap.put("aeropuertoOrigen", origen);
                        }

                        // Vuelos de la ruta
                        List<Map<String, Object>> vuelosRuta = new ArrayList<>();
                        if (parte.getVuelosRuta() != null) {
                            for (ParteAsignadaPlanDeVuelo vueloEnRuta : parte.getVuelosRuta()) {
                                PlanDeVuelo vuelo = vueloEnRuta.getPlanDeVuelo();
                                if (vuelo != null) {
                                    Map<String, Object> vueloMap = new HashMap<>();
                                    vueloMap.put("id", vuelo.getId());
                                    vueloMap.put("orden", vueloEnRuta.getOrden());
                                    vueloMap.put("ciudadOrigen", vuelo.getCiudadOrigen());
                                    vueloMap.put("ciudadDestino", vuelo.getCiudadDestino());
                                    vueloMap.put("horaSalida", vuelo.getHoraOrigen());
                                    vueloMap.put("horaLlegada", vuelo.getHoraDestino());
                                    vuelosRuta.add(vueloMap);
                                }
                            }
                        }
                        parteMap.put("vuelosRuta", vuelosRuta);
                        partesFormateadas.add(parteMap);
                    }
                }
                envioMap.put("parteAsignadas", partesFormateadas);
                envioMap.put("totalPartes", partesFormateadas.size());
                envioMap.put("totalVuelos", partesFormateadas.stream()
                        .mapToInt(p -> ((List<?>) p.get("vuelosRuta")).size())
                        .sum());

                enviosFormateados.add(envioMap);
            }

            response.put("estado", "éxito");
            response.put("envios", enviosFormateados);
            response.put("cantidadEncontrados", enviosFormateados.size());
            response.put("query", queryTrimmed);

        } catch (Exception e) {
            System.err.println("❌ Error en buscarEnvios: " + e.getMessage());
            e.printStackTrace();
            response.put("estado", "error");
            response.put("mensaje", e.getMessage());
        }

        long elapsed = System.currentTimeMillis() - start;
        response.put("tiempoMs", elapsed);
        System.out.println("🔍 [buscarEnvios] Completado en " + elapsed + "ms");

        return response;
    }

    @GetMapping("obtenerTodosFecha/{fecha}")
    public ArrayList<Envio> obtenerEnviosPorFecha(@PathVariable String fecha) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate localDate = LocalDate.parse(fecha, formatter);

        return envioService.obtenerEnviosPorFecha(localDate);
    }

    @GetMapping("obtenerPedidosConEstado")
    public Map<String, Object> obtenerPedidosConEstado() {
        return envioService.obtenerPedidosConEstado();
    }

    /**
     * ⚡ ENDPOINT PARA RUTAS DE ENVÍOS: Retorna envíos planificados CON sus rutas de
     * vuelos.
     * Este es el endpoint correcto para mostrar los aviones con envíos en el mapa.
     * A diferencia de /obtenerPendientes, este SÍ incluye los vuelos de cada parte.
     * 
     * @param limit Límite de envíos (por defecto 100, máximo 200)
     */
    @GetMapping("obtenerPlanificadosConRutas")
    @Transactional(readOnly = true)
    public Map<String, Object> obtenerEnviosPlanificadosConRutas(
            @RequestParam(defaultValue = "100") int limit) {
        long startTime = System.currentTimeMillis();
        int maxLimit = Math.min(limit, 200); // Límite estricto para evitar OOM
        System.out.println("✈️ [obtenerPlanificadosConRutas] Iniciando (limit=" + maxLimit + ")...");

        Map<String, Object> resultado = new HashMap<>();

        // Cargar aeropuertos para resolver IDs a objetos
        ArrayList<Aeropuerto> todosAeropuertos = aeropuertoService.obtenerTodosAeropuertos();
        Map<Integer, Aeropuerto> aeropuertosPorId = new HashMap<>();
        for (Aeropuerto a : todosAeropuertos) {
            aeropuertosPorId.put(a.getId(), a);
        }

        try {
            // Obtener envíos con partes asignadas
            List<Envio> enviosConPartes = envioService.obtenerEnviosConPartesYVuelosLimitado(maxLimit);
            System.out.println("✈️ Envíos con partes y vuelos: " + enviosConPartes.size());

            List<Map<String, Object>> enviosPlanificados = new ArrayList<>();
            List<Map<String, Object>> vuelosUnicos = new ArrayList<>();
            java.util.Set<Integer> vuelosVistos = new java.util.HashSet<>();

            java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd HH:mm");

            for (Envio envio : enviosConPartes) {
                if (envio.getParteAsignadas() == null || envio.getParteAsignadas().isEmpty()) {
                    continue;
                }

                // Filtrar partes NO entregadas que tienen vuelos
                List<ParteAsignada> partesConVuelos = new ArrayList<>();
                for (ParteAsignada parte : envio.getParteAsignadas()) {
                    if (!Boolean.TRUE.equals(parte.getEntregado()) &&
                            parte.getVuelosRuta() != null && !parte.getVuelosRuta().isEmpty()) {
                        partesConVuelos.add(parte);
                    }
                }

                if (partesConVuelos.isEmpty())
                    continue;

                Map<String, Object> envioMap = new HashMap<>();
                envioMap.put("id", envio.getId());
                envioMap.put("idEnvioPorAeropuerto", envio.getIdEnvioPorAeropuerto());
                envioMap.put("numProductos", envio.getNumProductos());
                envioMap.put("cliente", envio.getCliente());
                envioMap.put("fechaIngreso", envio.getFechaIngreso());

                if (envio.getAeropuertoDestino() != null) {
                    envioMap.put("aeropuertoDestino", Map.of(
                            "id", envio.getAeropuertoDestino().getId(),
                            "codigo", envio.getAeropuertoDestino().getCodigo(),
                            "ciudad", envio.getAeropuertoDestino().getCiudad(),
                            "latitud", envio.getAeropuertoDestino().getLatitud(),
                            "longitud", envio.getAeropuertoDestino().getLongitud()));
                }

                // Procesar partes con sus vuelos
                List<Map<String, Object>> partesMap = new ArrayList<>();
                for (ParteAsignada parte : partesConVuelos) {
                    Map<String, Object> parteMap = new HashMap<>();
                    parteMap.put("id", parte.getId());
                    parteMap.put("cantidad", parte.getCantidad());
                    parteMap.put("llegadaFinal", parte.getLlegadaFinal());

                    if (parte.getAeropuertoOrigen() != null) {
                        parteMap.put("aeropuertoOrigen", Map.of(
                                "id", parte.getAeropuertoOrigen().getId(),
                                "codigo", parte.getAeropuertoOrigen().getCodigo(),
                                "ciudad", parte.getAeropuertoOrigen().getCiudad(),
                                "latitud", parte.getAeropuertoOrigen().getLatitud(),
                                "longitud", parte.getAeropuertoOrigen().getLongitud()));
                    }

                    // Procesar vuelos de la ruta
                    List<Map<String, Object>> vuelosRutaMap = new ArrayList<>();
                    for (ParteAsignadaPlanDeVuelo vr : parte.getVuelosRuta()) {
                        if (vr.getPlanDeVuelo() == null)
                            continue;

                        PlanDeVuelo vuelo = vr.getPlanDeVuelo();
                        Map<String, Object> vueloMap = new HashMap<>();
                        vueloMap.put("id", vuelo.getId());
                        vueloMap.put("orden", vr.getOrden());

                        // horaOrigen y horaDestino son los nombres correctos en PlanDeVuelo
                        if (vuelo.getHoraOrigen() != null) {
                            vueloMap.put("horaSalida", vuelo.getHoraOrigen().format(formatter) + " (UTC+00:00)");
                        }
                        if (vuelo.getHoraDestino() != null) {
                            vueloMap.put("horaLlegada", vuelo.getHoraDestino().format(formatter) + " (UTC+00:00)");
                        }

                        // Origen - ciudadOrigen es Integer (ID), hay que buscar el aeropuerto
                        if (vuelo.getCiudadOrigen() != null) {
                            Aeropuerto origen = aeropuertosPorId.get(vuelo.getCiudadOrigen());
                            if (origen != null) {
                                vueloMap.put("ciudadOrigen", Map.of(
                                        "id", origen.getId(),
                                        "codigo", origen.getCodigo(),
                                        "ciudad", origen.getCiudad(),
                                        "latitud", origen.getLatitud(),
                                        "longitud", origen.getLongitud()));
                            }
                        }

                        // Destino - ciudadDestino es Integer (ID)
                        if (vuelo.getCiudadDestino() != null) {
                            Aeropuerto destino = aeropuertosPorId.get(vuelo.getCiudadDestino());
                            if (destino != null) {
                                vueloMap.put("ciudadDestino", Map.of(
                                        "id", destino.getId(),
                                        "codigo", destino.getCodigo(),
                                        "ciudad", destino.getCiudad(),
                                        "latitud", destino.getLatitud(),
                                        "longitud", destino.getLongitud()));
                            }
                        }

                        vuelosRutaMap.add(vueloMap);

                        // Agregar vuelo a la lista global (sin duplicados)
                        if (!vuelosVistos.contains(vuelo.getId())) {
                            vuelosVistos.add(vuelo.getId());
                            Map<String, Object> vueloGlobal = new HashMap<>(vueloMap);
                            vueloGlobal.put("envioId", envio.getId());
                            vueloGlobal.put("parteId", parte.getId());
                            vueloGlobal.put("cantidad", parte.getCantidad());
                            vuelosUnicos.add(vueloGlobal);
                        }
                    }
                    parteMap.put("vuelosRuta", vuelosRutaMap);
                    partesMap.add(parteMap);
                }

                envioMap.put("parteAsignadas", partesMap);
                envioMap.put("totalVuelos", partesMap.stream()
                        .mapToInt(p -> ((List<?>) p.get("vuelosRuta")).size())
                        .sum());
                enviosPlanificados.add(envioMap);
            }

            resultado.put("estado", "exito");
            resultado.put("envios", enviosPlanificados);
            resultado.put("cantidadEnvios", enviosPlanificados.size());
            resultado.put("vuelos", vuelosUnicos);
            resultado.put("cantidadVuelos", vuelosUnicos.size());

        } catch (Exception e) {
            System.err.println("❌ Error en obtenerPlanificadosConRutas: " + e.getMessage());
            e.printStackTrace();
            resultado.put("estado", "error");
            resultado.put("mensaje", e.getMessage());
            resultado.put("envios", new ArrayList<>());
            resultado.put("vuelos", new ArrayList<>());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        resultado.put("tiempoMs", elapsed);
        System.out.println("✈️ [obtenerPlanificadosConRutas] ✅ Completado en " + elapsed + "ms");

        return resultado;
    }

    /**
     * ⚡ ENDPOINT OPTIMIZADO: Retorna solo envíos pendientes con partes asignadas
     * NO entregadas, con datos mínimos para el frontend.
     * Esto evita cargar 43,000+ envíos y serializar 28MB de JSON.
     * ⚠️ NO incluye los vuelos de la ruta para evitar OOM.
     * 
     * @param limit Límite de envíos a retornar (por defecto 200, máximo 500)
     */
    @GetMapping("obtenerPendientes")
    public List<Map<String, Object>> obtenerEnviosPendientes(
            @RequestParam(defaultValue = "200") int limit) {
        long startTime = System.currentTimeMillis();
        // Limitar a máximo 500 para evitar OOM
        int maxLimit = Math.min(limit, 500);
        System.out.println("📦 [obtenerPendientes] Iniciando consulta optimizada (limit=" + maxLimit + ")...");

        // ⚡ USAR MÉTODO CON LÍMITE para evitar cargar todo a memoria
        List<Envio> enviosConPartes = envioService.obtenerEnviosConPartesAsignadasLimitado(maxLimit);
        System.out.println("📦 Envíos con partes encontrados: " + enviosConPartes.size());

        List<Map<String, Object>> resultado = new ArrayList<>();

        for (Envio envio : enviosConPartes) {
            if (envio.getParteAsignadas() == null || envio.getParteAsignadas().isEmpty()) {
                continue;
            }

            // Filtrar partes NO entregadas
            List<ParteAsignada> partesNoEntregadas = new ArrayList<>();
            for (ParteAsignada parte : envio.getParteAsignadas()) {
                if (!Boolean.TRUE.equals(parte.getEntregado())) {
                    partesNoEntregadas.add(parte);
                }
            }

            // Si todas las partes están entregadas, no incluir este envío
            if (partesNoEntregadas.isEmpty()) {
                continue;
            }

            Map<String, Object> envioMap = new HashMap<>();
            envioMap.put("id", envio.getId());
            envioMap.put("idEnvioPorAeropuerto", envio.getIdEnvioPorAeropuerto());
            envioMap.put("numProductos", envio.getNumProductos());
            envioMap.put("cliente", envio.getCliente());
            envioMap.put("fechaIngreso", envio.getFechaIngreso());

            // Aeropuerto destino (simplificado)
            if (envio.getAeropuertoDestino() != null) {
                Map<String, Object> destino = new HashMap<>();
                destino.put("id", envio.getAeropuertoDestino().getId());
                destino.put("codigo", envio.getAeropuertoDestino().getCodigo());
                destino.put("ciudad", envio.getAeropuertoDestino().getCiudad());
                destino.put("latitud", envio.getAeropuertoDestino().getLatitud());
                destino.put("longitud", envio.getAeropuertoDestino().getLongitud());
                envioMap.put("aeropuertoDestino", destino);
            }

            // Calcular productos asignados
            int productosAsignados = 0;
            for (ParteAsignada parte : envio.getParteAsignadas()) {
                productosAsignados += parte.getCantidad() != null ? parte.getCantidad() : 0;
            }
            envioMap.put("productosAsignados", productosAsignados);
            envioMap.put("totalPartes", envio.getParteAsignadas().size());

            // Partes asignadas (simplificado - SIN vuelos para evitar OOM)
            List<Map<String, Object>> partesMap = new ArrayList<>();
            for (ParteAsignada parte : partesNoEntregadas) {
                Map<String, Object> parteMap = new HashMap<>();
                parteMap.put("id", parte.getId());
                parteMap.put("cantidad", parte.getCantidad());
                parteMap.put("entregado", parte.getEntregado());
                parteMap.put("llegadaFinal", parte.getLlegadaFinal());

                // Aeropuerto origen de la parte
                if (parte.getAeropuertoOrigen() != null) {
                    Map<String, Object> origen = new HashMap<>();
                    origen.put("id", parte.getAeropuertoOrigen().getId());
                    origen.put("codigo", parte.getAeropuertoOrigen().getCodigo());
                    origen.put("ciudad", parte.getAeropuertoOrigen().getCiudad());
                    origen.put("latitud", parte.getAeropuertoOrigen().getLatitud());
                    origen.put("longitud", parte.getAeropuertoOrigen().getLongitud());
                    parteMap.put("aeropuertoOrigen", origen);
                }

                // ⚠️ NO incluir vuelosRuta para evitar OOM
                // El frontend puede obtener los vuelos por separado si los necesita
                parteMap.put("vuelosRuta", new ArrayList<>());
                partesMap.add(parteMap);
            }
            envioMap.put("parteAsignadas", partesMap);

            resultado.add(envioMap);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println(
                "📦 [obtenerPendientes] ✅ Completado en " + elapsed + "ms, " + resultado.size() + " envíos pendientes");

        return resultado;
    }

    @PostMapping("lecturaArchivo")
    public Map<String, Object> cargarEnvios(@RequestParam("arch") MultipartFile arch) throws IOException {
        long startTime = System.currentTimeMillis();
        ArrayList<Envio> envios = new ArrayList<>();
        Map<String, Object> resultado = new java.util.HashMap<>();

        // ⚡ OPTIMIZACIÓN: Cargar todos los aeropuertos UNA SOLA VEZ y crear un mapa
        System.out.println("📂 Cargando aeropuertos en caché...");
        ArrayList<Aeropuerto> todosAeropuertos = aeropuertoService.obtenerTodosAeropuertos();
        java.util.Map<String, Aeropuerto> aeropuertosPorCodigo = new java.util.HashMap<>();
        for (Aeropuerto a : todosAeropuertos) {
            aeropuertosPorCodigo.put(a.getCodigo(), a);
        }
        System.out.println("✅ " + todosAeropuertos.size() + " aeropuertos en caché");

        // ⚡ OPTIMIZACIÓN: Obtener los hubs UNA SOLA VEZ
        ArrayList<Aeropuerto> hubs = new ArrayList<>();
        String[] hubCodes = { "SPIM", "EBCI", "UBBB" };
        for (String code : hubCodes) {
            Aeropuerto hub = aeropuertosPorCodigo.get(code);
            if (hub != null) {
                hubs.add(hub);
            }
        }
        System.out.println("✅ " + hubs.size() + " hubs configurados");

        String enviosDatos = new String(arch.getBytes());
        String[] lineas = enviosDatos.split("\n");
        int i = 0;
        int errores = 0;

        for (String linea : lineas) {
            String data[] = linea.split("-");
            if (data.length > 1) {
                // ⚡ OPTIMIZACIÓN: Usar el mapa en lugar de consultar la BD
                Aeropuerto aeropuertoDestino = aeropuertosPorCodigo.get(data[4]);
                if (aeropuertoDestino != null) {
                    try {
                        Long idEnvioPorAeropuerto = Long.valueOf(data[0]);
                        Integer anho = Integer.parseInt(data[1].substring(0, 4));
                        Integer mes = Integer.parseInt(data[1].substring(4, 6));
                        Integer dia = Integer.parseInt(data[1].substring(6, 8));
                        Integer hora = Integer.parseInt(data[2]);
                        Integer minutos = Integer.parseInt(data[3]);
                        Integer numProductos = Integer.parseInt(data[5]);
                        String cliente = data[6];

                        LocalDateTime fechaIngreso = LocalDateTime.of(LocalDate.of(anho, mes, dia),
                                LocalTime.of(hora, minutos, 0));

                        String husoCiudadDestino = aeropuertoDestino.getHusoHorario();

                        Envio newEnvio = new Envio(idEnvioPorAeropuerto, fechaIngreso, husoCiudadDestino,
                                aeropuertoDestino, numProductos, cliente);

                        // ⚡ OPTIMIZACIÓN: Usar los hubs ya cargados
                        if (!hubs.isEmpty()) {
                            newEnvio.setAeropuertosOrigen(new ArrayList<>(hubs));
                        }

                        envios.add(newEnvio);
                    } catch (Exception e) {
                        errores++;
                    }
                }
            }
            i++;
            // ⚡ OPTIMIZACIÓN: Log cada 5000 envíos
            if (i % 5000 == 0) {
                System.out.println("📊 Procesados " + i + " líneas, " + envios.size() + " envíos válidos");
            }
        }

        System.out.println("📊 Envíos procesados: " + envios.size() + " (errores: " + errores + ")");

        if (!envios.isEmpty()) {
            System.out.println("💾 Guardando " + envios.size() + " envíos en BD...");
            envioService.insertarListaEnvios(envios);
            System.out.println("✅ Envíos guardados");
        }

        long endTime = System.currentTimeMillis();
        long durationInMillis = endTime - startTime;
        double durationInSeconds = durationInMillis / 1000.0;
        System.out.println("⏱️ Tiempo de ejecución: " + durationInSeconds + " segundos");

        // ⚡ OPTIMIZACIÓN: Devolver solo un resumen en lugar de todos los envíos
        resultado.put("estado", "éxito");
        resultado.put("mensaje", "Envíos cargados correctamente");
        resultado.put("enviosCargados", envios.size());
        resultado.put("errores", errores);
        resultado.put("tiempoEjecucionSegundos", durationInSeconds);
        return resultado;
    }

    /**
     * Carga envíos desde archivo. Soporta continuar desde donde falló.
     * 
     * @param skip Número de líneas a saltar (para continuar carga interrumpida).
     *             Usar el valor de "enviosCargados" del intento anterior.
     *             Ejemplo: /api/envios/leerArchivoBack?skip=2485000
     */
    @PostMapping("leerArchivoBack")
    public Map<String, Object> leerArchivoBack(@RequestParam(defaultValue = "0") int skip) {
        long startTime = System.currentTimeMillis();
        Scanner scanner = null;
        InputStream inputStream = null;
        Map<String, Object> resultado = new java.util.HashMap<>();

        // ⚡ OPTIMIZACIÓN: Guardar en lotes para evitar OutOfMemoryError
        final int BATCH_SIZE = 5000;
        ArrayList<Envio> batchEnvios = new ArrayList<>(BATCH_SIZE);
        int totalEnviosGuardados = 0;
        int lineasSaltadas = 0;

        try {
            // ⚡ NOTA: Ya no verificamos envíos existentes aquí para evitar cargar millones
            // en memoria
            // El usuario debe verificar con SELECT COUNT(*) FROM envio en MySQL

            if (skip > 0) {
                System.out.println("⏭️ Continuando carga - saltando primeras " + skip + " líneas...");
            } else {
                System.out.println("📂 Iniciando carga desde el principio...");
            }

            // ⚡ OPTIMIZACIÓN: Cargar todos los aeropuertos UNA SOLA VEZ y crear un mapa
            System.out.println("📂 Cargando aeropuertos en caché...");
            ArrayList<Aeropuerto> todosAeropuertos = aeropuertoService.obtenerTodosAeropuertos();
            java.util.Map<String, Aeropuerto> aeropuertosPorCodigo = new java.util.HashMap<>();
            for (Aeropuerto a : todosAeropuertos) {
                aeropuertosPorCodigo.put(a.getCodigo(), a);
            }
            System.out.println("✅ " + todosAeropuertos.size() + " aeropuertos en caché");

            // ⚡ OPTIMIZACIÓN: Obtener los hubs UNA SOLA VEZ
            ArrayList<Aeropuerto> hubs = new ArrayList<>();
            String[] hubCodes = { "SPIM", "EBCI", "UBBB" };
            for (String code : hubCodes) {
                Aeropuerto hub = aeropuertosPorCodigo.get(code);
                if (hub != null) {
                    hubs.add(hub);
                } else {
                    System.out.println("⚠️ Hub " + code + " no encontrado!");
                }
            }
            System.out.println("✅ " + hubs.size() + " hubs configurados");

            // Intentar leer desde el classpath primero (funciona en JAR y en desarrollo)
            inputStream = getClass().getClassLoader().getResourceAsStream("envios/pedidos-completos.txt");

            if (inputStream != null) {
                System.out.println("📂 Leyendo archivo desde classpath: envios/pedidos-completos.txt");
                scanner = new Scanner(inputStream, "UTF-8");
            } else {
                // Si no se encuentra en el classpath, intentar como archivo del sistema
                File enviosFile = new File("src/main/resources/envios/pedidos-completos.txt");

                if (!enviosFile.exists()) {
                    // También intentar desde la raíz del proyecto
                    enviosFile = new File("envios/pedidos-completos.txt");

                    if (!enviosFile.exists()) {
                        // Intentar con ruta absoluta relativa al directorio de trabajo
                        String workingDir = System.getProperty("user.dir");
                        enviosFile = new File(workingDir + "/src/main/resources/envios/pedidos-completos.txt");
                    }
                }

                if (enviosFile.exists()) {
                    System.out.println("📂 Leyendo archivo desde sistema de archivos: " + enviosFile.getAbsolutePath());
                    scanner = new Scanner(enviosFile, "UTF-8");
                } else {
                    System.err.println("❌ Archivo no encontrado. Buscado en:");
                    System.err.println("  - classpath:envios/pedidos-completos.txt");
                    System.err.println("  - src/main/resources/envios/pedidos-completos.txt");
                    System.err.println("  - envios/pedidos-completos.txt");
                    System.err.println("  - " + System.getProperty("user.dir")
                            + "/src/main/resources/envios/pedidos-completos.txt");
                    resultado.put("estado", "error");
                    resultado.put("mensaje", "Archivo no encontrado");
                    resultado.put("enviosCargados", 0);
                    return resultado;
                }
            }

            // ⏭️ Saltar líneas si es continuación
            while (lineasSaltadas < skip && scanner.hasNextLine()) {
                scanner.nextLine();
                lineasSaltadas++;
                if (lineasSaltadas % 100000 == 0) {
                    System.out.println("⏭️ Saltadas " + lineasSaltadas + "/" + skip + " líneas...");
                }
            }
            if (skip > 0) {
                System.out.println("⏭️ Saltadas " + lineasSaltadas + " líneas. Comenzando carga...");
            }

            // Procesar el archivo
            int lineasProcesadas = 0;
            int errores = 0;
            System.out.println("📂 Procesando envíos del archivo (guardando en lotes de " + BATCH_SIZE + ")...");

            while (scanner.hasNextLine()) {
                String linea = scanner.nextLine().trim();
                if (linea.isEmpty()) {
                    continue;
                }

                String data[] = linea.split("-");
                if (data.length > 1) {
                    // ⚡ OPTIMIZACIÓN: Usar el mapa en lugar de consultar la BD
                    Aeropuerto aeropuertoDestino = aeropuertosPorCodigo.get(data[4]);
                    if (aeropuertoDestino != null) {
                        try {
                            Long idEnvioPorAeropuerto = Long.valueOf(data[0]);
                            Integer anho = Integer.parseInt(data[1].substring(0, 4));
                            Integer mes = Integer.parseInt(data[1].substring(4, 6));
                            Integer dia = Integer.parseInt(data[1].substring(6, 8));
                            Integer hora = Integer.parseInt(data[2]);
                            Integer minutos = Integer.parseInt(data[3]);
                            Integer numProductos = Integer.parseInt(data[5]);
                            String cliente = data[6];

                            LocalDateTime fechaIngreso = LocalDateTime.of(LocalDate.of(anho, mes, dia),
                                    LocalTime.of(hora, minutos, 0));

                            String husoCiudadDestino = aeropuertoDestino.getHusoHorario();

                            Envio newEnvio = new Envio(idEnvioPorAeropuerto, fechaIngreso, husoCiudadDestino,
                                    aeropuertoDestino, numProductos, cliente);

                            // ⚡ OPTIMIZACIÓN: Usar los hubs ya cargados
                            if (!hubs.isEmpty()) {
                                newEnvio.setAeropuertosOrigen(new ArrayList<>(hubs));
                            }

                            batchEnvios.add(newEnvio);

                            // ⚡ GUARDAR EN LOTES para evitar OutOfMemoryError
                            if (batchEnvios.size() >= BATCH_SIZE) {
                                envioService.insertarListaEnvios(batchEnvios);
                                totalEnviosGuardados += batchEnvios.size();
                                batchEnvios.clear(); // Liberar memoria
                                System.out.println("💾 Guardados " + totalEnviosGuardados + " envíos (total con skip: "
                                        + (skip + totalEnviosGuardados) + ")...");
                            }
                        } catch (Exception e) {
                            errores++;
                        }
                    }
                }
                lineasProcesadas++;
                // Log cada 50000 líneas
                if (lineasProcesadas % 50000 == 0) {
                    System.out.println("📊 Procesadas " + lineasProcesadas + " líneas...");
                }
            }

            // Guardar el último lote (lo que quedó)
            if (!batchEnvios.isEmpty()) {
                envioService.insertarListaEnvios(batchEnvios);
                totalEnviosGuardados += batchEnvios.size();
                batchEnvios.clear();
                System.out.println("💾 Guardado último lote. Total: " + totalEnviosGuardados + " envíos");
            }

            System.out.println(
                    "✅ Carga completada: " + totalEnviosGuardados + " envíos nuevos (errores: " + errores + ")");
            System.out.println("✅ Total en BD: " + (skip + totalEnviosGuardados) + " envíos");

        } catch (FileNotFoundException e) {
            System.err.println("❌ Archivo de pedidos no encontrado: " + e.getMessage());
            e.printStackTrace();
            resultado.put("estado", "error");
            resultado.put("mensaje", "Archivo no encontrado: " + e.getMessage());
            resultado.put("enviosCargados", totalEnviosGuardados);
            resultado.put("totalConSkip", skip + totalEnviosGuardados);
            return resultado;
        } catch (Exception e) {
            System.err.println("❌ Error al cargar envíos desde archivo: " + e.getMessage());
            e.printStackTrace();
            resultado.put("estado", "error");
            resultado.put("mensaje", "Error: " + e.getMessage());
            resultado.put("enviosCargados", totalEnviosGuardados);
            resultado.put("totalConSkip", skip + totalEnviosGuardados);
            resultado.put("continuarCon", "skip=" + (skip + totalEnviosGuardados));
            return resultado;
        } finally {
            // Cerrar recursos
            if (scanner != null) {
                scanner.close();
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    System.err.println("Error al cerrar inputStream: " + e.getMessage());
                }
            }
        }

        long endTime = System.currentTimeMillis();
        long durationInMillis = endTime - startTime;
        double durationInSeconds = durationInMillis / 1000.0;
        System.out.println("⏱️ Tiempo de ejecución: " + durationInSeconds + " segundos");

        // ⚡ OPTIMIZACIÓN: Devolver solo un resumen en lugar de todos los envíos
        resultado.put("estado", "éxito");
        resultado.put("mensaje", "Envíos cargados correctamente");
        resultado.put("enviosCargadosNuevos", totalEnviosGuardados);
        resultado.put("lineasSaltadas", skip);
        resultado.put("totalEnvios", skip + totalEnviosGuardados);
        resultado.put("tiempoEjecucionSegundos", durationInSeconds);
        return resultado;
    }

}

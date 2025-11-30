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
     * ⚡ ENDPOINT OPTIMIZADO: Retorna solo envíos pendientes con partes asignadas
     * NO entregadas, con datos mínimos para el frontend.
     * Esto evita cargar 43,000+ envíos y serializar 28MB de JSON.
     */
    @GetMapping("obtenerPendientes")
    public List<Map<String, Object>> obtenerEnviosPendientes() {
        long startTime = System.currentTimeMillis();
        System.out.println("📦 [obtenerPendientes] Iniciando consulta optimizada...");

        // Obtener solo envíos que tienen partes asignadas con JOIN FETCH
        List<Envio> enviosConPartes = envioService.obtenerEnviosConPartesAsignadas();
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

            // Partes asignadas con vuelos (simplificado)
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

                // Vuelos de la ruta (simplificado)
                List<Map<String, Object>> vuelosMap = new ArrayList<>();
                if (parte.getVuelosRuta() != null) {
                    // Ordenar por orden
                    List<ParteAsignadaPlanDeVuelo> vuelosOrdenados = new ArrayList<>(parte.getVuelosRuta());
                    vuelosOrdenados.sort((a, b) -> {
                        int ordenA = a.getOrden() != null ? a.getOrden() : 0;
                        int ordenB = b.getOrden() != null ? b.getOrden() : 0;
                        return ordenA - ordenB;
                    });

                    for (ParteAsignadaPlanDeVuelo papv : vuelosOrdenados) {
                        PlanDeVuelo vuelo = papv.getPlanDeVuelo();
                        if (vuelo != null) {
                            Map<String, Object> vueloMap = new HashMap<>();
                            vueloMap.put("id", vuelo.getId());
                            vueloMap.put("orden", papv.getOrden());
                            vueloMap.put("ciudadOrigen", vuelo.getCiudadOrigen());
                            vueloMap.put("ciudadDestino", vuelo.getCiudadDestino());
                            vueloMap.put("horaSalida", vuelo.getHoraOrigen());
                            vueloMap.put("horaLlegada", vuelo.getHoraDestino());
                            vuelosMap.add(vueloMap);
                        }
                    }
                }
                parteMap.put("vuelosRuta", vuelosMap);
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
            // Verificar cuántos envíos ya existen en BD
            long enviosExistentes = envioService.obtenerEnvios().size();
            System.out.println("📊 Envíos existentes en BD: " + enviosExistentes);

            if (skip > 0) {
                System.out.println("⏭️ Continuando carga - saltando primeras " + skip + " líneas...");
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

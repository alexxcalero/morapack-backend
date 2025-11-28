package pe.edu.pucp.morapack.controllers;

import lombok.RequiredArgsConstructor;
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

    @PostMapping("lecturaArchivo")
    public ArrayList<Envio> cargarEnvios(@RequestParam("arch") MultipartFile arch) throws IOException {
        long startTime = System.currentTimeMillis();
        ArrayList<Envio> envios = new ArrayList<>();

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
        return envios;
    }

    @PostMapping("leerArchivoBack")
    public ArrayList<Envio> leerArchivoBack() {
        long startTime = System.currentTimeMillis();
        ArrayList<Envio> envios = new ArrayList<>();
        Scanner scanner = null;
        InputStream inputStream = null;

        try {
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
            inputStream = getClass().getClassLoader().getResourceAsStream("envios/pedidos-diciembre22-31.txt");

            if (inputStream != null) {
                System.out.println("📂 Leyendo archivo desde classpath: envios/pedidos-diciembre22-31.txt");
                scanner = new Scanner(inputStream, "UTF-8");
            } else {
                // Si no se encuentra en el classpath, intentar como archivo del sistema
                File enviosFile = new File("src/main/resources/envios/pedidos-diciembre22-31.txt");

                if (!enviosFile.exists()) {
                    // También intentar desde la raíz del proyecto
                    enviosFile = new File("envios/pedidos-diciembre22-31.txt");

                    if (!enviosFile.exists()) {
                        // Intentar con ruta absoluta relativa al directorio de trabajo
                        String workingDir = System.getProperty("user.dir");
                        enviosFile = new File(workingDir + "/src/main/resources/envios/pedidos-diciembre22-31.txt");
                    }
                }

                if (enviosFile.exists()) {
                    System.out.println("📂 Leyendo archivo desde sistema de archivos: " + enviosFile.getAbsolutePath());
                    scanner = new Scanner(enviosFile, "UTF-8");
                } else {
                    System.err.println("❌ Archivo no encontrado. Buscado en:");
                    System.err.println("  - classpath:envios/pedidos-diciembre22-31.txt");
                    System.err.println("  - src/main/resources/envios/pedidos-diciembre22-31.txt");
                    System.err.println("  - envios/pedidos-diciembre22-31.txt");
                    System.err.println("  - " + System.getProperty("user.dir")
                            + "/src/main/resources/envios/pedidos-diciembre22-31.txt");
                    return envios;
                }
            }

            // Procesar el archivo
            int i = 0;
            int errores = 0;
            System.out.println("📂 Procesando envíos del archivo...");

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

                            envios.add(newEnvio);
                        } catch (Exception e) {
                            errores++;
                        }
                    }
                }
                i++;
                // ⚡ OPTIMIZACIÓN: Log cada 5000 envíos en lugar de cada uno
                if (i % 5000 == 0) {
                    System.out.println("📊 Procesados " + i + " líneas, " + envios.size() + " envíos válidos");
                }
            }

            System.out.println("📊 Envíos procesados del archivo: " + envios.size() + " (errores: " + errores + ")");

            // Guardar todos los envíos en la base de datos
            if (!envios.isEmpty()) {
                System.out.println("💾 Guardando " + envios.size() + " envíos en BD...");
                envioService.insertarListaEnvios(envios);
                System.out.println("✅ Se cargaron " + envios.size() + " envíos desde el archivo");
            } else {
                System.err.println(
                        "⚠️  El archivo se leyó pero no se generaron envíos. Verifique el formato del archivo.");
            }

        } catch (FileNotFoundException e) {
            System.err.println("❌ Archivo de pedidos no encontrado: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("❌ Error al cargar envíos desde archivo: " + e.getMessage());
            e.printStackTrace();
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
        return envios;
    }

}

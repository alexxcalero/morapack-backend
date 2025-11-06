package pe.edu.pucp.morapack.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import pe.edu.pucp.morapack.models.*;
import pe.edu.pucp.morapack.services.AeropuertoService;
import pe.edu.pucp.morapack.services.EnvioService;
import pe.edu.pucp.morapack.services.PaisService;
import pe.edu.pucp.morapack.services.servicesImp.AeropuertoServiceImp;
import pe.edu.pucp.morapack.services.servicesImp.EnvioServiceImp;
import pe.edu.pucp.morapack.services.servicesImp.PaisServiceImp;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
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
    private Map<String, Aeropuerto> aeropuertoCache = new HashMap<>();

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

    @PostMapping("lecturaArchivo")
    public ArrayList<Envio> cargarEnvios(@RequestParam("arch") MultipartFile arch) throws IOException {
        long startTime = System.currentTimeMillis();
        ArrayList<Envio> envios = new ArrayList<>();
        String enviosDatos = new String(arch.getBytes());
        String[] lineas = enviosDatos.split("\n");
        Integer i = 0;

        for (String linea : lineas) {
            String data[] = linea.trim().split("-");

            // Validar que tenga el formato correcto: 7 campos
            if (data.length == 7) {
                String idPedido = data[0];
                String fechaStr = data[1]; // aaaammdd
                String horaStr = data[2]; // hh
                String minutoStr = data[3]; // mm
                String codigoDestino = data[4]; // dest
                String cantidadStr = data[5]; // ###
                String idCliente = data[6];

                Optional<Aeropuerto> aeropuertoOptionalDest = aeropuertoService
                        .obtenerAeropuertoPorCodigo(codigoDestino);

                if (aeropuertoOptionalDest.isPresent()) {
                    // Parsear fecha
                    Integer anho = Integer.parseInt(fechaStr.substring(0, 4));
                    Integer mes = Integer.parseInt(fechaStr.substring(4, 6));
                    Integer dia = Integer.parseInt(fechaStr.substring(6, 8));
                    Integer hora = Integer.parseInt(horaStr);
                    Integer minutos = Integer.parseInt(minutoStr);
                    Integer numProductos = Integer.parseInt(cantidadStr);

                    LocalDateTime fechaIngreso = LocalDateTime.of(
                            LocalDate.of(anho, mes, dia),
                            LocalTime.of(hora, minutos, 0));

                    String husoCiudadDestino = aeropuertoOptionalDest.get().getHusoHorario();

                    Envio newEnvio = new Envio(fechaIngreso, husoCiudadDestino,
                            aeropuertoOptionalDest.get(), numProductos, idCliente);
                    newEnvio.setIdPedidoExterno(idPedido);

                    // Configurar hubs
                    ArrayList<Aeropuerto> hubs = new ArrayList<>();
                    String[] hubCodes = { "SPIM", "EBCI", "UBBB" };

                    for (String code : hubCodes) {
                        Optional<Aeropuerto> hub = aeropuertoService.obtenerAeropuertoPorCodigo(code);
                        if (hub.isPresent()) {
                            hubs.add(hub.get());
                        }
                    }

                    if (!hubs.isEmpty()) {
                        newEnvio.setAeropuertosOrigen(hubs);
                    }

                    envios.add(newEnvio);
                } else {
                    System.out.println(
                            "ADVERTENCIA: Aeropuerto destino no encontrado: " + codigoDestino + " en línea " + i);
                }
            } else {
                System.out.println("ADVERTENCIA: Formato incorrecto en línea " + i + ": " + linea);
            }

            i++;
        }

        System.out.println("Total envíos cargados: " + envios.size() + " de " + i + " líneas");

        envioService.insertarListaEnvios(envios);

        long endTime = System.currentTimeMillis();
        long durationInMillis = endTime - startTime;
        double durationInSeconds = durationInMillis / 1000.0;
        System.out.println("Tiempo de ejecución: " + durationInSeconds + " segundos");

        return envios;
    }

    @PostMapping("LecturaArchivoBack")
    public ArrayList<Envio> cargarEnviosBack() {
        long startTime = System.currentTimeMillis();
        ArrayList<Envio> envios = new ArrayList<>();

        // Vaciar tabla de envíos antes de cargar nuevos
        envioService.eliminarTodosEnvios();

        try {
            // Directorio donde están los archivos de pedidos
            File enviosDir = new File("src/main/resources/envios/");

            // Filtrar solo archivos que empiezan con "_pedidos_" y terminan con ".txt"
            File[] archivos = enviosDir.listFiles((dir, name) -> name.startsWith("_pedidos_") && name.endsWith(".txt"));

            if (archivos == null || archivos.length == 0) {
                System.out.println(
                        "ADVERTENCIA: No se encontraron archivos de pedidos en " + enviosDir.getAbsolutePath());
                return envios;
            }

            System.out.println("📂 Archivos de pedidos encontrados: " + archivos.length);

            // Procesar cada archivo
            for (File archivo : archivos) {
                System.out.println("📄 Procesando archivo: " + archivo.getName());
                int enviosArchivo = procesarArchivoPedidos(archivo, envios);
                System.out.println("   ✅ " + enviosArchivo + " pedidos cargados de " + archivo.getName());
            }

            System.out.println("📦 Total envíos cargados: " + envios.size() + " de " + archivos.length + " archivos");

        } catch (Exception e) {
            System.out.println("❌ Error al cargar archivos de pedidos: " + e.getMessage());
            e.printStackTrace();
        }

        envioService.insertarListaEnvios(envios);

        long endTime = System.currentTimeMillis();
        long durationInMillis = endTime - startTime;
        double durationInSeconds = durationInMillis / 1000.0;
        System.out.println("⏱️ Tiempo de ejecución: " + durationInSeconds + " segundos");

        return envios;
    }

    /**
     * Procesa un archivo de pedidos individual
     * 
     * @param archivo Archivo a procesar
     * @param envios  Lista donde agregar los envíos procesados
     * @return Cantidad de envíos procesados de este archivo
     */
    private int procesarArchivoPedidos(File archivo, ArrayList<Envio> envios) {
        int contador = 0;

        try (Scanner scanner = new Scanner(archivo)) {
            while (scanner.hasNextLine()) {
                String linea = scanner.nextLine().trim();

                // Saltar líneas vacías
                if (linea.isEmpty()) {
                    continue;
                }

                String data[] = linea.split("-");

                // Validar que tenga el formato correcto: 7 campos
                if (data.length == 7) {
                    String idPedido = data[0];
                    String fechaStr = data[1]; // aaaammdd
                    String horaStr = data[2]; // hh
                    String minutoStr = data[3]; // mm
                    String codigoDestino = data[4]; // dest
                    String cantidadStr = data[5]; // ###
                    String idCliente = data[6];

                    Optional<Aeropuerto> aeropuertoOptionalDest = aeropuertoService
                            .obtenerAeropuertoPorCodigo(codigoDestino);

                    if (aeropuertoOptionalDest.isPresent()) {
                        try {
                            // Parsear fecha
                            Integer anho = Integer.parseInt(fechaStr.substring(0, 4));
                            Integer mes = Integer.parseInt(fechaStr.substring(4, 6));
                            Integer dia = Integer.parseInt(fechaStr.substring(6, 8));
                            Integer hora = Integer.parseInt(horaStr);
                            Integer minutos = Integer.parseInt(minutoStr);
                            Integer numProductos = Integer.parseInt(cantidadStr);

                            LocalDateTime fechaIngreso = LocalDateTime.of(
                                    LocalDate.of(anho, mes, dia),
                                    LocalTime.of(hora, minutos, 0));

                            String husoCiudadDestino = aeropuertoOptionalDest.get().getHusoHorario();

                            Envio newEnvio = new Envio(fechaIngreso, husoCiudadDestino,
                                    aeropuertoOptionalDest.get(), numProductos, idCliente);
                            newEnvio.setIdPedidoExterno(idPedido);

                            // Configurar hubs
                            ArrayList<Aeropuerto> hubs = new ArrayList<>();
                            String[] hubCodes = { "SPIM", "EBCI", "UBBB" };

                            for (String code : hubCodes) {
                                Optional<Aeropuerto> hub = aeropuertoService.obtenerAeropuertoPorCodigo(code);
                                if (hub.isPresent()) {
                                    hubs.add(hub.get());
                                }
                            }

                            if (!hubs.isEmpty()) {
                                newEnvio.setAeropuertosOrigen(hubs);
                            }

                            envios.add(newEnvio);
                            contador++;

                        } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
                            System.out.println("⚠️ Error de formato en línea: " + linea +
                                    " del archivo " + archivo.getName());
                        }
                    } else {
                        System.out.println("⚠️ Aeropuerto destino no encontrado: " + codigoDestino +
                                " en archivo " + archivo.getName());
                    }
                } else {
                    System.out.println("⚠️ Formato incorrecto en línea: " + linea +
                            " del archivo " + archivo.getName());
                }
            }
        } catch (FileNotFoundException e) {
            System.out.println("❌ Archivo no encontrado: " + archivo.getName());
        }

        return contador;
    }

}

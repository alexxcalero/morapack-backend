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

    @PostMapping("lecturaArchivo")
    public ArrayList<Envio> cargarEnvios(@RequestParam("arch") MultipartFile arch) throws IOException {
        long startTime = System.currentTimeMillis();
        ArrayList<Envio> envios = new ArrayList<>();
        String enviosDatos = new String(arch.getBytes());
        String[] lineas = enviosDatos.split("\n");
        Integer i = 0;
        for(String linea : lineas) {
            String data[] = linea.split("-");
            if(data.length > 1) {
                Optional<Aeropuerto> aeropuertoOptionalDest = aeropuertoService.obtenerAeropuertoPorCodigo(data[4]);
                if(aeropuertoOptionalDest.isPresent()) {
                    Long idEnvioPorAeropuerto = Long.valueOf(data[0]);
                    Integer anho = Integer.parseInt(data[1].substring(0,4));
                    Integer mes = Integer.parseInt(data[1].substring(4,6));
                    Integer dia = Integer.parseInt(data[1].substring(6,8));
                    Integer hora = Integer.parseInt(data[2]);
                    Integer minutos = Integer.parseInt(data[3]);
                    Integer numProductos = Integer.parseInt(data[5]);
                    String cliente = data[6];

                    LocalDateTime fechaIngreso = LocalDateTime.of(LocalDate.of(anho, mes, dia),
                            LocalTime.of(hora, minutos, 0));

                    String husoCiudadDestino = aeropuertoOptionalDest.get().getHusoHorario();

                    Envio newEnvio = new Envio(idEnvioPorAeropuerto, fechaIngreso, husoCiudadDestino, aeropuertoOptionalDest.get(), numProductos, cliente);

                    ArrayList<Aeropuerto> hubs = new ArrayList<>();
                    String[] hubCodes = { "SPIM", "EBCI", "UBBB" };

                    for (String code : hubCodes) {
                        Optional<Aeropuerto> hub = aeropuertoService.obtenerAeropuertoPorCodigo(code);
                        if (hub.isPresent()) {
                            hubs.add(hub.get());
                            System.out.println("DEBUG: Agregando hub " + code + " para envío " + i);
                        } else {
                            System.out.println("ERROR: Hub " + code + " no encontrado!");
                        }
                    }

                    if (hubs.isEmpty()) {
                        System.out.println("ERROR: No se encontraron hubs!");
                    } else {
                        System.out.println("DEBUG: Seteando " + hubs.size() + " hubs como origen para envío " + i);
                        newEnvio.setAeropuertosOrigen(hubs);
                    }

                    // Verificar después de setear
                    if (newEnvio.getAeropuertosOrigen() == null || newEnvio.getAeropuertosOrigen().isEmpty()) {
                        System.out.println("ERROR: aeropuertosOrigen quedó vacío después de setear!");
                    }
                    envios.add(newEnvio);
                }
            }
            i++;
            System.out.println("Envio #" + i);
        }

        envioService.insertarListaEnvios(envios);

        long endTime = System.currentTimeMillis();
        long durationInMillis = endTime - startTime;
        double durationInSeconds = durationInMillis / 1000.0;
        System.out.println("Tiempo de ejecucion: " + durationInSeconds + " segundos");
        return envios;
    }

    /*
    @PostMapping("LecturaArchivoBack")
    public ArrayList<Envio> cargarEnviosBack() {
        long startTime = System.currentTimeMillis();
        ArrayList<Envio> envios = new ArrayList<>();
        try {
            File enviosFile = new File("src/main/resources/envios/envios.txt");
            Scanner scanner = new Scanner(enviosFile);
            Integer i = 0;
            while (scanner.hasNextLine()) {
                String row = scanner.nextLine();
                String data[] = row.split("-");
                if (data.length > 1) {
                    Optional<Aeropuerto> aeropuertoOptionalDest = aeropuertoService.obtenerAeropuertoPorCodigo(data[3]);
                    if (aeropuertoOptionalDest.isPresent()) {
                        Integer anho = 2025;
                        Integer mes = 1;
                        Integer dia = Integer.parseInt(data[0]);
                        Integer hora = Integer.parseInt(data[1]);
                        Integer minutos = Integer.parseInt(data[2]);
                        Integer numProductos = Integer.parseInt(data[4]);
                        String cliente = data[5];

                        LocalDateTime fechaIngreso = LocalDateTime.of(LocalDate.of(anho, mes, dia),
                                LocalTime.of(hora, minutos, 0));

                        String husoCiudadDestino = aeropuertoOptionalDest.get().getHusoHorario();

                        Envio newEnvio = new Envio(fechaIngreso, husoCiudadDestino, aeropuertoOptionalDest.get(), numProductos, cliente);
                        ArrayList<Aeropuerto> hubs = new ArrayList<>();
                        String[] hubCodes = { "SPIM", "EBCI", "UBBB" };

                        for (String code : hubCodes) {
                            Optional<Aeropuerto> hub = aeropuertoService.obtenerAeropuertoPorCodigo(code);
                            if (hub.isPresent()) {
                                hubs.add(hub.get());
                                System.out.println("DEBUG: Agregando hub " + code + " para envío " + i);
                            } else {
                                System.out.println("ERROR: Hub " + code + " no encontrado!");
                            }
                        }

                        if (hubs.isEmpty()) {
                            System.out.println("ERROR: No se encontraron hubs!");
                        } else {
                            System.out.println("DEBUG: Seteando " + hubs.size() + " hubs como origen para envío " + i);
                            newEnvio.setAeropuertosOrigen(hubs);
                        }

                        // Verificar después de setear
                        if (newEnvio.getAeropuertosOrigen() == null || newEnvio.getAeropuertosOrigen().isEmpty()) {
                            System.out.println("ERROR: aeropuertosOrigen quedó vacío después de setear!");
                        }

                        envios.add(newEnvio);
                    }
                }
                System.out.println("Envio #" + i);
                i++;
            }
        } catch (FileNotFoundException e) {
            System.out.println("Archivo de pedidos no encontrado, error: " + e.getMessage());
        }

        envioService.insertarListaEnvios(envios);

        long endTime = System.currentTimeMillis();
        long durationInMillis = endTime - startTime;
        double durationInSeconds = durationInMillis / 1000.0;
        System.out.println("Tiempo de ejecucion: " + durationInSeconds + " segundos");
        return envios;
    }
    */
}

package pe.edu.pucp.morapack.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import pe.edu.pucp.morapack.models.*;
import pe.edu.pucp.morapack.services.*;
import pe.edu.pucp.morapack.services.servicesImp.AeropuertoServiceImp;
import pe.edu.pucp.morapack.services.servicesImp.ContinenteServiceImp;
import pe.edu.pucp.morapack.services.servicesImp.EnvioServiceImp;
import pe.edu.pucp.morapack.services.servicesImp.PaisServiceImp;
import pe.edu.pucp.morapack.services.servicesImp.PlanDeVueloServiceImp;

import java.time.*;
import java.util.*;

@CrossOrigin(origins = "*")
@RestController
@RequiredArgsConstructor
@RequestMapping("api/grasp")
public class GraspController {
    private final AeropuertoServiceImp aeropuertoService;
    private final ContinenteServiceImp continenteService;
    private final PaisServiceImp paisService;
    private final EnvioServiceImp envioService;
    private final PlanDeVueloServiceImp planDeVueloService;

    private Grasp grasp = new Grasp();
    private Boolean esPrimeraSimulacion;
    private ZonedDateTime ultimaFechaConsulta;
    private Integer num_ejec_semanal;
    private ArrayList<Envio> ultimoEnvioSemanal;

    private ZonedDateTime horaInicioDiaria;
    private ZonedDateTime horaSimulacionDiaria;
    private String husoHorarioDiaria;
    private ArrayList<Envio> enviosConRutaDiaria;

    private Solucion solucionDiaria;

    private long idColapso = -1;

    @GetMapping("iniciar")
    public Solucion iniciarGrasp() {
        ArrayList<Aeropuerto> aeropuertos = aeropuertoService.obtenerTodosAeropuertos();
        ArrayList<Continente> continentes = continenteService.obtenerTodosContinentes();
        ArrayList<Pais> paises = paisService.obtenerTodosPaises();
        ArrayList<Envio> envios = envioService.obtenerEnvios();
        ArrayList<PlanDeVuelo> planes = planDeVueloService.obtenerListaPlanesDeVuelo();
        System.out.println("DEBUG iniciarGrasp: aeropuertos=" + aeropuertos.size() +
                " planes=" + planes.size() + " envios=" + envios.size());

        System.out.println("DEBUG muestra planes: primero=" +
                (planes.isEmpty() ? "none" : planes.get(0).getId()) +
                " último=" + (planes.isEmpty() ? "none" : planes.get(planes.size() - 1).getId()));

        System.out.println("DEBUG muestra envío: primero=" +
                (envios.isEmpty() ? "none" : envios.get(0).getId()) +
                " destino=" + (envios.isEmpty() ? "none" : envios.get(0).getAeropuertoDestino().getCodigo()));

        num_ejec_semanal = 0;
        ultimoEnvioSemanal = new ArrayList<>();

        grasp.setAeropuertos(aeropuertos);
        grasp.setContinentes(continentes);
        grasp.setPaises(paises);
        grasp.setEnvios(envios);
        grasp.setPlanesOriginales(planes);
        grasp.setHubsPropio();

        ArrayList<Aeropuerto> hubs = grasp.getHubs();
        if (hubs != null && !hubs.isEmpty()) {
            // eliminar duplicados y preservar orden
            ArrayList<Aeropuerto> uniqHubs = new ArrayList<>(new LinkedHashSet<>(hubs));
            for (Envio e : grasp.getEnvios()) {
                e.setAeropuertosOrigen(new ArrayList<>(uniqHubs));
            }
        }
        // grasp.setEnvios(new ArrayList<>());
        // grasp.setPlanesOriginales(new ArrayList<>());

        grasp.setEnviosPorDia(new HashMap<>());
        grasp.setDias(new ArrayList<>());
        grasp.setVuelosInstanciados(new ArrayList<>());

        this.esPrimeraSimulacion = true;

        grasp.setEnviosPorDiaPropio();

        solucionDiaria = grasp.ejecucionDiaria();

        return solucionDiaria;
    }

    // @PostMapping("ejecucionDiaria/cargarEnvio")
    // public ArrayList<Envio> cargarEnviosDiaria(@RequestBody Map<String, String>
    // datos) {
    // long startTime = System.currentTimeMillis();
    // ArrayList<Pais> paises = paisService.obtenerTodosPaises();
    // ArrayList<Envio> envios = new ArrayList<>();
    // String enviosDatos = datos.get("data");
    // String[] lineas = enviosDatos.split("\n");

    // for(String linea : lineas) {
    // int i= 0;
    // String data[] = linea.split("-");
    // if (data.length > 1) {
    // Optional<Aeropuerto> aeropuertoOptionalOrig =
    // aeropuertoService.obtenerAeropuertoPorCodigo(data[0]);
    // //CDES:QQ
    // String dataCdes[] = data[4].split(":");
    // Optional<Aeropuerto> aeropuertoOptionalDest =
    // aeropuertoService.obtenerAeropuertoPorCodigo(dataCdes[0]);

    // if (aeropuertoOptionalOrig.isPresent() && aeropuertoOptionalDest.isPresent())
    // {
    // Aeropuerto aeropuertoOrigen = aeropuertoOptionalOrig.get();
    // Aeropuerto aeropuertoDest = aeropuertoOptionalDest.get();

    // int ciudadOrigen = aeropuertoOrigen.getId_aeropuerto();
    // long numero_envio_Aeropuerto = Long.parseLong(data[1]); // cambiar a
    // numero_envio_Aeropuerto
    // int ciudadDestino = aeropuertoDest.getId_aeropuerto();
    // int numPaquetes = Integer.parseInt(dataCdes[1]);

    // String husoCiudadOrigen = aeropuertoOrigen.getHuso_horario();
    // String husoCiudadDestino = aeropuertoDest.getHuso_horario();

    // //FORI y HORI
    // int anho = Integer.parseInt(data[2].substring(0,4));
    // int mes = Integer.parseInt(data[2].substring(4,6));
    // int dia = Integer.parseInt(data[2].substring(6,8));

    // String tiempoHM[] = data[3].split(":");
    // int hora = Integer.parseInt(tiempoHM[0]);
    // int minutos = Integer.parseInt(tiempoHM[1]);

    // LocalDateTime tiempoOrigen = LocalDateTime .of(LocalDate.of(anho,mes,dia),
    // LocalTime.of(hora,minutos,0));
    // int numDias = envioService.tipoVuelo(ciudadOrigen, ciudadDestino, paises);
    // LocalDateTime tiempoMax = tiempoOrigen.plusDays(numDias); //ya que en el
    // juego de datos aun no hay del mismo pais xd ni habra :v
    // //
    // Envio newEnvio = new
    // Envio(0,numero_envio_Aeropuerto,tiempoOrigen,ciudadOrigen,
    // ciudadDestino,tiempoMax,numPaquetes,husoCiudadOrigen,husoCiudadDestino);
    // envios.add(newEnvio);
    // }

    // }
    // System.out.println(i);
    // i++;
    // }

    // ArrayList<Paquete> paquetes = new ArrayList<>();

    // for (Envio envio : envios) {
    // ArrayList<Paquete> paquetesEnvio = new ArrayList<>();
    // for (int j = 0; j < envio.getNumPaquetes(); j++) {
    // Paquete paquete = new Paquete(0);
    // paquete.setEnvio(envio);
    // paquetesEnvio.add(paquete);
    // paquetes.add(paquete);
    // }
    // envio.setPaquetes(paquetesEnvio);
    // }

    // grasp.getEnvios().addAll(envios);

    // return envios;
    // }

    // @GetMapping("consultarColapso")
    // public long consultarColapso() {
    // return idColapso;
    // }

    // @GetMapping("ejecutar/{fechaHora}")
    // public ArrayList<Envio> ejecutarGrasp(@PathVariable String fechaHora) {
    // long startTime = System.currentTimeMillis();
    // int anio = Integer.parseInt(fechaHora.substring(0, 4));
    // int mes = Integer.parseInt(fechaHora.substring(4, 6));
    // int dia = Integer.parseInt(fechaHora.substring(6, 8));
    // int hora = Integer.parseInt(fechaHora.substring(9, 11));
    // int minutos = Integer.parseInt(fechaHora.substring(12, 14));
    // String husoHorarioStr = fechaHora.substring(15);

    // ZonedDateTime fechaInicio = ZonedDateTime.of(anio, mes, dia, hora, minutos,
    // 0, 0, ZoneId.of(husoHorarioStr));
    // ZonedDateTime fechaFin = fechaInicio.plusHours(2);
    // LocalDateTime fechaInicioLocal = fechaInicio.toLocalDateTime();
    // LocalDateTime fechaFinLocal = fechaFin.toLocalDateTime();

    // // Busqueda de envios en el rango de 2 horas
    // ArrayList<Envio> enviosEnRango =
    // envioService.obtenerEnviosPorFecha(fechaInicioLocal, husoHorarioStr,
    // fechaFinLocal);

    // // Busqueda de planes en el rango de 12 horas
    // ArrayList<PlanDeVuelo> planesEnRango;

    // if (esPrimeraSimulacion) {
    // planesEnRango =
    // planDeVueloService.obtenerPlanesDeVueloPorFecha(fechaInicioLocal,
    // husoHorarioStr, fechaFin.plusHours(17).toLocalDateTime());
    // grasp.setPlanes(planesEnRango);
    // esPrimeraSimulacion = false;
    // ultimaFechaConsulta = fechaFin.plusHours(15);
    // } else {
    // grasp.getPlanes().removeIf(plan ->
    // plan.getZonedHoraOrigen().isBefore(fechaInicio));
    // planesEnRango =
    // planDeVueloService.obtenerPlanesDeVueloPorFecha(ultimaFechaConsulta.toLocalDateTime(),
    // husoHorarioStr,
    // ultimaFechaConsulta.plusHours(2).toLocalDateTime());
    // grasp.getPlanes().addAll(planesEnRango);
    // ultimaFechaConsulta = ultimaFechaConsulta.plusHours(2);
    // }

    // grasp.getEnvios().addAll(enviosEnRango);
    // System.out.println("Cantidad Envios: " + grasp.getEnvios().size());
    // System.out.println("Cantidad Planes Antes GRASP: " +
    // grasp.getPlanes().size());

    // ArrayList<Envio> solucion = grasp.ejecutarGrasp(grasp.getAeropuertos(),
    // grasp.getEnvios(), grasp.getPlanes());

    // ArrayList<Envio> enviosSinRuta = grasp.buscarSinRuta(solucion);
    // idColapso = grasp.buscarIdColapso(enviosSinRuta, fechaInicio);
    // grasp.setEnvios(enviosSinRuta);

    // long endTime = System.currentTimeMillis();
    // long durationInMillis = endTime - startTime;
    // double durationInSeconds = durationInMillis / 1000.0;

    // System.out.println("Tiempo de ejecución: " + durationInSeconds +
    // " segundos");

    // num_ejec_semanal++;
    // if (num_ejec_semanal >= 1)
    // ultimoEnvioSemanal = solucion;

    // return solucion;
    // }

    // @GetMapping("iniciarDiaria/{fechaHora}")
    // public String iniciarGraspDiaria(@PathVariable String fechaHora) {
    // ArrayList<Aeropuerto> aeropuertos =
    // aeropuertoService.obtenerTodosAeropuertos();
    // ArrayList<Continente> continentes =
    // continenteService.obtenerTodosContinentes();
    // ArrayList<Pais> paises = paisService.obtenerTodosPaises();
    // grasp.setAeropuertos(aeropuertos);
    // grasp.setContinentes(continentes);
    // grasp.setPaises(paises);
    // grasp.setEnvios(new ArrayList<>());
    // grasp.setPlanes(new ArrayList<>());
    // int anio = Integer.parseInt(fechaHora.substring(0, 4));
    // int mes = Integer.parseInt(fechaHora.substring(4, 6));
    // int dia = Integer.parseInt(fechaHora.substring(6, 8));
    // int hora = Integer.parseInt(fechaHora.substring(9, 11));
    // int minutos = Integer.parseInt(fechaHora.substring(12, 14));
    // husoHorarioDiaria = fechaHora.substring(15);
    // horaInicioDiaria = ZonedDateTime.of(anio, mes, dia, hora, minutos, 0, 0,
    // ZoneId.of(husoHorarioDiaria));
    // horaSimulacionDiaria = horaInicioDiaria;
    // enviosConRutaDiaria = new ArrayList<>();

    // // Busqueda de planes en el rango de 17 horas
    // ArrayList<PlanDeVuelo> planesEnRango;
    // LocalDateTime fechaInicioLocal = horaInicioDiaria.toLocalDateTime();
    // planesEnRango =
    // planDeVueloService.obtenerPlanesDeVueloPorFecha(fechaInicioLocal,
    // husoHorarioDiaria, horaInicioDiaria.plusHours(17).toLocalDateTime());
    // grasp.setPlanes(planesEnRango);
    // return "Se inicio las operaciones dia a dia";
    // }

    // @GetMapping("ejecutarDiaria/{fechaHora}")
    // public ArrayList<Envio> ejecutarGraspDiaria(@PathVariable String fechaHora) {
    // long startTime = System.currentTimeMillis();
    // int anio = Integer.parseInt(fechaHora.substring(0, 4));
    // int mes = Integer.parseInt(fechaHora.substring(4, 6));
    // int dia = Integer.parseInt(fechaHora.substring(6, 8));
    // int hora = Integer.parseInt(fechaHora.substring(9, 11));
    // int minutos = Integer.parseInt(fechaHora.substring(12, 14));
    // int segundos = Integer.parseInt(fechaHora.substring(15, 17));

    // ZonedDateTime fechaFin = ZonedDateTime.of(anio, mes, dia, hora, minutos,
    // segundos, 0, ZoneId.of(husoHorarioDiaria));
    // ZonedDateTime fechaInicio = fechaFin.minusSeconds(60);
    // LocalDateTime fechaInicioLocal = fechaInicio.toLocalDateTime();
    // LocalDateTime fechaFinLocal = fechaFin.toLocalDateTime();

    // // Busqueda de envios en el rango de 60 segundos
    // ArrayList<Envio> enviosEnRango =
    // envioService.obtenerEnviosPorFecha(fechaInicioLocal, husoHorarioDiaria,
    // fechaFinLocal);

    // grasp.getPlanes().removeIf(plan ->
    // plan.getZonedHoraOrigen().isBefore(fechaInicio));

    // HashSet<Integer> enviosConRutaIds = new HashSet<>();
    // for (Envio envio : enviosConRutaDiaria)
    // enviosConRutaIds.add(envio.getId());

    // HashSet<Integer> graspEnviosIds = new HashSet<>();
    // for (Envio envio : grasp.getEnvios())
    // graspEnviosIds.add(envio.getId());

    // for (Envio envio : enviosEnRango)
    // if (!enviosConRutaIds.contains(envio.getId()) &&
    // !graspEnviosIds.contains(envio.getId()))
    // grasp.getEnvios().add(envio);

    // System.out.println("Cantidad Envios: " + grasp.getEnvios().size());
    // System.out.println("Cantidad Planes Antes GRASP: " +
    // grasp.getPlanes().size());

    // ArrayList<Envio> solucion = grasp.ejecutarGrasp(grasp.getAeropuertos(),
    // grasp.getEnvios(), grasp.getPlanes());
    // ArrayList<Envio> enviosSinRutaSolucion = new ArrayList<>();

    // for (Envio envio : solucion) {
    // boolean tieneRuta = true;
    // for (Producto producto : envio.getProductos()) {
    // if (producto.getRuta().getListaRutas().isEmpty()) {
    // tieneRuta = false;
    // break;
    // }
    // }
    // if (tieneRuta)
    // enviosConRutaDiaria.add(envio);
    // else
    // enviosSinRutaSolucion.add(envio);
    // }

    // grasp.setEnvios(enviosSinRutaSolucion);

    // long endTime = System.currentTimeMillis();
    // long durationInMillis = endTime - startTime;
    // double durationInSeconds = durationInMillis / 1000.0;
    // System.out.println("Tiempo de ejecucion: " + durationInSeconds +
    // " segundos");

    // horaSimulacionDiaria = fechaFin;

    // return solucion;
    // }

}

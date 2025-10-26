package pe.edu.pucp.morapack.models;

import lombok.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Getter
@Setter
@Builder
@Component
@NoArgsConstructor
@AllArgsConstructor
public class Grasp {

    private static final int MAX_ITERACIONES = 300; // bajar a 100-300 para pruebas, normal 1500
    private static final int MAX_SIN_MEJORA = 50;
    private static final int DIAS_A_INSTANCIAR = 5;

    private ArrayList<Aeropuerto> aeropuertos;
    private ArrayList<Pais> paises;
    private ArrayList<Continente> continentes;
    private ArrayList<Aeropuerto> hubs = new ArrayList<>();
    private ArrayList<Envio> envios;
    private Map<LocalDateTime, List<Envio>> enviosPorDia = new HashMap<>();
    private List<LocalDateTime> dias = new ArrayList<>();
    private ArrayList<PlanDeVuelo> planesOriginales;
    private ArrayList<VueloInstanciado> vuelosInstanciados = new ArrayList<>();

    // private ArrayList<RutasDiarias> rutasDiarias = new ArrayList<>();
    // private ArrayList<Envio> solucionSimulacion = new ArrayList<>();
    // Definir fabricas principales
    public void setHubsPropio() {
        for (Aeropuerto aeropuerto : this.aeropuertos) {
            if (aeropuerto.getCodigo().equals("SPIM"))
                this.hubs.add(aeropuerto);

            if (aeropuerto.getCodigo().equals("EBCI"))
                this.hubs.add(aeropuerto);

            if (aeropuerto.getCodigo().equals("UBBB"))
                this.hubs.add(aeropuerto);
        }
    }

    public void setEnviosPorDiaPropio() {
        // Aqui lo que se hace es separar los pedidos de acuerdo al día en que aparecen
        // (fecha en la que se realiza el pedido)
        // Finalmente, lo que se va a obtener es un mapa, donde la llave es cada fecha
        // de aparicion
        // de un envio, y el valor es una lista con los pedidos realizados ese dia
        this.enviosPorDia = new HashMap<>();
        this.enviosPorDia = envios.stream()
                .collect(Collectors.groupingBy(e -> e.getZonedFechaIngreso().toLocalDateTime()));

        // Aqui lo que se realiza es la obtencion de todas las fechas en donde hayan
        // aparecido pedidos, se ordenan ascendentemente
        // de acuerdo a la fecha, y finalmente se utiliza para poder imprimir la
        // cantidad de dias a planificar
        // Tener en cuenta que el archivo de envios deberia ser de un mes solo
        this.dias = new ArrayList<>();
        this.dias = this.enviosPorDia.keySet().stream().sorted().collect(Collectors.toList());
    }

    public Solucion ejecucionDiaria() {
        Solucion solucion = null;

        for (LocalDateTime dia : this.dias) {
            List<Envio> enviosDelDia = enviosPorDia.get(dia);

            Integer offset = Integer.parseInt(getAeropuertoByCodigo("SPIM").getHusoHorario());
            ZoneOffset zone = ZoneOffset.ofHours(offset);
            ZonedDateTime inicio = dia.atZone(ZoneId.of("UTC"));
            ZonedDateTime fin = inicio.plusDays(4);

            this.vuelosInstanciados = instanciarVuelosDiarios(this.planesOriginales, inicio, fin);

            RutasDiarias rutasDiarias = new RutasDiarias(dia, vuelosInstanciados, hubs, aeropuertos);
            solucion = ejecutarGrasp(enviosDelDia, rutasDiarias);

        }

        return solucion;
    }

    private Aeropuerto getAeropuertoByCodigo(String codigo) {
        for (Aeropuerto aeropuerto : this.aeropuertos) {
            if (aeropuerto.getCodigo().equals(codigo))
                return aeropuerto;
        }
        return null;
    }

    private ArrayList<VueloInstanciado> instanciarVuelosDiarios(ArrayList<PlanDeVuelo> planesDeVuelo,
            ZonedDateTime inicio, ZonedDateTime fin) {

        ArrayList<VueloInstanciado> vuelos = new ArrayList<>();

        Long diasTotales = ChronoUnit.DAYS.between(inicio, fin) + 1;
        int diasInstanciar = (int) Math.min(DIAS_A_INSTANCIAR, diasTotales);

        for (PlanDeVuelo plan : planesDeVuelo) {
            if (!esVueloUtil(plan))
                continue;
            for (int i = 0; i < diasInstanciar; i++) {
                // Replico el vuelo, para cada fecha especifica
                LocalDateTime fecha = inicio.toLocalDateTime().plusDays(i);
                LocalTime horaSalida = plan.getHoraOrigen().toLocalTime();
                LocalTime horaLlegada = plan.getHoraDestino().toLocalTime();

                Integer offsetOrigen = Integer.parseInt(plan.getHusoHorarioOrigen());
                Integer offsetDestino = Integer.parseInt(plan.getHusoHorarioDestino());
                ZoneOffset zoneOrigen = ZoneOffset.ofHours(offsetOrigen);
                ZoneOffset zoneDestino = ZoneOffset.ofHours(offsetDestino);

                // Se calcula la hora de salida y llegada
                ZonedDateTime salida = fecha.atZone(zoneOrigen).withZoneSameInstant(zoneOrigen)
                        .withHour(horaSalida.getHour()).withMinute(horaSalida.getMinute());
                ZonedDateTime llegada = fecha.atZone(zoneOrigen).withZoneSameInstant(zoneOrigen)
                        .withHour(horaLlegada.getHour()).withMinute(horaLlegada.getMinute());

                // Si la hora de llegada es antes que la de salida, significa que ha pasado una
                // noche, por lo que se suma un dia
                if (!llegada.isAfter(salida))
                    llegada = llegada.plusDays(1);

                ZonedDateTime llegadaDestino = llegada.withZoneSameInstant(zoneDestino);
                vuelos.add(new VueloInstanciado(plan, salida, llegadaDestino)); // Se agrega el vuelo a la lista de
                                                                                // vuelos instanciados
            }
        }

        // Se ordenan los vuelos por hora de salida
        vuelos.sort(Comparator.comparing(v -> v.getZonedHoraOrigen().toLocalDateTime()));
        return vuelos;
    }

    private boolean esVueloUtil(PlanDeVuelo plan) {
        // Al menos origen o destino debe ser hub
        boolean origenEsHub = hubs.stream()
                .anyMatch(h -> h.getId().equals(plan.getCiudadOrigen()));
        boolean destinoEsHub = hubs.stream()
                .anyMatch(h -> h.getId().equals(plan.getCiudadDestino()));
        return origenEsHub || destinoEsHub;
    }

    private Solucion ejecutarGrasp(List<Envio> enviosDelDia, RutasDiarias rutasDiarias) {

        Solucion mejor = null;
        int iteracionesSinMejora = 0;

        ArrayList<VueloInstanciado> vuelos = rutasDiarias.getVuelos();

        for (int i = 0; i < MAX_ITERACIONES && iteracionesSinMejora < MAX_SIN_MEJORA; i++) {
            // Reset capacidades
            vuelos.forEach(v -> v.setCapacidadOcupada(0)); // Se limpia la capacidad usada de cada vuelo
            enviosDelDia.forEach(e -> e.getParteAsignadas().clear()); // Se elimina cualquier asignacion que tenga un
                                                                      // envio

            faseConstruccion(enviosDelDia, rutasDiarias);
            busquedaLocal(enviosDelDia, rutasDiarias);

            Solucion cur = new Solucion(envios, vuelos);
            if (mejor == null || esMejor(cur, mejor)) {
                mejor = cur;
                iteracionesSinMejora = 0;
            } else {
                iteracionesSinMejora++;
            }
        }

        Objects.requireNonNull(mejor).recomputar();
        return mejor;
    }

    private Boolean esMejor(Solucion a, Solucion b) {
        if (b == null)
            return true;

        // Se verifica si a tiene mas envios completados que b
        if (a.getEnviosCompletados() != b.getEnviosCompletados())
            return a.getEnviosCompletados() > b.getEnviosCompletados();

        // Se verifica si la llegada media ponderada de a es menor que la de b
        // MediaPonderada(a) - MediaPonderada = -Negativo <- Significa que la
        // MediaPonderada(a) es menor a la de b
        return a.getLlegadaMediaPonderada().minus(b.getLlegadaMediaPonderada()).isNegative();
    }

    private void faseConstruccion(List<Envio> enviosDelDia, RutasDiarias rutasDiarias) {

        List<Envio> enviosCopia = new ArrayList<>(enviosDelDia);

        Collections.shuffle(enviosCopia, ThreadLocalRandom.current());

        for (Envio envio : enviosCopia) {
            Integer partesUsadas = 0;

            while (envio.cantidadRestante() > 0 && partesUsadas < 3) {
                List<CandidatoRuta> rutaCandidata = rutasDiarias.getCandidatosRuta(envio);

                if (rutaCandidata.isEmpty()) {
                    break;
                }
                Long mejor = rutaCandidata.get(0).getScore();
                Long peor = rutaCandidata.get(rutaCandidata.size() - 1).getScore();
                Long umbral = (long) (mejor + 0.3 * (peor - mejor + 1));
                List<CandidatoRuta> rcl = rutaCandidata.stream().filter(c -> c.getScore() <= umbral)
                        .collect(Collectors.toList());

                if (rcl.isEmpty())
                    break;

                CandidatoRuta escogido = rcl.get(ThreadLocalRandom.current().nextInt(rcl.size()));

                // Verificación de capacidad REAL (importante: asegurar la no sobreasignacion)
                Integer capacidadReal = Integer.MAX_VALUE;
                for (VueloInstanciado v : escogido.getTramos()) {
                    // Por cada vuelo de la ruta candidata elegida, se va a identificar la minima
                    // capacidad de los vuelos
                    capacidadReal = Math.min(capacidadReal, v.getCapacidadLibre());
                }

                Integer cant = Math.min(envio.cantidadRestante(), capacidadReal);
                if (cant <= 0)
                    break;

                for (VueloInstanciado v : escogido.getTramos())
                    v.asignar(cant); // Se va a asignar esa cantidad de productos a los vuelos de las rutas

                // Crear la parte asignada y vincularla al envio para mantener la relación
                // bidireccional
                ParteAsignada parte = new ParteAsignada(escogido.getTramos(), escogido.getLlegada(), cant,
                        escogido.getOrigen());
                parte.setEnvio(envio);
                envio.getParteAsignadas().add(parte);

                if (envio.getAeropuertoOrigen() == null && parte.getAeropuertoOrigen() != null) {
                    envio.setAeropuertoOrigen(parte.getAeropuertoOrigen());
                }
                partesUsadas++;
            }
        }
    }

    private void busquedaLocal(List<Envio> enviosDelDia, RutasDiarias rutasDiarias) {
        // Filtramos los envios con partes asignadas a algun vuelo
        List<Envio> enviosConPartes = envios.stream().filter(e -> !e.getParteAsignadas().isEmpty())
                .collect(Collectors.toList());

        // Se ordean aleatoriamente a los envios
        Collections.shuffle(enviosConPartes, ThreadLocalRandom.current());

        for (Envio envio : enviosConPartes) {
            // Se copia el arreglo actual de partes asignadas de un pedido
            List<ParteAsignada> snapshot = new ArrayList<>(envio.getParteAsignadas());
            for (ParteAsignada parte : snapshot) {
                // Se elimina los productos solicitados en el pedido en cada vuelo de la ruta
                List<VueloInstanciado> rutaActual = parte.getRuta();
                if (rutaActual != null) {
                    for (VueloInstanciado vuelo : rutaActual)
                        vuelo.desasignar(parte.getCantidad());
                }

                // Se elimina esta parte de la ruta
                envio.getParteAsignadas().remove(parte);

                List<CandidatoRuta> candidato = rutasDiarias.getCandidatosRuta(envio).stream()
                        .filter(c -> {
                            for (VueloInstanciado v : c.getTramos()) // Se verifica que todos los vuelos de la ruta
                                                                     // tengan espacio para la cantidad de esta parte
                                if (v.getCapacidadLibre() < parte.getCantidad())
                                    return false;
                            return true;
                        }) // Se verifica que el nuevo candidato de ruta, llegue antes que la ruta actual
                        .filter(c -> c.getLlegada().toInstant().isBefore(parte.getLlegadaFinal().toInstant()))
                        .collect(Collectors.toList());

                Boolean mejorado = false;
                if (!candidato.isEmpty()) { // Hay rutas candidatas
                    CandidatoRuta c = candidato.get(0); // Se escoje la mejor
                    for (VueloInstanciado v : c.getTramos())
                        v.asignar(parte.getCantidad());

                    // Se asigna la cantidad de productos a cada vuelo de la ruta
                    ParteAsignada nuevaParte = new ParteAsignada(c.getTramos(), c.getLlegada(), parte.getCantidad(),
                            c.getOrigen()); // Se agrega una nueva parte
                    nuevaParte.setEnvio(envio);
                    envio.getParteAsignadas().add(nuevaParte);
                    mejorado = true;
                }

                if (!mejorado) { // Si no mejoro, se restablece la ruta original
                    if (rutaActual != null) {
                        for (VueloInstanciado v : rutaActual)
                            v.asignar(parte.getCantidad());
                    }
                    // Asegurar que la parte restaurada tenga la referencia al envio
                    parte.setEnvio(envio);
                    envio.getParteAsignadas().add(parte);
                }
            }
        }
    }
}

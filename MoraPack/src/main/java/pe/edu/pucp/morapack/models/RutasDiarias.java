package pe.edu.pucp.morapack.models;

import lombok.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RutasDiarias {
    private Map<String, ArrayList<CandidatoRuta>> rutas = new HashMap<>();
    private LocalDateTime dia;
    private ArrayList<VueloInstanciado> vuelos;
    private ArrayList<Aeropuerto> hubs;
    private ArrayList<Aeropuerto> aeropuertos;

    public RutasDiarias(LocalDateTime dia, ArrayList<VueloInstanciado> vuelos, ArrayList<Aeropuerto> hubs, ArrayList<Aeropuerto> aeropuertos) {
        this.dia = dia;
        this.vuelos = vuelos;
        this.hubs = hubs;
        this.rutas = new HashMap<>();
        this.aeropuertos = aeropuertos;
    }

    public ArrayList<CandidatoRuta> getCandidatosRuta(Envio envio) {
        String clave = generarClave(envio);

        if(!rutas.containsKey(clave)) {
            // Generar candidatos para este envio
            ArrayList<CandidatoRuta> candidatos = generarCandidatos(envio, vuelos);
            rutas.put(clave, candidatos);
        }

        return new ArrayList<>(rutas.get(clave));
    }

    private String generarClave(Envio envio) {
        return envio.getAeropuertoDestino().getCodigo() + "_" +
                envio.getAeropuertosOrigen().stream()
                        .map(Aeropuerto::getCodigo)
                        .sorted()
                        .collect(Collectors.joining("-")) + "_" +
                envio.getNumProductos();
    }

    private ArrayList<CandidatoRuta> generarCandidatos(Envio envio, ArrayList<VueloInstanciado> vuelos) {
        ArrayList<CandidatoRuta> candidatos = new ArrayList<>();

        for(Aeropuerto origen : envio.getAeropuertosOrigen()) {
            Duration deadline = envio.deadlineDesde(origen);  // Se ve si es tramo intercontinente o intracontinente
            Instant limite = envio.getZonedFechaIngreso().toInstant().plus(deadline);  // Fecha limite de llegada

            List<PathState> beam = new ArrayList<>();  // Estado inicial

            // Estamos en el aeropuerto de origen, sin vuelos tomados y espacio infinito
            beam.add(new PathState(origen, null, new ArrayList<>(), null, Integer.MAX_VALUE));

            // Agrupar vuelos dependiendo de su origen
            Map<String, List<VueloInstanciado>> vuelosPorOrigen = vuelos.stream().collect(Collectors.groupingBy(v -> getAeropuertoById(v.getVueloBase().getCiudadOrigen()).getCodigo()));

            for(int nivel = 0; nivel < 4; nivel++) {
                List<PathState> nuevosEstados = new ArrayList<>();

                for(PathState ps : beam) {  // Iteramos en cada estado
                    // Para cada estado, se seleccionan los vuelos que salen del aeropuerto en donde se encuentra ese estado
                    List<VueloInstanciado> salidas = vuelosPorOrigen.getOrDefault(ps.getUbicacion().getCodigo(), Collections.emptyList());

                    for(VueloInstanciado v : salidas) {
                        // El vuelo sale antes de que aparezca el pedido
                        if(v.getZonedHoraOrigen().isBefore(envio.getZonedFechaIngreso()))
                            continue;

                        // La hora de llegada del ultimo estado es diferente de null
                        // Y la salida del vuelo es antes que la llegada del ultimo vuelo del estado actual
                        if(ps.getLlegadaUltimoVuelo() != null && v.getZonedHoraDestino().isBefore(ps.getLlegadaUltimoVuelo().plus(Duration.ofHours(2))))
                            continue;

                        // La llegada del vuelo es luego del plazo limite
                        if(v.getZonedHoraDestino().toInstant().isAfter(limite))
                            continue;

                        int capLibre = v.getCapacidadLibre();
                        if(capLibre <= 0) continue;  // Verificar capacidad libre

                        ArrayList<VueloInstanciado> ruta = new ArrayList<>(ps.getTramos());
                        ruta.add(v);  // Se agrega el vuelo a la ruta
                        int capRuta = Math.min(ps.getCapacidadRuta(), capLibre);  // Minima cantidad disponible de algun avion de la ruta

                        PathState nuevo = new PathState(getAeropuertoById(v.getVueloBase().getCiudadDestino()), v.getZonedHoraDestino(), ruta, v, capRuta);

                        // Verificar si llegamos al destino
                        if(getAeropuertoById(v.getVueloBase().getCiudadDestino()).getCodigo().equals(envio.getAeropuertoDestino().getCodigo())) {
                            long score = scoreRuta(ruta, v.getZonedHoraDestino(), envio, origen);  // Se calcula el score de la ruta
                            candidatos.add(new CandidatoRuta(ruta, v.getZonedHoraDestino(), score, capRuta, origen));  // Se agrega la ruta a los candidatos
                        } else {
                            nuevosEstados.add(nuevo);  // Se sigue expandiendo
                        }
                    }
                }

                // Se ordena por scoreRuta
                nuevosEstados.sort(Comparator.comparingLong(ps -> scoreRuta(ps.getTramos(), ps.getLlegadaUltimoVuelo(), envio, origen)));
                if(nuevosEstados.size() > 10)
                    nuevosEstados = nuevosEstados.subList(0, 10);

                beam = nuevosEstados;

                if(beam.isEmpty())
                    break;
            }
        }

        // Se ordena por score
        candidatos.sort(Comparator.comparingLong(CandidatoRuta::getScore));
        return candidatos;
    }

    private Aeropuerto getAeropuertoById(Integer id) {
        for(Aeropuerto a : aeropuertos) {
            if(a.getId().equals(id))
                return a;
        }
        return null;
    }

    private long scoreRuta(List<VueloInstanciado> tramos, ZonedDateTime llegada, Envio e, Aeropuerto origenElegido) {
        // Si la ruta esta vacia o no tiene llegada, se asigna un score muy alto (score malo)
        if(tramos.isEmpty() || llegada == null)
            return Long.MAX_VALUE / 4;

        long escalas = (long) tramos.size() * 10_000L;  // Cada escala suma 10k puntos
        long tiempo = llegada.toInstant().toEpochMilli() / 60_000L;  // Tiempo en minutos

        Duration dl = e.deadlineDesde(origenElegido);
        long plazoHastaLlegada = Duration.between(llegada.toInstant(), e.getZonedFechaIngreso().plus(dl)).toMinutes();

        return escalas + tiempo - plazoHastaLlegada;
    }
}

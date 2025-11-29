package pe.edu.pucp.morapack.models;

import lombok.*;
import org.springframework.stereotype.Component;

import java.time.*;
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

    private static final int MAX_ITERACIONES = 1000;
    private static final int MAX_SIN_MEJORA = 5;
    private static final int DIAS_A_INSTANCIAR = 3;

    private ArrayList<Aeropuerto> aeropuertos;
    private ArrayList<PlanDeVuelo> planesDeVuelo;
    private ArrayList<Pais> paises;
    private ArrayList<Continente> continentes;
    private ArrayList<Aeropuerto> hubs = new ArrayList<>();
    private ArrayList<Envio> envios;
    private Map<LocalDateTime, List<Envio>> enviosPorDia = new HashMap<>();
    private List<LocalDateTime> dias = new ArrayList<>();

    // Campos para manejo de rutas (antes en RutasDiarias)
    private Map<String, ArrayList<CandidatoRuta>> rutas = new HashMap<>();
    private Map<String, List<PlanDeVuelo>> vuelosPorOrigenCache;
    private Map<String, List<PlanDeVuelo>> vuelosPorOrigenYFecha;
    private Map<Integer, Aeropuerto> aeropuertoById;
    private Map<String, Duration> deadlineCache = new HashMap<>();

    // Definir fabricas principales
    public void setHubsPropio() {
        this.hubs = new ArrayList<>();
        if (this.aeropuertos == null)
            return;

        for (Aeropuerto aeropuerto : this.aeropuertos) {
            if ("SPIM".equals(aeropuerto.getCodigo())) {
                this.hubs.add(aeropuerto);
            } else if ("EBCI".equals(aeropuerto.getCodigo())) {
                this.hubs.add(aeropuerto);
            } else if ("UBBB".equals(aeropuerto.getCodigo())) {
                this.hubs.add(aeropuerto);
            }
        }
    }

    public void setEnviosPorDiaPropio() {
        // Aqui lo que se hace es separar los pedidos de acuerdo al día en que aparecen
        // (fecha en la que se realiza el pedido)
        // Finalmente, lo que se va a obtener es un mapa, donde la llave es cada fecha
        // de aparicion
        // de un envio, y el valor es una lista con los pedidos realizados ese dia
        // ✅ IMPORTANTE: Convertir a UTC para agrupar correctamente considerando husos
        // horarios
        this.enviosPorDia = new HashMap<>();
        this.enviosPorDia = envios.stream().collect(Collectors.groupingBy(e -> e.getZonedFechaIngreso()
                .withZoneSameInstant(java.time.ZoneOffset.UTC)
                .toLocalDateTime()));

        // Aqui lo que se realiza es la obtencion de todas las fechas en donde hayan
        // aparecido pedidos, se ordenan ascendentemente
        // de acuerdo a la fecha, y finalmente se utiliza para poder imprimir la
        // cantidad de dias a planificar
        // Tener en cuenta que el archivo de envios deberia ser de un mes solo
        // ✅ Las fechas ya están en UTC, por lo que el ordenamiento es correcto
        this.dias = new ArrayList<>();
        this.dias = this.enviosPorDia.keySet().stream().sorted().collect(Collectors.toList());
    }

    /**
     * Inicializa los caches necesarios para trabajar con vuelos diarios.
     * Este metodo debe ser llamado antes de usar getCandidatosRuta con un conjunto
     * de vuelos.
     * Filtra los vuelos por ventana temporal basada en los envíos para optimizar el
     * procesamiento.
     */
    public void inicializarCachesParaVuelos(ArrayList<PlanDeVuelo> todosLosVuelos, List<Envio> envios) {
        // Limpiar cache de rutas anteriores
        this.rutas.clear();
        this.deadlineCache.clear();

        // Inicializar mapa de aeropuertos por ID
        this.aeropuertoById = new HashMap<>();
        if (this.aeropuertos != null) {
            for (Aeropuerto a : this.aeropuertos) {
                this.aeropuertoById.put(a.getId(), a);
            }
        }

        // Filtrar vuelos por ventana temporal relevante para los envíos
        ArrayList<PlanDeVuelo> vuelosFiltrados = filtrarVuelosPorVentanaTemporal(todosLosVuelos, envios);

        // Precomputar vuelos por código de aeropuerto origen
        if (vuelosFiltrados != null && !vuelosFiltrados.isEmpty()) {
            this.vuelosPorOrigenCache = vuelosFiltrados.stream()
                    .collect(Collectors.groupingBy(v -> {
                        Aeropuerto origen = this.aeropuertoById.get(v.getCiudadOrigen());
                        return origen != null ? origen.getCodigo() : "";
                    }));
            this.vuelosPorOrigenYFecha = vuelosFiltrados.stream()
                    .collect(Collectors.groupingBy(v -> {
                        Aeropuerto origen = this.aeropuertoById.get(v.getCiudadOrigen());
                        String codigoOrigen = origen != null ? origen.getCodigo() : "";
                        return codigoOrigen + "_" + v.getZonedHoraOrigen().toLocalDate();
                    }));
        } else {
            this.vuelosPorOrigenCache = new HashMap<>();
            this.vuelosPorOrigenYFecha = new HashMap<>();
        }
    }

    /**
     * Versión sobrecargada que mantiene compatibilidad con código existente.
     * Usa todos los vuelos sin filtrar (menos eficiente).
     */
    public void inicializarCachesParaVuelos(ArrayList<PlanDeVuelo> vuelos) {
        inicializarCachesParaVuelos(vuelos, this.envios != null ? this.envios : new ArrayList<>());
    }

    /**
     * Filtra los vuelos para incluir solo aquellos que son relevantes para los
     * envíos dados.
     * Un vuelo es relevante si:
     * - Sale después de la fecha de ingreso del pedido más temprano
     * - Llega antes del deadline del pedido más tardío (fecha ingreso + 2-3 días)
     */
    private ArrayList<PlanDeVuelo> filtrarVuelosPorVentanaTemporal(ArrayList<PlanDeVuelo> todosLosVuelos,
            List<Envio> envios) {
        if (envios == null || envios.isEmpty() || todosLosVuelos == null || todosLosVuelos.isEmpty()) {
            return todosLosVuelos != null ? todosLosVuelos : new ArrayList<>();
        }

        // Encontrar la fecha de ingreso más temprana y el deadline más tardío
        ZonedDateTime fechaInicioMinima = null;
        ZonedDateTime fechaFinMaxima = null;

        for (Envio envio : envios) {
            ZonedDateTime fechaIngreso = envio.getZonedFechaIngreso();
            if (fechaInicioMinima == null || fechaIngreso.isBefore(fechaInicioMinima)) {
                fechaInicioMinima = fechaIngreso;
            }

            // Calcular deadline para cada origen posible del envío
            for (Aeropuerto origen : envio.getAeropuertosOrigen()) {
                Duration deadline = envio.deadlineDesde(origen);
                ZonedDateTime fechaLimite = fechaIngreso.plus(deadline);
                if (fechaFinMaxima == null || fechaLimite.isAfter(fechaFinMaxima)) {
                    fechaFinMaxima = fechaLimite;
                }
            }
        }

        // Si no hay envíos válidos, retornar todos los vuelos
        if (fechaInicioMinima == null || fechaFinMaxima == null) {
            return todosLosVuelos;
        }

        // Filtrar vuelos que están en la ventana temporal relevante
        // Un vuelo es relevante si sale después de fechaInicioMinima y llega antes de
        // fechaFinMaxima
        final ZonedDateTime inicio = fechaInicioMinima;
        final ZonedDateTime fin = fechaFinMaxima;

        return todosLosVuelos.stream()
                .filter(v -> {
                    ZonedDateTime salida = v.getZonedHoraOrigen();
                    ZonedDateTime llegada = v.getZonedHoraDestino();

                    // El vuelo debe salir después del inicio de la ventana (o al menos no mucho
                    // antes)
                    // y llegar antes del fin de la ventana
                    return salida.isAfter(inicio.minusHours(12)) && llegada.isBefore(fin.plusHours(12));
                })
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /*
     * public Solucion ejecucionDiaria() {
     * Solucion solucion = null;
     * 
     * for (LocalDateTime dia : this.dias) {
     * List<Envio> enviosDelDia = enviosPorDia.get(dia);
     * 
     * Integer offset =
     * Integer.parseInt(getAeropuertoByCodigo("SPIM").getHusoHorario());
     * ZoneOffset zone = ZoneOffset.ofHours(offset);
     * ZonedDateTime inicio = dia.atZone(ZoneId.of("UTC"));
     * ZonedDateTime fin = inicio.plusDays(4);
     * 
     * //this.vuelosInstanciados = instanciarVuelosDiarios(this.planesDeVuelo,
     * inicio, fin);
     * 
     * RutasDiarias rutasDiarias = new RutasDiarias(dia, vuelosInstanciados, hubs,
     * aeropuertos);
     * solucion = ejecutarGrasp(enviosDelDia, rutasDiarias);
     * 
     * }
     * 
     * return solucion;
     * }
     */

    public Aeropuerto getAeropuertoByCodigo(String codigo) {
        for (Aeropuerto aeropuerto : this.aeropuertos) {
            if (aeropuerto.getCodigo().equals(codigo))
                return aeropuerto;
        }
        return null;
    }

    /*
     * public ArrayList<VueloInstanciado>
     * instanciarVuelosDiarios(ArrayList<PlanDeVuelo> planesDeVuelo,
     * ZonedDateTime inicio, ZonedDateTime fin) {
     * 
     * ArrayList<VueloInstanciado> vuelos = new ArrayList<>();
     * 
     * Long diasTotales = ChronoUnit.DAYS.between(inicio, fin) + 1;
     * int diasInstanciar = (int) Math.min(DIAS_A_INSTANCIAR, diasTotales);
     * 
     * for(PlanDeVuelo plan : planesDeVuelo) {
     * if(!esVueloUtil(plan))
     * continue;
     * for(int i = 0; i < diasInstanciar; i++) {
     * // Replico el vuelo, para cada fecha especifica
     * LocalDateTime fecha = inicio.toLocalDateTime().plusDays(i);
     * LocalTime horaSalida = plan.getHoraOrigen().toLocalTime();
     * LocalTime horaLlegada = plan.getHoraDestino().toLocalTime();
     * 
     * Integer offsetOrigen = Integer.parseInt(plan.getHusoHorarioOrigen());
     * Integer offsetDestino = Integer.parseInt(plan.getHusoHorarioDestino());
     * ZoneOffset zoneOrigen = ZoneOffset.ofHours(offsetOrigen);
     * ZoneOffset zoneDestino = ZoneOffset.ofHours(offsetDestino);
     * 
     * // Se calcula la hora de salida y llegada
     * ZonedDateTime salida =
     * fecha.atZone(zoneOrigen).withZoneSameInstant(zoneOrigen)
     * .withHour(horaSalida.getHour()).withMinute(horaSalida.getMinute());
     * ZonedDateTime llegada =
     * fecha.atZone(zoneOrigen).withZoneSameInstant(zoneOrigen)
     * .withHour(horaLlegada.getHour()).withMinute(horaLlegada.getMinute());
     * 
     * // Si la hora de llegada es antes que la de salida, significa que ha pasado
     * una
     * // noche, por lo que se suma un dia
     * if (!llegada.isAfter(salida))
     * llegada = llegada.plusDays(1);
     * 
     * ZonedDateTime llegadaDestino = llegada.withZoneSameInstant(zoneDestino);
     * vuelos.add(new VueloInstanciado(plan, salida, llegadaDestino)); // Se agrega
     * el vuelo a la lista de
     * // vuelos instanciados
     * }
     * }
     * 
     * // Se ordenan los vuelos por hora de salida
     * vuelos.sort(Comparator.comparing(v ->
     * v.getZonedHoraOrigen().toLocalDateTime()));
     * return vuelos;
     * }
     */

    private boolean esVueloUtil(PlanDeVuelo plan) {
        // Al menos origen o destino debe ser hub
        boolean origenEsHub = hubs.stream()
                .anyMatch(h -> h.getId().equals(plan.getCiudadOrigen()));
        boolean destinoEsHub = hubs.stream()
                .anyMatch(h -> h.getId().equals(plan.getCiudadDestino()));
        return origenEsHub || destinoEsHub;
    }

    public Solucion ejecutarGrasp(List<Envio> envios, ArrayList<PlanDeVuelo> planesDeVuelo) {
        Solucion mejor = null;
        int iteracionesSinMejora = 0;

        // ArrayList<PlanDeVuelo> vuelos = rutasDiarias.getVuelos();

        Map<PlanDeVuelo, Integer> capacidadBaseVuelos = planesDeVuelo.stream()
                .collect(Collectors.toMap(v -> v, v -> v.getCapacidadOcupada() != null ? v.getCapacidadOcupada() : 0));
        Map<Aeropuerto, Integer> capacidadBaseAeropuertos = this.aeropuertos != null
                ? this.aeropuertos.stream()
                        .collect(Collectors.toMap(a -> a,
                                a -> a.getCapacidadOcupada() != null ? a.getCapacidadOcupada() : 0))
                : Collections.emptyMap();

        for (int i = 0; i < MAX_ITERACIONES && iteracionesSinMejora < MAX_SIN_MEJORA; i++) {
            // Reset capacidades
            planesDeVuelo.forEach(v -> v.setCapacidadOcupada(capacidadBaseVuelos.getOrDefault(v, 0))); // Se reinicia
                                                                                                       // respetando
                                                                                                       // ocupación base
            if (this.aeropuertos != null) {
                this.aeropuertos.forEach(a -> a.setCapacidadOcupada(capacidadBaseAeropuertos.getOrDefault(a, 0))); // Se
                                                                                                                   // reinicia
                                                                                                                   // respetando
                                                                                                                   // ocupación
                                                                                                                   // base
            }
            envios.forEach(e -> e.getParteAsignadas().clear()); // Se elimina cualquier asignacion que tenga un envio

            faseConstruccion(envios, planesDeVuelo);
            busquedaLocal(envios, planesDeVuelo);

            Solucion cur = new Solucion(new ArrayList<>(envios), planesDeVuelo);

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

    public Boolean esMejor(Solucion a, Solucion b) {
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

    private void faseConstruccion(List<Envio> envios, ArrayList<PlanDeVuelo> planesDeVuelo) {

        List<Envio> enviosCopia = new ArrayList<>(envios);

        Collections.shuffle(enviosCopia, ThreadLocalRandom.current());

        for (Envio envio : enviosCopia) {
            Integer partesUsadas = 0;

            while (envio.cantidadRestante() > 0 && partesUsadas < 3) {
                List<CandidatoRuta> rutaCandidata = getCandidatosRuta(envio, planesDeVuelo);

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
                for (PlanDeVuelo v : escogido.getTramos()) {
                    // Por cada vuelo de la ruta candidata elegida, se va a identificar la minima
                    // capacidad de los vuelos
                    capacidadReal = Math.min(capacidadReal, v.getCapacidadLibre());
                }

                // Verificar también capacidad de aeropuertos intermedios y destino
                for (int i = 0; i < escogido.getTramos().size(); i++) {
                    PlanDeVuelo vuelo = escogido.getTramos().get(i);
                    Aeropuerto destinoAeropuerto = getAeropuertoById(vuelo.getCiudadDestino());
                    if (destinoAeropuerto != null) {
                        // Los productos llegan al aeropuerto destino cuando el vuelo llega
                        // Verificar capacidad disponible en el aeropuerto destino
                        int capacidadLibreAeropuerto = destinoAeropuerto.getCapacidadLibre();
                        capacidadReal = Math.min(capacidadReal, capacidadLibreAeropuerto);
                    }
                }

                Integer cant = Math.min(envio.cantidadRestante(), capacidadReal);
                if (cant <= 0)
                    break;

                // Asignar capacidad en vuelos
                for (PlanDeVuelo v : escogido.getTramos())
                    v.asignar(cant); // Se va a asignar esa cantidad de productos a los vuelos de las rutas

                // Asignar/desasignar capacidad en aeropuertos
                // Para cada vuelo en la ruta:
                // - Si NO es el primer vuelo: desasignar capacidad del aeropuerto de origen
                // (los productos salen cuando el vuelo despega)
                // - Siempre: asignar capacidad en el aeropuerto de destino (los productos
                // llegan cuando el vuelo aterriza)
                for (int i = 0; i < escogido.getTramos().size(); i++) {
                    PlanDeVuelo vuelo = escogido.getTramos().get(i);

                    // Si NO es el primer vuelo, desasignar capacidad del aeropuerto de origen
                    // (los productos salen del aeropuerto cuando el vuelo despega)
                    if (i > 0) {
                        Aeropuerto origenAeropuerto = getAeropuertoById(vuelo.getCiudadOrigen());
                        if (origenAeropuerto != null) {
                            origenAeropuerto.desasignarCapacidad(cant);
                        }
                    }

                    // Asignar capacidad en el aeropuerto de destino (los productos llegan cuando el
                    // vuelo aterriza)
                    Aeropuerto destinoAeropuerto = getAeropuertoById(vuelo.getCiudadDestino());
                    if (destinoAeropuerto != null) {
                        destinoAeropuerto.asignarCapacidad(cant);
                    }
                }

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

    public ArrayList<CandidatoRuta> getCandidatosRuta(Envio envio, ArrayList<PlanDeVuelo> planesDeVuelo) {
        String clave = generarClave(envio);

        if (!rutas.containsKey(clave)) {
            // Generar candidatos para este envio
            ArrayList<CandidatoRuta> candidatos = generarCandidatos(envio, planesDeVuelo);
            rutas.put(clave, candidatos);
        }

        return new ArrayList<>(rutas.get(clave));
    }

    private String generarClave(Envio envio) {
        String base = envio.getAeropuertoDestino().getCodigo() + "_" +
                envio.getAeropuertosOrigen().stream()
                        .map(Aeropuerto::getCodigo)
                        .sorted()
                        .collect(Collectors.joining("-"))
                + "_" +
                envio.getNumProductos();

        if (envio.getId() != null) {
            return base + "_ID_" + envio.getId();
        }

        ZonedDateTime aparicion = envio.getZonedFechaIngreso();
        long instante = aparicion != null ? aparicion.toInstant().toEpochMilli() : 0L;
        return base + "_TS_" + instante;
    }

    private ArrayList<CandidatoRuta> generarCandidatos(Envio envio, ArrayList<PlanDeVuelo> vuelos) {
        ArrayList<CandidatoRuta> candidatos = new ArrayList<>();

        for (Aeropuerto origen : envio.getAeropuertosOrigen()) {
            Duration deadline = deadlineCache.computeIfAbsent(
                    origen.getCodigo() + "_" + envio.getAeropuertoDestino().getCodigo(),
                    k -> envio.deadlineDesde(origen)); // Se ve si es tramo intercontinente o intracontinente
            Instant limite = envio.getZonedFechaIngreso().toInstant().plus(deadline); // Fecha limite de llegada
            List<PathState> beam = new ArrayList<>(); // Estado inicial

            // Estamos en el aeropuerto de origen, sin vuelos tomados y espacio infinito
            beam.add(new PathState(origen, null, new ArrayList<>(), null, Integer.MAX_VALUE));

            for (int nivel = 0; nivel < 5; nivel++) {
                List<PathState> nuevosEstados = new ArrayList<>();

                for (PathState ps : beam) { // Iteramos en cada estado
                    // Para cada estado, se seleccionan los vuelos que salen del aeropuerto en donde
                    // se encuentra ese estado
                    Aeropuerto aeropuertoActual = ps.getUbicacion();
                    List<PlanDeVuelo> salidas = this.vuelosPorOrigenCache.getOrDefault(aeropuertoActual.getCodigo(),
                            Collections.emptyList());

                    for (PlanDeVuelo v : salidas) {
                        // El vuelo sale antes de que aparezca el pedido
                        if (v.getZonedHoraOrigen().isBefore(envio.getZonedFechaIngreso()))
                            continue;

                        // La hora de llegada del ultimo estado es diferente de null
                        // Y la salida del vuelo es antes que la llegada del ultimo vuelo del estado
                        // actual
                        if (ps.getLlegadaUltimoVuelo() != null && v.getZonedHoraOrigen()
                                .isBefore(ps.getLlegadaUltimoVuelo().plus(Duration.ofMinutes(30))))
                            continue;

                        // La llegada del vuelo es luego del plazo limite
                        if (v.getZonedHoraDestino().toInstant().isAfter(limite))
                            continue;

                        int capLibre = v.getCapacidadLibre();
                        if (capLibre <= 0)
                            continue; // Verificar capacidad libre

                        ArrayList<PlanDeVuelo> ruta = new ArrayList<>(ps.getTramos());
                        ruta.add(v); // Se agrega el vuelo a la ruta
                        int capRuta = Math.min(ps.getCapacidadRuta(), capLibre); // Minima cantidad disponible de algun
                                                                                 // avion de la ruta

                        Aeropuerto destinoAeropuerto = getAeropuertoById(v.getCiudadDestino());
                        if (destinoAeropuerto == null)
                            continue;

                        // Verificar capacidad del aeropuerto destino
                        // El aeropuerto debe tener espacio suficiente para recibir la cantidad de
                        // productos
                        int capacidadLibreAeropuerto = destinoAeropuerto.getCapacidadMaxima()
                                - destinoAeropuerto.getCapacidadOcupada();
                        if (capacidadLibreAeropuerto < envio.getNumProductos()) {
                            // Si el aeropuerto destino no tiene capacidad suficiente para todo el envio
                            // verificamos si al menos puede recibir la capacidad mínima de la ruta
                            if (capacidadLibreAeropuerto < Math.min(ps.getCapacidadRuta(), capLibre)) {
                                continue; // No hay espacio suficiente en el aeropuerto destino
                            }
                            // Ajustar la capacidad de la ruta al espacio disponible en el aeropuerto
                            capRuta = Math.min(capRuta, capacidadLibreAeropuerto);
                        }

                        PathState nuevo = new PathState(destinoAeropuerto, v.getZonedHoraDestino(), ruta, v, capRuta);

                        // Verificar si llegamos al destino
                        if (destinoAeropuerto.getCodigo().equals(envio.getAeropuertoDestino().getCodigo())) {
                            long score = scoreRuta(ruta, v.getZonedHoraDestino(), envio, origen); // Se calcula el score
                                                                                                  // de la ruta
                            candidatos.add(new CandidatoRuta(ruta, v.getZonedHoraDestino(), score, capRuta, origen)); // Se
                                                                                                                      // agrega
                                                                                                                      // la
                                                                                                                      // ruta
                                                                                                                      // a
                                                                                                                      // los
                                                                                                                      // candidatos
                        } else {
                            nuevosEstados.add(nuevo); // Se sigue expandiendo
                        }
                    }
                }

                // Se ordena por scoreRuta
                nuevosEstados.sort(Comparator
                        .comparingLong(ps -> scoreRuta(ps.getTramos(), ps.getLlegadaUltimoVuelo(), envio, origen)));
                if (nuevosEstados.size() > 10)
                    nuevosEstados = nuevosEstados.subList(0, 10);

                beam = nuevosEstados;

                if (beam.isEmpty())
                    break;
            }
        }

        // Se ordena por score
        candidatos.sort(Comparator.comparingLong(CandidatoRuta::getScore));
        return candidatos;
    }

    private Aeropuerto getAeropuertoById(Integer id) {
        return this.aeropuertoById.get(id);
    }

    private long scoreRuta(List<PlanDeVuelo> tramos, ZonedDateTime llegada, Envio e, Aeropuerto origenElegido) {
        // Si la ruta esta vacia o no tiene llegada, se asigna un score muy alto (score
        // malo)
        if (tramos.isEmpty() || llegada == null)
            return Long.MAX_VALUE / 4;

        long escalas = (long) tramos.size() * 10_000L; // Cada escala suma 10k puntos
        long tiempo = llegada.toInstant().toEpochMilli() / 60_000L; // Tiempo en minutos

        Duration dl = e.deadlineDesde(origenElegido);
        long plazoHastaLlegada = Duration.between(llegada.toInstant(), e.getZonedFechaIngreso().plus(dl)).toMinutes();

        return escalas + tiempo - plazoHastaLlegada;
    }

    private void busquedaLocal(List<Envio> envios, ArrayList<PlanDeVuelo> planesDeVuelo) {
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
                List<PlanDeVuelo> rutaActual = parte.getRuta();
                if (rutaActual != null) {
                    // Desasignar capacidad de vuelos
                    for (PlanDeVuelo vuelo : rutaActual)
                        vuelo.desasignar(parte.getCantidad());

                    // Desasignar/restaurar capacidad de aeropuertos
                    // Para cada vuelo en la ruta (en orden inverso para restaurar correctamente):
                    // - Desasignar capacidad del aeropuerto de destino (los productos ya no están
                    // ahí)
                    // - Si NO es el primer vuelo: restaurar capacidad del aeropuerto de origen (los
                    // productos vuelven a estar ahí)
                    for (int i = rutaActual.size() - 1; i >= 0; i--) {
                        PlanDeVuelo vuelo = rutaActual.get(i);

                        // Desasignar capacidad del aeropuerto de destino
                        Aeropuerto destinoAeropuerto = getAeropuertoById(vuelo.getCiudadDestino());
                        if (destinoAeropuerto != null) {
                            destinoAeropuerto.desasignarCapacidad(parte.getCantidad());
                        }

                        // Si NO es el primer vuelo, restaurar capacidad del aeropuerto de origen
                        // (los productos vuelven a estar en ese aeropuerto)
                        if (i > 0) {
                            Aeropuerto origenAeropuerto = getAeropuertoById(vuelo.getCiudadOrigen());
                            if (origenAeropuerto != null) {
                                origenAeropuerto.asignarCapacidad(parte.getCantidad());
                            }
                        }
                    }
                }

                // Se elimina esta parte de la ruta
                envio.getParteAsignadas().remove(parte);

                List<CandidatoRuta> candidato = getCandidatosRuta(envio, planesDeVuelo).stream()
                        .filter(c -> {
                            // Verificar capacidad de vuelos
                            for (PlanDeVuelo v : c.getTramos()) {
                                if (v.getCapacidadLibre() < parte.getCantidad())
                                    return false;
                            }
                            // Verificar capacidad de aeropuertos destino
                            for (PlanDeVuelo v : c.getTramos()) {
                                Aeropuerto destinoAeropuerto = getAeropuertoById(v.getCiudadDestino());
                                if (destinoAeropuerto != null
                                        && destinoAeropuerto.getCapacidadLibre() < parte.getCantidad()) {
                                    return false;
                                }
                            }
                            return true;
                        }) // Se verifica que el nuevo candidato de ruta, llegue antes que la ruta actual
                        .filter(c -> c.getLlegada().toInstant().isBefore(parte.getLlegadaFinal().toInstant()))
                        .collect(Collectors.toList());

                Boolean mejorado = false;
                if (!candidato.isEmpty()) { // Hay rutas candidatas
                    CandidatoRuta c = candidato.get(0); // Se escoje la mejor
                    // Asignar capacidad en vuelos
                    for (PlanDeVuelo v : c.getTramos())
                        v.asignar(parte.getCantidad());

                    // Asignar/desasignar capacidad en aeropuertos
                    // Para cada vuelo en la ruta:
                    // - Si NO es el primer vuelo: desasignar capacidad del aeropuerto de origen
                    // (los productos salen cuando el vuelo despega)
                    // - Siempre: asignar capacidad en el aeropuerto de destino (los productos
                    // llegan cuando el vuelo aterriza)
                    for (int i = 0; i < c.getTramos().size(); i++) {
                        PlanDeVuelo vuelo = c.getTramos().get(i);

                        // Si NO es el primer vuelo, desasignar capacidad del aeropuerto de origen
                        // (los productos salen del aeropuerto cuando el vuelo despega)
                        if (i > 0) {
                            Aeropuerto origenAeropuerto = getAeropuertoById(vuelo.getCiudadOrigen());
                            if (origenAeropuerto != null) {
                                origenAeropuerto.desasignarCapacidad(parte.getCantidad());
                            }
                        }

                        // Asignar capacidad en el aeropuerto de destino (los productos llegan cuando el
                        // vuelo aterriza)
                        Aeropuerto destinoAeropuerto = getAeropuertoById(vuelo.getCiudadDestino());
                        if (destinoAeropuerto != null) {
                            destinoAeropuerto.asignarCapacidad(parte.getCantidad());
                        }
                    }

                    // Se asigna la cantidad de productos a cada vuelo de la ruta
                    ParteAsignada nuevaParte = new ParteAsignada(c.getTramos(), c.getLlegada(), parte.getCantidad(),
                            c.getOrigen()); // Se agrega una nueva parte
                    nuevaParte.setEnvio(envio);
                    envio.getParteAsignadas().add(nuevaParte);
                    mejorado = true;
                }

                if (!mejorado) { // Si no mejoro, se restablece la ruta original
                    if (rutaActual != null) {
                        // Restaurar capacidad en vuelos
                        for (PlanDeVuelo v : rutaActual)
                            v.asignar(parte.getCantidad());

                        // Restaurar capacidad en aeropuertos
                        // Para cada vuelo en la ruta:
                        // - Restaurar capacidad del aeropuerto de destino (los productos vuelven a
                        // estar ahí)
                        // - Si NO es el primer vuelo: desasignar capacidad del aeropuerto de origen
                        // (los productos salen cuando el vuelo despega)
                        for (int i = 0; i < rutaActual.size(); i++) {
                            PlanDeVuelo vuelo = rutaActual.get(i);

                            // Restaurar capacidad del aeropuerto de destino
                            Aeropuerto destinoAeropuerto = getAeropuertoById(vuelo.getCiudadDestino());
                            if (destinoAeropuerto != null) {
                                destinoAeropuerto.asignarCapacidad(parte.getCantidad());
                            }

                            // Si NO es el primer vuelo, desasignar capacidad del aeropuerto de origen
                            // (los productos salen del aeropuerto cuando el vuelo despega)
                            if (i > 0) {
                                Aeropuerto origenAeropuerto = getAeropuertoById(vuelo.getCiudadOrigen());
                                if (origenAeropuerto != null) {
                                    origenAeropuerto.desasignarCapacidad(parte.getCantidad());
                                }
                            }
                        }
                    }
                    // Asegurar que la parte restaurada tenga la referencia al envio
                    parte.setEnvio(envio);
                    envio.getParteAsignadas().add(parte);
                }
            }
        }
    }
}

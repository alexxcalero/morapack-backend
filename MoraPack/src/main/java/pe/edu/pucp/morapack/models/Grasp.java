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

    // ⚡ OPTIMIZADO: Reducir iteraciones para completar en < 90 segundos
    private static final int MAX_ITERACIONES = 50; // Antes: 100
    private static final int MAX_SIN_MEJORA = 2; // Antes: 3
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

    // ⚡ SISTEMA DE RESERVAS: Para separar planificación (GRASP) de ejecución
    // temporal
    // Las reservas permiten verificar capacidades sin asignar realmente
    // Las asignaciones reales se harán cuando los vuelos lleguen (eventos
    // temporales)
    private Map<Integer, Integer> reservasVuelos = new HashMap<>(); // vueloId -> cantidad reservada
    private Map<Integer, Integer> reservasAeropuertos = new HashMap<>(); // aeropuertoId -> cantidad reservada

    // ⚡ RESERVAS CON TIEMPO: Para validar capacidad considerando llegadas simultáneas (dentro de 2 horas)
    // Estructura: aeropuertoId -> Lista de (tiempoLlegada, cantidad)
    private static class ReservaConTiempo {
        ZonedDateTime tiempoLlegada;
        Integer cantidad;
        ReservaConTiempo(ZonedDateTime tiempoLlegada, Integer cantidad) {
            this.tiempoLlegada = tiempoLlegada;
            this.cantidad = cantidad;
        }
    }
    private Map<Integer, List<ReservaConTiempo>> reservasAeropuertosConTiempo = new HashMap<>(); // aeropuertoId -> lista de reservas con tiempo

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

        // ⚡ DEBUG: Log de la ventana temporal calculada
        System.out.printf("🔍 [filtrarVuelosPorVentanaTemporal] Ventana temporal: %s hasta %s (total vuelos: %d)%n",
                inicio.format(java.time.format.DateTimeFormatter.ISO_ZONED_DATE_TIME),
                fin.format(java.time.format.DateTimeFormatter.ISO_ZONED_DATE_TIME),
                todosLosVuelos.size());

        ArrayList<PlanDeVuelo> vuelosFiltrados = todosLosVuelos.stream()
                .filter(v -> {
                    ZonedDateTime salida = v.getZonedHoraOrigen();
                    ZonedDateTime llegada = v.getZonedHoraDestino();

                    // El vuelo debe salir después del inicio de la ventana (o al menos no mucho
                    // antes)
                    // y llegar antes del fin de la ventana
                    return salida.isAfter(inicio.minusHours(12)) && llegada.isBefore(fin.plusHours(12));
                })
                .collect(Collectors.toCollection(ArrayList::new));

        // ⚡ DEBUG: Log de cuántos vuelos se filtraron
        System.out.printf("✅ [filtrarVuelosPorVentanaTemporal] Vuelos filtrados: %d de %d (%.1f%%)%n",
                vuelosFiltrados.size(), todosLosVuelos.size(),
                todosLosVuelos.size() > 0 ? (vuelosFiltrados.size() * 100.0 / todosLosVuelos.size()) : 0.0);

        return vuelosFiltrados;
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

    /**
     * ⚡ SISTEMA DE RESERVAS: Métodos para reservar capacidades sin asignarlas
     * realmente.
     * Las asignaciones reales se harán cuando los vuelos lleguen (eventos
     * temporales).
     */

    /**
     * Obtiene la capacidad libre de un vuelo considerando reservas
     */
    private int getCapacidadLibreConReservas(PlanDeVuelo vuelo) {
        int capacidadOcupada = vuelo.getCapacidadOcupada() != null ? vuelo.getCapacidadOcupada() : 0;
        int reservas = reservasVuelos.getOrDefault(vuelo.getId(), 0);
        return vuelo.getCapacidadMaxima() - capacidadOcupada - reservas;
    }

    /**
     * Obtiene la capacidad libre de un aeropuerto considerando reservas
     */
    private int getCapacidadLibreAeropuertoConReservas(Aeropuerto aeropuerto) {
        int capacidadOcupada = aeropuerto.getCapacidadOcupada() != null ? aeropuerto.getCapacidadOcupada() : 0;
        int reservas = reservasAeropuertos.getOrDefault(aeropuerto.getId(), 0);
        return aeropuerto.getCapacidadMaxima() - capacidadOcupada - reservas;
    }

    /**
     * Reserva capacidad en un vuelo (sin asignar realmente)
     */
    private void reservarVuelo(PlanDeVuelo vuelo, Integer cantidad) {
        if (vuelo.getId() != null) {
            reservasVuelos.put(vuelo.getId(), reservasVuelos.getOrDefault(vuelo.getId(), 0) + cantidad);
        }
    }

    /**
     * Libera reserva de capacidad en un vuelo
     */
    private void liberarReservaVuelo(PlanDeVuelo vuelo, Integer cantidad) {
        if (vuelo.getId() != null) {
            int reservaActual = reservasVuelos.getOrDefault(vuelo.getId(), 0);
            reservasVuelos.put(vuelo.getId(), Math.max(0, reservaActual - cantidad));
        }
    }

    /**
     * Reserva capacidad en un aeropuerto (sin asignar realmente)
     */
    private void reservarAeropuerto(Aeropuerto aeropuerto, Integer cantidad) {
        if (aeropuerto.getId() != null) {
            reservasAeropuertos.put(aeropuerto.getId(),
                    reservasAeropuertos.getOrDefault(aeropuerto.getId(), 0) + cantidad);
        }
    }

    /**
     * Libera reserva de capacidad en un aeropuerto
     */
    private void liberarReservaAeropuerto(Aeropuerto aeropuerto, Integer cantidad) {
        if (aeropuerto.getId() != null) {
            int reservaActual = reservasAeropuertos.getOrDefault(aeropuerto.getId(), 0);
            reservasAeropuertos.put(aeropuerto.getId(), Math.max(0, reservaActual - cantidad));
        }
    }

    /**
     * Reserva capacidad en un aeropuerto con tiempo de llegada (para validaciones de capacidad
     * considerando llegadas simultáneas dentro de 2 horas)
     */
    private void reservarAeropuertoConTiempo(Aeropuerto aeropuerto, ZonedDateTime tiempoLlegada, Integer cantidad) {
        if (aeropuerto.getId() != null && tiempoLlegada != null && cantidad != null) {
            reservasAeropuertosConTiempo.computeIfAbsent(aeropuerto.getId(), k -> new ArrayList<>())
                    .add(new ReservaConTiempo(tiempoLlegada, cantidad));
        }
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
            // ⚡ Reset reservas al inicio de cada iteración
            reservasVuelos.clear();
            reservasAeropuertos.clear();
            reservasAeropuertosConTiempo.clear();

            // Reset capacidades (solo para verificación, no se asignan realmente)
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

    /**
     * Versión de ejecutarGrasp que verifica timeout periódicamente y actualiza la mejor solución
     * en un AtomicReference para que pueda ser recuperada si hay timeout
     */
    public Solucion ejecutarGraspConTimeout(List<Envio> envios, ArrayList<PlanDeVuelo> planesDeVuelo,
            long timeoutMs, long inicioEjecucion, java.util.concurrent.atomic.AtomicReference<Solucion> mejorSolucionRef) {
        Solucion mejor = null;
        int iteracionesSinMejora = 0;

        Map<PlanDeVuelo, Integer> capacidadBaseVuelos = planesDeVuelo.stream()
                .collect(Collectors.toMap(v -> v, v -> v.getCapacidadOcupada() != null ? v.getCapacidadOcupada() : 0));
        Map<Aeropuerto, Integer> capacidadBaseAeropuertos = this.aeropuertos != null
                ? this.aeropuertos.stream()
                        .collect(Collectors.toMap(a -> a,
                                a -> a.getCapacidadOcupada() != null ? a.getCapacidadOcupada() : 0))
                : Collections.emptyMap();

        // ⚡ DEBUG: Log de capacidad ocupada base
        long vuelosConCapacidadOcupada = capacidadBaseVuelos.values().stream().filter(c -> c > 0).count();
        long aeropuertosConCapacidadOcupada = capacidadBaseAeropuertos.values().stream().filter(c -> c > 0).count();
        int totalCapacidadOcupadaVuelos = capacidadBaseVuelos.values().stream().mapToInt(Integer::intValue).sum();
        int totalCapacidadOcupadaAeropuertos = capacidadBaseAeropuertos.values().stream().mapToInt(Integer::intValue).sum();
        System.out.printf("🔍 [ejecutarGraspConTimeout] Capacidad ocupada base: %d vuelos con ocupación (%d total), %d aeropuertos con ocupación (%d total)%n",
                vuelosConCapacidadOcupada, totalCapacidadOcupadaVuelos,
                aeropuertosConCapacidadOcupada, totalCapacidadOcupadaAeropuertos);

        for (int i = 0; i < MAX_ITERACIONES && iteracionesSinMejora < MAX_SIN_MEJORA; i++) {
            // Verificar timeout periódicamente
            long tiempoTranscurrido = System.currentTimeMillis() - inicioEjecucion;
            if (tiempoTranscurrido >= timeoutMs) {
                System.out.printf("⏰ GRASP: Timeout alcanzado (%d ms), deteniendo iteraciones en iteración %d%n",
                        tiempoTranscurrido, i);
                // Devolver la mejor solución encontrada hasta el momento
                if (mejor != null) {
                    mejor.recomputar();
                    mejorSolucionRef.set(mejor);
                    return mejor;
                }
                // Si no hay mejor solución, devolver null (se manejará en el método llamador)
                return null;
            }

            // ⚡ Reset reservas al inicio de cada iteración
            reservasVuelos.clear();
            reservasAeropuertos.clear();
            reservasAeropuertosConTiempo.clear();

            // Reset capacidades (solo para verificación, no se asignan realmente)
            planesDeVuelo.forEach(v -> v.setCapacidadOcupada(capacidadBaseVuelos.getOrDefault(v, 0)));
            if (this.aeropuertos != null) {
                this.aeropuertos.forEach(a -> a.setCapacidadOcupada(capacidadBaseAeropuertos.getOrDefault(a, 0)));
            }
            envios.forEach(e -> e.getParteAsignadas().clear());

            faseConstruccion(envios, planesDeVuelo);
            busquedaLocal(envios, planesDeVuelo);

            Solucion cur = new Solucion(new ArrayList<>(envios), planesDeVuelo);

            if (mejor == null || esMejor(cur, mejor)) {
                // Crear copia profunda de los envíos para evitar que se modifiquen en iteraciones posteriores
                ArrayList<Envio> enviosCopia = new ArrayList<>();
                for (Envio envio : envios) {
                    Envio envioCopia = crearCopiaEnvioParaSolucion(envio);
                    enviosCopia.add(envioCopia);
                }
                mejor = new Solucion(enviosCopia, planesDeVuelo);
                iteracionesSinMejora = 0;
                // Actualizar la mejor solución en el AtomicReference para que esté disponible si hay timeout
                mejorSolucionRef.set(mejor);
            } else {
                iteracionesSinMejora++;
            }
        }

        if (mejor != null) {
            mejor.recomputar();
            mejorSolucionRef.set(mejor);
        }
        return mejor;
    }

    /**
     * Crea una copia profunda de un envío incluyendo sus partes asignadas y rutas
     * para guardar en la mejor solución
     */
    private Envio crearCopiaEnvioParaSolucion(Envio original) {
        Envio copia = new Envio();
        copia.setId(original.getId());
        copia.setCliente(original.getCliente());
        copia.setAeropuertosOrigen(original.getAeropuertosOrigen() != null ?
                new ArrayList<>(original.getAeropuertosOrigen()) : new ArrayList<>());
        copia.setAeropuertoDestino(original.getAeropuertoDestino());
        copia.setFechaIngreso(original.getFechaIngreso());
        copia.setHusoHorarioDestino(original.getHusoHorarioDestino());
        copia.setZonedFechaIngreso(original.getZonedFechaIngreso());
        copia.setIdEnvioPorAeropuerto(original.getIdEnvioPorAeropuerto());
        copia.setNumProductos(original.getNumProductos());
        copia.setAeropuertoOrigen(original.getAeropuertoOrigen());

        // Copiar partes asignadas con sus rutas
        copia.setParteAsignadas(new ArrayList<>());
        if (original.getParteAsignadas() != null) {
            for (ParteAsignada parteOriginal : original.getParteAsignadas()) {
                ParteAsignada parteCopia = new ParteAsignada();
                parteCopia.setCantidad(parteOriginal.getCantidad());
                parteCopia.setLlegadaFinal(parteOriginal.getLlegadaFinal());
                parteCopia.setAeropuertoOrigen(parteOriginal.getAeropuertoOrigen());
                parteCopia.setEntregado(parteOriginal.getEntregado());

                // Copiar la ruta (lista de vuelos)
                if (parteOriginal.getRuta() != null) {
                    List<PlanDeVuelo> rutaCopia = new ArrayList<>();
                    for (PlanDeVuelo vuelo : parteOriginal.getRuta()) {
                        PlanDeVuelo vueloCopia = new PlanDeVuelo();
                        vueloCopia.setId(vuelo.getId());
                        vueloCopia.setCiudadOrigen(vuelo.getCiudadOrigen());
                        vueloCopia.setCiudadDestino(vuelo.getCiudadDestino());
                        vueloCopia.setHoraOrigen(vuelo.getHoraOrigen());
                        vueloCopia.setHoraDestino(vuelo.getHoraDestino());
                        vueloCopia.setHusoHorarioOrigen(vuelo.getHusoHorarioOrigen());
                        vueloCopia.setHusoHorarioDestino(vuelo.getHusoHorarioDestino());
                        vueloCopia.setCapacidadMaxima(vuelo.getCapacidadMaxima());
                        vueloCopia.setCapacidadOcupada(vuelo.getCapacidadOcupada());
                        vueloCopia.setZonedHoraOrigen(vuelo.getZonedHoraOrigen());
                        vueloCopia.setZonedHoraDestino(vuelo.getZonedHoraDestino());
                        vueloCopia.setMismoContinente(vuelo.getMismoContinente());
                        vueloCopia.setEstado(vuelo.getEstado());
                        rutaCopia.add(vueloCopia);
                    }
                    parteCopia.setRuta(rutaCopia);
                }

                parteCopia.setEnvio(copia);
                copia.getParteAsignadas().add(parteCopia);
            }
        }

        return copia;
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

            // ⚡ Aumentado de 12 a 20 para permitir más divisiones de pedidos grandes
            // Esto ayuda a distribuir mejor las llegadas y evitar conflictos de capacidad
            while (envio.cantidadRestante() > 0 && partesUsadas < 20) {
                List<CandidatoRuta> rutaCandidata = getCandidatosRuta(envio, planesDeVuelo);

                if (rutaCandidata.isEmpty()) {
                    // ⚡ DEBUG: Log cuando no hay candidatos disponibles
                    if (envio.cantidadRestante() > 0) {
                        System.out.printf("⚠️ [GRASP] Envío ID=%d: No hay candidatos disponibles. Restante=%d, Partes usadas=%d%n",
                                envio.getId() != null ? envio.getId() : -1, envio.cantidadRestante(), partesUsadas);
                    }
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

                // ⚡ Verificación de capacidad considerando RESERVAS (no asignaciones reales)
                // Las asignaciones reales se harán cuando los vuelos lleguen (eventos
                // temporales)
                Integer capacidadReal = Integer.MAX_VALUE;
                for (PlanDeVuelo v : escogido.getTramos()) {
                    // Por cada vuelo de la ruta candidata elegida, se va a identificar la minima
                    // capacidad de los vuelos (considerando reservas)
                    capacidadReal = Math.min(capacidadReal, getCapacidadLibreConReservas(v));
                }

                // Verificar también capacidad de aeropuertos intermedios y destino
                // (considerando reservas)
                for (int i = 0; i < escogido.getTramos().size(); i++) {
                    PlanDeVuelo vuelo = escogido.getTramos().get(i);
                    Aeropuerto destinoAeropuerto = getAeropuertoById(vuelo.getCiudadDestino());
                    if (destinoAeropuerto != null) {
                        // Los productos llegan al aeropuerto destino cuando el vuelo llega
                        // Verificar capacidad disponible en el aeropuerto destino

                        // ⚡ CRÍTICO: Si es el aeropuerto destino FINAL del envío, considerar que
                        // múltiples partes del mismo envío pueden llegar en diferentes momentos
                        // Por lo tanto, solo considerar la capacidad base y las partes ya asignadas
                        // del mismo envío, no las reservas de otras partes que aún no han llegado
                        boolean esDestinoFinal = (i == escogido.getTramos().size() - 1) &&
                                destinoAeropuerto.getId().equals(envio.getAeropuertoDestino().getId());

                        int capacidadLibreAeropuerto;
                        if (esDestinoFinal) {
                            // ⚡ CRÍTICO: Para el destino final, considerar que múltiples envíos pueden llegar
                            // en diferentes momentos usando escalas. Solo considerar reservas que lleguen
                            // en el mismo momento (dentro de una ventana de 2 horas).
                            // Esto permite que envíos con diferentes tiempos de llegada no compitan directamente.
                            int capacidadOcupada = destinoAeropuerto.getCapacidadOcupada() != null
                                    ? destinoAeropuerto.getCapacidadOcupada() : 0;

                            // Calcular reservas que lleguen en el mismo momento que esta parte
                            // (usando el tiempo de llegada del candidato, que es la llegada final de la ruta)
                            ZonedDateTime llegadaEstaParte = escogido.getLlegada();
                            int reservasMismoMomento = 0;

                            // 1. Sumar reservas de partes ya asignadas de otros envíos que lleguen en el mismo momento
                            // ⚡ CRÍTICO: Solo considerar partes de envíos completamente planificados (sin cantidad restante)
                            // para evitar bloqueos mutuos cuando se planifican múltiples envíos simultáneamente.
                            // Esto permite que el algoritmo encuentre rutas con llegadas más separadas.
                            for (Envio otroEnvio : enviosCopia) {
                                if (otroEnvio.getId() != null && envio.getId() != null
                                        && otroEnvio.getId().equals(envio.getId()))
                                    continue; // Saltar el envío actual

                                // ⚡ SOLO considerar envíos completamente planificados para evitar bloqueos mutuos
                                if (otroEnvio.cantidadRestante() > 0)
                                    continue; // Saltar envíos que aún no están completamente planificados

                                if (otroEnvio.getParteAsignadas() != null) {
                                    for (ParteAsignada parte : otroEnvio.getParteAsignadas()) {
                                        if (parte.getLlegadaFinal() != null && llegadaEstaParte != null) {
                                            // ⚡ AJUSTE: Ventana de 1 hora para detectar llegadas simultáneas
                                            // Esto previene que dos vuelos lleguen casi al mismo tiempo y excedan la capacidad
                                            Duration diferencia = Duration.between(
                                                    parte.getLlegadaFinal(), llegadaEstaParte).abs();
                                            if (diferencia.toHours() <= 1) {
                                                reservasMismoMomento += parte.getCantidad();
                                            }
                                        }
                                    }
                                }
                            }

                            // 2. Sumar reservas temporales del mismo ciclo que lleguen dentro de 1 hora
                            // ⚡ CRÍTICO: Considerar reservas temporales para prevenir llegadas simultáneas que excedan capacidad
                            // Usamos una ventana de 1 hora para detectar llegadas simultáneas
                            // Esto previene que dos vuelos lleguen casi al mismo tiempo y excedan la capacidad del aeropuerto
                            List<ReservaConTiempo> reservasTemporales = reservasAeropuertosConTiempo.getOrDefault(
                                    destinoAeropuerto.getId(), Collections.emptyList());
                            for (ReservaConTiempo reserva : reservasTemporales) {
                                if (reserva.tiempoLlegada != null && llegadaEstaParte != null) {
                                    Duration diferencia = Duration.between(
                                            reserva.tiempoLlegada, llegadaEstaParte).abs();
                                    // ⚡ Ventana de 1 hora para detectar llegadas simultáneas
                                    // Esto permite que envíos grandes se planifiquen con llegadas separadas (más de 1 hora)
                                    // pero previene llegadas casi simultáneas que excedan la capacidad
                                    if (diferencia.toHours() <= 1) {
                                        reservasMismoMomento += reserva.cantidad;
                                    }
                                }
                            }

                            capacidadLibreAeropuerto = destinoAeropuerto.getCapacidadMaxima()
                                    - capacidadOcupada
                                    - reservasMismoMomento;
                            capacidadLibreAeropuerto = Math.max(0, capacidadLibreAeropuerto);
                        } else {
                            // Para aeropuertos intermedios: usar la verificación normal con reservas
                            capacidadLibreAeropuerto = getCapacidadLibreAeropuertoConReservas(destinoAeropuerto);
                        }
                        capacidadReal = Math.min(capacidadReal, capacidadLibreAeropuerto);
                    }
                }

                // ⚡ AJUSTE: Permitir planificar incluso si la capacidad es limitada
                // El algoritmo puede dividir el envío en múltiples partes con llegadas separadas
                // Solo rechazar si realmente no hay capacidad disponible (capacidadReal <= 0)
                // ⚡ MEJORA: Si capacidadReal es muy pequeña pero > 0, aún asignarla para permitir
                // que el envío se divida en muchas partes pequeñas con llegadas separadas
                Integer cant = Math.min(envio.cantidadRestante(), capacidadReal);

                // ⚡ CRÍTICO: Si capacidadReal es 0, intentar buscar otro candidato con llegada más separada
                // antes de rechazar completamente. Esto permite que envíos grandes se dividan mejor.
                if (cant <= 0 && capacidadReal == 0 && rcl.size() > 1) {
                    // Intentar con el siguiente candidato que tenga llegada más separada
                    // (ya que están ordenados por score, los siguientes pueden tener llegadas diferentes)
                    boolean encontradoAlternativa = false;
                    for (int idx = 1; idx < Math.min(rcl.size(), 5); idx++) { // Probar hasta 5 candidatos alternativos
                        CandidatoRuta candidatoAlt = rcl.get(idx);
                        // Recalcular capacidadReal para este candidato alternativo
                        Integer capacidadRealAlt = Integer.MAX_VALUE;
                        for (PlanDeVuelo v : candidatoAlt.getTramos()) {
                            capacidadRealAlt = Math.min(capacidadRealAlt, getCapacidadLibreConReservas(v));
                        }
                        // Verificar capacidad del aeropuerto destino para este candidato
                        if (!candidatoAlt.getTramos().isEmpty()) {
                            PlanDeVuelo ultimoVueloAlt = candidatoAlt.getTramos().get(candidatoAlt.getTramos().size() - 1);
                            Aeropuerto destinoAeropuertoAlt = getAeropuertoById(ultimoVueloAlt.getCiudadDestino());
                            if (destinoAeropuertoAlt != null) {
                                boolean esDestinoFinalAlt = destinoAeropuertoAlt.getId().equals(envio.getAeropuertoDestino().getId());
                                if (esDestinoFinalAlt) {
                                    int capacidadOcupadaAlt = destinoAeropuertoAlt.getCapacidadOcupada() != null ? destinoAeropuertoAlt.getCapacidadOcupada() : 0;
                                    ZonedDateTime llegadaAlt = candidatoAlt.getLlegada();
                                    int reservasMismoMomentoAlt = 0;

                                    // Sumar reservas de otros envíos completamente planificados
                                    for (Envio otroEnvio : enviosCopia) {
                                        if (otroEnvio.getId() != null && envio.getId() != null && otroEnvio.getId().equals(envio.getId()))
                                            continue;
                                        if (otroEnvio.cantidadRestante() > 0)
                                            continue;
                                        if (otroEnvio.getParteAsignadas() != null) {
                                            for (ParteAsignada parte : otroEnvio.getParteAsignadas()) {
                                                if (parte.getLlegadaFinal() != null && llegadaAlt != null) {
                                                    Duration diferencia = Duration.between(parte.getLlegadaFinal(), llegadaAlt).abs();
                                                    if (diferencia.toHours() <= 1) {
                                                        reservasMismoMomentoAlt += parte.getCantidad();
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Sumar reservas temporales
                                    List<ReservaConTiempo> reservasTempAlt = reservasAeropuertosConTiempo.getOrDefault(destinoAeropuertoAlt.getId(), Collections.emptyList());
                                    for (ReservaConTiempo reserva : reservasTempAlt) {
                                        if (reserva.tiempoLlegada != null && llegadaAlt != null) {
                                            Duration diferencia = Duration.between(reserva.tiempoLlegada, llegadaAlt).abs();
                                            if (diferencia.toHours() <= 1) {
                                                reservasMismoMomentoAlt += reserva.cantidad;
                                            }
                                        }
                                    }

                                    int capacidadLibreAeropuertoAlt = destinoAeropuertoAlt.getCapacidadMaxima() - capacidadOcupadaAlt - reservasMismoMomentoAlt;
                                    capacidadLibreAeropuertoAlt = Math.max(0, capacidadLibreAeropuertoAlt);
                                    capacidadRealAlt = Math.min(capacidadRealAlt, capacidadLibreAeropuertoAlt);
                                }
                            }
                        }

                        if (capacidadRealAlt > 0) {
                            // Encontramos un candidato alternativo con capacidad disponible
                            escogido = candidatoAlt;
                            capacidadReal = capacidadRealAlt;
                            cant = Math.min(envio.cantidadRestante(), capacidadReal);
                            encontradoAlternativa = true;
                            break;
                        }
                    }

                    if (!encontradoAlternativa) {
                        // No se encontró alternativa viable
                        if (envio.cantidadRestante() > 0 && partesUsadas == 0) {
                            System.out.printf("⚠️ [GRASP] Envío ID=%d: Sin capacidad disponible en esta iteración. Restante=%d, Partes usadas=%d, capacidadReal=%d%n",
                                    envio.getId() != null ? envio.getId() : -1, envio.cantidadRestante(), partesUsadas, capacidadReal);
                        }
                        break;
                    }
                } else if (cant <= 0) {
                    // ⚡ DEBUG: Log cuando no hay capacidad disponible en esta iteración
                    // Esto es normal en GRASP - otras iteraciones pueden encontrar solución
                    if (envio.cantidadRestante() > 0 && partesUsadas == 0) {
                        // Solo log si no se ha asignado ninguna parte (problema más serio)
                        System.out.printf("⚠️ [GRASP] Envío ID=%d: Sin capacidad disponible en esta iteración. Restante=%d, Partes usadas=%d, capacidadReal=%d%n",
                                envio.getId() != null ? envio.getId() : -1, envio.cantidadRestante(), partesUsadas, capacidadReal);

                        // ⚡ DEBUG DETALLADO: Mostrar información sobre el candidato elegido y por qué falló
                        if (escogido != null && escogido.getTramos() != null && !escogido.getTramos().isEmpty()) {
                            PlanDeVuelo ultimoVuelo = escogido.getTramos().get(escogido.getTramos().size() - 1);
                            Aeropuerto destinoFinal = getAeropuertoById(ultimoVuelo.getCiudadDestino());
                            if (destinoFinal != null) {
                                int capacidadOcupada = destinoFinal.getCapacidadOcupada() != null ? destinoFinal.getCapacidadOcupada() : 0;
                                int capacidadMaxima = destinoFinal.getCapacidadMaxima() != null ? destinoFinal.getCapacidadMaxima() : 0;
                                System.out.printf("   🔍 Destino: %s, Capacidad: %d/%d, Llegada candidato: %s%n",
                                        destinoFinal.getCodigo(), capacidadOcupada, capacidadMaxima,
                                        escogido.getLlegada() != null ? escogido.getLlegada().format(java.time.format.DateTimeFormatter.ISO_ZONED_DATE_TIME) : "null");

                                // Contar reservas temporales
                                List<ReservaConTiempo> reservasTemp = reservasAeropuertosConTiempo.getOrDefault(destinoFinal.getId(), Collections.emptyList());
                                int reservasEnVentana = 0;
                                if (escogido.getLlegada() != null) {
                                    for (ReservaConTiempo r : reservasTemp) {
                                        if (r.tiempoLlegada != null) {
                                            Duration diff = Duration.between(r.tiempoLlegada, escogido.getLlegada()).abs();
                                            if (diff.toHours() <= 1) {
                                                reservasEnVentana += r.cantidad;
                                            }
                                        }
                                    }
                                }
                                System.out.printf("   🔍 Reservas temporales en ventana de 1h: %d unidades%n", reservasEnVentana);
                            }
                        }
                    }
                    break;
                }

                // ⚡ RESERVAR capacidad en vuelos (NO asignar realmente)
                // Las asignaciones reales se harán cuando los vuelos lleguen (eventos
                // temporales)
                for (PlanDeVuelo v : escogido.getTramos())
                    reservarVuelo(v, cant);

                // ⚡ RESERVAR capacidad en aeropuertos (NO asignar realmente)
                // Para cada vuelo en la ruta:
                // - Si NO es el primer vuelo: liberar reserva del aeropuerto de origen
                // (los productos salen cuando el vuelo despega)
                // - Siempre: reservar capacidad en el aeropuerto de destino
                // (los productos llegan cuando el vuelo aterriza)
                for (int i = 0; i < escogido.getTramos().size(); i++) {
                    PlanDeVuelo vuelo = escogido.getTramos().get(i);

                    // Si NO es el primer vuelo, liberar reserva del aeropuerto de origen
                    // (los productos salen del aeropuerto cuando el vuelo despega)
                    if (i > 0) {
                        Aeropuerto origenAeropuerto = getAeropuertoById(vuelo.getCiudadOrigen());
                        if (origenAeropuerto != null) {
                            liberarReservaAeropuerto(origenAeropuerto, cant);
                        }
                    }

                    // Reservar capacidad en el aeropuerto de destino (los productos llegan cuando
                    // el
                    // vuelo aterriza)
                    Aeropuerto destinoAeropuerto = getAeropuertoById(vuelo.getCiudadDestino());
                    if (destinoAeropuerto != null) {
                        reservarAeropuerto(destinoAeropuerto, cant);
                        // ⚡ CRÍTICO: También registrar la reserva con tiempo de llegada para validaciones
                        // de capacidad considerando llegadas simultáneas (dentro de 2 horas)
                        if (i == escogido.getTramos().size() - 1) {
                            // Solo registrar para el último vuelo (llegada final)
                            reservarAeropuertoConTiempo(destinoAeropuerto, escogido.getLlegada(), cant);
                        }
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

        // ⚡ CRÍTICO: Filtrar y ajustar candidatos cacheados considerando reservas actuales
        // Los candidatos se generaron antes de que otros envíos hicieran reservas,
        // por lo que debemos verificar que aún tengan capacidad disponible
        ArrayList<CandidatoRuta> candidatosCacheados = rutas.get(clave);
        ArrayList<CandidatoRuta> candidatosValidos = new ArrayList<>();

        // ⚡ DEBUG: Log de candidatos antes del filtrado
        System.out.printf("🔍 [getCandidatosRuta] Envío ID=%d: Candidatos cacheados: %d%n",
                envio.getId() != null ? envio.getId() : -1, candidatosCacheados.size());

        for (CandidatoRuta candidato : candidatosCacheados) {
            // Recalcular la capacidad real de la ruta considerando reservas actuales
            Integer capacidadReal = Integer.MAX_VALUE;

            // Verificar capacidad de vuelos en la ruta
            for (PlanDeVuelo v : candidato.getTramos()) {
                capacidadReal = Math.min(capacidadReal, getCapacidadLibreConReservas(v));
            }

            // Verificar capacidad de aeropuertos en la ruta
            for (int i = 0; i < candidato.getTramos().size(); i++) {
                PlanDeVuelo vuelo = candidato.getTramos().get(i);
                Aeropuerto destinoAeropuerto = getAeropuertoById(vuelo.getCiudadDestino());
                if (destinoAeropuerto != null) {
                    boolean esDestinoFinal = (i == candidato.getTramos().size() - 1) &&
                            destinoAeropuerto.getId().equals(envio.getAeropuertoDestino().getId());

                    int capacidadLibreAeropuerto;
                    if (esDestinoFinal) {
                        // ⚡ CRÍTICO: Para el destino final, no considerar reservas durante el filtrado
                        // porque múltiples envíos pueden llegar en diferentes momentos usando escalas.
                        // La verificación precisa con tiempos de llegada se hace en faseConstruccion.
                        int capacidadOcupada = destinoAeropuerto.getCapacidadOcupada() != null
                                ? destinoAeropuerto.getCapacidadOcupada() : 0;
                        capacidadLibreAeropuerto = destinoAeropuerto.getCapacidadMaxima() - capacidadOcupada;
                        capacidadLibreAeropuerto = Math.max(0, capacidadLibreAeropuerto);
                    } else {
                        capacidadLibreAeropuerto = getCapacidadLibreAeropuertoConReservas(destinoAeropuerto);
                    }
                    capacidadReal = Math.min(capacidadReal, capacidadLibreAeropuerto);
                }
            }

            // Solo incluir candidatos que aún tengan capacidad disponible
            if (capacidadReal > 0) {
                // Crear una copia del candidato con la capacidad actualizada
                CandidatoRuta candidatoActualizado = new CandidatoRuta();
                candidatoActualizado.setTramos(candidato.getTramos());
                candidatoActualizado.setLlegada(candidato.getLlegada());

                // ⚡ AJUSTAR SCORE: Penalizar rutas con poca capacidad disponible
                // Esto hace que las rutas con escalas sean más atractivas cuando los vuelos directos están saturados
                long scoreAjustado = candidato.getScore();
                if (capacidadReal < envio.getNumProductos()) {
                    // Si la capacidad disponible es menor que el pedido completo, penalizar más
                    // Penalización inversamente proporcional a la capacidad disponible
                    // Multiplicador de 50 para que sea significativo comparado con la penalización por escalas (10k)
                    // Ejemplo: si capacidadReal = 100 y numProductos = 990, penalización = 44,500 puntos
                    // Esto hará que un vuelo directo con poca capacidad tenga peor score que una ruta con escalas con buena capacidad
                    long penalizacionCapacidad = (envio.getNumProductos() - capacidadReal) * 50L;
                    scoreAjustado += penalizacionCapacidad;
                }

                candidatoActualizado.setScore(scoreAjustado);
                candidatoActualizado.setCapacidadRuta(Math.min(candidato.getCapacidadRuta(), capacidadReal));
                candidatoActualizado.setOrigen(candidato.getOrigen());
                candidatosValidos.add(candidatoActualizado);
            }
        }

        // ⚡ DEBUG: Log de candidatos después del filtrado
        System.out.printf("✅ [getCandidatosRuta] Envío ID=%d: Candidatos válidos después de filtrar: %d de %d%n",
                envio.getId() != null ? envio.getId() : -1, candidatosValidos.size(), candidatosCacheados.size());

        return candidatosValidos;
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

        // ⚡ DEBUG: Log de inicio de generación de candidatos
        System.out.printf("🔍 [generarCandidatos] Envío ID=%d: Generando candidatos desde %d orígenes posibles%n",
                envio.getId() != null ? envio.getId() : -1, envio.getAeropuertosOrigen().size());

        for (Aeropuerto origen : envio.getAeropuertosOrigen()) {
            Duration deadline = deadlineCache.computeIfAbsent(
                    origen.getCodigo() + "_" + envio.getAeropuertoDestino().getCodigo(),
                    k -> envio.deadlineDesde(origen)); // Se ve si es tramo intercontinente o intracontinente
            Instant limite = envio.getZonedFechaIngreso().toInstant().plus(deadline); // Fecha limite de llegada
            List<PathState> beam = new ArrayList<>(); // Estado inicial

            // Estamos en el aeropuerto de origen, sin vuelos tomados y espacio infinito
            beam.add(new PathState(origen, null, new ArrayList<>(), null, Integer.MAX_VALUE));

            // ⚡ OPTIMIZADO: Reducir niveles de búsqueda de 5 a 3 para acelerar
            for (int nivel = 0; nivel < 3; nivel++) {
                List<PathState> nuevosEstados = new ArrayList<>();

                for (PathState ps : beam) { // Iteramos en cada estado
                    // Para cada estado, se seleccionan los vuelos que salen del aeropuerto en donde
                    // se encuentra ese estado
                    Aeropuerto aeropuertoActual = ps.getUbicacion();
                    List<PlanDeVuelo> salidas = this.vuelosPorOrigenCache.getOrDefault(aeropuertoActual.getCodigo(),
                            Collections.emptyList());

                    // ⚡ DEBUG: Log de vuelos disponibles por aeropuerto (solo en el primer nivel desde el origen)
                    if (nivel == 0 && ps.getUbicacion().equals(origen)) {
                        System.out.printf("🔍 [generarCandidatos] Envío ID=%d: Aeropuerto origen %s tiene %d vuelos disponibles%n",
                                envio.getId() != null ? envio.getId() : -1, aeropuertoActual.getCodigo(), salidas.size());
                    }

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

                        // ⚡ Verificar capacidad libre considerando reservas
                        int capLibre = getCapacidadLibreConReservas(v);
                        if (capLibre <= 0)
                            continue; // Verificar capacidad libre

                        ArrayList<PlanDeVuelo> ruta = new ArrayList<>(ps.getTramos());
                        ruta.add(v); // Se agrega el vuelo a la ruta
                        int capRuta = Math.min(ps.getCapacidadRuta(), capLibre); // Minima cantidad disponible de algun
                                                                                 // avion de la ruta

                        Aeropuerto destinoAeropuerto = getAeropuertoById(v.getCiudadDestino());
                        if (destinoAeropuerto == null)
                            continue;

                        // ⚡ Verificar capacidad del aeropuerto destino
                        boolean esDestinoFinal = destinoAeropuerto.getCodigo().equals(envio.getAeropuertoDestino().getCodigo());
                        int capacidadLibreAeropuerto;

                        if (esDestinoFinal) {
                            // ⚡ CRÍTICO: Para el destino final, no considerar reservas durante la generación inicial
                            // porque múltiples envíos pueden llegar en diferentes momentos usando escalas.
                            // Las reservas se verificarán más tarde en faseConstruccion considerando tiempos de llegada.
                            int capacidadOcupada = destinoAeropuerto.getCapacidadOcupada() != null
                                    ? destinoAeropuerto.getCapacidadOcupada() : 0;
                            capacidadLibreAeropuerto = destinoAeropuerto.getCapacidadMaxima() - capacidadOcupada;
                        } else {
                            // Para aeropuertos intermedios: usar la verificación normal con reservas
                            capacidadLibreAeropuerto = getCapacidadLibreAeropuertoConReservas(destinoAeropuerto);
                        }

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
                // ⚡ Aumentar beam size de 5 a 10 para explorar más rutas y encontrar más candidatos
                // Un beam size más grande permite mantener más rutas parciales en cada nivel,
                // lo que aumenta las posibilidades de encontrar rutas viables, especialmente cuando
                // hay muchas opciones de vuelos o cuando se necesitan múltiples escalas
                if (nuevosEstados.size() > 10)
                    nuevosEstados = nuevosEstados.subList(0, 10);

                beam = nuevosEstados;

                if (beam.isEmpty())
                    break;
            }
        }

        // Se ordena por score
        candidatos.sort(Comparator.comparingLong(CandidatoRuta::getScore));

        // ⚡ DEBUG: Log de candidatos generados
        System.out.printf("✅ [generarCandidatos] Envío ID=%d: Generados %d candidatos de ruta%n",
                envio.getId() != null ? envio.getId() : -1, candidatos.size());

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

        long escalas = (long) tramos.size() * 20_000L; // Cada escala suma 10k puntos
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
                // ⚡ Liberar reservas de la ruta actual (NO desasignar realmente)
                List<PlanDeVuelo> rutaActual = parte.getRuta();
                if (rutaActual != null) {
                    // Liberar reservas de vuelos
                    for (PlanDeVuelo vuelo : rutaActual)
                        liberarReservaVuelo(vuelo, parte.getCantidad());

                    // Liberar/restaurar reservas de aeropuertos
                    // Para cada vuelo en la ruta (en orden inverso para restaurar correctamente):
                    // - Liberar reserva del aeropuerto de destino (los productos ya no llegarán
                    // ahí)
                    // - Si NO es el primer vuelo: restaurar reserva del aeropuerto de origen (los
                    // productos vuelven a estar ahí)
                    for (int i = rutaActual.size() - 1; i >= 0; i--) {
                        PlanDeVuelo vuelo = rutaActual.get(i);

                        // Liberar reserva del aeropuerto de destino
                        Aeropuerto destinoAeropuerto = getAeropuertoById(vuelo.getCiudadDestino());
                        if (destinoAeropuerto != null) {
                            liberarReservaAeropuerto(destinoAeropuerto, parte.getCantidad());
                        }

                        // Si NO es el primer vuelo, restaurar reserva del aeropuerto de origen
                        // (los productos vuelven a estar en ese aeropuerto)
                        if (i > 0) {
                            Aeropuerto origenAeropuerto = getAeropuertoById(vuelo.getCiudadOrigen());
                            if (origenAeropuerto != null) {
                                reservarAeropuerto(origenAeropuerto, parte.getCantidad());
                            }
                        }
                    }
                }

                // Se elimina esta parte de la ruta
                envio.getParteAsignadas().remove(parte);

                List<CandidatoRuta> candidato = getCandidatosRuta(envio, planesDeVuelo).stream()
                        .filter(c -> {
                            // ⚡ Verificar capacidad de vuelos considerando reservas
                            for (PlanDeVuelo v : c.getTramos()) {
                                if (getCapacidadLibreConReservas(v) < parte.getCantidad())
                                    return false;
                            }
                            // ⚡ Verificar capacidad de aeropuertos destino considerando reservas
                            for (PlanDeVuelo v : c.getTramos()) {
                                Aeropuerto destinoAeropuerto = getAeropuertoById(v.getCiudadDestino());
                                if (destinoAeropuerto != null
                                        && getCapacidadLibreAeropuertoConReservas(destinoAeropuerto) < parte
                                                .getCantidad()) {
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
                    // ⚡ RESERVAR capacidad en vuelos (NO asignar realmente)
                    for (PlanDeVuelo v : c.getTramos())
                        reservarVuelo(v, parte.getCantidad());

                    // ⚡ RESERVAR capacidad en aeropuertos (NO asignar realmente)
                    // Para cada vuelo en la ruta:
                    // - Si NO es el primer vuelo: liberar reserva del aeropuerto de origen
                    // (los productos salen cuando el vuelo despega)
                    // - Siempre: reservar capacidad en el aeropuerto de destino
                    // (los productos llegan cuando el vuelo aterriza)
                    for (int i = 0; i < c.getTramos().size(); i++) {
                        PlanDeVuelo vuelo = c.getTramos().get(i);

                        // Si NO es el primer vuelo, liberar reserva del aeropuerto de origen
                        // (los productos salen del aeropuerto cuando el vuelo despega)
                        if (i > 0) {
                            Aeropuerto origenAeropuerto = getAeropuertoById(vuelo.getCiudadOrigen());
                            if (origenAeropuerto != null) {
                                liberarReservaAeropuerto(origenAeropuerto, parte.getCantidad());
                            }
                        }

                        // Reservar capacidad en el aeropuerto de destino (los productos llegan cuando
                        // el
                        // vuelo aterriza)
                        Aeropuerto destinoAeropuerto = getAeropuertoById(vuelo.getCiudadDestino());
                        if (destinoAeropuerto != null) {
                            reservarAeropuerto(destinoAeropuerto, parte.getCantidad());
                            // ⚡ CRÍTICO: También registrar la reserva con tiempo de llegada para validaciones
                            // de capacidad considerando llegadas simultáneas (dentro de 2 horas)
                            if (i == c.getTramos().size() - 1) {
                                // Solo registrar para el último vuelo (llegada final)
                                reservarAeropuertoConTiempo(destinoAeropuerto, c.getLlegada(), parte.getCantidad());
                            }
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
                        // ⚡ Restaurar reservas en vuelos (NO asignar realmente)
                        for (PlanDeVuelo v : rutaActual)
                            reservarVuelo(v, parte.getCantidad());

                        // ⚡ Restaurar reservas en aeropuertos (NO asignar realmente)
                        // Para cada vuelo en la ruta:
                        // - Restaurar reserva del aeropuerto de destino (los productos vuelven a
                        // estar ahí)
                        // - Si NO es el primer vuelo: liberar reserva del aeropuerto de origen
                        // (los productos salen cuando el vuelo despega)
                        for (int i = 0; i < rutaActual.size(); i++) {
                            PlanDeVuelo vuelo = rutaActual.get(i);

                            // Restaurar reserva del aeropuerto de destino
                            Aeropuerto destinoAeropuerto = getAeropuertoById(vuelo.getCiudadDestino());
                            if (destinoAeropuerto != null) {
                                reservarAeropuerto(destinoAeropuerto, parte.getCantidad());
                                // ⚡ CRÍTICO: También registrar la reserva con tiempo de llegada para validaciones
                                // de capacidad considerando llegadas simultáneas (dentro de 2 horas)
                                if (i == rutaActual.size() - 1 && parte.getLlegadaFinal() != null) {
                                    // Solo registrar para el último vuelo (llegada final)
                                    reservarAeropuertoConTiempo(destinoAeropuerto, parte.getLlegadaFinal(), parte.getCantidad());
                                }
                            }

                            // Si NO es el primer vuelo, liberar reserva del aeropuerto de origen
                            // (los productos salen del aeropuerto cuando el vuelo despega)
                            if (i > 0) {
                                Aeropuerto origenAeropuerto = getAeropuertoById(vuelo.getCiudadOrigen());
                                if (origenAeropuerto != null) {
                                    liberarReservaAeropuerto(origenAeropuerto, parte.getCantidad());
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

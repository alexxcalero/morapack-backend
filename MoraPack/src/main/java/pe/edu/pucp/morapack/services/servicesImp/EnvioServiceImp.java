package pe.edu.pucp.morapack.services.servicesImp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.pucp.morapack.models.Envio;
import pe.edu.pucp.morapack.models.ParteAsignada;
import pe.edu.pucp.morapack.models.ParteAsignadaPlanDeVuelo;
import pe.edu.pucp.morapack.models.PlanDeVuelo;
import pe.edu.pucp.morapack.repository.EnvioRepository;
import pe.edu.pucp.morapack.services.EnvioService;

import java.time.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EnvioServiceImp implements EnvioService {
    private final EnvioRepository envioRepository;

    @Override
    public Envio insertarEnvio(Envio envio) {
        return envioRepository.save(envio);
    }

    @Override
    public ArrayList<Envio> insertarListaEnvios(ArrayList<Envio> envios) {
        return (ArrayList<Envio>) envioRepository.saveAll(envios);
    }

    @Override
    public ArrayList<Envio> obtenerEnvios() {
        return (ArrayList<Envio>) envioRepository.findAll();
    }

    @Override
    public Optional<Envio> obtenerEnvioPorId(Integer id) {
        return envioRepository.findById(id);
    }

    @Override
    public ArrayList<Envio> obtenerEnviosPorFecha(LocalDate fecha) {
        return envioRepository.findByFechaIngreso(fecha);
    }

    @Override
    public Integer calcularTotalProductosEnvio(ArrayList<Envio> envios) {
        Integer totalProductos = 0;
        for (Envio envio : envios) {
            totalProductos += envio.getNumProductos();
        }
        return totalProductos;
    }

    @Override
    public ArrayList<Envio> obtenerEnviosPorAeropuertoOrigen(Integer idAeropuerto) {
        return envioRepository.findByAeropuertoOrigen(idAeropuerto);
    }

    @Override
    public ArrayList<Envio> obtenerEnviosPorAeropuertoDestino(Integer idAeropuerto) {
        return envioRepository.findByAeropuertoDestino(idAeropuerto);
    }

    @Override
    public ArrayList<Envio> obtenerEnviosFisicamenteEnAeropuerto(Integer idAeropuerto) {
        ArrayList<Envio> todosEnvios = obtenerEnvios();
        ArrayList<Envio> enviosEnAeropuerto = new ArrayList<>();
        ZonedDateTime ahora = ZonedDateTime.now();

        for (Envio envio : todosEnvios) {
            if (envio.getParteAsignadas() == null || envio.getParteAsignadas().isEmpty()) {
                // Si no tiene partes asignadas, no está en ningún aeropuerto aún
                continue;
            }

            // Verificar si al menos una parte asignada está físicamente en este aeropuerto
            for (ParteAsignada parte : envio.getParteAsignadas()) {
                // Si la parte ya fue entregada, no está en ningún aeropuerto
                if (parte.getEntregado() != null && parte.getEntregado()) {
                    continue;
                }

                Integer aeropuertoActualId = determinarAeropuertoActual(parte, ahora);

                if (aeropuertoActualId != null && aeropuertoActualId.equals(idAeropuerto)) {
                    // Este envío está físicamente en este aeropuerto
                    enviosEnAeropuerto.add(envio);
                    break; // Solo necesitamos una parte asignada para considerar que el envío está aquí
                }
            }
        }

        return enviosEnAeropuerto;
    }

    /**
     * Determina en qué aeropuerto está físicamente una parte asignada basándose en
     * su ruta de vuelos
     * 
     * @param parte La parte asignada
     * @param ahora La hora actual
     * @return El ID del aeropuerto donde está físicamente la parte, o null si está
     *         entregada
     */
    private Integer determinarAeropuertoActual(ParteAsignada parte, ZonedDateTime ahora) {
        List<ParteAsignadaPlanDeVuelo> vuelosRuta = parte.getVuelosRuta();

        if (vuelosRuta == null || vuelosRuta.isEmpty()) {
            // Si no tiene vuelos asignados, está en el aeropuerto origen
            if (parte.getAeropuertoOrigen() != null) {
                return parte.getAeropuertoOrigen().getId();
            }
            return null;
        }

        // Ordenar los vuelos por orden para procesarlos en secuencia
        List<ParteAsignadaPlanDeVuelo> vuelosOrdenados = vuelosRuta.stream()
                .sorted((a, b) -> Integer.compare(a.getOrden(), b.getOrden()))
                .collect(Collectors.toList());

        // Buscar el último vuelo que ya completó su llegada
        PlanDeVuelo ultimoVueloCompletado = null;
        for (ParteAsignadaPlanDeVuelo parteVuelo : vuelosOrdenados) {
            PlanDeVuelo vuelo = parteVuelo.getPlanDeVuelo();
            if (vuelo != null && vuelo.getZonedHoraDestino() != null) {
                ZonedDateTime llegadaVuelo = vuelo.getZonedHoraDestino();
                // Comparar en UTC para ser consistente
                if (llegadaVuelo.toInstant().isBefore(ahora.toInstant()) ||
                        llegadaVuelo.toInstant().equals(ahora.toInstant())) {
                    ultimoVueloCompletado = vuelo;
                } else {
                    // Este vuelo aún no ha llegado, así que paramos aquí
                    break;
                }
            }
        }

        if (ultimoVueloCompletado != null) {
            // Está en el aeropuerto destino del último vuelo completado
            return ultimoVueloCompletado.getCiudadDestino();
        } else {
            // Ningún vuelo ha llegado aún, está en el aeropuerto origen del primer vuelo
            PlanDeVuelo primerVuelo = vuelosOrdenados.get(0).getPlanDeVuelo();
            if (primerVuelo != null) {
                return primerVuelo.getCiudadOrigen();
            }
            // Si no hay primer vuelo válido, devolver el aeropuerto origen de la parte
            if (parte.getAeropuertoOrigen() != null) {
                return parte.getAeropuertoOrigen().getId();
            }
            return null;
        }
    }

    @Override
    public String determinarEstadoPedido(Envio envio) {
        if (envio == null) {
            return "SIN_PLANIFICAR";
        }

        // Si no tiene partes asignadas, no está planificado
        if (envio.getParteAsignadas() == null || envio.getParteAsignadas().isEmpty()) {
            return "SIN_PLANIFICAR";
        }

        // Verificar todas las partes del pedido
        boolean todasEntregadas = true;
        boolean todasLlegaronDestino = true;
        ZonedDateTime ahora = ZonedDateTime.now();

        for (ParteAsignada parte : envio.getParteAsignadas()) {
            // Verificar si está entregada
            boolean parteEntregada = parte.getEntregado() != null && parte.getEntregado();

            if (!parteEntregada) {
                todasEntregadas = false;
            }

            // Verificar si llegó al destino final
            boolean llegoADestino = false;

            // Cargar la ruta desde BD si no está cargada
            if (parte.getRuta() == null || parte.getRuta().isEmpty()) {
                parte.cargarRutaDesdeBD();
            }

            if (parte.getRuta() != null && !parte.getRuta().isEmpty() &&
                    envio.getAeropuertoDestino() != null) {
                // Verificar que la ruta termine en el aeropuerto destino final
                PlanDeVuelo ultimoVuelo = parte.getRuta().get(parte.getRuta().size() - 1);
                if (ultimoVuelo != null && ultimoVuelo.getCiudadDestino() != null &&
                        ultimoVuelo.getCiudadDestino().equals(envio.getAeropuertoDestino().getId())) {
                    // La ruta termina en el destino, verificar si ya llegó
                    if (parte.getLlegadaFinal() != null) {
                        // Comparar si la llegada final ya pasó
                        if (parte.getLlegadaFinal().toInstant().isBefore(ahora.toInstant()) ||
                                parte.getLlegadaFinal().toInstant().equals(ahora.toInstant())) {
                            llegoADestino = true;
                        }
                    }
                }
            }

            if (!llegoADestino && !parteEntregada) {
                todasLlegaronDestino = false;
            }
        }

        // Clasificar el estado del pedido
        if (todasEntregadas) {
            return "ENTREGADO";
        } else if (todasLlegaronDestino) {
            return "COMPLETADO";
        } else {
            return "PENDIENTE";
        }
    }

    @Override
    public Map<String, Object> obtenerPedidosConEstado() {
        ArrayList<Envio> todosEnvios = obtenerEnvios();
        Map<String, Object> resultado = new HashMap<>();

        List<Map<String, Object>> pedidosEntregados = new ArrayList<>();
        List<Map<String, Object>> pedidosCompletados = new ArrayList<>();
        List<Map<String, Object>> pedidosPendientes = new ArrayList<>();
        List<Map<String, Object>> pedidosSinPlanificar = new ArrayList<>();

        for (Envio envio : todosEnvios) {
            String estado = determinarEstadoPedido(envio);

            Map<String, Object> pedidoInfo = new HashMap<>();
            pedidoInfo.put("id", envio.getId());
            pedidoInfo.put("idEnvioPorAeropuerto", envio.getIdEnvioPorAeropuerto());
            pedidoInfo.put("cliente", envio.getCliente());
            pedidoInfo.put("numProductos", envio.getNumProductos());
            pedidoInfo.put("cantidadAsignada", envio.cantidadAsignada());
            pedidoInfo.put("cantidadRestante", envio.cantidadRestante());
            pedidoInfo.put("fechaIngreso", envio.getFechaIngreso());
            pedidoInfo.put("fechaLlegadaMax", envio.getFechaLlegadaMax());

            if (envio.getAeropuertoDestino() != null) {
                pedidoInfo.put("aeropuertoDestino", Map.of(
                        "id", envio.getAeropuertoDestino().getId(),
                        "codigo", envio.getAeropuertoDestino().getCodigo(),
                        "ciudad", envio.getAeropuertoDestino().getCiudad()));
            }

            pedidoInfo.put("estado", estado);

            // Agregar a la lista correspondiente
            switch (estado) {
                case "ENTREGADO":
                    pedidosEntregados.add(pedidoInfo);
                    break;
                case "COMPLETADO":
                    pedidosCompletados.add(pedidoInfo);
                    break;
                case "PENDIENTE":
                    pedidosPendientes.add(pedidoInfo);
                    break;
                default:
                    pedidosSinPlanificar.add(pedidoInfo);
                    break;
            }
        }

        resultado.put("entregados", pedidosEntregados);
        resultado.put("completados", pedidosCompletados);
        resultado.put("pendientes", pedidosPendientes);
        resultado.put("sinPlanificar", pedidosSinPlanificar);

        resultado.put("cantidadEntregados", pedidosEntregados.size());
        resultado.put("cantidadCompletados", pedidosCompletados.size());
        resultado.put("cantidadPendientes", pedidosPendientes.size());
        resultado.put("cantidadSinPlanificar", pedidosSinPlanificar.size());
        resultado.put("totalPedidos", todosEnvios.size());

        return resultado;
    }

    @Override
    public ArrayList<Envio> obtenerEnviosEnRango(LocalDateTime fechaInicio, String husoHorarioInicio,
            LocalDateTime fechaFin, String husoHorarioFin) {
        System.out.println("🔍 [EnvioService] obtenerEnviosEnRango: inicio=" + fechaInicio + ", fin=" + fechaFin);

        // Convertir las fechas de entrada a ZonedDateTime
        Integer offsetInicio = Integer.parseInt(husoHorarioInicio);
        Integer offsetFin = Integer.parseInt(husoHorarioFin);
        ZoneOffset zoneInicio = ZoneOffset.ofHours(offsetInicio);
        ZoneOffset zoneFin = ZoneOffset.ofHours(offsetFin);

        ZonedDateTime zonedFechaInicio = fechaInicio.atZone(zoneInicio);
        ZonedDateTime zonedFechaFin = fechaFin.atZone(zoneFin);

        // Convertir a UTC para hacer la consulta más precisa
        ZonedDateTime fechaInicioUTC = zonedFechaInicio.withZoneSameInstant(ZoneOffset.UTC);
        ZonedDateTime fechaFinUTC = zonedFechaFin.withZoneSameInstant(ZoneOffset.UTC);

        // Ampliar el rango para considerar todas las zonas horarias posibles (-12 a +14
        // horas)
        // Esto asegura que no perdamos envíos debido a diferencias de zona horaria
        LocalDateTime fechaInicioConsulta = fechaInicioUTC.toLocalDateTime().minusHours(14);
        LocalDateTime fechaFinConsulta = fechaFinUTC.toLocalDateTime().plusHours(14);

        System.out
                .println("🔍 [EnvioService] Ejecutando consulta BD: " + fechaInicioConsulta + " a " + fechaFinConsulta);

        // Consulta optimizada en la BD (solo trae envíos relevantes)
        ArrayList<Envio> enviosCandidatos = envioRepository.findByFechaIngresoBetween(
                fechaInicioConsulta, fechaFinConsulta);

        System.out.println("🔍 [EnvioService] Candidatos de BD: " + enviosCandidatos.size());

        // Filtrar en memoria considerando las zonas horarias reales (sobre un conjunto
        // mucho menor)
        ArrayList<Envio> enviosEnRango = new ArrayList<>();
        for (Envio envio : enviosCandidatos) {
            ZonedDateTime zonedFechaIngreso = obtenerZonedFechaIngreso(envio);

            // Un envío está en el rango si su fecha de ingreso está dentro del rango
            boolean ingresoEnRango = !zonedFechaIngreso.isBefore(zonedFechaInicio) &&
                    !zonedFechaIngreso.isAfter(zonedFechaFin);

            if (ingresoEnRango) {
                enviosEnRango.add(envio);
            }
        }

        System.out.println("🔍 [EnvioService] Envíos filtrados en rango: " + enviosEnRango.size());
        return enviosEnRango;
    }

    @Override
    public ArrayList<Envio> obtenerEnviosDesdeFecha(LocalDateTime fechaInicio, String husoHorarioInicio) {
        // Convertir la fecha de entrada a ZonedDateTime
        Integer offsetInicio = Integer.parseInt(husoHorarioInicio);
        ZoneOffset zoneInicio = ZoneOffset.ofHours(offsetInicio);
        ZonedDateTime zonedFechaInicio = fechaInicio.atZone(zoneInicio);

        // Convertir a UTC para hacer la consulta más precisa
        ZonedDateTime fechaInicioUTC = zonedFechaInicio.withZoneSameInstant(ZoneOffset.UTC);

        // Ampliar el rango para considerar todas las zonas horarias posibles (-12 a +14
        // horas)
        // Esto asegura que no perdamos envíos debido a diferencias de zona horaria
        LocalDateTime fechaInicioConsulta = fechaInicioUTC.toLocalDateTime().minusHours(14);

        // Consulta optimizada en la BD (solo trae envíos relevantes)
        ArrayList<Envio> enviosCandidatos = envioRepository.findByFechaIngresoGreaterThanEqual(
                fechaInicioConsulta);

        // Filtrar en memoria considerando las zonas horarias reales (sobre un conjunto
        // mucho menor)
        ArrayList<Envio> enviosDesdeFecha = new ArrayList<>();
        for (Envio envio : enviosCandidatos) {
            ZonedDateTime zonedFechaIngreso = obtenerZonedFechaIngreso(envio);

            // Un envío está incluido si su fecha de ingreso es igual o posterior a la fecha
            // de inicio
            boolean ingresoDesdeFecha = !zonedFechaIngreso.isBefore(zonedFechaInicio);

            if (ingresoDesdeFecha) {
                enviosDesdeFecha.add(envio);
            }
        }

        return enviosDesdeFecha;
    }

    @Override
    public ArrayList<Envio> obtenerEnviosEnRangoConPartes(LocalDateTime fechaInicio, String husoHorarioInicio,
            LocalDateTime fechaFin, String husoHorarioFin) {
        // Convertir las fechas de entrada a ZonedDateTime
        Integer offsetInicio = Integer.parseInt(husoHorarioInicio);
        Integer offsetFin = Integer.parseInt(husoHorarioFin);
        ZoneOffset zoneInicio = ZoneOffset.ofHours(offsetInicio);
        ZoneOffset zoneFin = ZoneOffset.ofHours(offsetFin);

        ZonedDateTime zonedFechaInicio = fechaInicio.atZone(zoneInicio);
        ZonedDateTime zonedFechaFin = fechaFin.atZone(zoneFin);

        // Convertir a UTC para hacer la consulta más precisa
        ZonedDateTime fechaInicioUTC = zonedFechaInicio.withZoneSameInstant(ZoneOffset.UTC);
        ZonedDateTime fechaFinUTC = zonedFechaFin.withZoneSameInstant(ZoneOffset.UTC);

        // Ampliar el rango para considerar todas las zonas horarias posibles
        LocalDateTime fechaInicioConsulta = fechaInicioUTC.toLocalDateTime().minusHours(14);
        LocalDateTime fechaFinConsulta = fechaFinUTC.toLocalDateTime().plusHours(14);

        // ⚡ Consulta con JOIN FETCH para cargar parteAsignadas de forma eficiente
        ArrayList<Envio> enviosCandidatos = envioRepository.findByFechaIngresoBetweenWithPartes(
                fechaInicioConsulta, fechaFinConsulta);

        // Filtrar en memoria considerando las zonas horarias reales
        ArrayList<Envio> enviosEnRango = new ArrayList<>();
        for (Envio envio : enviosCandidatos) {
            ZonedDateTime zonedFechaIngreso = obtenerZonedFechaIngreso(envio);

            boolean ingresoEnRango = !zonedFechaIngreso.isBefore(zonedFechaInicio) &&
                    !zonedFechaIngreso.isAfter(zonedFechaFin);

            if (ingresoEnRango) {
                enviosEnRango.add(envio);
            }
        }

        return enviosEnRango;
    }

    @Override
    public ArrayList<Envio> obtenerEnviosDesdeFechaConPartes(LocalDateTime fechaInicio, String husoHorarioInicio) {
        // Convertir la fecha de entrada a ZonedDateTime
        Integer offsetInicio = Integer.parseInt(husoHorarioInicio);
        ZoneOffset zoneInicio = ZoneOffset.ofHours(offsetInicio);
        ZonedDateTime zonedFechaInicio = fechaInicio.atZone(zoneInicio);

        // Convertir a UTC para hacer la consulta más precisa
        ZonedDateTime fechaInicioUTC = zonedFechaInicio.withZoneSameInstant(ZoneOffset.UTC);

        // Ampliar el rango para considerar todas las zonas horarias posibles
        LocalDateTime fechaInicioConsulta = fechaInicioUTC.toLocalDateTime().minusHours(14);

        // ⚡ Consulta con JOIN FETCH para cargar parteAsignadas de forma eficiente
        ArrayList<Envio> enviosCandidatos = envioRepository.findByFechaIngresoGreaterThanEqualWithPartes(
                fechaInicioConsulta);

        // Filtrar en memoria considerando las zonas horarias reales
        ArrayList<Envio> enviosDesdeFecha = new ArrayList<>();
        for (Envio envio : enviosCandidatos) {
            ZonedDateTime zonedFechaIngreso = obtenerZonedFechaIngreso(envio);

            boolean ingresoDesdeFecha = !zonedFechaIngreso.isBefore(zonedFechaInicio);

            if (ingresoDesdeFecha) {
                enviosDesdeFecha.add(envio);
            }
        }

        return enviosDesdeFecha;
    }

    /**
     * Método auxiliar para obtener el ZonedDateTime de la fecha de ingreso del
     * envío.
     * Si no está cargado, lo carga manualmente.
     */
    private ZonedDateTime obtenerZonedFechaIngreso(Envio envio) {
        if (envio.getZonedFechaIngreso() != null) {
            return envio.getZonedFechaIngreso();
        }
        // Si no está cargado, cargarlo manualmente
        Integer offsetDestino = Integer.parseInt(envio.getHusoHorarioDestino());
        ZoneOffset zoneDestino = ZoneOffset.ofHours(offsetDestino);
        return envio.getFechaIngreso().atZone(zoneDestino);
    }

    /**
     * ⚡ OPTIMIZADO: Obtiene solo envíos que tienen partes asignadas.
     * Usa dos queries separadas para evitar MultipleBagFetchException de Hibernate.
     * Query 1: Carga envíos con parteAsignadas y aeropuertos
     * Query 2: Carga vuelosRuta para las partes asignadas
     */
    @Override
    public List<Envio> obtenerEnviosConPartesAsignadas() {
        // Query 1: Obtener envíos con partes asignadas y aeropuertos
        List<Envio> envios = envioRepository.findEnviosConPartesAsignadas();
        
        if (envios.isEmpty()) {
            return envios;
        }
        
        // Recopilar IDs de envíos para la segunda query
        List<Integer> envioIds = envios.stream()
                .map(Envio::getId)
                .collect(Collectors.toList());
        
        // Query 2: Cargar vuelosRuta para todas las partes de estos envíos
        List<ParteAsignada> partesConVuelos = envioRepository.findPartesConVuelosByEnvioIds(envioIds);
        
        // Crear mapa de parteId -> parteConVuelos para actualizar las referencias
        Map<Integer, ParteAsignada> partesMap = partesConVuelos.stream()
                .collect(Collectors.toMap(ParteAsignada::getId, p -> p, (p1, p2) -> p1));
        
        // Actualizar las partes en los envíos con los vuelosRuta cargados
        for (Envio envio : envios) {
            if (envio.getParteAsignadas() != null) {
                for (ParteAsignada parte : envio.getParteAsignadas()) {
                    ParteAsignada parteConVuelos = partesMap.get(parte.getId());
                    if (parteConVuelos != null && parteConVuelos.getVuelosRuta() != null) {
                        parte.setVuelosRuta(parteConVuelos.getVuelosRuta());
                    }
                }
            }
        }
        
        return envios;
    }
}

package pe.edu.pucp.morapack.services.servicesImp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.pucp.morapack.models.Envio;
import pe.edu.pucp.morapack.models.ParteAsignada;
import pe.edu.pucp.morapack.models.ParteAsignadaPlanDeVuelo;
import pe.edu.pucp.morapack.models.PlanDeVuelo;
import pe.edu.pucp.morapack.repository.EnvioRepository;
import pe.edu.pucp.morapack.services.EnvioService;

import java.time.LocalDate;
import java.time.ZonedDateTime;
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
     * Determina en qué aeropuerto está físicamente una parte asignada basándose en su ruta de vuelos
     * @param parte La parte asignada
     * @param ahora La hora actual
     * @return El ID del aeropuerto donde está físicamente la parte, o null si está entregada
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
                    "ciudad", envio.getAeropuertoDestino().getCiudad()
                ));
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
}

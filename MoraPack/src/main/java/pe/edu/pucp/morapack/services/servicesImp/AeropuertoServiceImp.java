package pe.edu.pucp.morapack.services.servicesImp;

import lombok.RequiredArgsConstructor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.pucp.morapack.models.Aeropuerto;
import pe.edu.pucp.morapack.repository.AeropuertoRepository;
import pe.edu.pucp.morapack.services.AeropuertoService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AeropuertoServiceImp implements AeropuertoService {
    private static final Logger logger = LoggerFactory.getLogger(AeropuertoServiceImp.class);

    @Autowired
    private final AeropuertoRepository aeropuertoRepository;

    public Aeropuerto insertarAeropuerto(Aeropuerto aeropuerto) {
        return aeropuertoRepository.save(aeropuerto);
    }

    public ArrayList<Aeropuerto> insertarListaAeropuertos(ArrayList<Aeropuerto> aeropuertos) {
        return (ArrayList<Aeropuerto>) aeropuertoRepository.saveAll(aeropuertos);
    }

    public Optional<Aeropuerto> obtenerAeropuertoPorId(Integer id) {
        return aeropuertoRepository.findById(id);
    }

    public Optional<Aeropuerto> obtenerAeropuertoPorCodigo(String codigo) {
        return aeropuertoRepository.findAeropuertoByCodigo(codigo);
    }

    public ArrayList<Aeropuerto> obtenerTodosAeropuertos() {
        return (ArrayList<Aeropuerto>) aeropuertoRepository.findAll();
    }

    public void aumentarProductosEnAlmacen(Integer cantProductos) {

    }

    /**
     * ⚡ OPTIMIZADO: Obtiene múltiples aeropuertos por IDs en una sola consulta.
     */
    public List<Aeropuerto> obtenerAeropuertosPorIds(List<Integer> aeropuertoIds) {
        if (aeropuertoIds == null || aeropuertoIds.isEmpty()) {
            return new ArrayList<Aeropuerto>();
        }
        ArrayList<Aeropuerto> resultado = new ArrayList<>();
        aeropuertoRepository.findAllById(aeropuertoIds).forEach(resultado::add);
        return resultado;
    }

    /**
     * ⚡ Operación atómica: Incrementa la capacidad ocupada del aeropuerto.
     * Evita condiciones de carrera al ejecutarse directamente en la base de datos.
     */
    @Override
    public void incrementarCapacidadOcupada(Integer id, Integer cantidad) {
        if (id == null || cantidad == null || cantidad <= 0) {
            return;
        }
        try {
            aeropuertoRepository.incrementarCapacidadOcupada(id, cantidad);
        } catch (Exception e) {
            System.err.printf("❌ Error al incrementar capacidad del aeropuerto %d: %s%n", id, e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * ⚡ Operación atómica: Decrementa la capacidad ocupada del aeropuerto.
     * Evita condiciones de carrera al ejecutarse directamente en la base de datos.
     */
    @Override
    public void decrementarCapacidadOcupada(Integer id, Integer cantidad) {
        if (id == null || cantidad == null || cantidad <= 0) {
            return;
        }
        try {
            aeropuertoRepository.decrementarCapacidadOcupada(id, cantidad);
        } catch (Exception e) {
            System.err.printf("❌ Error al decrementar capacidad del aeropuerto %d: %s%n", id, e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Disminuye la capacidad ocupada de un aeropuerto cuando un vuelo despega.
     *
     * @param aeropuertoId ID del aeropuerto
     * @param cantidad Cantidad a disminuir (debe ser positiva)
     * @return true si se actualizó correctamente, false en caso contrario
     */
    @Override
    @Transactional
    public boolean disminuirCapacidadOcupada(Integer aeropuertoId, Integer cantidad) {
        try {
            if (aeropuertoId == null || cantidad == null || cantidad <= 0) {
                logger.warn("⚠️ Parámetros inválidos para disminuir capacidad: aeropuertoId={}, cantidad={}",
                    aeropuertoId, cantidad);
                return false;
            }

            Optional<Aeropuerto> aeropuertoOpt = aeropuertoRepository.findById(aeropuertoId);
            if (aeropuertoOpt.isEmpty()) {
                logger.warn("⚠️ Aeropuerto no encontrado: {}", aeropuertoId);
                return false;
            }

            Aeropuerto aeropuerto = aeropuertoOpt.get();
            Integer capacidadActual = aeropuerto.getCapacidadOcupada() != null
                ? aeropuerto.getCapacidadOcupada()
                : 0;

            // Calcular nueva capacidad (no puede ser negativa)
            Integer nuevaCapacidad = Math.max(0, capacidadActual - cantidad);

            // Actualizar capacidad ocupada
            aeropuerto.setCapacidadOcupada(nuevaCapacidad);
            aeropuertoRepository.save(aeropuerto);

            logger.info("✅ Capacidad disminuida en aeropuerto {}: {} -> {} (-{})",
                aeropuertoId, capacidadActual, nuevaCapacidad, cantidad);

            return true;
        } catch (Exception e) {
            logger.error("❌ Error al disminuir capacidad del aeropuerto {}: {}",
                aeropuertoId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Aumenta la capacidad ocupada de un aeropuerto cuando un vuelo aterriza.
     * ⚡ Usa operación atómica para evitar condiciones de carrera y limitar automáticamente a capacidad máxima.
     *
     * @param aeropuertoId ID del aeropuerto
     * @param cantidad Cantidad a aumentar (debe ser positiva)
     * @return true si se actualizó correctamente, false en caso contrario
     */
    @Override
    @Transactional
    public boolean aumentarCapacidadOcupada(Integer aeropuertoId, Integer cantidad) {
        try {
            if (aeropuertoId == null || cantidad == null || cantidad <= 0) {
                logger.warn("⚠️ Parámetros inválidos para aumentar capacidad: aeropuertoId={}, cantidad={}",
                    aeropuertoId, cantidad);
                return false;
            }

            Optional<Aeropuerto> aeropuertoOpt = aeropuertoRepository.findById(aeropuertoId);
            if (aeropuertoOpt.isEmpty()) {
                logger.warn("⚠️ Aeropuerto no encontrado: {}", aeropuertoId);
                return false;
            }

            Aeropuerto aeropuerto = aeropuertoOpt.get();
            Integer capacidadActual = aeropuerto.getCapacidadOcupada() != null
                ? aeropuerto.getCapacidadOcupada()
                : 0;

            // ⚡ CRÍTICO: Usar operación atómica que limita automáticamente a capacidad máxima
            // Esto evita condiciones de carrera cuando múltiples vuelos aterrizan simultáneamente
            aeropuertoRepository.incrementarCapacidadOcupada(aeropuertoId, cantidad);

            // Recargar el aeropuerto para obtener el valor actualizado
            aeropuertoOpt = aeropuertoRepository.findById(aeropuertoId);
            if (aeropuertoOpt.isPresent()) {
                Integer capacidadFinal = aeropuertoOpt.get().getCapacidadOcupada() != null
                    ? aeropuertoOpt.get().getCapacidadOcupada()
                    : 0;

                // Verificar si se limitó a la capacidad máxima
                Integer capacidadMaxima = aeropuerto.getCapacidadMaxima();
                if (capacidadMaxima != null && capacidadMaxima > 0 && capacidadFinal >= capacidadMaxima) {
                    logger.warn("⚠️ Capacidad limitada a máxima en aeropuerto {}: {} (intentó aumentar +{})",
                        aeropuertoId, capacidadMaxima, cantidad);
                } else {
                    logger.info("✅ Capacidad aumentada en aeropuerto {}: {} -> {} (+{})",
                        aeropuertoId, capacidadActual, capacidadFinal, cantidad);
                }
            }

            return true;
        } catch (Exception e) {
            logger.error("❌ Error al aumentar capacidad del aeropuerto {}: {}",
                aeropuertoId, e.getMessage(), e);
            return false;
        }
    }
}

package pe.edu.pucp.morapack.services.servicesImp;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pe.edu.pucp.morapack.models.Aeropuerto;
import pe.edu.pucp.morapack.repository.AeropuertoRepository;
import pe.edu.pucp.morapack.services.AeropuertoService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AeropuertoServiceImp implements AeropuertoService {
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
}

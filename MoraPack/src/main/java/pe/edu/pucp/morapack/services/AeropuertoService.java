package pe.edu.pucp.morapack.services;

import pe.edu.pucp.morapack.models.Aeropuerto;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public interface AeropuertoService {
    Aeropuerto insertarAeropuerto(Aeropuerto aeropuerto);

    ArrayList<Aeropuerto> insertarListaAeropuertos(ArrayList<Aeropuerto> aeropuertos);

    Optional<Aeropuerto> obtenerAeropuertoPorId(Integer id);

    Optional<Aeropuerto> obtenerAeropuertoPorCodigo(String codigo);

    ArrayList<Aeropuerto> obtenerTodosAeropuertos();

    /**
     * ⚡ OPTIMIZADO: Obtiene múltiples aeropuertos por IDs en una sola consulta.
     */
    List<Aeropuerto> obtenerAeropuertosPorIds(List<Integer> aeropuertoIds);

    /**
     * ⚡ Operación atómica: Incrementa la capacidad ocupada del aeropuerto.
     * Evita condiciones de carrera al ejecutarse directamente en la base de datos.
     *
     * @param id ID del aeropuerto
     * @param cantidad Cantidad a incrementar
     */
    void incrementarCapacidadOcupada(Integer id, Integer cantidad);

    /**
     * ⚡ Operación atómica: Decrementa la capacidad ocupada del aeropuerto.
     * Evita condiciones de carrera al ejecutarse directamente en la base de datos.
     *
     * @param id ID del aeropuerto
     * @param cantidad Cantidad a decrementar
     */
    void decrementarCapacidadOcupada(Integer id, Integer cantidad);
}

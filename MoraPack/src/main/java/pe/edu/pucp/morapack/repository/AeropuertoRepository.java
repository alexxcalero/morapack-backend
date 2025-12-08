package pe.edu.pucp.morapack.repository;

import pe.edu.pucp.morapack.models.Aeropuerto;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface AeropuertoRepository extends CrudRepository<Aeropuerto, Integer> {
    Optional<Aeropuerto> findAeropuertoByCodigo(String codigo);

    /**
     * ⚡ Operación atómica: Incrementa la capacidad ocupada del aeropuerto.
     * Evita condiciones de carrera al ejecutarse directamente en la base de datos.
     * La capacidad no excederá la capacidad máxima (se limita en el SQL).
     */
    @Modifying
    @Transactional
    @Query("UPDATE Aeropuerto a SET a.capacidadOcupada = " +
            "CASE " +
            "  WHEN (COALESCE(a.capacidadOcupada, 0) + :cantidad) > a.capacidadMaxima THEN a.capacidadMaxima " +
            "  ELSE COALESCE(a.capacidadOcupada, 0) + :cantidad " +
            "END " +
            "WHERE a.id = :id")
    void incrementarCapacidadOcupada(@Param("id") Integer id, @Param("cantidad") Integer cantidad);

    /**
     * ⚡ Operación atómica: Decrementa la capacidad ocupada del aeropuerto.
     * Evita condiciones de carrera al ejecutarse directamente en la base de datos.
     * La capacidad no será negativa (se limita a 0 en el SQL).
     */
    @Modifying
    @Transactional
    @Query("UPDATE Aeropuerto a SET a.capacidadOcupada = " +
            "GREATEST(0, COALESCE(a.capacidadOcupada, 0) - :cantidad) " +
            "WHERE a.id = :id")
    void decrementarCapacidadOcupada(@Param("id") Integer id, @Param("cantidad") Integer cantidad);
}

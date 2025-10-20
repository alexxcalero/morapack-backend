package pe.edu.pucp.morapack.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pe.edu.pucp.morapack.models.Aeropuerto;

import java.util.Optional;

@Repository
public interface AeropuertoRepository extends JpaRepository<Aeropuerto, Integer> {
    Optional<Aeropuerto> findByCodigo(String codigo);
}

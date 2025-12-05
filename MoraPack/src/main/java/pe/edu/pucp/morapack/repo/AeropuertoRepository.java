package pe.edu.pucp.morapack.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.edu.pucp.morapack.model.Aeropuerto;

import java.util.Optional;

public interface AeropuertoRepository extends JpaRepository<Aeropuerto, Integer> {

    Optional<Aeropuerto> findByCodigoIataIgnoreCase(String codigoIata);
}

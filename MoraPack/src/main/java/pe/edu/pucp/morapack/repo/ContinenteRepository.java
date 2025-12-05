package pe.edu.pucp.morapack.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pe.edu.pucp.morapack.model.Continente;
import java.util.Optional;

@Repository
public interface ContinenteRepository extends JpaRepository<Continente, Integer> {

    Optional<Continente> findByNombre(String nombre);
}

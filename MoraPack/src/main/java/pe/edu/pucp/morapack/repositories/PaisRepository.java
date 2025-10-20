package pe.edu.pucp.morapack.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pe.edu.pucp.morapack.models.Pais;

@Repository
public interface PaisRepository extends JpaRepository<Pais, Integer> {
}

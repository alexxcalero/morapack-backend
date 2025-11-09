package pe.edu.pucp.morapack.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pe.edu.pucp.morapack.models.ParteAsignadaPlanDeVuelo;

@Repository
public interface ParteAsignadaPlanDeVueloRepository extends JpaRepository<ParteAsignadaPlanDeVuelo, Integer> {
}

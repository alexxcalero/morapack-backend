package pe.edu.pucp.morapack.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pe.edu.pucp.morapack.model.Ciudad;

import java.util.List;

@Repository
public interface CiudadRepository extends JpaRepository<Ciudad, Integer> {

    List<Ciudad> findByNombrePais(String nombrePais);

    // opcional: ignorando mayúsculas/minúsculas
    List<Ciudad> findByNombrePaisIgnoreCase(String nombrePais);
}

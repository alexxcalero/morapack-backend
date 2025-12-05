// src/main/java/pe/edu/pucp/morapack/repo/EnvioVueloRepository.java
package pe.edu.pucp.morapack.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.edu.pucp.morapack.model.Envio;
import pe.edu.pucp.morapack.model.EnvioVuelo;

import java.util.List;

public interface EnvioVueloRepository extends JpaRepository<EnvioVuelo, Long> {

    List<EnvioVuelo> findByEnvioOrderByOrdenTramoAsc(Envio envio);
}

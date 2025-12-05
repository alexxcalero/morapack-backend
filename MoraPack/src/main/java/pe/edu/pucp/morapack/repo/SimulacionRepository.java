package pe.edu.pucp.morapack.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import pe.edu.pucp.morapack.model.EstadoSimulacion;
import pe.edu.pucp.morapack.model.Simulacion;
import pe.edu.pucp.morapack.model.TipoSimulacion;

import java.util.Optional;

public interface SimulacionRepository extends JpaRepository<Simulacion, Long> {

    @Modifying
    @Query("UPDATE Simulacion s SET s.esActiva = false WHERE s.tipoSimulacion = :tipo")
    void desactivarSimulacionesPorTipo(TipoSimulacion tipo);

    Optional<Simulacion> findFirstByTipoSimulacionAndEsActivaOrderByIdDesc(
            TipoSimulacion tipoSimulacion,
            boolean esActiva
    );

    Optional<Simulacion> findFirstByTipoSimulacionAndEstadoOrderByIdDesc(
            TipoSimulacion tipoSimulacion, EstadoSimulacion estado);
}

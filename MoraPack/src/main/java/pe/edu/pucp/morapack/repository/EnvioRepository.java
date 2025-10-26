package pe.edu.pucp.morapack.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pe.edu.pucp.morapack.models.Envio;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;

@Repository
public interface EnvioRepository extends JpaRepository<Envio, Integer> {
    @Query("SELECT e FROM Envio e WHERE FUNCTION('DATE', e.fechaIngreso) = :fecha")
    ArrayList<Envio> findByFechaIngreso(LocalDate fecha);
}

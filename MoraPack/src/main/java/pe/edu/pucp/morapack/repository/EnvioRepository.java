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

    @Query("SELECT e FROM Envio e WHERE e.aeropuertoOrigen.id = :idAeropuerto")
    ArrayList<Envio> findByAeropuertoOrigen(@Param("idAeropuerto") Integer idAeropuerto);

    @Query("SELECT e FROM Envio e WHERE e.aeropuertoDestino.id = :idAeropuerto")
    ArrayList<Envio> findByAeropuertoDestino(@Param("idAeropuerto") Integer idAeropuerto);

    /**
     * Obtiene envíos cuya fecha de ingreso está dentro del rango especificado.
     * Esta consulta es optimizada para trabajar con índices en fechaIngreso.
     */
    @Query("SELECT e FROM Envio e WHERE e.fechaIngreso >= :fechaInicio AND e.fechaIngreso <= :fechaFin")
    ArrayList<Envio> findByFechaIngresoBetween(
            @Param("fechaInicio") LocalDateTime fechaInicio,
            @Param("fechaFin") LocalDateTime fechaFin);

    /**
     * Obtiene envíos cuya fecha de ingreso es igual o posterior a la fecha
     * especificada.
     * Esta consulta es optimizada para trabajar con índices en fechaIngreso.
     */
    @Query("SELECT e FROM Envio e WHERE e.fechaIngreso >= :fechaInicio")
    ArrayList<Envio> findByFechaIngresoGreaterThanEqual(@Param("fechaInicio") LocalDateTime fechaInicio);
}

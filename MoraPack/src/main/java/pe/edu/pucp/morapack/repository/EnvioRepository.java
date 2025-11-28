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

        /**
         * Obtiene envíos CON sus parteAsignadas para inicialización del planificador.
         * Usa JOIN FETCH para cargar relaciones en una sola query.
         * ⚠️ Solo usar cuando realmente se necesiten las partes asignadas.
         */
        @Query("SELECT DISTINCT e FROM Envio e LEFT JOIN FETCH e.parteAsignadas WHERE e.fechaIngreso >= :fechaInicio AND e.fechaIngreso <= :fechaFin")
        ArrayList<Envio> findByFechaIngresoBetweenWithPartes(
                        @Param("fechaInicio") LocalDateTime fechaInicio,
                        @Param("fechaFin") LocalDateTime fechaFin);

        /**
         * Obtiene envíos CON sus parteAsignadas desde una fecha específica.
         * Usa JOIN FETCH para cargar relaciones en una sola query.
         * ⚠️ Solo usar cuando realmente se necesiten las partes asignadas.
         */
        @Query("SELECT DISTINCT e FROM Envio e LEFT JOIN FETCH e.parteAsignadas WHERE e.fechaIngreso >= :fechaInicio")
        ArrayList<Envio> findByFechaIngresoGreaterThanEqualWithPartes(@Param("fechaInicio") LocalDateTime fechaInicio);

        /**
         * ⚡ OPTIMIZADO: Obtiene SOLO envíos que tienen partes asignadas (pendientes de
         * entrega).
         * Usa INNER JOIN FETCH para traer solo envíos con partes y cargar las
         * relaciones.
         * Esta query es mucho más eficiente que cargar todos los 43K+ envíos.
         */
        @Query("SELECT DISTINCT e FROM Envio e " +
                        "INNER JOIN FETCH e.parteAsignadas pa " +
                        "LEFT JOIN FETCH pa.aeropuertoOrigen " +
                        "LEFT JOIN FETCH e.aeropuertoDestino " +
                        "LEFT JOIN FETCH pa.vuelosRuta vr " +
                        "LEFT JOIN FETCH vr.planDeVuelo")
        ArrayList<Envio> findEnviosConPartesAsignadas();

        /**
         * ⚡ CONTEOS RÁPIDOS: Cuenta total de envíos sin cargar entidades
         */
        @Query("SELECT COUNT(e) FROM Envio e")
        long countTotalEnvios();

        /**
         * ⚡ CONTEOS RÁPIDOS: Cuenta envíos sin partes asignadas (sin planificar)
         */
        @Query("SELECT COUNT(DISTINCT e) FROM Envio e WHERE e.parteAsignadas IS EMPTY")
        long countEnviosSinPlanificar();

        /**
         * ⚡ CONTEOS RÁPIDOS: Cuenta envíos con al menos una parte asignada
         */
        @Query("SELECT COUNT(DISTINCT e) FROM Envio e WHERE e.parteAsignadas IS NOT EMPTY")
        long countEnviosConPartes();
}

package pe.edu.pucp.morapack.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import pe.edu.pucp.morapack.models.Envio;
import pe.edu.pucp.morapack.models.Envio.EstadoEnvio;
import pe.edu.pucp.morapack.models.ParteAsignada;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
public interface EnvioRepository extends JpaRepository<Envio, Integer> {

    @Query("SELECT e FROM Envio e WHERE FUNCTION('DATE', e.fechaIngreso) = :fecha")
    ArrayList<Envio> findByFechaIngreso(@Param("fecha") LocalDate fecha);

    @Query("SELECT e FROM Envio e WHERE e.aeropuertoOrigen.id = :idAeropuerto")
    ArrayList<Envio> findByAeropuertoOrigen(@Param("idAeropuerto") Integer idAeropuerto);

    @Query("SELECT e FROM Envio e WHERE e.aeropuertoDestino.id = :idAeropuerto")
    ArrayList<Envio> findByAeropuertoDestino(@Param("idAeropuerto") Integer idAeropuerto);

    /**
     * Envios por rango de fecha de ingreso.
     */
    @Query("SELECT e FROM Envio e " +
           "WHERE e.fechaIngreso >= :fechaInicio AND e.fechaIngreso <= :fechaFin")
    ArrayList<Envio> findByFechaIngresoBetween(
            @Param("fechaInicio") LocalDateTime fechaInicio,
            @Param("fechaFin") LocalDateTime fechaFin);

    /**
     * Envios con fechaIngreso >= fechaInicio.
     */
    @Query("SELECT e FROM Envio e WHERE e.fechaIngreso >= :fechaInicio")
    ArrayList<Envio> findByFechaIngresoGreaterThanEqual(
            @Param("fechaInicio") LocalDateTime fechaInicio);

    /**
     * Envios con partes asignadas (fetch parts) para inicialización del planificador.
     */
    @Query("SELECT DISTINCT e FROM Envio e " +
           "LEFT JOIN FETCH e.parteAsignadas " +
           "WHERE e.fechaIngreso >= :fechaInicio AND e.fechaIngreso <= :fechaFin")
    ArrayList<Envio> findByFechaIngresoBetweenWithPartes(
            @Param("fechaInicio") LocalDateTime fechaInicio,
            @Param("fechaFin") LocalDateTime fechaFin);

    /**
     * Envios con partes asignadas desde fechaInicio (fetch parts).
     */
    @Query("SELECT DISTINCT e FROM Envio e " +
           "LEFT JOIN FETCH e.parteAsignadas " +
           "WHERE e.fechaIngreso >= :fechaInicio")
    ArrayList<Envio> findByFechaIngresoGreaterThanEqualWithPartes(
            @Param("fechaInicio") LocalDateTime fechaInicio);

    /**
     * Envios que tienen partes asignadas (pendientes de entrega).
     */
    @Query("SELECT DISTINCT e FROM Envio e " +
           "INNER JOIN FETCH e.parteAsignadas pa " +
           "LEFT JOIN FETCH pa.aeropuertoOrigen " +
           "LEFT JOIN FETCH e.aeropuertoDestino")
    ArrayList<Envio> findEnviosConPartesAsignadas();

    /**
     * Envios con partes asignadas NO entregadas, limitado a N registros (native).
     */
    @Query(value = "SELECT DISTINCT e.* FROM envio e " +
                   "INNER JOIN parte_asignada pa ON pa.id_envio = e.id " +
                   "WHERE pa.entregado IS NULL OR pa.entregado = false " +
                   "ORDER BY e.fecha_ingreso DESC " +
                   "LIMIT :limite",
           nativeQuery = true)
    List<Envio> findEnviosConPartesAsignadasLimitado(@Param("limite") int limite);

    /**
     * Partes asignadas básicas (sin vuelos) por IDs de envío.
     */
    @Query("SELECT DISTINCT pa FROM ParteAsignada pa " +
           "LEFT JOIN FETCH pa.aeropuertoOrigen " +
           "WHERE pa.envio.id IN :envioIds " +
           "AND (pa.entregado IS NULL OR pa.entregado = false)")
    List<ParteAsignada> findPartesBasicasByEnvioIds(
            @Param("envioIds") List<Integer> envioIds);

    /**
     * Partes asignadas con vuelosRuta y planDeVuelo.
     */
    @Query("SELECT DISTINCT pa FROM ParteAsignada pa " +
           "LEFT JOIN FETCH pa.vuelosRuta vr " +
           "LEFT JOIN FETCH vr.planDeVuelo " +
           "WHERE pa.envio.id IN :envioIds")
    ArrayList<ParteAsignada> findPartesConVuelosByEnvioIds(
            @Param("envioIds") List<Integer> envioIds);

    /**
     * Cuenta envíos por estado.
     */
    @Query("SELECT COUNT(e) FROM Envio e WHERE e.estado = :estado")
    long countByEstado(@Param("estado") EstadoEnvio estado);

    /**
     * Cuenta envíos con estado NULL.
     */
    @Query("SELECT COUNT(e) FROM Envio e WHERE e.estado IS NULL")
    long countByEstadoIsNull();

    /**
     * Búsqueda por ID exacto (native).
     */
    @Query(value = "SELECT DISTINCT e.* FROM envio e " +
                   "LEFT JOIN parte_asignada pa ON pa.id_envio = e.id " +
                   "WHERE e.id = :id " +
                   "LIMIT :limite",
           nativeQuery = true)
    List<Envio> buscarPorIdExacto(
            @Param("id") Integer id,
            @Param("limite") int limite);

    /**
     * Búsqueda por patrón parcial de ID (native).
     */
    @Query(value = "SELECT DISTINCT e.* FROM envio e " +
                   "LEFT JOIN parte_asignada pa ON pa.id_envio = e.id " +
                   "WHERE CAST(e.id AS CHAR) LIKE :patron " +
                   "LIMIT :limite",
           nativeQuery = true)
    List<Envio> buscarPorIdParcial(
            @Param("patron") String patron,
            @Param("limite") int limite);

    /**
     * Envíos sin estado (se filtran por fecha en memoria).
     */
    @Query("SELECT e FROM Envio e WHERE e.estado IS NULL")
    ArrayList<Envio> findByEstadoIsNull();

    // Muestra de 100 envíos más recientes
    List<Envio> findTop100ByOrderByIdDesc();

    // Últimos 100 envíos en estados de interés
    List<Envio> findTop100ByEstadoInOrderByIdDesc(List<EstadoEnvio> estados);
}

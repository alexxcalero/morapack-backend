package pe.edu.pucp.morapack.services.servicesImp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import pe.edu.pucp.morapack.dtos.PlanDeVueloResponse;
import pe.edu.pucp.morapack.models.PlanDeVuelo;
import pe.edu.pucp.morapack.repository.AeropuertoRepository;
import pe.edu.pucp.morapack.repository.PlanDeVueloRepository;
import pe.edu.pucp.morapack.services.PlanDeVueloService;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PlanDeVueloServiceImp implements PlanDeVueloService {
    private final PlanDeVueloRepository planDeVueloRepository;
    private final AeropuertoRepository aeropuertoRepository;

    public List<PlanDeVuelo> obtenerVuelosIniciales(LocalDateTime fechaInicioUTC, int limit) {
        return planDeVueloRepository.findProximosDesde(fechaInicioUTC, PageRequest.of(0, limit));
    }

    @Override
    public PlanDeVuelo insertarPlanDeVuelo(PlanDeVuelo planDeVuelo) {
        return planDeVueloRepository.save(planDeVuelo);
    }

    @Override
    public ArrayList<PlanDeVuelo> insertarListaPlanesDeVuelo(ArrayList<PlanDeVuelo> planesDeVuelo) {
        return (ArrayList<PlanDeVuelo>) planDeVueloRepository.saveAll(planesDeVuelo);
    }

    @Override
    public Optional<PlanDeVuelo> obtenerPlanDeVueloPorId(Integer id) {
        return planDeVueloRepository.findById(id);
    }

    @Override
    public ArrayList<PlanDeVueloResponse> obtenerPlanesDeVuelo() {
        return planDeVueloRepository.queryPlanDeVueloWithAeropuerto();
    }

    @Override
    public ArrayList<PlanDeVuelo> obtenerListaPlanesDeVuelo() {
        return (ArrayList<PlanDeVuelo>) planDeVueloRepository.findAll();
    }

    @Override
    public Integer planAcabaAlSiguienteDia(String tInicio, String tFin, String husoOrigen, String husoDestino,
            Integer aa, Integer mm, Integer dd) {
        Integer cantidad = 0;
        LocalTime horaInicio = LocalTime.parse(tInicio);
        LocalTime horaFin = LocalTime.parse(tFin);

        Integer offsetOrigen = Integer.parseInt(husoOrigen);
        Integer offsetDestino = Integer.parseInt(husoDestino);

        ZoneOffset zoneOrigen = ZoneOffset.ofHours(offsetOrigen);
        ZoneOffset zoneDestino = ZoneOffset.ofHours(offsetDestino);

        ZonedDateTime zonedHoraInicio = ZonedDateTime.of(aa, mm, dd, horaInicio.getHour(), horaInicio.getMinute(), 0, 0,
                zoneOrigen);
        ZonedDateTime convertedHoraInicio = zonedHoraInicio.withZoneSameInstant(zoneDestino);

        if (!convertedHoraInicio.toLocalDate().isEqual(zonedHoraInicio.toLocalDate()))
            cantidad++;

        if (convertedHoraInicio.toLocalTime().isAfter(horaFin))
            cantidad++;

        return cantidad;
    }

    @Override
    public ArrayList<PlanDeVuelo> obtenerVuelosEnRango(LocalDateTime fechaInicio, String husoHorarioInicio,
            LocalDateTime fechaFin, String husoHorarioFin) {
        // Convertir las fechas de entrada a ZonedDateTime
        Integer offsetInicio = Integer.parseInt(husoHorarioInicio);
        Integer offsetFin = Integer.parseInt(husoHorarioFin);
        ZoneOffset zoneInicio = ZoneOffset.ofHours(offsetInicio);
        ZoneOffset zoneFin = ZoneOffset.ofHours(offsetFin);

        ZonedDateTime zonedFechaInicio = fechaInicio.atZone(zoneInicio);
        ZonedDateTime zonedFechaFin = fechaFin.atZone(zoneFin);

        // Convertir a UTC para hacer la consulta más precisa
        ZonedDateTime fechaInicioUTC = zonedFechaInicio.withZoneSameInstant(ZoneOffset.UTC);
        ZonedDateTime fechaFinUTC = zonedFechaFin.withZoneSameInstant(ZoneOffset.UTC);

        // Ampliar el rango para considerar todas las zonas horarias posibles (-12 a +14
        // horas)
        // Esto asegura que no perdamos vuelos debido a diferencias de zona horaria
        LocalDateTime fechaInicioConsulta = fechaInicioUTC.toLocalDateTime().minusHours(14);
        LocalDateTime fechaFinConsulta = fechaFinUTC.toLocalDateTime().plusHours(14);

        // Consulta optimizada en la BD (solo trae vuelos relevantes)
        ArrayList<PlanDeVuelo> vuelosCandidatos = planDeVueloRepository.findByHoraOrigenBetween(
                fechaInicioConsulta, fechaFinConsulta);

        // Filtrar en memoria considerando las zonas horarias reales (sobre un conjunto
        // mucho menor)
        ArrayList<PlanDeVuelo> vuelosEnRango = new ArrayList<>();
        for (PlanDeVuelo vuelo : vuelosCandidatos) {
            ZonedDateTime zonedHoraOrigen = obtenerZonedHoraOrigen(vuelo);

            // Un vuelo está en el rango si su hora de origen (despegue) está dentro del
            // rango
            boolean origenEnRango = !zonedHoraOrigen.isBefore(zonedFechaInicio) &&
                    !zonedHoraOrigen.isAfter(zonedFechaFin);

            if (origenEnRango) {
                vuelosEnRango.add(vuelo);
            }
        }

        return vuelosEnRango;
    }

    @Override
    public ArrayList<PlanDeVuelo> obtenerVuelosDesdeFecha(LocalDateTime fechaInicio, String husoHorarioInicio) {
        // Convertir la fecha de entrada a ZonedDateTime
        Integer offsetInicio = Integer.parseInt(husoHorarioInicio);
        ZoneOffset zoneInicio = ZoneOffset.ofHours(offsetInicio);
        ZonedDateTime zonedFechaInicio = fechaInicio.atZone(zoneInicio);

        // Convertir a UTC para hacer la consulta más precisa
        ZonedDateTime fechaInicioUTC = zonedFechaInicio.withZoneSameInstant(ZoneOffset.UTC);

        // Ampliar el rango para considerar todas las zonas horarias posibles (-12 a +14
        // horas)
        // Esto asegura que no perdamos vuelos debido a diferencias de zona horaria
        LocalDateTime fechaInicioConsulta = fechaInicioUTC.toLocalDateTime().minusHours(14);

        // Consulta optimizada en la BD (solo trae vuelos relevantes)
        ArrayList<PlanDeVuelo> vuelosCandidatos = planDeVueloRepository.findByHoraOrigenGreaterThanEqual(
                fechaInicioConsulta);

        // Filtrar en memoria considerando las zonas horarias reales (sobre un conjunto
        // mucho menor)
        ArrayList<PlanDeVuelo> vuelosDesdeFecha = new ArrayList<>();
        for (PlanDeVuelo vuelo : vuelosCandidatos) {
            ZonedDateTime zonedHoraOrigen = obtenerZonedHoraOrigen(vuelo);

            // Un vuelo está incluido si su hora de origen (despegue) es igual o posterior a
            // la fecha de inicio
            boolean origenDesdeFecha = !zonedHoraOrigen.isBefore(zonedFechaInicio);

            if (origenDesdeFecha) {
                vuelosDesdeFecha.add(vuelo);
            }
        }

        return vuelosDesdeFecha;
    }

    /**
     * Método auxiliar para obtener el ZonedDateTime de la hora de origen del vuelo.
     * Si no está cargado, lo carga manualmente.
     */
    private ZonedDateTime obtenerZonedHoraOrigen(PlanDeVuelo vuelo) {
        if (vuelo.getZonedHoraOrigen() != null) {
            return vuelo.getZonedHoraOrigen();
        }
        // Si no está cargado, cargarlo manualmente
        Integer offsetOrigen = Integer.parseInt(vuelo.getHusoHorarioOrigen());
        ZoneOffset zoneOrigen = ZoneOffset.ofHours(offsetOrigen);
        return vuelo.getHoraOrigen().atZone(zoneOrigen);
    }

    /**
     * Método auxiliar para obtener el ZonedDateTime de la hora de destino del
     * vuelo.
     * Si no está cargado, lo carga manualmente.
     */
    private ZonedDateTime obtenerZonedHoraDestino(PlanDeVuelo vuelo) {
        if (vuelo.getZonedHoraDestino() != null) {
            return vuelo.getZonedHoraDestino();
        }
        // Si no está cargado, cargarlo manualmente
        Integer offsetDestino = Integer.parseInt(vuelo.getHusoHorarioDestino());
        ZoneOffset zoneDestino = ZoneOffset.ofHours(offsetDestino);
        return vuelo.getHoraDestino().atZone(zoneDestino);
    }

    /**
     * ⚡ OPTIMIZADO: Obtiene múltiples planes de vuelo por IDs en una sola consulta.
     */
    @Override
    public List<PlanDeVuelo> obtenerPlanesDeVueloPorIds(List<Integer> vueloIds) {
        if (vueloIds == null || vueloIds.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(planDeVueloRepository.findAllById(vueloIds));
    }
}

package pe.edu.pucp.morapack.services.servicesImp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.pucp.morapack.dtos.PlanDeVueloResponse;
import pe.edu.pucp.morapack.models.PlanDeVuelo;
import pe.edu.pucp.morapack.repositories.AeropuertoRepository;
import pe.edu.pucp.morapack.repositories.PlanDeVueloRepository;
import pe.edu.pucp.morapack.services.PlanDeVueloService;

import java.time.*;
import java.util.ArrayList;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PlanDeVueloServiceImp implements PlanDeVueloService {
    private final PlanDeVueloRepository planDeVueloRepository;
    private final AeropuertoRepository aeropuertoRepository;

    @Override
    public PlanDeVuelo insertarPlanDeVuelo(PlanDeVuelo planDeVuelo) {
        return planDeVueloRepository.save(planDeVuelo);
    }

    @Override
    public ArrayList<PlanDeVuelo> insertarListaPlanesDeVuelo(ArrayList<PlanDeVuelo> planesDeVuelo) {
        return (ArrayList<PlanDeVuelo>)planDeVueloRepository.saveAll(planesDeVuelo);
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
        return (ArrayList<PlanDeVuelo>)planDeVueloRepository.findAll();
    }

    @Override
    public Integer planAcabaAlSiguienteDia(String tInicio, String tFin, String husoOrigen, String husoDestino, Integer aa, Integer mm, Integer dd) {
        Integer cantidad = 0;
        LocalTime horaInicio = LocalTime.parse(tInicio);
        LocalTime horaFin = LocalTime.parse(tFin);

        Integer offsetOrigen = Integer.parseInt(husoOrigen);
        Integer offsetDestino = Integer.parseInt(husoDestino);

        ZoneOffset zoneOrigen = ZoneOffset.ofHours(offsetOrigen);
        ZoneOffset zoneDestino = ZoneOffset.ofHours(offsetDestino);

        ZonedDateTime zonedHoraInicio = ZonedDateTime.of(aa, mm, dd, horaInicio.getHour(), horaInicio.getMinute(), 0, 0, zoneOrigen);
        ZonedDateTime convertedHoraInicio = zonedHoraInicio.withZoneSameInstant(zoneDestino);

        if(!convertedHoraInicio.toLocalDate().isEqual(zonedHoraInicio.toLocalDate()))
            cantidad++;

        if(convertedHoraInicio.toLocalTime().isAfter(horaFin))
            cantidad++;

        return cantidad;
    }
}

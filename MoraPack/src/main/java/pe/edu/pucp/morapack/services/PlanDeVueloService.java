package pe.edu.pucp.morapack.services;

import pe.edu.pucp.morapack.dtos.PlanDeVueloResponse;
import pe.edu.pucp.morapack.models.PlanDeVuelo;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;

public interface PlanDeVueloService {
    PlanDeVuelo insertarPlanDeVuelo(PlanDeVuelo planDeVuelo);
    ArrayList<PlanDeVuelo> insertarListaPlanesDeVuelo(ArrayList<PlanDeVuelo> planesDeVuelo);
    Optional<PlanDeVuelo> obtenerPlanDeVueloPorId(Integer id);
    ArrayList<PlanDeVueloResponse> obtenerPlanesDeVuelo();
    ArrayList<PlanDeVuelo> obtenerListaPlanesDeVuelo();
    Integer planAcabaAlSiguienteDia(String tInicio, String tFin, String husoOrigen, String husoDestino, Integer aa, Integer mm, Integer dd);
}

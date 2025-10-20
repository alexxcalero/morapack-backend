package pe.edu.pucp.morapack.services.servicesImp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.pucp.morapack.models.Aeropuerto;
import pe.edu.pucp.morapack.repositories.AeropuertoRepository;
import pe.edu.pucp.morapack.services.AeropuertoService;

import java.util.ArrayList;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AeropuertoServiceImp implements AeropuertoService {
    private final AeropuertoRepository aeropuertoRepository;

    @Override
    public Aeropuerto insertarAeropuerto(Aeropuerto aeropuerto) {
        return aeropuertoRepository.save(aeropuerto);
    }

    @Override
    public ArrayList<Aeropuerto> insertListaAeropuertos(ArrayList<Aeropuerto> aeropuertos) {
        return (ArrayList<Aeropuerto>)aeropuertoRepository.saveAll(aeropuertos);
    }

    @Override
    public Optional<Aeropuerto> obtenerAeropuertoPorId(Integer id) {
        return aeropuertoRepository.findById(id);
    }

    @Override
    public Optional<Aeropuerto> obtenerAeropuertoPorCodigo(String codigo) {
        return aeropuertoRepository.findByCodigo(codigo);
    }

    @Override
    public ArrayList<Aeropuerto> obtenerTodosAeropuertos() {
        return (ArrayList<Aeropuerto>)aeropuertoRepository.findAll();
    }
}

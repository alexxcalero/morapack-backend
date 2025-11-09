package pe.edu.pucp.morapack.services.servicesImp;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import pe.edu.pucp.morapack.models.Aeropuerto;
import pe.edu.pucp.morapack.repository.AeropuertoRepository;
import pe.edu.pucp.morapack.services.AeropuertoService;

import java.util.ArrayList;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AeropuertoServiceImp implements AeropuertoService {
    @Autowired
    private final AeropuertoRepository aeropuertoRepository;

    public Aeropuerto insertarAeropuerto(Aeropuerto aeropuerto) {
        return aeropuertoRepository.save(aeropuerto);
    }

    public ArrayList<Aeropuerto> insertarListaAeropuertos(ArrayList<Aeropuerto> aeropuertos) {
        return (ArrayList<Aeropuerto>) aeropuertoRepository.saveAll(aeropuertos);
    }

    public Optional<Aeropuerto> obtenerAeropuertoPorId(Integer id) {
        return aeropuertoRepository.findById(id);
    }

    public Optional<Aeropuerto> obtenerAeropuertoPorCodigo(String codigo) {
        return aeropuertoRepository.findAeropuertoByCodigo(codigo);
    }

    public ArrayList<Aeropuerto> obtenerTodosAeropuertos() {
        return (ArrayList<Aeropuerto>) aeropuertoRepository.findAll();
    }

    public void aumentarProductosEnAlmacen(Integer cantProductos) {

    }
}

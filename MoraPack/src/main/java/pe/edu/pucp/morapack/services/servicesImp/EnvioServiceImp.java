package pe.edu.pucp.morapack.services.servicesImp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.pucp.morapack.models.Envio;
import pe.edu.pucp.morapack.models.Pais;
import pe.edu.pucp.morapack.repositories.EnvioRepository;
import pe.edu.pucp.morapack.services.EnvioService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EnvioServiceImp implements EnvioService {
    private final EnvioRepository envioRepository;

    @Override
    public Envio insertarEnvio(Envio envio) {
        return envioRepository.save(envio);
    }

    @Override
    public ArrayList<Envio> insertarListaEnvios(ArrayList<Envio> envios) {
        return (ArrayList<Envio>)envioRepository.saveAll(envios);
    }

    @Override
    public ArrayList<Envio> obtenerEnvios() {
        return (ArrayList<Envio>)envioRepository.findAll();
    }

    @Override
    public Optional<Envio> obtenerEnvioPorId(Integer id) {
        return envioRepository.findById(id);
    }

    @Override
    public ArrayList<Envio> obtenerEnviosPorFecha(LocalDate fecha) {
        return envioRepository.findByFechaIngreso(fecha);
    }

    @Override
    public Integer calcularTotalProductosEnvio(ArrayList<Envio> envios) {
        Integer totalProductos = 0;
        for(Envio envio : envios) {
            totalProductos += envio.getNumProductos();
        }
        return totalProductos;
    }
}

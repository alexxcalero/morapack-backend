package pe.edu.pucp.morapack.services.servicesImp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.pucp.morapack.models.Pais;
import pe.edu.pucp.morapack.repository.PaisRepository;
import pe.edu.pucp.morapack.services.PaisService;

import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class PaisServiceImp implements PaisService {
    private final PaisRepository paisRepository;

    @Override
    public Pais insertarPais(Pais pais) {
        return paisRepository.save(pais);
    }

    @Override
    public ArrayList<Pais> obtenerTodosPaises() {
        return (ArrayList<Pais>) paisRepository.findAll();
    }

    public void eliminarTodosPaises() {
        paisRepository.deleteAll();
    }
}

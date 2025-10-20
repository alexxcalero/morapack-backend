package pe.edu.pucp.morapack.services.servicesImp;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.pucp.morapack.models.Continente;
import pe.edu.pucp.morapack.repositories.ContinenteRepository;
import pe.edu.pucp.morapack.services.ContinenteService;

import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class ContinenteServiceImp implements ContinenteService {
    private final ContinenteRepository continenteRepository;

    @Override
    public Continente insertarContinente(Continente continente) {
        return continenteRepository.save(continente);
    }

    @Override
    public ArrayList<Continente> obtenerTodosContinentes() {
        return (ArrayList<Continente>)continenteRepository.findAll();
    }
}

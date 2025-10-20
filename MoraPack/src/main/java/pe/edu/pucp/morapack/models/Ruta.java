package pe.edu.pucp.morapack.models;

import lombok.*;

import java.util.ArrayList;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Ruta {
    private Integer id;

    // Lista con los ids de los planes de vuelos
    private ArrayList<Integer> listaRutas = new ArrayList<>();

    public void copiarRuta(Ruta ruta) {
        this.id = ruta.getId();
        this.listaRutas.clear();
        this.listaRutas.addAll(ruta.getListaRutas());
    }
}

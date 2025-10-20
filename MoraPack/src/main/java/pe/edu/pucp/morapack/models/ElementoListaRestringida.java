package pe.edu.pucp.morapack.models;

import lombok.*;

import java.util.ArrayList;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ElementoListaRestringida {
    private Integer id;

    // Lista de rutas posibles
    private ArrayList<Integer> listaElementos = new ArrayList<>();

    private Double fitnessSolucion;
}

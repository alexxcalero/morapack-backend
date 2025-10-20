package pe.edu.pucp.morapack.models;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "aeropuerto")
public class Aeropuerto {
    @Id
    @Column(unique = true, nullable = false)
    private Integer id;

    @OneToOne
    @JoinColumn(name = "id_pais")
    private Pais pais;

    @OneToMany(mappedBy = "aeropuerto", cascade = CascadeType.ALL)
    private List<Producto> productos = new ArrayList<>();

    private String codigo;
    private String husoHorario;
    private Integer capacidadMaxima;
    private Integer capacidadOcupada;
    private String ciudad;
    private String abreviatura;
    private Integer estado;
    private Double longitud;
    private Double latitud;

    public boolean estaLleno() {
        return this.capacidadMaxima == this.capacidadOcupada;
    }
}

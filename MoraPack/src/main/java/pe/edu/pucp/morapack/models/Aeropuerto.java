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
    private String longitud;  // Verificar luego como sera esto
    private String latitud;  // Verificar luego como sera esto

    public boolean estaLleno() {
        return this.capacidadMaxima == this.capacidadOcupada;
    }

    public void setIdPais(Integer id) {
        if(this.pais == null)
            this.pais = new Pais();
        this.pais.setId(id);
    }

    public Integer getIdPais() {
        if(this.pais == null)
            return -1;
        return this.pais.getId();
    }
}

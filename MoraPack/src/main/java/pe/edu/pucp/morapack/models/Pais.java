package pe.edu.pucp.morapack.models;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pais")
public class Pais {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(unique = true, nullable = false)
    private Integer id;

    private String nombre;

    @ManyToOne
    @JoinColumn(name = "id_continente")
    private Continente continente;

    public void setIdContinente(Integer id) {
        if(this.continente == null)
            this.continente = new Continente();
        this.continente.setId(id);
    }

    public Integer getIdContinente() {
        if(this.continente == null)
            return -1;
        return this.continente.getId();
    }
}

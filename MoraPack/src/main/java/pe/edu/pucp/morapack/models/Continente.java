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
@Table(name = "continente")
public class Continente {
    @Id
    @Column(unique = true, nullable = false)
    private Integer id;

    private String nombre;

    @OneToMany(mappedBy = "continente", cascade = CascadeType.ALL)
    private List<Pais> paises = new ArrayList<>();
}

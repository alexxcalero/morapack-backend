package pe.edu.pucp.morapack.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "ciudad")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Ciudad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_ciudad")
    private Integer id;

    @Column(name = "nombre", nullable = false, length = 100)
    private String nombre;

    @Column(name = "nombre_pais", nullable = false, length = 100)
    private String nombrePais;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_continente", nullable = false)
    private Continente continente;

}

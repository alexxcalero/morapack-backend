package pe.edu.pucp.morapack.models;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "producto")
public class Producto {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(unique = true, nullable = false)
    private Integer id;

    private Integer estado;

    @Transient
    private Ruta ruta =  new Ruta();

    @ManyToOne
    @JoinColumn(name = "id_envio")
    private Envio envio;

    @ManyToOne
    @JoinColumn(name = "id_aeropuerto")
    private Aeropuerto aeropuerto;
}

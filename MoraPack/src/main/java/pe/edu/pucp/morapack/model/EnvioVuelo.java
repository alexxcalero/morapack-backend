package pe.edu.pucp.morapack.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
    name = "envio_vuelo",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_envio_orden", columnNames = {"id_envio", "orden_tramo"})
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnvioVuelo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_envio_vuelo")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_envio", nullable = false)
    private Envio envio;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_vuelo", nullable = false)
    private Vuelo vuelo;

    @Column(name = "orden_tramo", nullable = false)
    private Integer ordenTramo;
}

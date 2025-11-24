package pe.edu.pucp.morapack.models;

import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonBackReference;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "parte_asignada_plan_de_vuelo")
public class ParteAsignadaPlanDeVuelo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(unique = true, nullable = false)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "id_parte_asignada", nullable = false)
    @JsonBackReference
    private ParteAsignada parteAsignada;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "id_plan_de_vuelo", nullable = false)
    private PlanDeVuelo planDeVuelo;

    @Column(nullable = false)
    private Integer orden; // Orden del vuelo en la ruta (1, 2, 3, ...)
}

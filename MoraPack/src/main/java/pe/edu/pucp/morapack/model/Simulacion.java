package pe.edu.pucp.morapack.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "simulacion")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Simulacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_simulacion")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_simulacion", nullable = false)
    private TipoSimulacion tipoSimulacion;

    @Column(name = "es_activa", nullable = false)
    private boolean esActiva;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false)
    private EstadoSimulacion estado;

    @Column(name = "fecha_inicio", nullable = false)
    private LocalDateTime fechaInicio;

    @Column(name = "fecha_fin")
    private LocalDateTime fechaFin;

    @Column(name = "tiempo_simulado_inicio", nullable = false)
    private LocalDateTime tiempoSimuladoInicio;

    @Column(name = "tiempo_simulado_fin")
    private LocalDateTime tiempoSimuladoFin;

    @Column(name = "entrada_json", columnDefinition = "json")
    private String entradaJson;

    @Column(name = "salida_json", columnDefinition = "json")
    private String salidaJson;
}

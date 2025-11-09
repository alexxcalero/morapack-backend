package pe.edu.pucp.morapack.models;

import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonManagedReference;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "parte_asignada")
public class ParteAsignada {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(unique = true, nullable = false)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "id_envio")
    @JsonBackReference
    private Envio envio;

    // Lista transient para uso en memoria durante la planificación
    @Transient
    private List<PlanDeVuelo> ruta;

    // Relación persistente con PlanDeVuelo a través de tabla intermedia
    @OneToMany(mappedBy = "parteAsignada", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JsonManagedReference
    @Builder.Default
    private List<ParteAsignadaPlanDeVuelo> vuelosRuta = new ArrayList<>();

    private ZonedDateTime llegadaFinal;
    private Integer cantidad;

    @ManyToOne
    @JoinColumn(name = "id_aeropuerto_origen")
    private Aeropuerto aeropuertoOrigen;

    public ParteAsignada(List<PlanDeVuelo> ruta, ZonedDateTime llegadaFinal, Integer cantidad, Aeropuerto aeropuertoOrigen) {
        this.ruta = ruta;
        this.llegadaFinal = llegadaFinal;
        this.cantidad = cantidad;
        this.aeropuertoOrigen = aeropuertoOrigen;
        this.vuelosRuta = new ArrayList<>();
    }

    /**
     * Sincroniza la ruta transient con la relación persistente
     * Debe llamarse antes de persistir para guardar la relación en BD
     */
    public void sincronizarRutaConBD() {
        if (this.ruta == null) {
            this.vuelosRuta.clear();
            return;
        }

        // Limpiar relaciones existentes
        this.vuelosRuta.clear();

        // Crear nuevas relaciones
        int orden = 1;
        for (PlanDeVuelo vuelo : this.ruta) {
            ParteAsignadaPlanDeVuelo relacion = new ParteAsignadaPlanDeVuelo();
            relacion.setParteAsignada(this);
            relacion.setPlanDeVuelo(vuelo);
            relacion.setOrden(orden++);
            this.vuelosRuta.add(relacion);
        }
    }

    /**
     * Carga la ruta transient desde la relación persistente
     * Debe llamarse después de cargar desde BD
     */
    public void cargarRutaDesdeBD() {
        if (this.vuelosRuta == null || this.vuelosRuta.isEmpty()) {
            this.ruta = new ArrayList<>();
            return;
        }

        // Ordenar por orden y extraer los planes de vuelo
        this.ruta = this.vuelosRuta.stream()
                .sorted((a, b) -> Integer.compare(a.getOrden(), b.getOrden()))
                .map(ParteAsignadaPlanDeVuelo::getPlanDeVuelo)
                .collect(Collectors.toList());
    }

    /**
     * Se ejecuta automáticamente después de cargar el entity desde BD
     */
    @PostLoad
    private void postLoad() {
        cargarRutaDesdeBD();
    }
}

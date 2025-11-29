package pe.edu.pucp.morapack.models;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import lombok.*;

import java.time.*;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "envio")
public class Envio {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(unique = true, nullable = false)
    private Integer id;

    private Long idEnvioPorAeropuerto;

    // ⚡ CAMBIO CRÍTICO: LAZY loading para evitar cargar 40K envíos con todas sus
    // relaciones
    // Las queries que necesiten parteAsignadas deben usar JOIN FETCH explícitamente
    @OneToMany(mappedBy = "envio", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JsonManagedReference
    private List<ParteAsignada> parteAsignadas = new ArrayList<>();

    private LocalDateTime fechaIngreso;
    private String husoHorarioDestino;

    @Transient
    private List<Aeropuerto> aeropuertosOrigen = new ArrayList<>(); // Multihub

    @ManyToOne
    @JoinColumn(name = "id_aeropuerto_destino")
    private Aeropuerto aeropuertoDestino;

    @ManyToOne
    @JoinColumn(name = "id_aeropuerto_origen")
    private Aeropuerto aeropuertoOrigen;

    private Integer numProductos;

    private LocalDateTime fechaLlegadaMax;

    private String cliente;

    @Transient
    private ZonedDateTime zonedFechaIngreso;

    @Transient
    private ZonedDateTime zonedFechaLlegadaMax;

    public Envio(Long idEnvioPorAeropuerto, LocalDateTime fechaIngreso, String husoHorarioDestino, Aeropuerto destino,
            Integer numProductos, String cliente) {
        this.idEnvioPorAeropuerto = idEnvioPorAeropuerto;
        this.parteAsignadas = new ArrayList<>();
        this.fechaIngreso = fechaIngreso;
        this.husoHorarioDestino = husoHorarioDestino;
        this.aeropuertosOrigen = new ArrayList<>();
        this.aeropuertoDestino = destino;
        this.husoHorarioDestino = husoHorarioDestino;
        this.numProductos = numProductos;
        this.cliente = cliente;
    }

    public Integer cantidadAsignada() {
        try {
            if (this.parteAsignadas == null) {
                return 0;
            }
            return this.parteAsignadas.stream().mapToInt(ParteAsignada::getCantidad).sum();
        } catch (org.hibernate.LazyInitializationException e) {
            // Si hay error de lazy loading, retornar 0 (asumiendo que no hay partes asignadas cargadas)
            return 0;
        }
    }

    public Integer cantidadRestante() {
        return Math.max(0, this.numProductos - this.cantidadAsignada());
    }

    public Boolean estaCompleto() {
        try {
            return this.cantidadRestante() == 0;
        } catch (Exception e) {
            // Si hay error al calcular, asumir que no está completo
            return false;
        }
    }

    public Boolean esMismoContinente(Aeropuerto orig) {
        return orig.getPais().getContinente().getId().equals(this.aeropuertoDestino.getPais().getContinente().getId());
    }

    public Duration deadlineDesde(Aeropuerto origen) {
        return esMismoContinente(origen) ? Duration.ofDays(2) : Duration.ofDays(3);
    }

    @PostLoad
    private void cargarZonedDateTime() {
        Integer offsetDestino = Integer.parseInt(husoHorarioDestino);

        ZoneOffset zoneDestino = ZoneOffset.ofHours(offsetDestino);

        this.zonedFechaIngreso = fechaIngreso.atZone(zoneDestino);
    }
}

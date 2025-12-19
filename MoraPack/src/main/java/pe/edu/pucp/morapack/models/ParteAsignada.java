package pe.edu.pucp.morapack.models;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonManagedReference;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
    private List<ParteAsignadaPlanDeVuelo> vuelosRuta = new ArrayList<>();

    // Campo persistente en BD - ÚNICO campo que JPA verá
    @Column(name = "llegada_final", columnDefinition = "TIMESTAMP")
    private LocalDateTime llegadaFinalLocal;

    // ✅ Campo para almacenar el huso horario del destino (ej: "-5", "-3", "1", etc.)
    // Esto permite que llegadaFinal tenga su huso horario sin depender de que envio esté cargado
    @Column(name = "huso_horario_destino", length = 10)
    private String husoHorarioDestino;

    // ❌ ELIMINADO: Campo transient llegadaFinal
    // PROBLEMA: Hibernate/Jackson estaba intentando acceder a este campo y encontrar un LocalDateTime
    // SOLUCIÓN: Usar solo métodos helper que calculen ZonedDateTime cuando sea necesario
    // El campo llegadaFinal ya no existe como campo de instancia para evitar que Hibernate lo detecte

    private Integer cantidad;

    @ManyToOne
    @JoinColumn(name = "id_aeropuerto_origen")
    private Aeropuerto aeropuertoOrigen;

    // Indica si el producto ya fue entregado al cliente (liberado del aeropuerto destino)
    private Boolean entregado = false;

    // Getters y setters básicos
    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Envio getEnvio() { return envio; }
    public void setEnvio(Envio envio) { this.envio = envio; }

    public List<PlanDeVuelo> getRuta() { return ruta; }
    public void setRuta(List<PlanDeVuelo> ruta) { this.ruta = ruta; }

    public List<ParteAsignadaPlanDeVuelo> getVuelosRuta() { return vuelosRuta; }
    public void setVuelosRuta(List<ParteAsignadaPlanDeVuelo> vuelosRuta) { this.vuelosRuta = vuelosRuta; }

    public Integer getCantidad() { return cantidad; }
    public void setCantidad(Integer cantidad) { this.cantidad = cantidad; }

    public Aeropuerto getAeropuertoOrigen() { return aeropuertoOrigen; }
    public void setAeropuertoOrigen(Aeropuerto aeropuertoOrigen) { this.aeropuertoOrigen = aeropuertoOrigen; }

    public Boolean getEntregado() { return entregado; }
    public void setEntregado(Boolean entregado) { this.entregado = entregado; }

    // Constructor sin argumentos
    public ParteAsignada() {
        this.vuelosRuta = new ArrayList<>();
    }

    public ParteAsignada(List<PlanDeVuelo> ruta, ZonedDateTime llegadaFinal, Integer cantidad, Aeropuerto aeropuertoOrigen) {
        this.ruta = ruta;
        // ✅ Ya no almacenamos llegadaFinal como campo, solo llegadaFinalLocal
        this.llegadaFinalLocal = llegadaFinal != null ? llegadaFinal.toLocalDateTime() : null;
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
        // ✅ Ya no inicializamos llegadaFinal aquí porque el campo ya no existe
        // El ZonedDateTime se calcula dinámicamente en getLlegadaFinal() cuando sea necesario
    }

    /**
     * Se ejecuta antes de persistir el entity en BD
     * Ya no necesitamos limpiar llegadaFinal porque el campo ya no existe
     * llegadaFinalLocal ya está sincronizado por el setter setLlegadaFinal()
     */
    @PrePersist
    @PreUpdate
    private void prePersist() {
        // ✅ Ya no hay nada que hacer aquí porque llegadaFinal ya no existe como campo
        // llegadaFinalLocal ya está sincronizado por setLlegadaFinal()
    }

    // Getter y setter para llegadaFinalLocal (campo persistente)
    public LocalDateTime getLlegadaFinalLocal() {
        return llegadaFinalLocal;
    }

    public void setLlegadaFinalLocal(LocalDateTime llegadaFinalLocal) {
        this.llegadaFinalLocal = llegadaFinalLocal;
    }

    /**
     * Getter para llegadaFinal - calcula ZonedDateTime dinámicamente desde llegadaFinalLocal
     * NO hay campo de instancia llegadaFinal para evitar que Hibernate lo detecte
     *
     * CRÍTICO: Este método calcula el ZonedDateTime cada vez que se llama usando atZone(),
     * NUNCA usa ZonedDateTime.from() que es lo que causa el error
     *
     * Usa el husoHorarioDestino almacenado en esta parte, o el del envío como fallback
     */
    @JsonIgnore
    public ZonedDateTime getLlegadaFinal() {
        // ✅ Si llegadaFinalLocal es null, devolver null inmediatamente
        if (llegadaFinalLocal == null) {
            return null;
        }

        try {
            // ✅ PRIMERO: Intentar usar el huso horario almacenado en esta parte
            String husoHorario = this.husoHorarioDestino;

            // ✅ SEGUNDO: Si no hay huso horario almacenado, usar el del envío como fallback
            if (husoHorario == null && envio != null && envio.getHusoHorarioDestino() != null) {
                husoHorario = envio.getHusoHorarioDestino();
            }

            // ✅ Calcular ZonedDateTime dinámicamente usando atZone() (NUNCA usar ZonedDateTime.from())
            if (husoHorario != null) {
                Integer offsetDestino = Integer.parseInt(husoHorario);
                ZoneOffset zoneDestino = ZoneOffset.ofHours(offsetDestino);
                return llegadaFinalLocal.atZone(zoneDestino);
            } else {
                // Si no hay información de zona horaria, usar UTC como fallback
                return llegadaFinalLocal.atZone(ZoneOffset.UTC);
            }
        } catch (Exception e) {
            System.err.printf("⚠️ Error al convertir llegadaFinalLocal a ZonedDateTime para parte %d: %s%n",
                    id != null ? id : -1, e.getMessage());
            e.printStackTrace();
            // Fallback: usar UTC
            try {
                return llegadaFinalLocal.atZone(ZoneOffset.UTC);
            } catch (Exception e2) {
                System.err.printf("❌ Error crítico al convertir llegadaFinalLocal a ZonedDateTime (UTC): %s%n",
                        e2.getMessage());
                return null;
            }
        }
    }

    /**
     * Setter para llegadaFinal que también actualiza llegadaFinalLocal y husoHorarioDestino
     * Ya no almacenamos llegadaFinal como campo, solo sincronizamos llegadaFinalLocal y el huso horario
     */
    public void setLlegadaFinal(ZonedDateTime llegadaFinal) {
        if (llegadaFinal != null) {
            try {
                // ✅ Sincronizar llegadaFinalLocal (campo persistente)
                this.llegadaFinalLocal = llegadaFinal.toLocalDateTime();

                // ✅ Almacenar el huso horario del ZonedDateTime
                // Extraer el offset en horas (ej: "-05:00" -> "-5")
                ZoneOffset offset = llegadaFinal.getOffset();
                int offsetHours = offset.getTotalSeconds() / 3600;
                this.husoHorarioDestino = String.valueOf(offsetHours);
            } catch (Exception e) {
                System.err.printf("⚠️ Error al convertir llegadaFinal a LocalDateTime en setter para parte %d: %s%n",
                        id != null ? id : -1, e.getMessage());
                this.llegadaFinalLocal = null;
                this.husoHorarioDestino = null;
            }
        } else {
            this.llegadaFinalLocal = null;
            this.husoHorarioDestino = null;
        }
    }

    // Getter y setter para husoHorarioDestino
    public String getHusoHorarioDestino() {
        return husoHorarioDestino;
    }

    public void setHusoHorarioDestino(String husoHorarioDestino) {
        this.husoHorarioDestino = husoHorarioDestino;
    }
}

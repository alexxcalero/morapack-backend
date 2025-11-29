package pe.edu.pucp.morapack.models;

import lombok.*;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Solucion {
    private ArrayList<Envio> envios;
    private ArrayList<PlanDeVuelo> vuelos;
    private Integer enviosCompletados;
    private Duration llegadaMediaPonderada;

    public Solucion(ArrayList<Envio> e, ArrayList<PlanDeVuelo> v) {
        this.envios = e;
        this.vuelos = v;
        recomputar();
    }

    public void recomputar() {
        int completos = 0;
        long sumMin = 0;
        long sumCant = 0;

        for(Envio e : this.envios) {
            if(e.estaCompleto()) completos++;

            // ⚡ Proteger acceso a parteAsignadas para evitar LazyInitializationException
            try {
                List<ParteAsignada> partes = e.getParteAsignadas();
                if(partes != null) {
                    for(ParteAsignada p : partes) {
                        long minutos = ChronoUnit.MINUTES.between(e.getZonedFechaIngreso(), p.getLlegadaFinal());
                        sumMin += minutos * (long) p.getCantidad();
                        sumCant += p.getCantidad();
                    }
                }
            } catch (org.hibernate.LazyInitializationException ex) {
                // Si hay error de lazy loading, simplemente no contar estas partes
                // (el envío ya fue contado como completo o no completo según estaCompleto())
            }
        }

        this.enviosCompletados = completos;

        if(sumCant > 0) this.llegadaMediaPonderada = Duration.ofMinutes(sumMin / sumCant);
        else this.llegadaMediaPonderada = Duration.ofDays(999);
    }
}

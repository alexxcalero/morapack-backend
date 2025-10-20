package pe.edu.pucp.morapack.models;

import lombok.*;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Getter
@Setter
@Builder
@Component
@NoArgsConstructor
@AllArgsConstructor
public class Grasp {
    private ArrayList<Aeropuerto> aeropuertos;
    private ArrayList<Pais> paises;
    private ArrayList<Continente> continentes;
    private ArrayList<Envio> envios = new ArrayList<>();
    private ArrayList<PlanDeVuelo> planes = new ArrayList<>();
    private ArrayList<Envio> solucionSimulacion;

    public ArrayList<Envio> faseConstructivaGrasp(ArrayList<Aeropuerto> aeropuertos, ArrayList<PlanDeVuelo> planes, ArrayList<Envio> envios) {
        ArrayList<Envio> solucion = new ArrayList<>();

        Collections.shuffle(envios);  // Mezclo la lista de envios
        Collections.shuffle(planes);  // Mezclo la lista de vuelos

        Integer tamanhoRCL = 2;  // Tamanho de la lista restringida

        // OJO - REVISAR : ES LO QUE EN MI CODIGO ES EL ARREGLO DE HUBS
        Map<Integer, List<PlanDeVuelo>> planesPorAeropuertoOrigen = planes.stream().collect(Collectors.groupingBy(PlanDeVuelo::getCiudadOrigen));

        // Ordenamos los envios por fecha de llegada maxima, de menor a mayor
        envios.sort(Comparator.comparing(Envio::getZonedFechaLlegadaMax));

        // Iteramos para encontrar la solucion para cada pedido
        for(Envio envio : envios) {
            ArrayList<ElementoListaRestringida> listaRestringida = new ArrayList<ElementoListaRestringida>(tamanhoRCL);  // Creamos la RCL
            List<PlanDeVuelo> planesVueloOrigen = planesPorAeropuertoOrigen.get(envio.getAeropuertoOrigen());  // Obtenemos los planes de vuelo del aeropuerto origen
            planesVueloOrigen.sort(Comparator.comparing(PlanDeVuelo::getZonedHoraOrigen));

            for(PlanDeVuelo planDeVuelo : planesVueloOrigen) {
                if(planDeVuelo.getZonedHoraOrigen().isAfter(envio.getZonedFechaLlegadaMax())) break;

                Integer i = 0;

                ArrayList<Integer> listaObtenida = generarRutaVuelo(aeropuertos, planesPorAeropuertoOrigen, planDeVuelo, envio, i);

                // Si hay una ruta encontrada, la colocaremos en nuestra lista retringida
                if(!listaObtenida.isEmpty()) {
                    ArrayList<Integer> listaCaminos = new ArrayList<>();
                    listaCaminos.addAll(listaObtenida);

                    // Evaluamos si ingresa a nuestra lista
                    ElementoListaRestringida rutaPotencial = ElementoListaRestringida.builder().listaElementos(listaCaminos)
                     .fitnessSolucion(getFitness(listaCaminos, planes))
                     .build();

                    // Si la lista no esta completamente llega, se agrega
                    if(listaRestringida.size() < tamanhoRCL) {
                        listaRestringida.add(rutaPotencial);
                    } else {  // Se busca el elemento a reemplazar, evaluando fitness de cada ruta
                        for(ElementoListaRestringida e : listaRestringida) {
                            if(e.getFitnessSolucion() < rutaPotencial.getFitnessSolucion()) {
                                listaRestringida.set(listaRestringida.indexOf(e), rutaPotencial);
                                break;
                            }
                        }
                    }

                    // Ordenamos la lista segun el fitness
                    Collections.sort(listaRestringida, Comparator.comparing(ElementoListaRestringida::getFitnessSolucion));
                }
            }

            // Si la lista esta vacia, no habria rutas y por lo tanto ocurre colapso
            if(listaRestringida.isEmpty()) {
                System.out.println("No se pudo encontrar rutas posibles para paquetes del envio " + envio.getId());
                break;
            }

            // Como hay varios productos en un envio, se asignaran tantos como se puedan a la primera opcion. Luego, se asignaran los restantes a la segunda opcion y asi consecutivamente.
            // De no poder asignar todos los paquetes a estos pedidos, se considerara como colapso logistico

            Integer cantProductos = 0;
            Integer id = 0;
            while(cantProductos != envio.getProductos().size() && listaRestringida.size() != 0) {
                ElementoListaRestringida e = listaRestringida.get(id);

                // Mientras haya espacio disponible en todos los vuelos
                while(espacioDisponible(e.getListaElementos(), planes)) {
                    // Luego de comprobar que se pueda asignar todas las rutas a dicho producto, y aumentar en 1 el espacio ocupado en el plan de vuelo

                    // OJO - REVISAR : AQUI EN VEZ DE SUMAR +1, SE DEBERIA INTENTAR INSERTAR LA MAXIMA CANTIDAD DE PRODUCTOS POSIBLES DE UN VUELO

                    for(Integer idPlanAsignable : e.getListaElementos()) {
                        PlanDeVuelo p = obtenerPlanById(planes, idPlanAsignable);
                        p.setCapacidadOcupada(p.getCapacidadOcupada() + 1);
                        envio.getProductos().get(cantProductos).getRuta().getListaRutas().add(idPlanAsignable);
                    }
                    cantProductos++;
                    if(cantProductos == envio.getProductos().size()) break;
                }

                listaRestringida.remove(id);
                id++;
                if(id >= listaRestringida.size()) break;
                if(cantProductos == envio.getProductos().size()) break;
            }

            solucion.add(envio);
        }

        return solucion;
    }

    public PlanDeVuelo obtenerPlanById(ArrayList<PlanDeVuelo> planes, Integer idPlanAsignable) {
        return planes.stream().filter(plan -> plan.getId() == idPlanAsignable).findFirst().orElse(null);
    }

    public Boolean espacioDisponible(ArrayList<Integer> vuelos, ArrayList<PlanDeVuelo> planes) {
        for(Integer vuelo : vuelos) {
            PlanDeVuelo p = obtenerPlanById(planes, vuelo);
            if(p.estaLleno()) return false;
        }

        return true;
    }

    public Aeropuerto aeropuertoById(ArrayList<Aeropuerto> aeropuertos, Integer id) {
        return aeropuertos.stream().filter(a -> a.getId() == id).findFirst().orElse(null);
    }

    public Double getFitness(ArrayList<Integer> listaCaminos, ArrayList<PlanDeVuelo> planes) {
        Double fitnessTotal = 0.0;
        Double minutosTotales = 0.0;
        Integer vuelosAtomar = 0;

        for(Integer camino : listaCaminos) {
            PlanDeVuelo p = obtenerPlanById(planes, camino);

            // Fitness se determinara por la cantidad de vuelos a tomar
            vuelosAtomar++;

            // Tiempo en minutos totales para llegar al destino
            Long segundosTotalesOrigen = p.getZonedHoraOrigen().toInstant().getEpochSecond();
            Long segundosTotalesDestino = p.getZonedHoraDestino().toInstant().getEpochSecond();
            Double minutosVuelo = (double) Duration.ofSeconds(Math.abs(segundosTotalesDestino - segundosTotalesOrigen)).toMinutes();

            minutosTotales += minutosVuelo;
        }

        Double pesoMinutos = 0.6;
        Double pesoVuelos = 0.4;

        fitnessTotal = (vuelosAtomar * pesoVuelos + minutosTotales * pesoMinutos) * -1;

        return fitnessTotal;
    }

    public ArrayList<Integer> generarRutaVuelo(ArrayList<Aeropuerto> aeropuertos, Map<Integer, List<PlanDeVuelo>> planes, PlanDeVuelo planFinalizado, Envio envio, Integer id) {
        ArrayList<Integer> listaEnConstruccion = new ArrayList<>();

        // Identificamos el aeropuerto en el que nos encontramos
        Integer aeropuertoActual = planFinalizado.getCiudadDestino();

        // Anhadimos a la lista
        // En caso llegamos al destino, se devuelve el id de este plan
        if(aeropuertoActual == envio.getAeropuertoDestino()
        && !planFinalizado.estaLleno()
        && envio.getZonedFechaIngreso().isBefore(planFinalizado.getZonedHoraOrigen())
        && envio.getZonedFechaLlegadaMax().isAfter(planFinalizado.getZonedHoraDestino())) {
            listaEnConstruccion.add(planFinalizado.getId());
            return listaEnConstruccion;
        }

        id++;
        List<PlanDeVuelo> planesVueloOrigen = planes.get(aeropuertoActual);

        if (planesVueloOrigen != null) {
            List<PlanDeVuelo> planesFiltrados = planesVueloOrigen.stream().filter(p -> p.getZonedHoraOrigen().isAfter(planFinalizado.getZonedHoraDestino())).toList();

            // Si todavia no llegamos al destino, se buscan caminos
            for(PlanDeVuelo potencial : planesFiltrados) {
                if(id > 4) new ArrayList<>();

                if(potencial.getId() != planFinalizado.getId()
                && aeropuertoActual == potencial.getCiudadOrigen()
                && planFinalizado.getZonedHoraDestino().isBefore(potencial.getZonedHoraOrigen())
                && !aeropuertoById(aeropuertos, potencial.getCiudadDestino()).estaLleno()
                && !potencial.estaLleno()
                && envio.getZonedFechaIngreso().isBefore(potencial.getZonedHoraOrigen())
                && envio.getZonedFechaLlegadaMax().isAfter(potencial.getZonedHoraDestino())) {
                    // Buscamos nuevas rutas basadas en el potencial actual
                    ArrayList<Integer> nuevasRutas = generarRutaVuelo(aeropuertos, planes, potencial, envio, id);

                    // Si tenemos una lista no vacia, agregamos y devolvemos
                    if(!nuevasRutas.isEmpty()) {
                        listaEnConstruccion.add(planFinalizado.getId());
                        listaEnConstruccion.addAll(nuevasRutas);
                        return listaEnConstruccion;
                    }
                }
            }
        }

        // Si llegamos aca significa que no se encontro solucion y devolvemos un arreglo vacio
        return new ArrayList<>();
    }

    private Integer obtenerCiudadActual(Ruta ruta, ArrayList<PlanDeVuelo> planes) {
        Integer ultimaCiudad = 0;

        for(Integer idPlan : ruta.getListaRutas()) {
            PlanDeVuelo p = obtenerPlanById(planes, idPlan);
            ultimaCiudad = p.getCiudadDestino();
        }

        return ultimaCiudad;
    }

    private Integer obtenerCiudadDestino(Ruta ruta, ArrayList<PlanDeVuelo> planes) {
        Integer ultimoPlanId = ruta.getListaRutas().get(ruta.getListaRutas().size() - 1);
        PlanDeVuelo ultimoPlan = obtenerPlanById(planes, ultimoPlanId);
        return ultimoPlan.getCiudadDestino();
    }

    private Boolean esRutaValida(Ruta ruta, ArrayList<PlanDeVuelo> planes, Envio envio, Producto producto) {
        Integer ultimoPlanId = ruta.getListaRutas().get(ruta.getListaRutas().size() - 1);
        PlanDeVuelo p = obtenerPlanById(planes, ultimoPlanId);

        if(envio.getFechaLlegadaMax().isAfter(p.getHoraDestino()))
            return true;
        else
            return false;
    }

    private void generarCaminos(Ruta rutaActual, ArrayList<PlanDeVuelo> planes, Envio envio, Producto producto, Integer destinoFinal, ArrayList<Ruta> vecindario, Integer[] contador) {
        if(contador[0] >= 1)
            return;

        Integer ciudadActual = obtenerCiudadActual(rutaActual, planes);  // Obtener la ultima ciudad en la ruta actual
        if(ciudadActual == destinoFinal) {
            // Si llegamos al destino final, validamos y agregamos esta ruta al vecindario
            if(esRutaValida(rutaActual, planes, envio, producto) || contador[0] < 3) {
                Ruta rutaNueva = new Ruta();
                rutaNueva.copiarRuta(rutaActual);
                vecindario.add(rutaNueva);
                contador[0]++;
                return;
            }
        }

        // Buscamos vuelos posibles desde la ciudad actual
        // 1. Filtra planes de vuelo que tengan como ciudad de origen la ciudad actual
        // 2. La ruta generada no contiene al plan de vuelo a evaluar
        // 3. La hora de destino del plan a evaluar es antes a la fecha máxima de entrega del envío
        // 4. La hora de partida del plan a evaluar es despues de la hora de destino del ultimo plan de la ruta
        for(PlanDeVuelo vuelo : planes) {
            if(vuelo.getCiudadOrigen() == ciudadActual && !rutaActual.getListaRutas().contains(vuelo.getId()) && vuelo.getHoraDestino().isBefore(envio.getFechaLlegadaMax()) && vuelo.getHoraOrigen().isAfter(obtenerPlanById(planes,rutaActual.getListaRutas().get(rutaActual.getListaRutas().size() - 1)).getHoraDestino())) {
                Ruta nuevaRuta = new Ruta();
                nuevaRuta.copiarRuta(rutaActual);
                nuevaRuta.getListaRutas().add(vuelo.getId());  // Agregamos el vuelo a la nueva ruta
                generarCaminos(nuevaRuta, planes, envio, producto, destinoFinal, vecindario, contador);
            }
        }
    }

    private ArrayList<Ruta> generarVecindario(Ruta rutaActual, ArrayList<PlanDeVuelo> planes, Envio envio, Producto producto) {
        ArrayList<Ruta> vecindario = new ArrayList<>();
        Ruta rutaInicial = new Ruta();
        Integer[] contador = {0};
        rutaInicial.getListaRutas().add(rutaActual.getListaRutas().get(0));  // Tomamos el primer vuelo como inicio

        generarCaminos(rutaInicial, planes, envio, producto, obtenerCiudadDestino(rutaActual, planes), vecindario, contador);

        return vecindario;
    }

    public Ruta getRutaMejorada(Producto producto, Ruta rutaActual, ArrayList<Aeropuerto> aeropuertos, ArrayList<PlanDeVuelo> planes, Envio envio) {
        Ruta mejorRuta = null;
        Double mejorFitness = Double.MAX_VALUE;

        ArrayList<Ruta> vecindario = generarVecindario(rutaActual, planes, envio, producto);
        for(Ruta rutaVecindario : vecindario) {
            Double fitnessVecindario = getFitness(rutaVecindario.getListaRutas(), planes);

            if(Math.abs(fitnessVecindario) < Math.abs(mejorFitness)) {
                mejorRuta = rutaVecindario;
                mejorFitness = fitnessVecindario;
            }
        }

        if(Math.abs(mejorFitness) < Math.abs(getFitness(rutaActual.getListaRutas(), planes)))
            return mejorRuta;
        else
            return null;
    }

    public ArrayList<Envio> busquedaLocalGrasp(ArrayList<Aeropuerto> aeropuertos, ArrayList<PlanDeVuelo> planes, ArrayList<Envio> enviosCubiertos) {
        Integer n = enviosCubiertos.size();
        ArrayList<Envio> enviosMejorados = new ArrayList<>();

        for(Integer i = 0; i < n; i++) {
            Envio envioActual = enviosCubiertos.get(i);

            for(Producto producto : envioActual.getProductos()) {
                Ruta rutaActual = producto.getRuta();
                //System.out.println(producto.getRuta().getListaRutas().size());

                if(rutaActual.getListaRutas().size() == 0)
                    continue;

                Ruta rutaMejorada = getRutaMejorada(producto, rutaActual, aeropuertos, planes, envioActual);
                if(rutaMejorada != null)
                    producto.setRuta(rutaMejorada);
            }
            enviosMejorados.add(envioActual);
        }
        return enviosMejorados;
    }

    public void imprimirSolucionEncontrada(ArrayList<Aeropuerto> aeropuertos, ArrayList<PlanDeVuelo> planes, ArrayList<Envio> enviosSolicitados) {
        System.out.println(" ---- ENVIOS PROGRAMADOS ----");

        for(Envio envio : enviosSolicitados) {
            if(envio.getAeropuertoOrigen() != -1 && envio.getAeropuertoDestino() != -1) {
                System.out.println("ENVIO #" + envio.getId());
                String ciudadOg = aeropuertoById(aeropuertos, envio.getAeropuertoOrigen()).getCiudad();
                String ciudadFi = aeropuertoById(aeropuertos,envio.getAeropuertoDestino()).getCiudad();
                System.out.println(ciudadOg + " -> " + ciudadFi);
                System.out.println(envio.getFechaIngreso() + " -> " + envio.getFechaLlegadaMax());

                for(Producto producto : envio.getProductos()) {
                    System.out.println("=======");
                    System.out.println("Producto #" + producto.getId());
                    System.out.println("Ruta:");
                    for(Integer idPlan : producto.getRuta().getListaRutas()) {
                        PlanDeVuelo p = obtenerPlanById(planes, idPlan);
                        String ciudadOgP = aeropuertoById(aeropuertos, p.getCiudadOrigen()).getCiudad();
                        String ciudadFiP = aeropuertoById(aeropuertos,p.getCiudadDestino()).getCiudad();
                        System.out.println(ciudadOgP + " -> " + ciudadFiP);
                        System.out.println(p.getHoraOrigen() + "->" + p.getHoraDestino());
                    }
                    System.out.println("=======");
                }
            }
        }
    }

    public ArrayList<Envio> ejecutarGrasp(ArrayList<Aeropuerto> aeropuertos, ArrayList<Envio> envios, ArrayList<PlanDeVuelo> planes) {
        ArrayList<Envio> mejorSolucion = new ArrayList<>();
        Integer n = 1;

        for(Integer i = 0; i < n; i++) {
            ArrayList<Envio> enviosCubiertos = faseConstructivaGrasp(aeropuertos, planes, envios);
            mejorSolucion = enviosCubiertos;
            //imprimirSolucionEncontrada(aeropuertos,planes,enviosCubiertos);

            //ArrayList<Envio> solucion = busquedaLocalGrasp(aeropuertos, planes, enviosCubiertos);
            //imprimirSolucionEncontrada(aeropuertos,planes,solucion);
            //mejorSolucion = solucion;
        }

        return mejorSolucion;
    }

    public ArrayList<Envio> buscarSinRuta(ArrayList<Envio> envios) {
        ArrayList<Envio> enviosSinRuta = new ArrayList<>();
        Integer i = 0;

        for(Envio envio : envios) {
            for(Producto producto : envio.getProductos()) {
                ArrayList<Integer> listaRutas = producto.getRuta().getListaRutas();
                if(listaRutas.isEmpty()) {
                    System.out.println(envio.getId());
                    enviosSinRuta.add(envio);
                    i++;
                    break;
                }
            }
        }

        System.out.println("=============================");
        System.out.println("Envios con paquetes sin ruta:");
        System.out.println(i);

        return enviosSinRuta;
    }

    public Integer buscarIdColapso(ArrayList<Envio> enviosSinRuta, ZonedDateTime fechaInicio) {
        Integer idColapso = -1;

        for(Envio envio : enviosSinRuta) {
            if(fechaInicio.isAfter(envio.getZonedFechaLlegadaMax())) {
                idColapso = envio.getId();
                break;
            }
        }

        return idColapso;
    }
}

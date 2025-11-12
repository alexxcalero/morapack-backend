package pe.edu.pucp.morapack.principal;


import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins("http://localhost:3000", "https://1inf54-981-5e.inf.pucp.edu.pe/")  // Falta agregar la url del V
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowedHeaders("*");
                //.allowCredentials(false);
    }
}

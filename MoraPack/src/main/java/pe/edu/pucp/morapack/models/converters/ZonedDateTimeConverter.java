package pe.edu.pucp.morapack.models.converters;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Convierte ZonedDateTime a LocalDateTime para almacenamiento en BD
 * y viceversa al cargar desde BD.
 *
 * Nota: Al cargar desde BD, se asume UTC como zona horaria por defecto.
 * La zona horaria correcta debe establecerse después usando @PostLoad si es necesario.
 */
@Converter
public class ZonedDateTimeConverter implements AttributeConverter<ZonedDateTime, LocalDateTime> {

    @Override
    public LocalDateTime convertToDatabaseColumn(ZonedDateTime zonedDateTime) {
        if (zonedDateTime == null) {
            return null;
        }
        // Guardar como LocalDateTime (sin zona horaria) en BD
        return zonedDateTime.toLocalDateTime();
    }

    @Override
    public ZonedDateTime convertToEntityAttribute(LocalDateTime localDateTime) {
        if (localDateTime == null) {
            return null;
        }
        try {
            // Cargar desde BD y convertir a ZonedDateTime usando UTC como zona por defecto
            // La zona horaria correcta se establecerá en @PostLoad si es necesario
            return localDateTime.atZone(ZoneOffset.UTC);
        } catch (Exception e) {
            System.err.printf("⚠️ Error en ZonedDateTimeConverter.convertToEntityAttribute: %s%n", e.getMessage());
            e.printStackTrace();
            // Fallback: intentar crear ZonedDateTime directamente
            throw new RuntimeException("Error al convertir LocalDateTime a ZonedDateTime: " + e.getMessage(), e);
        }
    }
}

package org.sdase.commons.server.jackson.filter;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializer;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;
import io.openapitools.jackson.dataformat.hal.annotation.EmbeddedResource;
import io.openapitools.jackson.dataformat.hal.annotation.Link;
import org.sdase.commons.server.jackson.EnableFieldFilter;

import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import java.util.List;
import java.util.stream.Stream;

public class FieldFilterSerializerModifier extends BeanSerializerModifier {

   private static final String FIELD_FILTER_QUERY_PARAM = "fields";

   @Context
   private UriInfo uriInfo;

   @Override
   public JsonSerializer<?> modifySerializer(SerializationConfig config, BeanDescription beanDesc, JsonSerializer<?> serializer) {
      if (serializer instanceof BeanSerializer && beanDesc.getBeanClass().isAnnotationPresent(EnableFieldFilter.class)) {
         return new FieldFilterSerializer((BeanSerializer) serializer, uriInfo);
      } else {
         return serializer;
      }
   }

   private static class FieldFilterSerializer extends BeanSerializer {
      FieldFilterSerializer(BeanSerializerBase src, UriInfo uriInfo) {
         super(src);
         for (int i = 0; i < _props.length; i++) {
            BeanPropertyWriter prop = _props[i];
            _props[i] = new SkipFieldBeanPropertyWriter(prop, uriInfo);
         }
      }

      private static class SkipFieldBeanPropertyWriter extends BeanPropertyWriter {

         private static final ThreadLocal<Boolean> NESTED = ThreadLocal.withInitial(() -> false);

         private final transient UriInfo uriInfo;

         SkipFieldBeanPropertyWriter(BeanPropertyWriter prop, UriInfo uriInfo) {
            super(prop);
            this.uriInfo = uriInfo;
         }

         @Override
         public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            if (NESTED.get()) {
               super.serializeAsField(bean, gen, prov);
            }
            else if (!hasAnyFieldFilter() || isHalAnnotated() || isIncludedField()) {
               try {
                  NESTED.set(true);
                  super.serializeAsField(bean, gen, prov);
               } finally {
                  NESTED.set(false);
               }
            }
         }

         private boolean hasAnyFieldFilter() {
            try {
               List<String> fieldFilters = uriInfo.getQueryParameters().get(FIELD_FILTER_QUERY_PARAM);
               return fieldFilters != null && !fieldFilters.isEmpty();
            }
            catch (Exception ignored) {
               // maybe there is some odd state, e.g. not in a request context
               return false;
            }
         }

         private boolean isIncludedField() {
            Stream<String> requestedFields = uriInfo.getQueryParameters().get(FIELD_FILTER_QUERY_PARAM).stream()
                  .map(fields -> fields.split(","))
                  .flatMap(Stream::of)
                  .map(String::trim);
            return requestedFields.anyMatch(fieldName -> fieldName.equals(getName()));
         }

         private boolean isHalAnnotated() {
            return getAnnotation(Link.class) != null || getAnnotation(EmbeddedResource.class) != null;
         }
      }
   }


}

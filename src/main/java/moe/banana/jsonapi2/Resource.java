package moe.banana.jsonapi2;

import com.google.common.collect.Sets;
import com.squareup.moshi.*;

import java.io.IOException;
import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

public abstract class Resource implements Serializable {

    /**
     * {@link Document} object containing information to the document
     */
    public Document _doc;

    /**
     * Type identifier of the resource
     */
    public String _type;

    /**
     * Unique resource identifier
     */
    public String _id;

    /**
     * add resource as data and attach the document to this resource
     * @param document document to add resource to
     */
    public void addTo(Document document) {
        document.addData(this);
    }

    /**
     * add resource as included resource and attach the document to this resource
     * @param document document to add resource to
     */
    public void includeBy(Document document) {
        document.addInclude(this);
    }

    public Resource() {
        _type = typeNameOf(getClass());
    }

    static class Adapter<T extends Resource> extends JsonAdapter<T> {

        Class<T> type;
        Map<String, Binding> bindings = new LinkedHashMap<>();
        Map<String, JsonAdapter> attributes = new LinkedHashMap<>();
        Map<String, Type> relationships = new LinkedHashMap<>();
        JsonAdapter<ResourceLinkage> linkageAdapter;

        @SuppressWarnings("unchecked")
        Adapter(Class<T> clazz, Moshi moshi) {
            this.type = clazz;
            Field[] fields = type.getFields();
            linkageAdapter = moshi.adapter(ResourceLinkage.class);
            for (Field field: fields) {
                if (Modifier.isTransient(field.getModifiers())) {
                    continue;
                }
                String name = field.getName();
                if (name.startsWith("_")) {
                    continue;
                }
                Annotation[] annotations = field.getAnnotations();
                Set<Annotation> annotationSet = Sets.newHashSet();
                for (Annotation annotation : annotations) {
                    if (annotation.annotationType().isAnnotationPresent(JsonQualifier.class)) {
                        annotationSet.add(annotation);
                    }
                    if (annotation.annotationType() == Json.class) {
                        name = ((Json) annotation).name();
                    }
                }
                Type type = field.getGenericType();
                if (Relationship.class.isAssignableFrom(Types.getRawType(type))) {
                    if (type instanceof ParameterizedType) {
                        Type typeParameter = ((ParameterizedType) type).getActualTypeArguments()[0];
                        if (!(typeParameter instanceof Class<?>)) {
                            throw new IllegalArgumentException("Unresolvable parameter type [" + type + "]");
                        }
                    } else {
                        throw new IllegalArgumentException("Expect linked type to be ParameterizedType");
                    }
                    relationships.put(name, field.getGenericType());
                } else {
                    attributes.put(name, moshi.adapter(type, annotationSet));
                }
                bindings.put(name, new Binding(field));
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public T fromJson(JsonReader reader) throws IOException {
            T resource;
            try {
                resource = type.newInstance();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (reader.peek() == JsonReader.Token.NULL) {
                    reader.skipValue(); // skip read of null values
                    continue;
                }
                switch (name) {
                    case "id": {
                        resource._id = reader.nextString();
                    } break;
                    case "type": {
                        resource._type = reader.nextString();
                    } break;
                    case "attributes": {
                        reader.beginObject();
                        while (reader.hasNext()) {
                            String key = reader.nextName();
                            if (reader.peek() == JsonReader.Token.NULL) {
                                reader.skipValue(); // skip read of null values
                                continue;
                            }
                            if (attributes.containsKey(key)) {
                                bindings.get(key).set(resource, attributes.get(key).fromJson(reader));
                            } else {
                                reader.skipValue();
                            }
                        }
                        reader.endObject();
                    } break;
                    case "relationships": {
                        reader.beginObject();
                        while (reader.hasNext()) {
                            String key = reader.nextName();
                            if (reader.peek() == JsonReader.Token.NULL) {
                                reader.skipValue(); // skip read of null values
                                continue;
                            }
                            if (relationships.containsKey(key)) {
                                Type type = relationships.get(key);
                                Class<?> rawType = Types.getRawType(type);
                                if (rawType == HasOne.class) {
                                    reader.beginObject();
                                    while (reader.hasNext()) {
                                        String key1 = reader.nextName();
                                        if (reader.peek() == JsonReader.Token.NULL) {
                                            reader.skipValue(); // skip read of null values
                                            continue;
                                        }
                                        switch (key1) {
                                            case "data": {
                                                bindings.get(key).set(resource, new HasOne<>(resource, linkageAdapter.fromJson(reader)));
                                            } break;
                                            default: {
                                                reader.skipValue();
                                            } break;
                                        }
                                    }
                                    reader.endObject();
                                } else if (rawType == HasMany.class) {
                                    reader.beginObject();
                                    while (reader.hasNext()) {
                                        switch (reader.nextName()) {
                                            case "data": {
                                                reader.beginArray();
                                                List<ResourceLinkage> linkages = new ArrayList<>();
                                                while (reader.hasNext()) {
                                                    linkages.add(linkageAdapter.fromJson(reader));
                                                }
                                                bindings.get(key).set(resource, new HasMany<>(
                                                        (Class<? extends Resource>) ((ParameterizedType) type).getActualTypeArguments()[0],
                                                        resource, linkages.toArray(new ResourceLinkage[linkages.size()])));
                                                reader.endArray();
                                            }
                                            break;
                                            default: {
                                                reader.skipValue();
                                            }
                                            break;
                                        }
                                    }
                                    reader.endObject();
                                }
                            } else {
                                reader.skipValue();
                            }
                        }
                        reader.endObject();
                    } break;
                    default: {
                        reader.skipValue();
                    } break;
                }
            }
            reader.endObject();
            return resource;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void toJson(JsonWriter writer, T value) throws IOException {
            writer.beginObject();
            writer.name("type").value(value._type);
            writer.name("id").value(value._id);
            boolean hasAttributes = false;
            for (Map.Entry<String, JsonAdapter> entry : attributes.entrySet()) {
                Binding binding = bindings.get(entry.getKey());
                Object v = binding.get(value);
                if (v != null) { // skip write of null values
                    if (!hasAttributes) {
                        writer.name("attributes").beginObject();
                        hasAttributes = true;
                    }
                    writer.name(entry.getKey());
                    entry.getValue().toJson(writer, v);
                }
            }
            if (hasAttributes) {
                writer.endObject();
            }
            boolean hasRelationships = false;
            for (String key : relationships.keySet()) {
                Object v = bindings.get(key).get(value);
                if (v == null) {
                    continue;
                }
                if (!hasRelationships) {
                    writer.name("relationships").beginObject();
                    hasRelationships = true;
                }
                if (v instanceof HasOne) {
                    ResourceLinkage linkage = ((HasOne) v).linkage;
                    writer.name(key).beginObject().name("data");
                    linkageAdapter.toJson(writer, linkage);
                    writer.endObject();
                } else if (v instanceof HasMany) {
                    ResourceLinkage[] linkages = ((HasMany) v).linkages;
                    writer.name(key).beginObject().name("data").beginArray();
                    for (ResourceLinkage linkage : linkages) {
                        linkageAdapter.toJson(writer, linkage);
                    }
                    writer.endArray().endObject();
                }
            }
            if (hasRelationships) {
                writer.endObject();
            }
            writer.endObject();
        }
    }

    private static class Binding {

        private final Field field;

        Binding(Field field) {
            this.field = field;
        }

        public void set(Object target, Object value){
            try {
                field.set(target, value);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        public Object get(Object source) {
            try {
                return field.get(source);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static String typeNameOf(Class<? extends Resource> type) {
        return type.getAnnotation(JsonApi.class).type();
    }

    @JsonApi(type = "__unresolved")
    static class UnresolvedResource extends Resource { }
}

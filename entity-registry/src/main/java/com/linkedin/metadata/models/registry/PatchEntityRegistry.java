package com.linkedin.metadata.models.registry;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.linkedin.data.schema.DataSchema;
import com.linkedin.metadata.models.AspectSpec;
import com.linkedin.metadata.models.DataSchemaFactory;
import com.linkedin.metadata.models.EntitySpec;
import com.linkedin.metadata.models.EntitySpecBuilder;
import com.linkedin.metadata.models.EventSpec;
import com.linkedin.metadata.models.registry.config.Entities;
import com.linkedin.metadata.models.registry.config.Entity;
import com.linkedin.metadata.models.registry.template.AspectTemplateEngine;
import com.linkedin.util.Pair;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.artifact.versioning.ComparableVersion;

import static com.linkedin.metadata.Constants.*;
import static com.linkedin.metadata.models.registry.EntityRegistryUtils.*;


/**
 * Implementation of {@link EntityRegistry} that is similar to {@link ConfigEntityRegistry} but different in one important way.
 * It builds potentially partially specified {@link com.linkedin.metadata.models.PartialEntitySpec} objects from an entity registry config yaml file
 */
@Slf4j
public class PatchEntityRegistry implements EntityRegistry {

  private final DataSchemaFactory dataSchemaFactory;
  private final Map<String, EntitySpec> entityNameToSpec;
  private final Map<String, EventSpec> eventNameToSpec;
  private final Map<String, AspectSpec> _aspectNameToSpec;

  private final String registryName;
  private final ComparableVersion registryVersion;
  private final String identifier;

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(new YAMLFactory());
  static {
    int maxSize = Integer.parseInt(System.getenv().getOrDefault(INGESTION_MAX_SERIALIZED_STRING_LENGTH, MAX_JACKSON_STRING_SIZE));
    OBJECT_MAPPER.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxStringLength(maxSize).build());
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("PatchEntityRegistry[" + "identifier=" + identifier + ';');
    entityNameToSpec.entrySet()
        .forEach(entry -> sb.append("[entityName=")
            .append(entry.getKey())
            .append(";aspects=[")
            .append(
                entry.getValue().getAspectSpecs().stream().map(AspectSpec::getName).collect(Collectors.joining(",")))
            .append("]]"));
    eventNameToSpec.entrySet()
        .forEach(entry -> sb.append("[eventName=")
            .append(entry.getKey())
            .append("]"));
    return sb.toString();
  }

  public PatchEntityRegistry(Pair<Path, Path> configFileClassPathPair, String registryName,
      ComparableVersion registryVersion) throws IOException, EntityRegistryException {
    this(DataSchemaFactory.withCustomClasspath(configFileClassPathPair.getSecond()), configFileClassPathPair.getFirst(),
        registryName, registryVersion);
  }

  public PatchEntityRegistry(String entityRegistryRoot, String registryName, ComparableVersion registryVersion)
      throws EntityRegistryException, IOException {
    this(EntityRegistryUtils.getFileAndClassPath(entityRegistryRoot), registryName, registryVersion);
  }

  public PatchEntityRegistry(DataSchemaFactory dataSchemaFactory, Path configFilePath, String registryName,
      ComparableVersion registryVersion) throws FileNotFoundException, EntityRegistryException {
    this(dataSchemaFactory, new FileInputStream(configFilePath.toString()), registryName, registryVersion);
  }

  private PatchEntityRegistry(DataSchemaFactory dataSchemaFactory, InputStream configFileStream, String registryName,
      ComparableVersion registryVersion) throws EntityRegistryException {
    this.dataSchemaFactory = dataSchemaFactory;
    this.registryName = registryName;
    this.registryVersion = registryVersion;
    entityNameToSpec = new HashMap<>();
    Entities entities = EntityRegistryUtils.readEntities(OBJECT_MAPPER, configFileStream);
    identifier = EntityRegistryUtils.getIdentifier(entities);

    // Build Entity Specs
    EntitySpecBuilder entitySpecBuilder = new EntitySpecBuilder();
    for (Entity entity : entities.getEntities()) {
      log.info("Discovered entity {} with aspects {}", entity.getName(),
              String.join(",", entity.getAspects()));
      List<AspectSpec> aspectSpecs = new ArrayList<>();
      if (entity.getKeyAspect() != null) {
        AspectSpec keyAspectSpec = buildAspectSpec(entity.getKeyAspect(), entitySpecBuilder);
        log.info("Adding key aspect {} with spec {}", entity.getKeyAspect(), keyAspectSpec);
        aspectSpecs.add(keyAspectSpec);
      }
      entity.getAspects().forEach(aspect -> {
        if (!aspect.equals(entity.getKeyAspect())) {
          AspectSpec aspectSpec = buildAspectSpec(aspect, entitySpecBuilder);
          log.info("Adding aspect {} with spec {}", aspect, aspectSpec);
          aspectSpecs.add(aspectSpec);
        }
      });

      EntitySpec entitySpec =
          entitySpecBuilder.buildPartialEntitySpec(entity.getName(), entity.getKeyAspect(), aspectSpecs);

      entityNameToSpec.put(entity.getName().toLowerCase(), entitySpec);
    }

    // Build Event Specs
    eventNameToSpec = EntityRegistryUtils.getEventNameToSpec(entities, dataSchemaFactory);
    _aspectNameToSpec = populateAspectMap(new ArrayList<>(entityNameToSpec.values()));
  }

  @Override
  public String getIdentifier() {
    return this.identifier;
  }

  @Nonnull
  @Override
  public EntitySpec getEntitySpec(@Nonnull String entityName) {
    String lowercaseEntityName = entityName.toLowerCase();
    if (!entityNameToSpec.containsKey(lowercaseEntityName)) {
      throw new IllegalArgumentException(
          String.format("Failed to find entity with name %s in EntityRegistry", entityName));
    }
    return entityNameToSpec.get(lowercaseEntityName);
  }

  @Nonnull
  @Override
  public EventSpec getEventSpec(@Nonnull String eventName) {
    String lowercaseEventName = eventName.toLowerCase();
    if (!eventNameToSpec.containsKey(lowercaseEventName)) {
      throw new IllegalArgumentException(
          String.format("Failed to find event with name %s in EntityRegistry", eventName));
    }
    return eventNameToSpec.get(lowercaseEventName);
  }

  @Nonnull
  @Override
  public Map<String, EntitySpec> getEntitySpecs() {
    return entityNameToSpec;
  }

  @Nonnull
  @Override
  public Map<String, AspectSpec> getAspectSpecs() {
    return _aspectNameToSpec;
  }

  @Nonnull
  @Override
  public Map<String, EventSpec> getEventSpecs() {
    return eventNameToSpec;
  }

  @Nonnull
  @Override
  public AspectTemplateEngine getAspectTemplateEngine() {
    //TODO: support patch based templates

    return new AspectTemplateEngine();
  }

  private AspectSpec buildAspectSpec(String aspectName, EntitySpecBuilder entitySpecBuilder) {
    Optional<DataSchema> aspectSchema = dataSchemaFactory.getAspectSchema(aspectName);
    Optional<Class> aspectClass = dataSchemaFactory.getAspectClass(aspectName);
    if (!aspectSchema.isPresent()) {
      throw new IllegalArgumentException(String.format("Aspect %s does not exist", aspectName));
    }
    AspectSpec aspectSpec = entitySpecBuilder.buildAspectSpec(aspectSchema.get(), aspectClass.get());
    aspectSpec.setRegistryName(this.registryName);
    aspectSpec.setRegistryVersion(this.registryVersion);
    return aspectSpec;
  }

}

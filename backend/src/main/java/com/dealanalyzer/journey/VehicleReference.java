package com.dealanalyzer.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The versioned make, model, trim and feature list a Shopping Brief chooses from. Briefs store
 * identifiers plus this list's version, so a later list cannot reinterpret an earlier brief.
 */
@Component
public class VehicleReference {
  static final String RESOURCE = "shopping/vehicle-reference-v1.json";

  public record Option(String id, String label) {}
  public record Model(String id, String label, List<Option> trims) {}
  public record Make(String id, String label, List<Model> models) {}
  public record ReferenceList(String version, String description, List<Make> makes, List<Option> features) {}

  private final ReferenceList list;
  private final Map<String, Make> makes = new LinkedHashMap<>();
  private final Map<String, Model> models = new LinkedHashMap<>();
  private final Map<String, String> modelMake = new LinkedHashMap<>();
  private final Map<String, Option> features = new LinkedHashMap<>();

  VehicleReference(ObjectMapper json) {
    try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
      list = json.readValue(input, ReferenceList.class);
    } catch (IOException failure) { throw new UncheckedIOException("Vehicle reference list unavailable", failure); }
    if (list.version() == null || list.version().isBlank()) throw new IllegalStateException("Vehicle reference list has no version");
    for (var make : list.makes()) {
      unique(makes.put(make.id(), make));
      for (var model : make.models()) { unique(models.put(model.id(), model)); modelMake.put(model.id(), make.id()); }
    }
    for (var feature : list.features()) unique(features.put(feature.id(), feature));
  }

  private static void unique(Object previous) {
    if (previous != null) throw new IllegalStateException("Vehicle reference list repeats an identifier");
  }

  public ReferenceList list() { return list; }
  public String version() { return list.version(); }
  Optional<Make> make(String id) { return Optional.ofNullable(id == null ? null : makes.get(id)); }

  /** The model, only when it belongs to the given make. */
  Optional<Model> model(String makeId, String modelId) {
    if (modelId == null || makeId == null || !makeId.equals(modelMake.get(modelId))) return Optional.empty();
    return Optional.of(models.get(modelId));
  }

  Optional<Option> trim(Model model, String trimId) {
    return model.trims().stream().filter(t -> t.id().equals(trimId)).findFirst();
  }

  Optional<Option> feature(String id) { return Optional.ofNullable(id == null ? null : features.get(id)); }
}

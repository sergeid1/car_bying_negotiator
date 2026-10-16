package com.dealanalyzer.operations;

import com.dealanalyzer.ai.ModelProvenance;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** An in-memory usage store for tests outside this package that need a real {@link ProviderMeter}. */
public class RecordingUsageStore extends ProviderUsageStore {
  public record Row(ModelProvenance provenance, String license, long units, ProviderMeter.Status status,
      ProviderMeter.ModelUsage usage) {}

  public final List<Row> rows = new CopyOnWriteArrayList<>();
  private final List<Object[]> started = new CopyOnWriteArrayList<>();

  public RecordingUsageStore() { super(null); }

  @Override public String start(String caseId, ProviderMeter.Operation operation, Object provider, long units) {
    started.add(new Object[] {null, null, units});
    return String.valueOf(started.size() - 1);
  }

  @Override public String startModel(String caseId, ProviderMeter.Operation operation, ModelProvenance provenance,
      String license, long units) {
    started.add(new Object[] {provenance, license, units});
    return String.valueOf(started.size() - 1);
  }

  @Override public String startModelScoped(ModelUsageScope scope, ProviderMeter.Operation operation,
      ModelProvenance provenance, String license, long units) {
    return startModel(scope.caseId(), operation, provenance, license, units);
  }

  @Override public void finish(String id, ProviderMeter.Status status) { finish(id, status, null); }

  @Override public void finish(String id, ProviderMeter.Status status, ProviderMeter.ModelUsage usage) {
    Object[] start = started.get(Integer.parseInt(id));
    rows.add(new Row((ModelProvenance) start[0], (String) start[1], (long) start[2], status, usage));
  }

  public Row last() { return rows.getLast(); }
}

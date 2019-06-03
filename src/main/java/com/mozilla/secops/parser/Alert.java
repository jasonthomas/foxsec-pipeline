package com.mozilla.secops.parser;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Payload parser for incoming alert events
 *
 * <p>This parser will process alerts that are generated by pipelines, permitting ingestion of
 * alerts as part of feedback from other analysis components.
 */
public class Alert extends PayloadBase implements Serializable {
  private static final long serialVersionUID = 1L;

  private com.mozilla.secops.alert.Alert alert;

  /**
   * Get alert object
   *
   * @return Alert
   */
  public com.mozilla.secops.alert.Alert getAlert() {
    return alert;
  }

  private Map<String, Object> convertInput(String input) {
    ObjectMapper mapper = new ObjectMapper();
    Map<String, Object> fields = new HashMap<String, Object>();
    try {
      fields = mapper.readValue(input, new TypeReference<Map<String, Object>>() {});
    } catch (IOException exc) {
      return null;
    }
    return fields;
  }

  @Override
  public Boolean matcher(String input, ParserState state) {
    Map<String, Object> fields = convertInput(input);
    if (fields == null) {
      return false;
    }
    if ((fields.get("summary") != null)
        && (fields.get("metadata") != null)
        && (fields.get("severity") != null)
        && (fields.get("category") != null)
        && (fields.get("id") != null)
        && (fields.get("timestamp") != null)) {
      return true;
    }
    return false;
  }

  @Override
  @JsonProperty("type")
  public Payload.PayloadType getType() {
    return Payload.PayloadType.ALERT;
  }

  /** Construct matcher object. */
  public Alert() {}

  /**
   * Construct parser object.
   *
   * @param input Input string.
   * @param e Parent {@link Event}.
   * @param state State
   */
  public Alert(String input, Event e, ParserState state) {
    alert = com.mozilla.secops.alert.Alert.fromJSON(input);
  }
}

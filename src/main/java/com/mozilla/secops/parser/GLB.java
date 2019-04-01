package com.mozilla.secops.parser;

import com.google.api.client.json.JsonParser;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.logging.v2.model.HttpRequest;
import com.google.api.services.logging.v2.model.LogEntry;
import java.io.IOException;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import org.joda.time.DateTime;

/** Payload parser for Google Load Balancer log data. */
public class GLB extends PayloadBase implements Serializable {
  private static final long serialVersionUID = 1L;

  private final JacksonFactory jfmatcher;

  private String requestMethod;
  private String userAgent;
  private String requestUrl;
  private String sourceAddress;
  private Integer status;
  private URL parsedUrl;

  @Override
  public Boolean matcher(String input, ParserState state) {
    try {
      LogEntry entry = state.getLogEntryHint();
      if (entry == null) {
        JsonParser jp = jfmatcher.createJsonParser(input);
        entry = jp.parse(LogEntry.class);
        jp.close();
      }

      Map<String, Object> m = entry.getJsonPayload();
      if (m == null) {
        return false;
      }
      String eType = (String) m.get("@type");
      if (eType != null) {
        if (eType.equals(
            "type.googleapis.com/google.cloud.loadbalancing.type.LoadBalancerLogEntry")) {
          return true;
        }
      }
    } catch (IOException exc) {
      // pass
    } catch (IllegalArgumentException exc) {
      // pass
    }
    return false;
  }

  @Override
  public Payload.PayloadType getType() {
    return Payload.PayloadType.GLB;
  }

  /** Construct matcher object. */
  public GLB() {
    jfmatcher = new JacksonFactory();
  }

  /**
   * Construct parser object.
   *
   * @param input Input string.
   * @param e Parent {@link Event}.
   * @param state State
   */
  public GLB(String input, Event e, ParserState state) {
    jfmatcher = null;
    LogEntry entry = state.getLogEntryHint();
    if (entry == null) {
      // Reuse JacksonFactory from parser state
      JacksonFactory jf = state.getGoogleJacksonFactory();
      JsonParser jp;
      try {
        jp = jf.createJsonParser(input);
      } catch (IOException exc) {
        return;
      }
      try {
        entry = jp.parse(LogEntry.class);
      } catch (IOException exc) {
        return;
      } finally {
        try {
          jp.close();
        } catch (IOException jexc) {
          throw new RuntimeException(jexc.getMessage());
        }
      }
    }
    HttpRequest h = entry.getHttpRequest();
    if (h == null) {
      return;
    }

    String ets = entry.getTimestamp();
    if (ets != null) {
      DateTime d = Parser.parseISO8601(ets);
      if (d != null) {
        e.setTimestamp(d);
      }
    }

    sourceAddress = h.getRemoteIp();
    requestUrl = h.getRequestUrl();
    userAgent = h.getUserAgent();
    requestMethod = h.getRequestMethod();
    status = h.getStatus();

    if (h.getRequestUrl() != null) {
      try {
        parsedUrl = new URL(h.getRequestUrl());
      } catch (MalformedURLException exc) {
        // pass
      }
    }

    Normalized n = e.getNormalized();
    n.addType(Normalized.Type.HTTP_REQUEST);
    n.setSourceAddress(sourceAddress);
    n.setUserAgent(userAgent);
    n.setRequestMethod(requestMethod);
    n.setRequestStatus(status);
    n.setRequestUrl(requestUrl);
    if (parsedUrl != null) {
      n.setUrlRequestPath(parsedUrl.getPath());
      n.setUrlRequestHost(parsedUrl.getHost());
    }
  }

  @Override
  public String eventStringValue(EventFilterPayload.StringProperty property) {
    switch (property) {
      case GLB_REQUESTMETHOD:
        return requestMethod;
      case GLB_URLREQUESTPATH:
        if (parsedUrl == null) {
          return null;
        }
        return parsedUrl.getPath();
    }
    return null;
  }

  @Override
  public Integer eventIntegerValue(EventFilterPayload.IntegerProperty property) {
    switch (property) {
      case GLB_STATUS:
        return status;
    }
    return null;
  }

  /**
   * Get parsed URL object
   *
   * @return URL
   */
  public URL getParsedUrl() {
    return parsedUrl;
  }

  /**
   * Get request URL.
   *
   * @return Request URL string.
   */
  public String getRequestUrl() {
    return requestUrl;
  }

  /**
   * Get user agent.
   *
   * @return User agent string.
   */
  public String getUserAgent() {
    return userAgent;
  }

  /**
   * Get request method.
   *
   * @return Request method string.
   */
  public String getRequestMethod() {
    return requestMethod;
  }

  /**
   * Get source address.
   *
   * @return Source address string.
   */
  public String getSourceAddress() {
    return sourceAddress;
  }

  /**
   * Get status.
   *
   * @return status integer.
   */
  public Integer getStatus() {
    return status;
  }
}

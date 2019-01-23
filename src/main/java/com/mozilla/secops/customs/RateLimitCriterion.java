package com.mozilla.secops.customs;

import com.mozilla.secops.alert.Alert;
import com.mozilla.secops.parser.Event;
import com.mozilla.secops.parser.SecEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Operate in conjunction with {@link RateLimitAnalyzer} to apply analysis criterion to incoming
 * event stream.
 */
public class RateLimitCriterion extends DoFn<KV<String, Long>, KV<String, Alert>> {
  private static final long serialVersionUID = 1L;

  private final int MAX_SAMPLE = 5;

  private final Alert.AlertSeverity severity;
  private final String customsMeta;
  private final String monitoredResource;
  private final Long limit;
  private final PCollectionView<Map<String, Iterable<Event>>> eventView;

  private Logger log;

  /**
   * {@link RateLimitCriterion} static initializer
   *
   * @param severity Severity to use for generated alerts
   * @param customsMeta Customs metadata tag to place on alert
   * @param limit Generate alert if count meets or exceeds limit value in window
   */
  public RateLimitCriterion(
      Alert.AlertSeverity severity,
      String customsMeta,
      Long limit,
      PCollectionView<Map<String, Iterable<Event>>> eventView,
      String monitoredResource) {
    this.severity = severity;
    this.customsMeta = customsMeta;
    this.limit = limit;
    this.eventView = eventView;
    this.monitoredResource = monitoredResource;
  }

  @Setup
  public void setup() {
    log = LoggerFactory.getLogger(RateLimitCriterion.class);
    log.info(
        "initialized new rate limit criterion analyzer, {} {} {}", severity, customsMeta, limit);
  }

  private Boolean uniqueAttribute(Iterable<Event> eventList, Function<Event, String> fn) {
    Event[] events = ((Collection<Event>) eventList).toArray(new Event[0]);
    if (events.length == 0) {
      return false;
    }
    if (events.length == 1) {
      return true;
    }
    String comp = fn.apply(events[0]);
    if (comp == null) {
      return false;
    }
    for (Event e : events) {
      if (!(fn.apply(e).equals(comp))) {
        return false;
      }
    }
    return true;
  }

  @ProcessElement
  public void processElement(ProcessContext c) {
    KV<String, Long> e = c.element();
    Map<String, Iterable<Event>> eventMap = c.sideInput(eventView);

    String key = e.getKey();
    Long valueCount = e.getValue();
    if (valueCount < limit) {
      return;
    }

    // Take a arbitrary sample of any events that were included in the detection window to be added
    // to the alert as metadata
    ArrayList<Event> sample = new ArrayList<Event>();
    Boolean sampleTruncated = false;
    Iterable<Event> eventList = eventMap.get(key);
    int i = 0;
    if (eventList != null) {
      for (Event ev : eventList) {
        sample.add(ev);
        if (++i >= MAX_SAMPLE) {
          sampleTruncated = true;
          break;
        }
      }
    }

    Alert alert = new Alert();

    // Set our category to customs to indicate this alert originated from the customs pipeline
    alert.setCategory("customs");

    // Add the name of the detector to the metadata to indicate the detector rule that fired
    alert.addMetadata("customs_category", customsMeta);

    // customs_suspected is set to include the key on which the rate limiting logic operated, this
    // could be a single value or multiple values joined with a + symbol depending on the
    // detector configuration
    alert.addMetadata("customs_suspected", key);

    // The number of events seen for the key within the window, and the threshold setting in the
    // detector configuration. Since an alert is being generated the count will always meet or
    // exceed the threshold.
    alert.addMetadata("customs_count", valueCount.toString());
    alert.addMetadata("customs_threshold", limit.toString());

    // Set an alert summary
    alert.setSummary(
        String.format(
            "%s customs %s %s %d %d", monitoredResource, customsMeta, key, valueCount, limit));

    // If all of the in-scope events in the alert pertain to the same account ID, include this
    // unique account ID value as metadata.
    if (uniqueAttribute(
        eventList, le -> le.<SecEvent>getPayload().getSecEventData().getActorAccountId())) {
      alert.addMetadata(
          "customs_unique_actor_accountid",
          sample.get(0).<SecEvent>getPayload().getSecEventData().getActorAccountId());
    }

    // If SMS recipient was the same for all events, store that as metadata
    if (uniqueAttribute(
        eventList, le -> le.<SecEvent>getPayload().getSecEventData().getSmsRecipient())) {
      alert.addMetadata(
          "customs_unique_sms_recipient",
          sample.get(0).<SecEvent>getPayload().getSecEventData().getSmsRecipient());
    }

    // If email recipient was the same for all events, store that as metadata
    if (uniqueAttribute(
        eventList, le -> le.<SecEvent>getPayload().getSecEventData().getEmailRecipient())) {
      alert.addMetadata(
          "customs_unique_email_recipient",
          sample.get(0).<SecEvent>getPayload().getSecEventData().getEmailRecipient());
    }

    // If source address was unique for all events, store that as metadata
    if (uniqueAttribute(
        eventList, le -> le.<SecEvent>getPayload().getSecEventData().getSourceAddress())) {
      alert.addMetadata(
          "customs_unique_source_address",
          sample.get(0).<SecEvent>getPayload().getSecEventData().getSourceAddress());

      // Since we had a unique source address, also see if we can pull in unique country and city
      // values as well
      if (uniqueAttribute(
          eventList, le -> le.<SecEvent>getPayload().getSecEventData().getSourceAddressCity())) {
        alert.addMetadata(
            "customs_unique_source_address_city",
            sample.get(0).<SecEvent>getPayload().getSecEventData().getSourceAddressCity());
      }
      if (uniqueAttribute(
          eventList, le -> le.<SecEvent>getPayload().getSecEventData().getSourceAddressCountry())) {
        alert.addMetadata(
            "customs_unique_source_address_country",
            sample.get(0).<SecEvent>getPayload().getSecEventData().getSourceAddressCountry());
      }
    }

    // If object account ID was unique for all events, store that as metadata
    if (uniqueAttribute(
        eventList, le -> le.<SecEvent>getPayload().getSecEventData().getDestinationAccountId())) {
      alert.addMetadata(
          "customs_unique_object_accountid",
          sample.get(0).<SecEvent>getPayload().getSecEventData().getDestinationAccountId());
    }

    // Finally, store a sample of events that were part of this detection as metadata in the
    // alert.
    if (sample.size() > 0) {
      alert.addMetadata("customs_sample", Event.iterableToJson(sample));
      alert.addMetadata("customs_sample_truncated", sampleTruncated.toString());
    }

    if (!alert.hasCorrectFields()) {
      throw new IllegalArgumentException("alert has invalid field configuration");
    }

    alert.setSeverity(severity);
    c.output(KV.of(key, alert));
  }
}

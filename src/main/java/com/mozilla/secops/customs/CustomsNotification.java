package com.mozilla.secops.customs;

import com.mozilla.secops.alert.Alert;
import java.util.ArrayList;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubIO;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PDone;

/**
 * Convert {@link Alert} objects generated by pipeline to {@link CustomsAlert} and submit them over
 * Pubsub.
 */
public class CustomsNotification extends PTransform<PCollection<String>, PDone> {
  private static final long serialVersionUID = 1L;

  private final String topic;

  private final Boolean escalateAccountCreation;
  private final Boolean escalateAccountCreationDistributed;
  private final Boolean escalateSourceLoginFailure;
  private final Boolean escalateSourceLoginFailureDistributed;
  private final Boolean escalatePasswordResetAbuse;

  /**
   * Initialize new CustomsNotification
   *
   * @param options Pipeline options
   */
  public CustomsNotification(Customs.CustomsOptions options) {
    topic = options.getCustomsNotificationTopic();

    escalateAccountCreation = options.getEscalateAccountCreation();
    escalateAccountCreationDistributed = options.getEscalateAccountCreationDistributed();
    escalateSourceLoginFailure = options.getEscalateSourceLoginFailure();
    escalateSourceLoginFailureDistributed = options.getEscalateSourceLoginFailureDistributed();
    escalatePasswordResetAbuse = options.getEscalatePasswordResetAbuse();
  }

  private Boolean allowEscalation(Alert a) {
    switch (a.getMetadataValue("customs_category")) {
      case Customs.CATEGORY_ACCOUNT_CREATION_ABUSE:
        return escalateAccountCreation;
      case Customs.CATEGORY_ACCOUNT_CREATION_ABUSE_DIST:
        return escalateAccountCreationDistributed;
      case Customs.CATEGORY_SOURCE_LOGIN_FAILURE:
        return escalateSourceLoginFailure;
      case Customs.CATEGORY_SOURCE_LOGIN_FAILURE_DIST:
        return escalateSourceLoginFailureDistributed;
      case Customs.CATEGORY_PASSWORD_RESET_ABUSE:
        return escalatePasswordResetAbuse;
    }
    return false;
  }

  @Override
  public PDone expand(PCollection<String> col) {
    return col.apply(
            ParDo.of(
                new DoFn<String, String>() {
                  private static final long serialVersionUID = 1L;

                  @ProcessElement
                  public void processElement(ProcessContext c) {
                    Alert a = Alert.fromJSON(c.element());
                    if (!allowEscalation(a)) {
                      return;
                    }
                    ArrayList<CustomsAlert> ca = CustomsAlert.fromAlert(a);
                    if (ca == null) {
                      return;
                    }
                    for (CustomsAlert i : ca) {
                      c.output(i.toJSON());
                    }
                  }
                }))
        .apply(PubsubIO.writeStrings().to(topic));
  }
}

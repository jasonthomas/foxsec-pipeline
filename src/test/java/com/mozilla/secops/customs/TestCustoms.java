package com.mozilla.secops.customs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.mozilla.secops.TestUtil;
import com.mozilla.secops.alert.Alert;
import com.mozilla.secops.alert.AlertMeta;
import com.mozilla.secops.input.Input;
import com.mozilla.secops.parser.Event;
import com.mozilla.secops.parser.ParserDoFn;
import com.mozilla.secops.parser.ParserTest;
import com.mozilla.secops.state.DatastoreStateInterface;
import com.mozilla.secops.state.State;
import com.mozilla.secops.window.GlobalTriggers;
import java.util.Arrays;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.testing.TestStream;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.EnvironmentVariables;

public class TestCustoms {
  @Rule public final transient TestPipeline p = TestPipeline.create();
  @Rule public final EnvironmentVariables environmentVariables = new EnvironmentVariables();

  private Customs.CustomsOptions getTestOptions() {
    Customs.CustomsOptions ret = PipelineOptionsFactory.as(Customs.CustomsOptions.class);
    ret.setUseEventTimestamp(true);
    ret.setMonitoredResourceIndicator("test");
    ret.setMaxmindCityDbPath(ParserTest.TEST_GEOIP_DBPATH);
    ret.setDatastoreNamespace("testcustoms");
    return ret;
  }

  private void testEnv() throws Exception {
    environmentVariables.set("DATASTORE_EMULATOR_HOST", "localhost:8081");
    environmentVariables.set("DATASTORE_EMULATOR_HOST_PATH", "localhost:8081/datastore");
    environmentVariables.set("DATASTORE_HOST", "http://localhost:8081");
    environmentVariables.set("DATASTORE_PROJECT_ID", "foxsec-pipeline");
    clearState();
  }

  public void clearState() throws Exception {
    State state =
        new State(new DatastoreStateInterface(CustomsVelocity.VELOCITY_KIND, "testcustoms"));
    state.initialize();
    state.deleteAll();
    state.done();
    state =
        new State(
            new DatastoreStateInterface(
                PrivateRelayForward.DATASTORE_KIND, PrivateRelayForward.DATASTORE_NAMESPACE));
    state.initialize();
    state.deleteAll();
    state.done();
  }

  public TestCustoms() {}

  @Test
  public void parseTest() throws Exception {
    String[] eb1 = TestUtil.getTestInputArray("/testdata/customs_rl_badlogin_simple1.txt");

    TestStream<String> s =
        TestStream.create(StringUtf8Coder.of())
            .advanceWatermarkTo(new Instant(0L))
            .addElements(eb1[0], Arrays.copyOfRange(eb1, 1, eb1.length))
            .advanceWatermarkToInfinity();

    PCollection<Long> count =
        p.apply(s)
            .apply(ParDo.of(new ParserDoFn()))
            .apply(new GlobalTriggers<Event>(5))
            .apply(Combine.globally(Count.<Event>combineFn()).withoutDefaults());

    PAssert.thatSingleton(count).isEqualTo(26L);

    p.run().waitUntilFinish();
  }

  @Test
  public void accountCreationAbuseTest() throws Exception {
    String[] eb1 = TestUtil.getTestInputArray("/testdata/customs_createacctabuse.txt");
    String[] eb2 = TestUtil.getTestInputArray("/testdata/customs_rl_badlogin_simple1.txt");
    TestStream<String> s =
        TestStream.create(StringUtf8Coder.of())
            .advanceWatermarkTo(new Instant(0L))
            .addElements(eb1[0], Arrays.copyOfRange(eb1, 1, eb1.length))
            .advanceProcessingTime(Duration.standardSeconds(5))
            // Add some unrelated elements for the second component
            .addElements(eb2[0], Arrays.copyOfRange(eb2, 1, eb2.length))
            .advanceProcessingTime(Duration.standardSeconds(5))
            .advanceWatermarkToInfinity();

    Customs.CustomsOptions options = getTestOptions();
    options.setEnableAccountCreationAbuseDetector(true);
    options.setAccountCreationThreshold(3);
    options.setXffAddressSelector("127.0.0.1/32");
    options.setGenerateConfigurationTicksInterval(1);
    options.setGenerateConfigurationTicksMaximum(5L);

    Input input = TestCustomsUtil.wiredInputStream(options, s);

    PCollection<Alert> alerts =
        Customs.executePipeline(p, p.apply(input.simplexReadRaw()), options);

    PAssert.that(alerts)
        .satisfies(
            x -> {
              int alertCnt = 0;
              int totalCnt = 0;
              for (Alert a : x) {
                totalCnt++;
                if (a.getCategory().equals("customs")) {
                  assertEquals("customs", a.getCategory());
                  assertEquals("216.160.83.56", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS));
                  assertEquals("3", a.getMetadataValue(AlertMeta.Key.COUNT));
                  assertEquals(
                      "account_creation_abuse",
                      a.getMetadataValue(AlertMeta.Key.ALERT_SUBCATEGORY_FIELD));
                  assertEquals("test suspicious account creation, 216.160.83.56 3", a.getSummary());
                  alertCnt++;
                }
              }
              assertEquals(1, alertCnt);
              assertEquals(6, totalCnt);
              return null;
            });

    p.run().waitUntilFinish();
  }

  @Test
  public void accountCreationAbuseTestDist() throws Exception {
    String[] eb1 = TestUtil.getTestInputArray("/testdata/customs_createacctabuse_dist.txt");
    TestStream<String> s =
        TestStream.create(StringUtf8Coder.of())
            .advanceWatermarkTo(new Instant(0L))
            .addElements(eb1[0], Arrays.copyOfRange(eb1, 1, eb1.length))
            .advanceWatermarkToInfinity();

    Customs.CustomsOptions options = getTestOptions();
    options.setEnableAccountCreationAbuseDetector(true);
    options.setXffAddressSelector("127.0.0.1/32");
    // Increase session creation limit here so we don't trip an alert for that as part of
    // the same address component of the test
    options.setAccountCreationThreshold(10);
    options.setAccountCreationDistributedThreshold(5);

    PCollection<Alert> alerts = Customs.executePipeline(p, p.apply(s), options);

    PCollection<Long> count =
        alerts.apply(Combine.globally(Count.<Alert>combineFn()).withoutDefaults());
    PAssert.thatSingleton(count).isEqualTo(6L);

    PAssert.that(alerts)
        .satisfies(
            x -> {
              int cnt = 0;
              for (Alert a : x) {
                if (!a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS).equals("216.160.83.56")) {
                  continue;
                }
                assertEquals("customs", a.getCategory());
                assertEquals("216.160.83.56", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS));
                assertEquals("6", a.getMetadataValue(AlertMeta.Key.COUNT));
                assertEquals("user3@mail.com", a.getMetadataValue(AlertMeta.Key.EMAIL));
                assertEquals(
                    "account_creation_abuse_distributed",
                    a.getMetadataValue(AlertMeta.Key.NOTIFY_MERGE));
                assertEquals(
                    "account_creation_abuse_distributed",
                    a.getMetadataValue(AlertMeta.Key.ALERT_SUBCATEGORY_FIELD));
                assertEquals(
                    "test suspicious distributed account creation, 216.160.83.56 6",
                    a.getSummary());
                cnt++;
              }
              assertEquals(1, cnt);
              return null;
            });

    p.run().waitUntilFinish();
  }

  @Test
  public void sourceLoginFailureTest() throws Exception {
    String[] eb1 = TestUtil.getTestInputArray("/testdata/customs_rl_badlogin_simple1.txt");
    TestStream<String> s =
        TestStream.create(StringUtf8Coder.of())
            .advanceWatermarkTo(new Instant(0L))
            .addElements(eb1[0], Arrays.copyOfRange(eb1, 1, eb1.length))
            .advanceWatermarkToInfinity();

    Customs.CustomsOptions options = getTestOptions();
    options.setEnableSourceLoginFailureDetector(true);
    // Also test summary generation here
    options.setEnableSummaryAnalysis(true);
    options.setSourceLoginFailureThreshold(10);
    options.setXffAddressSelector("127.0.0.1/32");

    PCollection<Alert> alerts = Customs.executePipeline(p, p.apply(s), options);

    PAssert.that(alerts)
        .satisfies(
            x -> {
              int lfCnt = 0;
              int lfdCnt = 0;
              int sCnt = 0;
              int tCnt = 0;
              for (Alert a : x) {
                if (a.getMetadataValue(AlertMeta.Key.ALERT_SUBCATEGORY_FIELD)
                    .equals("source_login_failure")) {
                  assertEquals("customs", a.getCategory());
                  assertEquals("216.160.83.56", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS));
                  // Should be 10, since two events have a blocked errno and shouldn't be factored
                  // in
                  assertEquals("10", a.getMetadataValue(AlertMeta.Key.COUNT));
                  assertEquals("spock@mozilla.com", a.getMetadataValue(AlertMeta.Key.EMAIL));
                  assertEquals(
                      "source_login_failure", a.getMetadataValue(AlertMeta.Key.NOTIFY_MERGE));
                  assertEquals(
                      "source_login_failure",
                      a.getMetadataValue(AlertMeta.Key.ALERT_SUBCATEGORY_FIELD));
                  assertEquals(
                      "test source login failure threshold exceeded, 216.160.83.56 10 in 10 minutes",
                      a.getSummary());
                  lfCnt++;
                } else if (a.getMetadataValue(AlertMeta.Key.ALERT_SUBCATEGORY_FIELD)
                    .equals("summary")) {
                  assertEquals("customs", a.getCategory());
                  assertEquals("22", a.getCustomMetadataValue("login_failure"));
                  assertEquals("test summary for period, login_failure 22", a.getSummary());
                  sCnt++;
                } else if (a.getMetadataValue(AlertMeta.Key.ALERT_SUBCATEGORY_FIELD)
                    .equals("source_login_failure_distributed")) {
                  assertEquals("customs", a.getCategory());
                  // Should be ten, since two addresses are duplicates
                  assertEquals("10", a.getMetadataValue(AlertMeta.Key.COUNT));
                  assertEquals("kirk@mozilla.com", a.getMetadataValue(AlertMeta.Key.EMAIL));
                  assertEquals(
                      "source_login_failure_distributed",
                      a.getMetadataValue(AlertMeta.Key.NOTIFY_MERGE));
                  assertEquals(
                      "source_login_failure_distributed",
                      a.getMetadataValue(AlertMeta.Key.ALERT_SUBCATEGORY_FIELD));
                  assertEquals(
                      "test distributed source login failure threshold exceeded for single "
                          + "account, 10 addresses in 10 minutes",
                      a.getSummary());
                  lfdCnt++;
                } else {
                  fail(
                      String.format(
                          "unexpected category %s",
                          a.getMetadataValue(AlertMeta.Key.ALERT_SUBCATEGORY_FIELD)));
                }
                tCnt++;
              }
              assertEquals(1, lfCnt);
              assertEquals(1, lfdCnt);
              assertEquals(1, sCnt);
              assertEquals(3, tCnt);
              return null;
            });

    p.run().waitUntilFinish();
  }

  @Test
  public void velocityTest() throws Exception {
    testEnv();

    String[] eb1 = TestUtil.getTestInputArray("/testdata/customs_velocity1.txt");
    TestStream<String> s =
        TestStream.create(StringUtf8Coder.of())
            .advanceWatermarkTo(new Instant(0L))
            .addElements(eb1[0], Arrays.copyOfRange(eb1, 1, eb1.length))
            .advanceWatermarkToInfinity();

    Customs.CustomsOptions options = getTestOptions();
    options.setEnableVelocityDetector(true);
    options.setXffAddressSelector("127.0.0.1/32");

    PCollection<Alert> alerts = Customs.executePipeline(p, p.apply(s), options);

    PAssert.that(alerts)
        .satisfies(
            x -> {
              int cnt = 0;
              for (Alert a : x) {
                assertEquals("81.2.69.192", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS));
                assertEquals(
                    "216.160.83.56", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_PREVIOUS));
                assertEquals(
                    "00000000000000000000000000000000", a.getMetadataValue(AlertMeta.Key.UID));
                assertEquals("riker@mozilla.com", a.getMetadataValue(AlertMeta.Key.EMAIL));
                assertEquals(
                    "test 00000000000000000000000000000000 velocity exceeded, "
                        + "7740.82 km in 9 seconds",
                    a.getSummary());
                assertEquals("velocity", a.getMetadataValue(AlertMeta.Key.NOTIFY_MERGE));
                assertEquals("London", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_CITY));
                assertEquals("GB", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_COUNTRY));
                assertEquals(
                    "Milton", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_PREVIOUS_CITY));
                assertEquals(
                    "US", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_PREVIOUS_COUNTRY));
                assertEquals("7740.82", a.getMetadataValue(AlertMeta.Key.KM_DISTANCE));
                assertEquals("9", a.getMetadataValue(AlertMeta.Key.TIME_DELTA_SECONDS));
                cnt++;
              }
              assertEquals(1, cnt);
              return null;
            });

    p.run().waitUntilFinish();
  }

  @Test
  public void velocityMonitorOnlyTest() throws Exception {
    testEnv();

    String[] eb1 = TestUtil.getTestInputArray("/testdata/customs_velocity1.txt");
    TestStream<String> s =
        TestStream.create(StringUtf8Coder.of())
            .advanceWatermarkTo(new Instant(0L))
            .addElements(eb1[0], Arrays.copyOfRange(eb1, 1, eb1.length))
            .advanceWatermarkToInfinity();

    Customs.CustomsOptions options = getTestOptions();
    options.setEnableVelocityDetectorMonitorOnly(true);
    options.setEnableVelocityDetector(true);
    options.setXffAddressSelector("127.0.0.1/32");

    PCollection<Alert> alerts = Customs.executePipeline(p, p.apply(s), options);

    PAssert.that(alerts)
        .satisfies(
            x -> {
              int cnt = 0;
              int regular = 0;
              int monitorOnly = 0;
              for (Alert a : x) {
                if (a.getMetadataValue(AlertMeta.Key.NOTIFY_MERGE).equals("velocity")) {
                  assertEquals(
                      "test 00000000000000000000000000000000 velocity exceeded, "
                          + "7740.82 km in 9 seconds",
                      a.getSummary());
                  regular++;
                } else if (a.getMetadataValue(AlertMeta.Key.NOTIFY_MERGE)
                    .equals("velocity_monitor_only")) {
                  assertEquals(
                      "test 00000000000000000000000000000000 velocity exceeded, "
                          + "7740.82 km in 9 seconds (monitor only)",
                      a.getSummary());
                  monitorOnly++;
                }
                assertEquals("81.2.69.192", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS));
                assertEquals(
                    "216.160.83.56", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_PREVIOUS));
                assertEquals(
                    "00000000000000000000000000000000", a.getMetadataValue(AlertMeta.Key.UID));
                assertEquals("riker@mozilla.com", a.getMetadataValue(AlertMeta.Key.EMAIL));
                assertEquals("London", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_CITY));
                assertEquals("GB", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_COUNTRY));
                assertEquals(
                    "Milton", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_PREVIOUS_CITY));
                assertEquals(
                    "US", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_PREVIOUS_COUNTRY));
                assertEquals("7740.82", a.getMetadataValue(AlertMeta.Key.KM_DISTANCE));
                assertEquals("9", a.getMetadataValue(AlertMeta.Key.TIME_DELTA_SECONDS));
                cnt++;
              }
              assertEquals(2, cnt);
              assertEquals(1, regular);
              assertEquals(1, monitorOnly);
              return null;
            });

    p.run().waitUntilFinish();
  }

  @Test
  public void velocityMonitorOnly2Test() throws Exception {
    testEnv();

    String[] eb1 = TestUtil.getTestInputArray("/testdata/customs_velocity1.txt");
    TestStream<String> s =
        TestStream.create(StringUtf8Coder.of())
            .advanceWatermarkTo(new Instant(0L))
            .addElements(eb1[0], Arrays.copyOfRange(eb1, 1, eb1.length))
            .advanceWatermarkToInfinity();

    // tests when monitor is smaller than actual
    Customs.CustomsOptions options = getTestOptions();
    options.setEnableVelocityDetector(true);
    options.setEnableVelocityDetectorMonitorOnly(true);
    options.setMinimumDistanceForAlert(8000.0);
    options.setXffAddressSelector("127.0.0.1/32");

    PCollection<Alert> alerts = Customs.executePipeline(p, p.apply(s), options);

    PAssert.that(alerts)
        .satisfies(
            x -> {
              int cnt = 0;
              for (Alert a : x) {
                assertEquals("81.2.69.192", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS));
                assertEquals(
                    "216.160.83.56", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_PREVIOUS));
                assertEquals(
                    "00000000000000000000000000000000", a.getMetadataValue(AlertMeta.Key.UID));
                assertEquals("riker@mozilla.com", a.getMetadataValue(AlertMeta.Key.EMAIL));
                assertEquals(
                    "test 00000000000000000000000000000000 velocity exceeded, "
                        + "7740.82 km in 9 seconds (monitor only)",
                    a.getSummary());
                assertEquals(
                    "velocity_monitor_only", a.getMetadataValue(AlertMeta.Key.NOTIFY_MERGE));
                assertEquals("London", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_CITY));
                assertEquals("GB", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_COUNTRY));
                assertEquals(
                    "Milton", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_PREVIOUS_CITY));
                assertEquals(
                    "US", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_PREVIOUS_COUNTRY));
                assertEquals("7740.82", a.getMetadataValue(AlertMeta.Key.KM_DISTANCE));
                assertEquals("9", a.getMetadataValue(AlertMeta.Key.TIME_DELTA_SECONDS));
                cnt++;
              }
              assertEquals(1, cnt);
              return null;
            });

    p.run().waitUntilFinish();
  }

  @Test
  public void velocityMonitorOnly3Test() throws Exception {
    testEnv();

    String[] eb1 = TestUtil.getTestInputArray("/testdata/customs_velocity1.txt");
    TestStream<String> s =
        TestStream.create(StringUtf8Coder.of())
            .advanceWatermarkTo(new Instant(0L))
            .addElements(eb1[0], Arrays.copyOfRange(eb1, 1, eb1.length))
            .advanceWatermarkToInfinity();

    // tests when monitor is larger than actual
    Customs.CustomsOptions options = getTestOptions();
    options.setEnableVelocityDetector(true);
    options.setEnableVelocityDetectorMonitorOnly(true);
    options.setMinimumDistanceForAlertMonitorOnly(8000.0);
    options.setXffAddressSelector("127.0.0.1/32");

    PCollection<Alert> alerts = Customs.executePipeline(p, p.apply(s), options);

    PAssert.that(alerts)
        .satisfies(
            x -> {
              int cnt = 0;
              for (Alert a : x) {
                assertEquals("81.2.69.192", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS));
                assertEquals(
                    "216.160.83.56", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_PREVIOUS));
                assertEquals(
                    "00000000000000000000000000000000", a.getMetadataValue(AlertMeta.Key.UID));
                assertEquals("riker@mozilla.com", a.getMetadataValue(AlertMeta.Key.EMAIL));
                assertEquals(
                    "test 00000000000000000000000000000000 velocity exceeded, "
                        + "7740.82 km in 9 seconds",
                    a.getSummary());
                assertEquals("velocity", a.getMetadataValue(AlertMeta.Key.NOTIFY_MERGE));
                assertEquals("London", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_CITY));
                assertEquals("GB", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_COUNTRY));
                assertEquals(
                    "Milton", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_PREVIOUS_CITY));
                assertEquals(
                    "US", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_PREVIOUS_COUNTRY));
                assertEquals("7740.82", a.getMetadataValue(AlertMeta.Key.KM_DISTANCE));
                assertEquals("9", a.getMetadataValue(AlertMeta.Key.TIME_DELTA_SECONDS));
                cnt++;
              }
              assertEquals(1, cnt);
              return null;
            });

    p.run().waitUntilFinish();
  }

  @Test
  public void velocityTestBelowMinimumDistance() throws Exception {
    testEnv();

    String[] eb1 = TestUtil.getTestInputArray("/testdata/customs_velocity1.txt");
    TestStream<String> s =
        TestStream.create(StringUtf8Coder.of())
            .advanceWatermarkTo(new Instant(0L))
            .addElements(eb1[0], Arrays.copyOfRange(eb1, 1, eb1.length))
            .advanceWatermarkToInfinity();

    Customs.CustomsOptions options = getTestOptions();
    options.setEnableVelocityDetector(true);
    options.setXffAddressSelector("127.0.0.1/32");
    options.setMinimumDistanceForAlert(9000.0);

    PCollection<Alert> alerts = Customs.executePipeline(p, p.apply(s), options);

    PAssert.that(alerts).empty();

    p.run().waitUntilFinish();
  }

  @Test
  public void passwordResetAbuseTest() throws Exception {
    testEnv();

    String[] eb1 = TestUtil.getTestInputArray("/testdata/customs_abuse_password_reset1.txt");
    TestStream<String> s =
        TestStream.create(StringUtf8Coder.of())
            .advanceWatermarkTo(new Instant(0L))
            .addElements(eb1[0], Arrays.copyOfRange(eb1, 1, eb1.length))
            .advanceWatermarkToInfinity();

    Customs.CustomsOptions options = getTestOptions();
    options.setEnablePasswordResetAbuseDetector(true);
    options.setXffAddressSelector("127.0.0.1/32");

    PCollection<Alert> alerts = Customs.executePipeline(p, p.apply(s), options);

    PAssert.that(alerts)
        .satisfies(
            x -> {
              int cnt = 0;
              for (Alert a : x) {
                assertEquals("10.0.0.1", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS));
                assertEquals(
                    "test 10.0.0.1 attempted password reset on 5 distinct accounts in 10 minute window",
                    a.getSummary());
                assertEquals("customs", a.getCategory());
                assertEquals(
                    "password_reset_abuse",
                    a.getMetadataValue(AlertMeta.Key.ALERT_SUBCATEGORY_FIELD));
                assertEquals(
                    "password_reset_abuse", a.getMetadataValue(AlertMeta.Key.NOTIFY_MERGE));
                cnt++;
              }
              assertEquals(1, cnt);
              return null;
            });

    p.run().waitUntilFinish();
  }

  @Test
  public void statusComparatorTest() throws Exception {
    testEnv();

    // Note we also test CustomsLoginFailureForAtRiskAccount here

    String[] eb1 = TestUtil.getTestInputArray("/testdata/customs_status_comparator1.txt");
    String[] eb2 = TestUtil.getTestInputArray("/testdata/customs_status_comparator2.txt");
    TestStream<String> s =
        TestStream.create(StringUtf8Coder.of())
            .advanceWatermarkTo(new Instant(0L))
            .addElements(eb1[0], Arrays.copyOfRange(eb1, 1, eb1.length))
            .advanceProcessingTime(Duration.standardSeconds(30))
            .addElements(eb2[0], Arrays.copyOfRange(eb2, 1, eb2.length))
            .advanceWatermarkToInfinity();

    Customs.CustomsOptions options = getTestOptions();
    options.setEnableStatusComparator(true);
    options.setStatusComparatorAddressPath("/testdata/customs_status_comparator_address_list.txt");
    options.setEnableLoginFailureAtRiskAccount(true);

    PCollection<Alert> alerts = Customs.executePipeline(p, p.apply(s), options);

    PAssert.that(alerts)
        .satisfies(
            x -> {
              int cnt = 0;
              int sccnt = 0;
              int lfcnt = 0;
              for (Alert a : x) {
                System.out.println(a.toJSON());
                if (a.getMetadataValue(AlertMeta.Key.ALERT_SUBCATEGORY_FIELD)
                    .equals("status_comparator")) {
                  assertEquals("spock@mozilla.com", a.getMetadataValue(AlertMeta.Key.EMAIL));
                  assertEquals("3.3.3.3", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS));
                  assertEquals(
                      "test status check comparator indicates known address", a.getSummary());
                  assertEquals("customs", a.getCategory());
                  assertEquals("status_comparator", a.getMetadataValue(AlertMeta.Key.NOTIFY_MERGE));
                  assertEquals(
                      "status_comparator",
                      a.getMetadataValue(AlertMeta.Key.ALERT_SUBCATEGORY_FIELD));
                  sccnt++;
                } else if (a.getMetadataValue(AlertMeta.Key.ALERT_SUBCATEGORY_FIELD)
                    .equals("login_failure_at_risk_account")) {
                  assertEquals("test login failure for at risk account, 10.0.1.11", a.getSummary());
                  assertEquals("spock@mozilla.com", a.getMetadataValue(AlertMeta.Key.EMAIL));
                  assertEquals("10.0.1.11", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS));
                  assertEquals("customs", a.getCategory());
                  assertEquals(
                      "login_failure_at_risk_account",
                      a.getMetadataValue(AlertMeta.Key.NOTIFY_MERGE));
                  assertEquals(
                      "login_failure_at_risk_account",
                      a.getMetadataValue(AlertMeta.Key.ALERT_SUBCATEGORY_FIELD));
                  lfcnt++;
                } else {
                  fail("unexpected category");
                }
                cnt++;
              }
              assertEquals(2, cnt);
              assertEquals(1, sccnt);
              assertEquals(1, lfcnt);
              return null;
            });

    p.run().waitUntilFinish();
  }

  @Test
  public void activityMonitorTest() throws Exception {
    testEnv();

    String[] eb1 =
        TestUtil.getTestInputArray("/testdata/customs_activity_for_monitored_accounts1.txt");
    String[] eb2 =
        TestUtil.getTestInputArray("/testdata/customs_activity_for_monitored_accounts2.txt");
    TestStream<String> s =
        TestStream.create(StringUtf8Coder.of())
            .advanceWatermarkTo(new Instant(0L))
            .addElements(eb1[0], Arrays.copyOfRange(eb1, 1, eb1.length))
            .advanceProcessingTime(Duration.standardSeconds(30))
            .addElements(eb2[0], Arrays.copyOfRange(eb2, 1, eb2.length))
            .advanceWatermarkToInfinity();

    Customs.CustomsOptions options = getTestOptions();
    options.setEnableActivityMonitor(true);
    options.setActivityMonitorAccountPath(
        "/testdata/customs_activity_for_monitored_accounts_account_list.txt");

    PCollection<Alert> alerts = Customs.executePipeline(p, p.apply(s), options);

    PAssert.that(alerts)
        .satisfies(
            x -> {
              int amcnt = 0;
              for (Alert a : x) {
                if (a.getMetadataValue(AlertMeta.Key.ALERT_SUBCATEGORY_FIELD)
                    .equals("activity_monitor")) {
                  assertEquals("spock@mozilla.com", a.getMetadataValue(AlertMeta.Key.EMAIL));
                  assertEquals("127.0.0.1", a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS));
                  assertEquals(
                      "test activity on monitored account - action loginSuccess", a.getSummary());
                  assertEquals("customs", a.getCategory());
                  assertEquals("activity_monitor", a.getMetadataValue(AlertMeta.Key.NOTIFY_MERGE));
                  amcnt++;
                } else {
                  fail("unexpected category");
                }
              }
              assertEquals(1, amcnt);
              return null;
            });

    p.run().waitUntilFinish();
  }

  @Test
  public void privateRelayForwardTest() throws Exception {
    testEnv();

    String[] eb1 = TestUtil.getTestInputArray("/testdata/privaterelay/privaterelay1.txt");
    String[] eb2 = TestUtil.getTestInputArray("/testdata/privaterelay/privaterelay2.txt");
    TestStream<String> s =
        TestStream.create(StringUtf8Coder.of())
            .advanceWatermarkTo(new Instant(0L))
            .addElements(eb1[0], Arrays.copyOfRange(eb1, 1, eb1.length))
            .advanceWatermarkTo(new Instant(120000L))
            .advanceProcessingTime(Duration.standardSeconds(120))
            .addElements(eb2[0], Arrays.copyOfRange(eb2, 1, eb2.length))
            .advanceWatermarkToInfinity();

    Customs.CustomsOptions options = getTestOptions();
    options.setEnablePrivateRelayForward(true);

    PCollection<Alert> alerts = Customs.executePipeline(p, p.apply(s), options);

    PAssert.that(alerts)
        .satisfies(
            x -> {
              int totalcnt = 0;
              for (Alert a : x) {
                System.out.println(a.toJSON());
                if (a.getMetadataValue(AlertMeta.Key.ALERT_SUBCATEGORY_FIELD)
                    .equals("private_relay_forward")) {
                  if (a.getSummary().contains("11111111111111111111111111111111")) {
                    assertEquals(
                        "test private relay address hash mismatch for 11111111111111111111111111111111",
                        a.getSummary());
                    assertEquals(
                        "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                        a.getMetadataValue(AlertMeta.Key.REAL_ADDRESS_HASH_ACTUAL));
                    assertEquals(
                        "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                        a.getMetadataValue(AlertMeta.Key.REAL_ADDRESS_HASH_EXPECTED));
                    assertEquals(
                        "11111111111111111111111111111111", a.getMetadataValue(AlertMeta.Key.UID));
                  } else if (a.getSummary().contains("00000000000000000000000000000000")) {
                    assertEquals(
                        "test private relay address hash mismatch for 00000000000000000000000000000000",
                        a.getSummary());
                    assertEquals(
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                        a.getMetadataValue(AlertMeta.Key.REAL_ADDRESS_HASH_ACTUAL));
                    assertEquals(
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        a.getMetadataValue(AlertMeta.Key.REAL_ADDRESS_HASH_EXPECTED));
                    assertEquals(
                        "00000000000000000000000000000000", a.getMetadataValue(AlertMeta.Key.UID));
                  }
                } else {
                  fail("unexpected category");
                }
                totalcnt++;
              }
              assertEquals(2, totalcnt);
              return null;
            });

    p.run().waitUntilFinish();
  }
}

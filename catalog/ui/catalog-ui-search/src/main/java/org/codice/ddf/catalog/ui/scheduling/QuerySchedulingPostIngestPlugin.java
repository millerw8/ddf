package org.codice.ddf.catalog.ui.scheduling;

import static ddf.util.Fallible.error;
import static ddf.util.Fallible.of;
import static ddf.util.Fallible.success;

import com.google.common.annotations.VisibleForTesting;
import ddf.catalog.CatalogFramework;
import ddf.catalog.Constants;
import ddf.catalog.data.Metacard;
import ddf.catalog.operation.CreateResponse;
import ddf.catalog.operation.DeleteResponse;
import ddf.catalog.operation.Query;
import ddf.catalog.operation.QueryRequest;
import ddf.catalog.operation.QueryResponse;
import ddf.catalog.operation.Response;
import ddf.catalog.operation.Update;
import ddf.catalog.operation.UpdateResponse;
import ddf.catalog.operation.impl.QueryImpl;
import ddf.catalog.operation.impl.QueryRequestImpl;
import ddf.catalog.plugin.PluginExecutionException;
import ddf.catalog.plugin.PostIngestPlugin;
import ddf.security.Subject;
import ddf.util.Fallible;
import ddf.util.MapUtils;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteScheduler;
import org.apache.ignite.IgniteState;
import org.apache.ignite.Ignition;
import org.apache.ignite.scheduler.SchedulerFuture;
import org.codice.ddf.catalog.ui.metacard.workspace.QueryMetacardTypeImpl;
import org.codice.ddf.catalog.ui.metacard.workspace.WorkspaceAttributes;
import org.codice.ddf.catalog.ui.metacard.workspace.WorkspaceMetacardImpl;
import org.codice.ddf.catalog.ui.metacard.workspace.WorkspaceTransformer;
import org.codice.ddf.catalog.ui.query.monitor.email.EmailNotifier;
import org.codice.ddf.security.common.Security;
import org.geotools.filter.text.cql2.CQLException;
import org.geotools.filter.text.ecql.ECQL;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.opengis.filter.Filter;
import org.opengis.filter.sort.SortBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuerySchedulingPostIngestPlugin implements PostIngestPlugin {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(QuerySchedulingPostIngestPlugin.class);

  public static final String QUERIES_CACHE_NAME = "scheduled queries";

  public static final long QUERY_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);

  private static final org.joda.time.format.DateTimeFormatter ISO_8601_DATE_FORMAT =
      DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ").withZoneUTC();

  private static final Security SECURITY = Security.getInstance();

  private static Fallible<IgniteScheduler> scheduler =
      error(
          "An Ignite scheduler has not been obtained for this query! Have any queries been started yet?");

  /**
   * This {@link IgniteCache} relates metacards to running {@link Ignite} scheduled jobs. Keys are
   * metacard IDs (unique identifiers of metacards) while values are running {@link Ignite} jobs.
   * This {@link IgniteCache} will become available as soon as a job is scheduled if a running
   * {@link Ignite} instance is available.
   */
  @VisibleForTesting
  static Fallible<Map<String, SchedulerFuture<?>>> runningQueries =
      error(
          "An Ignite cache has not been obtained for this query! Have any queries been started yet?");

  private final CatalogFramework catalogFramework;

  private final EmailNotifier emailNotifierService;

  private final WorkspaceTransformer workspaceTransformer;

  public QuerySchedulingPostIngestPlugin(
      CatalogFramework catalogFramework,
      EmailNotifier emailNotifierService,
      WorkspaceTransformer workspaceTransformer) {
    this.catalogFramework = catalogFramework;
    this.emailNotifierService = emailNotifierService;
    this.workspaceTransformer = workspaceTransformer;

    // TODO TEMP
    LOGGER.warn("Query scheduling plugin created!");
  }

  public enum RepetitionTimeUnit {
    // To repeat each unit of time in a cron expression, fields will either need to be starred ("*")
    // or filled with the value of that field in the start date-time.
    // where "S" is the value of that field in the start date:
    // every minute: * * * * *
    // every hour:   S * * * *
    // every day:    S S * * *
    // every week:   S S * * S
    // every month:  S S S * *
    // every year:   S S S S *

    MINUTES(),
    HOURS(0),
    DAYS(0, 1),
    WEEKS(0, 1, 4),
    MONTHS(0, 1, 2),
    YEARS(0, 1, 2, 3);

    private int[] cronFieldsThatShouldBeFilled;

    RepetitionTimeUnit(int... cronFieldsThatShouldBeFilled) {
      this.cronFieldsThatShouldBeFilled = cronFieldsThatShouldBeFilled;
    }

    public boolean cronFieldShouldBeFilled(int index) {
      return IntStream.of(cronFieldsThatShouldBeFilled)
          .anyMatch(cronFieldIndex -> cronFieldIndex == index);
    }

    public String makeCronToRunEachUnit(DateTime start) {
      final String[] cronFields = new String[5];
      final int[] startValues =
          new int[] {
            start.getMinuteOfHour(),
            start.getHourOfDay(),
            start.getDayOfMonth(),
            start.getMonthOfYear(),
            // Joda's day of the week value := 1-7 Monday-Sunday;
            // cron's day of the week value := 0-6 Sunday-Saturday
            start.getDayOfWeek() % 7
          };
      for (int i = 0; i < 5; i++) {
        if (cronFieldShouldBeFilled(i)) {
          cronFields[i] = String.valueOf(startValues[i]);
        } else {
          cronFields[i] = "*";
        }
      }

      return String.join(" ", cronFields);
    }
  }

  private Fallible<SchedulerFuture<?>> scheduleJob(
      final Map<String, Object> queryMetacardData,
      final IgniteScheduler scheduler,
      final int scheduleInterval,
      String scheduleUnit,
      String scheduleStartString,
      String scheduleEndString) {
    if (scheduleInterval <= 0) {
      return error("A task cannot be executed every %d %s!", scheduleInterval, scheduleUnit);
    }

    DateTime now = DateTime.now();
    DateTime start;
    DateTime end;
    try {
      start = DateTime.parse(scheduleStartString, ISO_8601_DATE_FORMAT);
    } catch (DateTimeParseException exception) {
      return error(
          "The start date attribute of this metacard, \"%s\", could not be parsed: %s",
          scheduleStartString, exception.getMessage());
    }
    try {
      end = DateTime.parse(scheduleEndString, ISO_8601_DATE_FORMAT);
    } catch (DateTimeParseException exception) {
      return error(
          "The end date attribute of this metacard, \"%s\", could not be parsed: %s",
          scheduleStartString, exception.getMessage());
    }

    long nowStartDiff = start.minus(now.getMillis()).getMillis();
    long startEndDiff = end.minus(start.getMillis()).getMillis();

    RepetitionTimeUnit unit;
    try {
      unit = RepetitionTimeUnit.valueOf(scheduleUnit.toUpperCase());
    } catch (IllegalArgumentException exception) {
      return error(
          "The unit of time \"%s\" for the scheduled query time interval is not recognized!",
          scheduleUnit);
    }

    final long unitsBeforeWeStart;
    final long unitsBeforeWeEnd;
    switch (unit) {
      case MINUTES:
        unitsBeforeWeStart = nowStartDiff / (1000L * 60L);
        unitsBeforeWeEnd = 1 + unitsBeforeWeStart + startEndDiff / (1000L * 60L);
        break;
      case HOURS:
        unitsBeforeWeStart = nowStartDiff / (1000L * 60L * 60L);
        unitsBeforeWeEnd = 1 + unitsBeforeWeStart + startEndDiff / (1000L * 60L * 60L);
        break;
      case DAYS:
        unitsBeforeWeStart = nowStartDiff / (1000L * 60L * 60L * 24L);
        unitsBeforeWeEnd = 1 + unitsBeforeWeStart + startEndDiff / (1000L * 60L * 60L * 24L);
        break;
      case WEEKS:
        unitsBeforeWeStart = nowStartDiff / (1000L * 60L * 60L * 24L * 7L);
        unitsBeforeWeEnd = 1 + unitsBeforeWeStart + startEndDiff / (1000L * 60L * 60L * 24L * 7L);
        break;
      case MONTHS:
        // TODO: Months need some work because days/month varies
        unitsBeforeWeStart = unitsBeforeWeEnd = 1;
        //        unitsBeforeWeStart = 1 + nowStartDiff / (1000L * 60L * 60L * 24L * 7L * 12L);
        //        unitsBeforeWeEnd = unitsBeforeWeStart + startEndDiff / (1000L * 60L * 60L * 24L *
        // 7L * (end.getDayOfYear() - start.getDayOfYear()));
        break;
      case YEARS:
        unitsBeforeWeStart = nowStartDiff / (1000L * 60L * 60L * 24L * 365L);
        unitsBeforeWeEnd = 1 + unitsBeforeWeStart + startEndDiff / (1000L * 60L * 60L * 24L * 365L);
        break;
      default:
        unitsBeforeWeStart = 1;
        unitsBeforeWeEnd = 1;
        break;
    }

    LOGGER.warn(
        "We'll be repeating this {} times...",
        Math.ceil((unitsBeforeWeEnd - unitsBeforeWeStart) / scheduleInterval));

    final QuerySchedulingPostIngestPlugin thisPlugin = this;
    final SchedulerFuture<?> job =
        scheduler.scheduleLocal(
            new Runnable() {
              private long unitsPassedSinceStarted = 0;

              @Override
              public void run() {
                // TODO: Figure out how to cancel the job when the end date-time is reached.
                if (unitsPassedSinceStarted >= unitsBeforeWeEnd) {
                  LOGGER.warn("Ending scheduled query...");
                  thisPlugin.cancelSchedule(queryMetacardData).elseDo(LOGGER::error);
                  return;
                }
                if (unitsPassedSinceStarted >= unitsBeforeWeStart
                    && (unitsPassedSinceStarted - unitsBeforeWeStart) % scheduleInterval == 0) {
                  //                  unitsPassedSinceStarted = 0;
                  thisPlugin.emailOwner(queryMetacardData).elseDo(LOGGER::error);
                } // else {
                unitsPassedSinceStarted++;
                //                }
              }
            },
            unit.makeCronToRunEachUnit(
                start)); // TODO: surround this in an IgniteException try-catch

    return of(job);
  }

  private Fallible<SchedulerFuture<?>> scheduleJob(
      final Map<String, Object> queryMetacardData, final IgniteScheduler scheduler) {
    return MapUtils.tryGet(
            queryMetacardData, QueryMetacardTypeImpl.QUERY_IS_SCHEDULED, Boolean.class)
        .tryMap(
            isScheduled -> {
              if (!isScheduled) {
                return error("This metacard does not have scheduling enabled!");
              }

              return MapUtils.tryGet(
                      queryMetacardData, QueryMetacardTypeImpl.QUERY_SCHEDULE_AMOUNT, Integer.class)
                  .tryMap(
                      scheduleInterval ->
                          MapUtils.tryGet(
                                  queryMetacardData,
                                  QueryMetacardTypeImpl.QUERY_SCHEDULE_UNIT,
                                  String.class)
                              .tryMap(
                                  scheduleUnit ->
                                      MapUtils.tryGet(
                                              queryMetacardData,
                                              QueryMetacardTypeImpl.QUERY_SCHEDULE_END,
                                              String.class)
                                          .tryMap(
                                              scheduleEndString ->
                                                  MapUtils.tryGet(
                                                          queryMetacardData,
                                                          QueryMetacardTypeImpl
                                                              .QUERY_SCHEDULE_START,
                                                          String.class)
                                                      .tryMap(
                                                          scheduleStartString ->
                                                              scheduleJob(
                                                                  queryMetacardData,
                                                                  scheduler,
                                                                  scheduleInterval,
                                                                  scheduleUnit,
                                                                  scheduleStartString,
                                                                  scheduleEndString)))));
            });
  }

  private Fallible<?> emailOwner(final Map<String, Object> queryMetacardData) {
    // TODO TEMP
    LOGGER.warn("Emailing metacard owner...");

    return MapUtils.tryGet(queryMetacardData, QueryMetacardTypeImpl.QUERY_CQL, String.class)
        .tryMap(
            cqlQuery -> {
              Filter filter;
              try {
                filter = ECQL.toFilter(cqlQuery);
              } catch (CQLException exception) {
                return error(
                    "There was a problem reading the given query expression: "
                        + exception.getMessage());
              }

              final Query query =
                  new QueryImpl(
                      filter,
                      1,
                      Constants.DEFAULT_PAGE_SIZE,
                      SortBy.NATURAL_ORDER,
                      true,
                      QUERY_TIMEOUT_MS);
              final QueryRequest queryRequest = new QueryRequestImpl(query, true);

              QueryResponse queryResults;
              //              try {
              Subject systemSubject = SECURITY.runAsAdmin(SECURITY::getSystemSubject);
              queryResults = systemSubject.execute(() -> catalogFramework.query(queryRequest));
              //                  {
              //                  try {
              //                    catalogFramework.query(queryRequest);
              //                  } catch (UnsupportedQueryException exception) {
              //                    return error(
              //                        "The query \"%s\" is not supported by the given catalog
              // framework: %s",
              //                    cqlQuery, exception.getMessage());
              //                  } catch (SourceUnavailableException exception) {
              //                    return error(
              //                        "The catalog framework sources were unavailable: " +
              //                            exception.getMessage());
              //                  } catch (FederationException exception) {
              //                    return error(
              //                        "There was a problem with executing a federated search for
              // the query \"%s\": %s",
              //                        cqlQuery, exception.getMessage());
              //                  }
              //                }
              //              ));
              //                            } catch (UnsupportedQueryException exception) {
              //                              return error(
              //                                  "The query \"%s\" is not supported by the given
              // catalog
              //               framework: %s",
              //                                  cqlQuery, exception.getMessage());
              //                            } catch (SourceUnavailableException exception) {
              //                              return error(
              //                                  "The catalog framework sources were unavailable: "
              // +
              //               exception.getMessage());
              //                            } catch (FederationException exception) {
              //                              return error(
              //                                  "There was a problem with executing a federated
              // search for the
              //               query \"%s\": %s",
              //                                  cqlQuery, exception.getMessage());
              //                            }

              final Map<String, Pair<WorkspaceMetacardImpl, Long>> notifiableQueryResults =
                  MapUtils.fromList(
                      queryResults.getResults(),
                      result -> result.getMetacard().getId(),
                      result ->
                          Pair.of(
                              WorkspaceMetacardImpl.from(result.getMetacard()),
                              queryResults.getHits()));

              emailNotifierService.notify(notifiableQueryResults);

              return success();
            });
  }

  private Fallible<?> schedule(final Map<String, Object> queryMetacardData) {
    if (Ignition.state() != IgniteState.STARTED) {
      return error("Cron queries cannot be scheduled without a running Ignite instance!");
    }

    if (scheduler.hasError()) {
      final Ignite ignite = Ignition.ignite();
      scheduler = of(ignite.scheduler());
      runningQueries = of(new HashMap<>());
    }

    return scheduler.tryMap(
        scheduler ->
            runningQueries.tryMap(
                runningQueries ->
                    MapUtils.tryGet(queryMetacardData, Metacard.ID, String.class)
                        .tryMap(
                            id -> {
                              if (runningQueries.containsKey(id)) {
                                return error(
                                    "This metacard cannot be scheduled because a job is already scheduled for it!");
                              }

                              return scheduleJob(queryMetacardData, scheduler)
                                  .ifValue(job -> runningQueries.put(id, job));
                            })));
  }

  private Fallible<?> cancelSchedule(final Map<String, Object> queryMetacardData) {
    if (!queryMetacardData.containsKey(QueryMetacardTypeImpl.QUERY_IS_SCHEDULED)) {
      return success();
    }

    return MapUtils.tryGet(
            queryMetacardData, QueryMetacardTypeImpl.QUERY_IS_SCHEDULED, Boolean.class)
        .tryMap(
            isScheduled -> {
              if (!isScheduled) {
                return success();
              } else {
                return runningQueries.tryMap(
                    runningQueries ->
                        MapUtils.tryGet(queryMetacardData, Metacard.ID, String.class)
                            .tryMap(
                                id -> {
                                  final @Nullable SchedulerFuture<?> job = runningQueries.get(id);
                                  if (job == null) {
                                    return error(
                                        "This job scheduled under the ID \"%s\" was not found in the scheduled queries job cache!",
                                        id);
                                  } else if (job.isCancelled()) {
                                    return error(
                                        "This job scheduled under the ID \"%s\" cannot be cancelled because it is not running!",
                                        id);
                                  }

                                  job.cancel();
                                  runningQueries.remove(id);

                                  return success().mapValue(null);
                                }));
              }
            });
  }

  private Fallible<?> processMetacard(
      Metacard workspaceMetacard, Function<Map<String, Object>, Fallible<?>> metacardAction) {
    if (!WorkspaceMetacardImpl.isWorkspaceMetacard(workspaceMetacard)) {
      return success();
    }

    return MapUtils.tryGet(
            workspaceTransformer.transform(workspaceMetacard),
            WorkspaceAttributes.WORKSPACE_QUERIES,
            List.class)
        .tryMap(
            metacards -> {
              for (Map<String, Object> queryMetacardData : (List<Map<String, Object>>) metacards) {
                if (queryMetacardData.containsKey(QueryMetacardTypeImpl.QUERY_IS_SCHEDULED)) {
                  MapUtils.tryGet(
                          queryMetacardData,
                          QueryMetacardTypeImpl.QUERY_IS_SCHEDULED,
                          Boolean.class)
                      .tryMap(
                          isScheduled ->
                              isScheduled
                                  ? metacardAction.apply(queryMetacardData).mapValue(null)
                                  : success())
                      .elseDo(
                          error ->
                              LOGGER.error(
                                  "There was a problem scheduling a job for this query metacard: "
                                      + error));
                }
              }

              return success();
            });
  }

  private static void throwErrorsIfAny(List<ImmutablePair<Metacard, String>> errors)
      throws PluginExecutionException {
    if (!errors.isEmpty()) {
      throw new PluginExecutionException(
          errors
              .stream()
              .map(
                  metacardAndError ->
                      String.format(
                          "There was an error attempting to schedule execution of workspace metacard \"%s\": %s",
                          metacardAndError.getLeft().getId(), metacardAndError.getRight()))
              .collect(Collectors.joining("\n")));
    }
  }

  private <T extends Response> T processSingularResponse(
      T response,
      List<Metacard> metacards,
      Function<Map<String, Object>, Fallible<?>> metacardAction)
      throws PluginExecutionException {
    List<ImmutablePair<Metacard, String>> errors = new ArrayList<>();

    for (Metacard metacard : metacards) {
      // TODO TEMP
      LOGGER.debug(
          String.format("Processing metacard of type %s...", metacard.getMetacardType().getName()));
      processMetacard(metacard, metacardAction)
          .elseDo(error -> errors.add(ImmutablePair.of(metacard, error)));
    }

    throwErrorsIfAny(errors);

    return response;
  }

  @Override
  public CreateResponse process(CreateResponse creation) throws PluginExecutionException {
    // TODO TEMP
    LOGGER.warn("Processing creation...");
    return processSingularResponse(creation, creation.getCreatedMetacards(), this::schedule);
  }

  @Override
  public UpdateResponse process(UpdateResponse updates) throws PluginExecutionException {
    // TODO TEMP
    LOGGER.warn("Processing update...");
    List<ImmutablePair<Metacard, String>> errors = new ArrayList<>();

    for (Update update : updates.getUpdatedMetacards()) {
      // TODO TEMP
      LOGGER.warn(
          String.format(
              "Processing old metacard of type %s...",
              update.getOldMetacard().getMetacardType().getName()));
      processMetacard(update.getOldMetacard(), this::cancelSchedule)
          .elseDo(error -> errors.add(ImmutablePair.of(update.getOldMetacard(), error)));
      // TODO TEMP
      LOGGER.warn(
          String.format(
              "Processing new metacard of type %s...",
              update.getNewMetacard().getMetacardType().getName()));
      processMetacard(update.getNewMetacard(), this::schedule)
          .elseDo(error -> errors.add(ImmutablePair.of(update.getNewMetacard(), error)));
    }

    throwErrorsIfAny(errors);

    return updates;
  }

  @Override
  public DeleteResponse process(DeleteResponse deletion) throws PluginExecutionException {
    // TODO TEMP
    LOGGER.warn("Processing deletion...");
    return processSingularResponse(deletion, deletion.getDeletedMetacards(), this::cancelSchedule);
  }
}

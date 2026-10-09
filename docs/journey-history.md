# Journey analytical READ contract

`GET /api/v1/journey/history/{indicator}` supports `tml`, `tr`, `ti`, `jl` and
the existing JWT authentication. It reuses the approved V2 readers; no SQL KPI
formula, source selection, target, or duration is changed.

Required parameters: `from`, `to`; `mode=ponto|mpd` except for TR, which rejects
mode. Optional existing filters: `map`, `employeeCode`, `role=motorista|ajudante`,
`expurge=all|expurged|not_expurged`. Analysis defaults to drivers when role is absent.
Additional parameters: `populationRole=motorista|ajudante` (default motorista,
used when the shared role filter is absent), `search` (accent/case insensitive, map/crew/vehicle context),
`scope=all|d0|earlier`, `includePrevious=true|false` (default true).

The response contains selected `summary`, contextual `daily`, selected
`collaborators`, `previousYearDaily`, requested and context dates, server-local
`asOf`, nullable latest `snapshotAt`, effective `role`, `population`, and
`hasLiveOrigin`. The context spans complete selected calendar months and their
Monday–Saturday boundary weeks. Only dates through server-local today are read;
future contextual dates return empty counts, with NULL adherence.

Counts: `total`, `attained`, `notAttained`, `pending`, `unavailable`, `evaluated`,
nullable `adherence`, `distinctMaps`, `expurged`, `anomalies`.

* Only FINALIZADO/CONCLUIDO with non-NULL official `achieved` is evaluable.
* `evaluated = attained + notAttained`; `adherence = 100 * attained / evaluated`.
  No evaluable results means NULL, not zero. EM_ANDAMENTO is pending even if an
  outcome happens to be supplied. Other/missing states or outcomes are unavailable.
* TR and operational JL (MPD) count one `date+map` in period/day summaries.
  TML, TI and laboral JL count `date+map+employeeCode`. Thus helpers can have
  several individual observations for the same map. Collaborator counts always
  retain each person's distinct map observations.
* Exact repeated snapshots are deduplicated; newer versions replace older ones.
  Conflicting outcomes at the same snapshot or across replicated map facts become
  unavailable. Missing map/employee identity is unavailable, never a fabricated pass.
* Expurge is included by default and filtered explicitly for the analytical series before crew search.
  Anomalies are reported without silently changing the procedure's verdict.
* Crew search finds a map via any retained team member before the analysis role
  is selected. Other explicit filters still apply first.

Reads are sequential monthly chunks. Current context is aggregated into a compact
response, rather than transmitting raw yearly DTOs. Previous-year reads cover the
same selected months: full closed months, and only the same elapsed day-of-month
for the current partial month. February 29 clamps to February 28 in nonleap years.
The UI groups up to three weeks by day, larger scopes by month/week intersections
(no duplicate boundary days), and multiple months by month. Sundays remain in
monthly/person results but are absent from weekly Monday–Saturday results.
Calendar months can intersect 4, 5 or 6 Monday–Saturday weeks; the UI retains all
actual intersections instead of dropping dates to force four/five buckets.

Silent live refresh may request `includePrevious=false`; the client retains the
previous-year series for the same filter key. Error responses use the existing
Journey 400/500 advice. No persistent cache or external writes are introduced.

## Educators team report

`team` contains one selected date/map/person observation with `employeeCode`,
`employeeName`, `role`, `fleet`, nullable `departureDate`, nullable `expurgePresent`, official
`counts`, nullable `seconds`, and nullable `journeyExceeded`. Missing identities
are omitted from this report. It follows dates, map, collaborator, population,
crew search and scope, but precedes the shared expurge filter so the report's own
expurge selection is independent. The graph and summary filters are unchanged.

Durations are included only for closed cycles with nonnegative numeric seconds
and no temporal-order anomaly. The client averages those samples after filtering
maps by fleet/expurge and hiding selected people. Departure dates come from the real
map departure timestamp, which may differ from the map date after midnight. Duplicate same-day exits appear once in the date list.

Journey overruns use the closed official JL outcome joined by date/map/person,
never the selected indicator's failed count. Non-JL views read JL only for the
selected period in monthly chunks. TR uses JL `ponto`; other views use the
selected mode. Missing/open JL is NULL, not zero overruns. The UI marks partial
coverage. The report displays TML/TI means as mm:ss and TR/JL means as hh:mm,
without wrapping durations at 24 hours.

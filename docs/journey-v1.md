# Journey v1 READ API

All four endpoints require the existing JWT authentication and return one item per
`date + map + role + employeeCode`. The same employee may occur in multiple maps
on one day. They never exclude expurged rows unless requested.

| Endpoint | Source | `mode` |
| --- | --- | --- |
| `GET /api/v1/journey/tml` | `sp_tml_v2` | required: `ponto` or `mpd` |
| `GET /api/v1/journey/tr` | `sp_tr_v2` | absent |
| `GET /api/v1/journey/ti` | `sp_ti_v2` | required: `ponto` or `mpd` |
| `GET /api/v1/journey/jl` | `sp_jl_v2` | required: `ponto` or `mpd` |

Common query parameters: required `from` and `to` in `YYYY-MM-DD` format, with
`to >= from`; optional positive numeric `map` and `employeeCode`; optional
`role=motorista|ajudante`; optional
`expurge=all|expurged|not_expurged` (default `all`). All filters act on the
procedure result. Each request executes its procedure once.

The API controller maps core read models to v1 response DTOs. The core service
depends on `JourneyReadRepository`; its JDBC adapter owns the four approved V2
procedure definitions and their fixed parameter counts. The reusable
`operationalread` executor receives a generic `ProcedureSpec` and knows no
Journey indicator names. It skips
informational result sets and accepts a data set only when its identity and
indicator columns match the requested procedure. An unexpected result shape
fails the read instead of returning a misleading empty snapshot.

The response is `{from, to, snapshotAt, items}`. `snapshotAt` is the latest
`dt_snapshot` among the returned items and is `null` for an empty response.
Every item also carries its own `snapshotAt`, a shared `context` object with
map and employee identity, an indicator-specific payload, and `expurge`.
All date-time fields are local database date-times. Every duration is a
nullable integer number of seconds, including values over 24 hours. The
indicator payload separates `lifecycleStatus`, `targetStatus`, and nullable
`achieved`; it does not recalculate or infer the official KPI. JL component
indicators have no lifecycle status because `sp_jl_v2` does not return it for
those components.

`expurge` exposes nullable `present`, `count`, the procedure's aggregated text
columns `ids`, `types`, `reasons`, `observations`, and typed `flags`. The text
aggregates are preserved verbatim because their separator is not part of the
current SQL contract. An HTTP 400 returns `INVALID_JOURNEY_REQUEST`; procedure
failures return HTTP 500 with `JOURNEY_READ_ERROR`, without JDBC details.

The period snapshots also support `/api/v1/journey/history/{indicator}`.
See [journey-history.md](journey-history.md) for its aggregation denominator,
calendar context, previous-year alignment and incomplete/expurged handling. BEES delivery
progress remains in IFW-29 and is not part of the TR response yet.

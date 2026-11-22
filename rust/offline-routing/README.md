# Clear Maps offline routing core

This crate is the first native routing milestone for Android. It is intentionally separate from
`cell-intelligence`: routing and radio positioning have different data models and performance
profiles.

## Current scope

- Versioned compact binary directed graph format (`CLROUTE1`, version 1).
- Time-weighted A* route search in Rust.
- Directed edges preserve one-way travel direction.
- Input validation and bounded graph-size checks before allocation.
- Small deterministic unit fixtures for route correctness and corrupt-input rejection.

The current milestone is the routing kernel and format contract. It is **not yet wired into the
Android APK**, and it does not yet import OSM PBF files, resolve turn restrictions, or download
regions. Those integration steps must land before offline routing can be advertised as available.

## Binary graph format (little endian)

1. 8 bytes: ASCII magic `CLROUTE1`
2. u32: format version (1)
3. u32: node count
4. u32: directed edge count
5. `node_count` records: latitude f64, longitude f64
6. `node_count + 1` u32 CSR edge offsets
7. `edge_count` records: target node u32, distance metres f32, expected speed m/s f32

Edges are directed. A one-way OSM road must produce only the legal direction; a bidirectional road
produces two directed edges. The importer must reject inaccessible roads and apply turn restrictions
before writing the graph. Route search minimizes estimated travel time.

## Memory/performance notes

The router keeps coordinates, CSR offsets, edges, best-cost and parent arrays in memory for the
selected graph. It does not load OSM tags or geometry objects into the routing hot path. The first
implementation uses a linear nearest-node scan; the production importer must add a compact spatial
index before large regions are enabled. Region files should be memory-mapped or loaded per region,
with a small adjacent-region window for cross-border routing.

## Data acquisition

Use regional OSM PBF extracts for bulk region downloads. Overpass is reserved for small, bounded,
cached queries and diagnostics; Overpass Turbo is a query interface, not a bulk country extract
provider. Updates must be applied to a staging graph and atomically activated only after validation.

## Required next milestones

1. PBF importer in Rust (roads, direction, access, speed defaults, ferries and turn restrictions).
2. Spatial index + region boundary stitching and cross-region graph routing.
3. JNI bridge and Android NDK builds for arm64-v8a, armeabi-v7a and x86_64.
4. Download manager, resumable downloads, checksums, atomic activation and region catalogue.
5. Settings UI named **Загрузка карт**, storage accounting and update scheduling.
6. Offline-first integration with `NavigationRoutingRepository` and end-to-end airplane-mode tests.

OSM attribution and ODbL compliance must be included in the app and in any redistributed derived
database.

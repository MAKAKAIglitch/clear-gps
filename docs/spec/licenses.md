# Licenses and data provenance

Status: review draft. The repository currently uses MIT; this commit does not change it.

## Code and dependencies
Moving from MIT to GPL-3.0 changes fork and distribution terms. Do not switch without the owner's explicit decision and a contributor-rights review. Preserve existing notices. For direct and transitive dependencies, record exact version, source, SPDX license, license URL and required notices. Recheck when dependencies change and before releases.

## Data and services
- OpenStreetMap-derived map data has attribution and ODbL considerations; verify the selected style/tile provider's terms.
- WiGLE imports remain subject to WiGLE's current terms; do not assume an imported dataset is redistributable.
- BeaconDB API access does not grant rights to publish a mirror or dataset dump.
- SRTM/Copernicus DEM packs need source, resolution, datum, attribution and redistribution review.
- opendbc and vehicle-specific DBC files need source-specific license review.

Store source name, acquisition time, dataset digest where available and applicable terms reference. Preserve provenance when duplicates merge. A release gate must inspect third-party assets, generated data and transitive libraries, not just the root LICENSE.

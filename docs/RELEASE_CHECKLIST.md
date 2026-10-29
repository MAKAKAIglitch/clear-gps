# Release checklist

A checked item requires evidence from CI, test output or a documented device run.

## Build integrity
- [ ] CI passes on a clean checkout.
- [ ] Core-detect JVM and Android unit tests pass.
- [ ] Release build and R8 configuration are inspected.
- [ ] Version metadata and signing are correct.
- [ ] No secrets, local SDK paths or private route logs are in the tree.

## Detector and position
- [ ] The risk index is not described as probability without a calibration report.
- [ ] Missing hardware capability is neutral.
- [ ] The detector excludes the app's own mock output.
- [ ] No trusted current fix means publication stops.
- [ ] Zone boundary and antimeridian tests pass.
- [ ] Jamming, multipath and replay limits are documented.

## Privacy and data
- [ ] Online services are disabled by default and require explicit consent.
- [ ] Network payload fields are covered by tests.
- [ ] Default exports omit raw radio identifiers and exact coordinates where applicable.
- [ ] Imports are size-limited, validated and transactional.
- [ ] Retention and clear-data controls are tested.
- [ ] Direct and transitive dependencies are license-reviewed.

## Device validation
- [ ] Mock publication is checked with an independent reader.
- [ ] GPS, Auto and Radio modes are tested separately.
- [ ] Permission denial, service stop, Doze, process kill and reboot paths are tested.
- [ ] Battery and position-error reports include device and build identifiers.
- [ ] Release notes describe vendor and Android restrictions.

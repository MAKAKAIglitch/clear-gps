# BLE beacon formats and identity

## Purpose

Radio V2 uses BLE advertisement payloads as the primary source of beacon identity. A beacon's stable protocol fields take precedence over its Bluetooth advertiser address. This lets one physical beacon keep one database key when the operating system reports a different private/random MAC.

## Supported formats

| Format | Stable key / information | Positioning use |
|---|---|---|
| iBeacon | Proximity UUID + unsigned Major + unsigned Minor | Stable BLE key |
| Eddystone-UID | 10-byte Namespace + 6-byte Instance | Stable BLE key |
| Eddystone-URL | Decoded compressed URL | Metadata only; URL is not guaranteed unique |
| Eddystone-TLM v0 | Battery mV, signed 8.8 temperature, advertisement count, uptime | Telemetry only; frame contains no beacon ID |
| Eddystone-EID | 8-byte rotating encrypted/ephemeral ID | Recognized, but not a persistent key without resolver provisioning data |
| RuuviTag RAWv2 | Manufacturer ID 0x0499; 6-byte embedded MAC plus environmental/IMU telemetry | Stable key from the payload MAC, plus decoded sensor data |
| AltBeacon | Bluetooth company ID + 20-byte Beacon ID | Stable BLE key |
| SemBeacon | AltBeacon identity plus Eddystone-URL-compatible scan response | Stable BLE key plus URI metadata |

SemBeacon is identified when both components appear in the same Android ScanRecord. Its database key remains the underlying AltBeacon ID, whether or not the Eddystone-URL scan response is received on a particular scan. This prevents one physical beacon from splitting into separate AltBeacon and SemBeacon points.

## Identifier encoding

The database uses radio_type = 'ble' and the existing identifier text field:

- ibeacon:<uuid>:<major>:<minor>
- eddystone-uid:<namespace-hex>:<instance-hex>
- altbeacon:<company-id-hex>:<20-byte-id-hex> (SemBeacon uses the same AltBeacon key)
- ruuvi:<embedded-mac>

Hexadecimal components are lowercase. Major/Minor are unsigned decimal integers. Manufacturer-specific MFG_RESERVED bytes and Eddystone RFU bytes do not participate in identity.

## Ruuvi RAWv2 sensor data

For the production 24-byte RAWv2 frame under manufacturer ID 0x0499, the embedded six-byte MAC is the stable beacon key (`ruuvi:<mac>`). The parser decodes temperature in 0.005 °C steps, relative humidity in 0.0025% steps, pressure with the 50,000 Pa offset, three signed acceleration axes, battery voltage, TX power, movement counter and measurement sequence. Spec-defined unavailable sentinels are converted to null rather than treated as zero. The reserved all-FF or all-zero embedded MAC leaves the sensor values readable but does not create a persistent position key. The payload MAC is a protocol identifier; Ethernet multicast-bit filtering is not applied. Current support targets RAWv2; deprecated RAWv1 and proposal/beta variants are not claimed as supported.

## Address fallback

The Bluetooth address is not part of iBeacon, Eddystone-UID, AltBeacon or SemBeacon identity. It is used only for generic BLE advertisers with no stable protocol frame, and only when Android API 35+ reports ADDRESS_TYPE_PUBLIC, BLUETOOTH_CONNECT is already granted, and the address bits are unicast/global. The BLE scanner does not request that permission for this fallback. RANDOM, ANONYMOUS, UNKNOWN, missing address type and malformed addresses are rejected.

Android versions before API 35 do not expose BluetoothDevice.getAddressType() publicly. On those versions generic MAC-only advertisements are deliberately not used as permanent map/position keys. Recognized protocol frames still work because their IDs are carried in the advertisement payload.

## Positioning and telemetry boundaries

Only StableIdentity frames and RuuviTag RAWv2 frames with a valid embedded MAC become RadioSignal('ble', ... ) and enter fingerprint matching, map-point storage and radio observations. Eddystone-URL/TLM values and Ruuvi sensor telemetry are decoded and surfaced in the scan summary; EID is labelled rotating. Metadata-only frames are not converted into synthetic stable points. EID resolution requires a future explicit resolver/provisioning design; a rotating EID must not be joined by guessed heuristics.

RSSI remains the measured receive signal strength. The protocol's calibrated TX/reference power is parsed as metadata and is not written into the existing frequency column. No position accuracy is claimed from a single RSSI sample; radio positioning still depends on known coordinates, repeated scans and the current fingerprint/anchor estimator.

BeaconDB's Bluetooth request field is MAC-based. Protocol IDs are not rewritten or sent as fake MAC addresses; only eligible public-MAC fallback observations can pass that contract.

## Storage compatibility

The current SQLite schema already keys radio points by (radio_type, identifier). This feature adds no schema migration. Previously imported BLE MAC rows remain in place; new protocol IDs create distinct, normalized keys and merge repeated observations of the same beacon ID.

## Tests and remaining validation

BleBeaconAdvertisementParserTest covers iBeacon, Eddystone UID/URL/TLM/EID, AltBeacon, SemBeacon composition, malformed/truncated payloads and address policy. Android instrumentation/field validation remains necessary to verify that target devices expose the expected manufacturer/service data, scan responses and address types under real RF conditions. Indoor location accuracy must be measured separately.

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
| AltBeacon | Bluetooth company ID + 20-byte Beacon ID | Stable BLE key |
| SemBeacon | AltBeacon identity plus Eddystone-URL-compatible scan response | Stable BLE key plus URI metadata |

SemBeacon is identified when both components appear in the same Android ScanRecord. If Android/device firmware does not deliver the scan response, the underlying AltBeacon identity is still usable.

## Identifier encoding

The database uses radio_type = 'ble' and the existing identifier text field:

- ibeacon:<uuid>:<major>:<minor>
- eddystone-uid:<namespace-hex>:<instance-hex>
- altbeacon:<company-id-hex>:<20-byte-id-hex>
- sembeacon:<company-id-hex>:<20-byte-id-hex>

Hexadecimal components are lowercase. Major/Minor are unsigned decimal integers. Manufacturer-specific MFG_RESERVED bytes and Eddystone RFU bytes do not participate in identity.

## Address fallback

The Bluetooth address is not part of iBeacon, Eddystone-UID, AltBeacon or SemBeacon identity. It is used only for generic BLE advertisers with no stable protocol frame, and only when Android API 35+ reports ADDRESS_TYPE_PUBLIC, BLUETOOTH_CONNECT is already granted, and the address bits are unicast/global. The BLE scanner does not request that permission for this fallback. RANDOM, ANONYMOUS, UNKNOWN, missing address type and malformed addresses are rejected.

Android versions before API 35 do not expose BluetoothDevice.getAddressType() publicly. On those versions generic MAC-only advertisements are deliberately not used as permanent map/position keys. Recognized protocol frames still work because their IDs are carried in the advertisement payload.

## Positioning and telemetry boundaries

Only StableIdentity frames become RadioSignal('ble', ... ) and enter fingerprint matching, map-point storage and radio observations. Eddystone-URL/TLM/EID frames are decoded but not converted into synthetic stable points. EID resolution requires a future explicit resolver/provisioning design; a rotating EID must not be joined by guessed heuristics.

RSSI remains the measured receive signal strength. The protocol's calibrated TX/reference power is parsed as metadata and is not written into the existing frequency column. No position accuracy is claimed from a single RSSI sample; radio positioning still depends on known coordinates, repeated scans and the current fingerprint/anchor estimator.

BeaconDB's Bluetooth request field is MAC-based. Protocol IDs are not rewritten or sent as fake MAC addresses; only eligible public-MAC fallback observations can pass that contract.

## Storage compatibility

The current SQLite schema already keys radio points by (radio_type, identifier). This feature adds no schema migration. Previously imported BLE MAC rows remain in place; new protocol IDs create distinct, normalized keys and merge repeated observations of the same beacon ID.

## Tests and remaining validation

BleBeaconAdvertisementParserTest covers iBeacon, Eddystone UID/URL/TLM/EID, AltBeacon, SemBeacon composition, malformed/truncated payloads and address policy. Android instrumentation/field validation remains necessary to verify that target devices expose the expected manufacturer/service data, scan responses and address types under real RF conditions. Indoor location accuracy must be measured separately.

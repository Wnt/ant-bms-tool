# ANT BMS protocols — what this pack's BMS actually speaks

**Verified on the pack 2026-09-22 (BLE name `ANT-BLE20A`, 20S): the BMS speaks ANT's *old* protocol
(`DB DB` / `5A 5A` / `A5 A5`).** It ignores the new `7E A1 … AA 55` frames completely. The ANT app 2.3.5
only uses the new protocol for passwords, parameters and controls (it has *no* old-protocol write code),
which is why nothing set from the app ever took effect. Section 1 is what works; section 2 documents the
new protocol as reverse-engineered from the app, kept for reference.

BLE transport for both: service `0000ffe0-…`, characteristic `0000ffe1-…` (write-without-response +
notify, 20-byte chunks). **The module drops the link after ~9 s without any write** — keep polling.

---

## 1. Old protocol (this BMS)

All frames are 6 bytes: `hdr hdr reg valHi valLo sum`, `sum = (reg + valHi + valLo) & 0xFF`.

| Frame | Meaning | Reply |
|---|---|---|
| `DB DB 00 00 00 00` | status poll | 140-byte status frame (below) |
| `5A 5A reg 00 00 sum` | read register `reg` | echo `5A 5A reg hi lo sum` with the value (`FFFF` = not available) |
| `A5 A5 reg hi lo sum` | write register / control | echo `A5 A5 reg hi lo sum`; for toggles the echo carries the **resulting** state (252 → `0001` = auto-balance now on) |
| `A5 A5 FF 00 00 FF` | control 255 = apply settings | echo; protections are re-evaluated immediately (this is what cleared VDiffHigh) |
| `5A 5A 5A 00 00 5A` | "2021" handshake | `5A 5A 5A 2E E6 6E` (0x2EE6 = 12006, also readable at reg 90) |

Writes take effect after control 255. (Whether they persist across a BMS power cycle is not yet verified.)

### Status frame (140 bytes, big-endian) — layout from the app's `jiexi1()`
| Offset | Field |
|---|---|
| 0–3 | `AA 55 AA FF` |
| 4–5 | pack voltage ×0.1 V |
| 6–69 | 32 × u16 cell voltage mV (cells beyond `cell_count` are 0) |
| 70–73 | current i32 ×0.1 A (**negative = charging**) |
| 74 | SOC % |
| 75–78 | physical capacity µAh (50 000 000 = 50 Ah) |
| 79–82 | remaining capacity µAh |
| 83–86 | total cycle capacity mAh |
| 87–90 | runtime s |
| 91–92 / 93–94 | MOS temp / balance-board temp (i16 °C) |
| 95–102 | 4 × i16 battery temps (−40 = no sensor) |
| 103 / 104 / 105 | charge-MOS state / discharge-MOS state / balance state (tables below) |
| 106–107 / 108–109 | "DS" voltage / discharge-MOS voltage (raw) |
| 111–114 | power i32 W |
| 115 / 116–117 | max-cell index / mV; 118 / 119–120 min-cell index / mV; 121–122 avg mV; 123 cell count |
| 132–135 | **balancing bitmap** u32: bit n−1 set = cell n is being bled (verified: 0x00001000 while #13 was balanced) |
| 124–131, 136–137 | not decoded (124–125 changes with charge state; 136–137 changes every frame) |
| 138–139 | checksum = `sum(bytes[4:138]) & 0xFFFF` |

MOS states (index → meaning; from the app's string tables): **charge** 0 Close, 1 Open, 2 CellOverV,
3 CurrentProtect, 4 BattFull, 5 TotalVExceed, 6 BattOverTemp, 7 PowerOverTemp, 8 CurrentException,
9 BalanceLoose, 10 BoardOverTemp, 12 OpenFailed, 13 DisTubeException, 14 Waiting, 15 ManualClose,
16 Lv2OverV, 17 LowTempProtect, **18 VDiffHigh**, 20 SelfDetectError.
**discharge** 0 Close, 1 Open, 2 CellLowV, 3 CurrentProtect, 4 Lv2OverCurrent, 5 AllUnderV, 6 BattOverTemp,
7 PowerOverTemp, 8 CurrentException, 9 BalanceLoose, 10 BoardOverTemp, 11 ChargeOpen, 12 ShortCircuit,
13 DisTubeException, 14 OpenFailed, 15 ManualClose, 16 Lv2LowV, 17 LowTempProtect, **18 VDiffHigh**,
19 SelfDetectError. **balance** 0 Close, 1 ExtremeBalance, 2 ChargeVDiff, 3 BalanceOverTemp, 4 AutoBalance,
5 ManualBalance.

### Register map (klotztech/VBMS wiki + values read from this BMS on 2026-09-22)
| reg | parameter | scale | value read |
|---|---|---|---|
| 1 | cell OV alarm | mV | 4250 |
| 2 | cell UV warning | mV | 3100 |
| 3 | cell OV protect | mV | 4200 |
| 4 | cell UV protect | mV | 2900 |
| 5 | cell OV recover | mV | 4100 |
| 6 | cell UV recover | mV | 3200 |
| 7 | pack OV protect | 0.1 V | 1000 (100.0 V) |
| 8 | pack UV protect | 0.1 V | 0 (off) |
| 9 / 10 | charge OC protect (0.1 A) / delay (s) | | 200 (20 A) / 5 |
| 11 / 12 | discharge OC protect (0.1 A) / delay (s) | | 1000 (100 A) / 5 |
| 13 | balance limit voltage | mV | 4200 |
| 14 | charge-balance start voltage | mV | 4100 |
| 15 | balance start difference | mV | 10 |
| 16 | balance current (index 1–20) | | 18 |
| 17 | system reference voltage | mV | 3000 |
| 18 | current sensor range | | 1650 |
| 19 | start current | 0.1 A | 25 |
| 20 / 21 | short-circuit current (A) / delay (µs) | | 200 / 800 |
| 22 | no-current auto-standby | s | 180 |
| 23 | total-voltage ADC zero | | 3505 |
| 24 | cell count | | 20 |
| 25 / 26 | charge high-temp protect / recover | °C | 60 / 55 |
| 27 / 28 | discharge high-temp protect / recover | °C | 60 / 55 |
| 29 / 30 | MOS high-temp protect / recover | °C | 75 / 70 |
| 31 / 32 | physical capacity **low / high** word | µAh | 0xF080 / 0x02FA = 50.0 Ah |
| 33–36 | remaining / cycle capacity words | | read as FFFF (not readable) |
| 37 / 38 | *(undocumented; looks like charge low-temp protect / recover)* | °C signed | −2 / 3 |
| 39 / 40 | *(undocumented; looks like discharge low-temp protect / recover)* | °C signed | −10 / −5 |
| 41 / 42 | tire length (mm) / pulses per week | | 1000 / 23 |
| 43 | *(undocumented)* | | 2400 |
| **44** | **cell voltage difference protection** — verified: raising it 1000→2000 mV + apply cleared `VDiffHigh` | mV | 1000 → **now 2000** |
| 47 | *(undocumented)* | | 35 |
| 90 | firmware/protocol id? (same as handshake reply) | | 12006 |
| 100 | runtime | s | 0 |

Controls (write with `A5 A5`): 247 shutdown, 248 zero current, 249 discharge MOS (0 off / 1 on),
250 charge MOS (0 off / 1 on), 251 LiFePO₄ mode, **252 auto-balance (toggle; echo shows new state)**,
253 factory reset, 254 reboot, **255 apply settings**.

---

## 2. New protocol (`7E A1`) — from app-service.js, for reference only (NOT spoken by this BMS)

- Frame: `7E A1 func regLo regHi len data[len] crcHi crcLo AA 55`; CRC16-Modbus over bytes 1..5+len,
  stored byte-swapped. Reads carry `len` but no data (10-byte frame). Reply func = request func + 0x10.
- func 1 = status (`7E A1 01 00 00 BE 18 55 AA 55`); reply 0x11: byte 6 = auth level, 7 state, 8 temp
  count, 9 cell count, 10–25 protect/warn bitmaps, 34.. cells u16 LE mV, temps, pack V ×0.01, current
  ×0.1, SOC, SOH, dis-MOS, chg-MOS, balance state, capacities, runtime, … (full parse: `antbms.py decode_status`).
- func 2 = read parameter block (reply 0x12, u16 LE words); pages: voltage (0,52), temp (56,44),
  current (104,32), balance (140,12), battery/capacity (152,142; reg 154 cell count, 162 capacity Ah×1e6
  u32), system (298,34), other (378,18).
- func 0x22 = write parameter (reply 0x42, result code in byte 3: 0 ok, 1 no permission, 2 below min,
  3 above max, 5 wrong password, 10 set-ok-needs-save); then control 7 (func 0x51) commits.
- func 0x23 = password (reg 330/338/346/354 for Lv1–4 8 chars, 362 Lv5 12 chars; reply 0x43 data =
  granted level). Every 0x11 status frame also reports the current auth level in byte 6.
- func 0x51 = control (reply 0x61, result u16: 1 ok, 2 failed, 3 no permission): 13 auto-balance on,
  14 off, 6/4 charge MOS on/off, 3/1 discharge MOS on/off, 7 save, 9 reset, 12 factory reset.

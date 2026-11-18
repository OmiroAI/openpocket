# Recorder storage accounting

Pocket's [published specifications](https://heypocket.com/pages/home) list **64 GB
onboard storage** (checked 2026-11-18). That is nominal hardware capacity, not a
value to hardcode as the denominator for available recording space.

## Command and units

`APP&SPACE` returns `MCU&SPA&<free_mib>&<total_mib>`. Both values are whole MiB:
1 MiB = 1,048,576 bytes. The storage calculation multiplies filesystem block size
by the free or total block count, then divides by 2^20 before formatting the reply.
The free field precedes the total field. Integer values have MiB-level precision.

Validate nonnegative free, positive total, free <= total, and safe conversion to
bytes. Used space is total minus free. Percentage is used / total × 100. Display
byte amounts as decimal MB (1,000,000 bytes) and GB (1,000,000,000 bytes), consistently
with the rest of the Android UI. Do not label the protocol fields as KB or decimal
MB; the earlier KB/59.6 MB partition interpretation was incorrect.

For example, 1,024 MiB used out of 60,000 MiB is approximately 1.7% full.

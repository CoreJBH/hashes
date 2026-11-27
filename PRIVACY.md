# Privacy

Hashes keeps the mining session on the phone. It has no account, no analytics and
no server of its own, and it never asks for a private key or a seed phrase.

## What leaves the phone

- **The pool you chose.** The app opens exactly one connection, to the Stratum V1
  endpoint selected in `POOL & PAYOUT` (TLS where the pool offers it), and speaks
  the Stratum protocol there. The login line carries your payout address and the
  worker name `<address>.hashes`, because that is how a solo pool knows where to
  pay a block; the password field carries the pool's conventional `x`.
- **Nothing else.** There is no telemetry, no crash reporting, no advertising SDK
  and no Google Play Services in the app or in its dependency set.

## What stays on the phone

- **The payout address** you typed or scanned, the chosen pool endpoint and the
  TURBO preference, in the app's own preferences file.
- **Your best hashes** (session and all-time), so the terminal can show the best
  work this device has produced across restarts.
- **Nothing to spend.** The app holds no wallet, no key and no seed. A payout
  address is a destination, not a credential: the pool pays it in the coinbase of
  any block found, and the coins are spendable only with a wallet you control
  elsewhere.

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET` | The Stratum connection to the pool you selected |
| `CAMERA` | Only while the QR scanner is open, to read a payout address from a QR code; never in the background, and no image is stored or sent |
| `FOREGROUND_SERVICE` | A running session keeps going while the app is not on screen; Android only allows that as a foreground service |
| `FOREGROUND_SERVICE_DATA_SYNC` | The type that session runs under: an ongoing data connection, which is exactly what the pool link is. It is a normal type, not a special-use declaration |
| `POST_NOTIFICATIONS` | The status-bar entry a foreground service must show: the live hash rate, the mode and a `STOP` action (Android 13+, asked at the first start) |

The app does not ask for `ACCESS_NETWORK_STATE`: it has no use for the network
state, because a pool that is unreachable simply fails the connection and the
status line reports that failure.

The manifest declares the camera as not required, so the app installs on a device
without one and the address can still be typed or pasted.

## Background

Mining continues when you leave the terminal, and the notification in the status
bar is how you can tell. The session runs as a `dataSync` foreground service: the
notification shows `COOL` or `TURBO`, the pool link, the live hash rate and the
block being hashed, and it carries a `STOP` action that ends the session at once.
Nothing else runs in the background, there is no scheduled work, no wake lock and
no sensor.

It stops when you press `[ STOP / START MINER ]`, when you press `STOP` in the
notification, or when you swipe the app out of the recent-tasks list. If Android
itself ends the foreground service - for example the platform's daily budget for
`dataSync` services - the session stops with it rather than lingering half-alive,
and nothing reconnects on its own until you start it again.

Declining the notification permission on Android 13+ does not stop the service:
the session still runs, the status-bar entry is simply not drawn, and the running
service remains visible in the system's own task manager.

## Removing your data

Uninstalling the app, or clearing its storage in Android settings, deletes the
saved address, the endpoint and the best-share records. Nothing is kept anywhere
else.

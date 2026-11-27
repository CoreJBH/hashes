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

Those are the only two. The app does not ask for `ACCESS_NETWORK_STATE`: it has
no use for the network state, because a pool that is unreachable simply fails the
connection and the status line reports that failure.

The manifest declares the camera as not required, so the app installs on a device
without one and the address can still be typed or pasted.

## Background

Nothing runs in the background. The session starts when you press
`[ START / STOP MINER ]`, keeps the screen awake while it runs, and stops when the
app leaves the screen; no foreground service, no notification, no work behind your
back.

## Removing your data

Uninstalling the app, or clearing its storage in Android settings, deletes the
saved address, the endpoint and the best-share records. Nothing is kept anywhere
else.

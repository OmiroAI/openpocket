# Recording sync workflow

This is the recorder-side workflow. Agent endpoint, authentication, scheduling, retry policy, and Android user flows await product requirements.

1. Connect over BLE and subscribe to control and media notifications.
2. Establish device binding if needed, read battery and recording state, and synchronize the recorder clock when authorized by the product flow.
3. Enumerate directories and files. Compare them with a durable local sync record; treat names and sizes as untrusted input.
4. Select BLE transfer or, when battery and network state allow, the Wi-Fi SoftAP transfer described in [recorder.md](recorder.md). Preserve the phone's previous network state.
5. Stage each recording locally. Accept it only after the announced byte count and media integrity checks pass. An interrupted or short transfer remains pending.
6. Restore the prior phone network and power down the recorder Wi-Fi module after any Wi-Fi attempt.
7. Deliver an accepted recording to the configured self-hosted agent once that contract is specified. Mark sync complete only after the agent confirms durable receipt. Do not delete recorder media as part of this workflow.

## Failure boundaries

- BLE may disconnect while a transfer is active; resume or retry only according to a verified offset contract.
- The Wi-Fi TCP listener handles one client session per module boot. Reinitialize it for another file.
- The SoftAP has no internet gateway, so a phone with one Wi-Fi radio may need to switch back to its previous network before upload.
- A response such as `MCU&REC&ERR`, `MCU&DISK&ERR`, or `MCU&TIMEOUT` needs a visible failure state and safe retry decision.
- The transfer path must never log keys, passwords, raw recordings, or agent credentials.

## Bench verification still needed

Verify pairing, listing, BLE boundary handling, Wi-Fi cleanup, network restoration, short-transfer detection, and duplicate prevention on the target firmware and Android versions. Offline QA cannot establish radio or recorder behavior.

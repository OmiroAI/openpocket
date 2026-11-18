# Open Pocket

Use the Pocket AI recorder you love without a subscription. Open Pocket brings recordings onto your Android phone and sends them to your own server, where your agent can turn them into transcripts and notes.

![Pocket recorder → Android phone → your server → your agent](docs/assets/workflow.svg)

**Development preview.** The hardware test target is Pixel 7 Pro. The app requires Android 13 or later, a compatible Pocket recorder and its binding key. Keep the app open while syncing.

1. **Connect Pocket** in Settings. New recordings download automatically over Wi-Fi or Bluetooth. You can play phone copies directly in the app.
2. **Connect your server** with its private HTTPS address and upload token. Open Pocket retries interrupted deliveries and keeps local audio until the server confirms receipt.
3. **Open Delivered** to see recordings accepted by your server. An optional transcription worker and Hermes integration support notes and follow-up in your agent's own interface.

### Try the Android build

Open [APK builds](https://github.com/OmiroAI/openpocket/actions/workflows/build.yml), select a successful run for the revision you want, and download **open-pocket-debug-apk** under **Artifacts**. Sign in to GitHub to download. Unzip the archive and verify it with `sha256sum -c SHA256SUMS` before sideloading the APK. CI preview builds use temporary debug signing keys. Android may reject an update from a different signing key. Uninstalling erases app-private recordings and settings.

For local development, run `./android/gradlew -p android assembleDebug testDebugUnitTest` with JDK 17 and Android SDK 35.

### Set up your own destination

Start with the [setup guide](docs/setup.md). Read the [release checklist](docs/product/release-readiness.md) for current verification limits, or browse the [technical documentation](docs/README.md) for protocol and integration details.

[MIT License](LICENSE)

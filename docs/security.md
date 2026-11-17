# Local data and credentials

Never commit recorder binding keys, Wi-Fi passwords, agent tokens, signing keys, logs containing those values, or audio pulled from a recorder. Use synthetic metadata and generated test audio when tests need fixtures. Review the staged diff before every commit. Store credentials using platform facilities once the Android design is agreed.

Sync should preserve the original recording until successful delivery is verified. Any later deletion behavior needs an explicit product requirement and a user-facing confirmation.

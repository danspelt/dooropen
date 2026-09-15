# Project Rules

## Required workflow

- After every code or configuration change, run the relevant focused checks.
- Before considering any task complete, run the full verification suite: `./gradlew test`, `./gradlew lint`, and `./gradlew assembleDebug` (use `.\gradlew.bat` on Windows).
- Only when every required check passes, review the diff for secrets and unintended changes, then commit and push the current branch.
- Never push changes when any required check fails. Fix the failure and rerun the complete verification suite first.
- Do not commit secrets, `.env` files, credentials, API keys, or production data.

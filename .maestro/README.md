# Maestro

Maestro tests account sign-in, session verification and navigation across Element X.
Install the CLI using the [Maestro documentation](https://docs.maestro.dev/).

<!--- TOC -->

* [Run test](#run-test)
  * [Output](#output)
* [Write test](#write-test)
* [CI](#ci)
  * [Account settings](#account-settings)
  * [Run and troubleshoot](#run-and-troubleshoot)
* [iOS](#ios)
* [Future](#future)

<!--- END -->

## Run test

Run these commands in Bash from the repository root, with a debug APK installed on an emulator or device.

Use an existing dedicated test account on a Matrix Authentication Service (MAS) provider, such as matrix.org. Enable recovery for that account before running the suite, and supply its current recovery key. The account must have joined the configured test room and be able to send messages and polls there. The suite also creates temporary rooms and invites the two configured test users; use accounts and a room intended for this testing.

```bash
export MAESTRO_APP_ID=io.element.android.x.debug
export MAESTRO_USERNAME='@test-runner:matrix.org'
export MAESTRO_ROOM_NAME='Maestro test room'
export MAESTRO_INVITEE1_MXID='@test-peer-1:matrix.org'
export MAESTRO_INVITEE2_MXID='@test-peer-2:matrix.org'
read -r -s -p 'Test-account password: ' MAESTRO_PASSWORD; echo
read -r -s -p 'Test-account recovery key: ' MAESTRO_RECOVERY_KEY; echo
export MAESTRO_PASSWORD MAESTRO_RECOVERY_KEY
source .github/workflows/scripts/maestro/prepare-env.sh
maestro test .maestro/allTests.yaml
```

The preparation script checks missing or empty settings and converts a full Matrix user ID into the username and account provider used by the MAS login form. A username without a provider defaults to matrix.org; set `MAESTRO_HOMESERVER` to use another MAS-backed provider. The suite currently tests password authentication, not SSO or CAPTCHA flows.

### Output

Maestro prints the result on the console and writes diagnostic files under `~/.maestro/tests`. Explicit screenshots use `build/maestro` names. Raw local diagnostics can contain entered credentials; use the CI sanitization script before sharing text diagnostics. CI uploads only redacted text and the suite's explicit screenshots, excluding recordings and automatic failure screenshots.

## Write test

Tests are yaml files. Generally each yaml file should leave the app in the same screen than at the beginning.

Start the Element X app and run this command to help writing test.

```shell
maestro studio
```

Note that sometimes, this prevent running the test. So kill the `maestro studio` process to be able to run the test again.

Also, if updating the application code, do not forget to deploy again the application before running the maestro tests.

## CI

The [Maestro (local) workflow](../.github/workflows/maestro-local.yml) builds the debug APK and runs the suite on a GitHub-hosted Android emulator. It does not use Maestro Cloud or need a Maestro Cloud API key. A configuration job checks required settings before building the APK. Runs sharing the test account are queued to avoid interfering with each other.

### Account settings

In the repository's **Settings → Secrets and variables → Actions**, add the following settings. Passwords and recovery keys must be **Secrets**. The other account settings can be **Variables** or **Secrets**; a same-named secret takes precedence.

| Name | Storage | Required value |
| --- | --- | --- |
| `MAESTRO_USERNAME` | Variable or secret | Test-account username or full `@user:server` Matrix ID. |
| `MAESTRO_PASSWORD` | Secret | Password belonging to that same account. |
| `MAESTRO_RECOVERY_KEY` | Secret | Current recovery key for that same account. |
| `MAESTRO_ROOM_NAME` | Variable or secret | Exact displayed name of a joined, writable test room. |
| `MAESTRO_INVITEE1_MXID` | Variable or secret | Full Matrix ID of the first test user to invite. |
| `MAESTRO_INVITEE2_MXID` | Variable or secret | Full Matrix ID of a different test user to invite. |
| `MAESTRO_HOMESERVER` | Variable or secret, optional | MAS-backed account provider. Derived from a full username, otherwise matrix.org. |
| `MAESTRO_ENVIRONMENT` | Repository variable, optional | Name of the Actions environment containing these settings; defaults to `maestro`. |
| `MAESTRO_CLEANUP_SESSIONS` | Variable, optional | Set to `true` only for a dedicated test account to terminate **all** its remaining MAS sessions after testing. Defaults to `false` on forks. |

Existing `MATRIX_MAESTRO_ACCOUNT_PASSWORD` and `MATRIX_MAESTRO_ACCOUNT_RECOVERY_KEY` secrets remain accepted as fallbacks. `MATRIX_MAESTRO_ACCOUNT_USERNAME` is also accepted as a username alias. Room and invitee settings also accept the `MATRIX_MAESTRO_ACCOUNT_` or `MATRIX_MAESTRO_` prefix with the same suffix (`ROOM_NAME`, `INVITEE1_MXID`, `INVITEE2_MXID`). Prefer the `MAESTRO_*` names for new configuration. CI fixes `MAESTRO_APP_ID` to the debug APK it builds.

If you added the settings under **Settings → Environments**, the jobs must select that exact environment. Set the repository variable `MAESTRO_ENVIRONMENT`, or choose the environment when manually running the workflow. Repository-level settings work without any environment-specific values. Existing environment protection rules still apply.

Forks must configure their own account, room and invitees. The upstream repository retains its existing test-account defaults. The suite signs out its own session on success; broad session cleanup is optional on forks and uses the same resolved username, provider and password as the test.

### Run and troubleshoot

Open **Actions → Maestro (local) → Run workflow**, select the branch containing the changes, optionally select the configured environment, and run it. Pull requests whose head is in the same repository also run automatically. Pull requests from another repository are skipped because their secrets are unavailable.

* **Check Maestro configuration fails:** the log names each missing or empty setting without printing its value. Check the exact names and selected environment. Passwords and recovery keys stored only as Variables are not read.
* **Invalid credentials:** the MAS provider rejected the configured username/password pair. Check that both settings belong to the same test account and provider. This happens before recovery-key verification.
* **Device verification fails:** confirm recovery was already enabled and the configured recovery key belongs to the account that just signed in.
* **Room or invitee is not found:** check the exact displayed room name, room membership and full Matrix IDs. The suite needs two other test accounts.
* **Inspecting a failed run:** download `test-results` for redacted text diagnostics and explicit screenshots. Raw recordings and automatic failure screenshots are excluded because text redaction cannot safely redact images.

Re-run on the branch containing a workflow fix. Re-running an older workflow execution continues to use that execution's original code, even when account secrets have since been updated.

## iOS

Need to install `idb-companion` first

```shell
brew install idb-companion
```

Also:
https://github.com/mobile-dev-inc/maestro/issues/146
https://github.com/mobile-dev-inc/maestro/issues/107
So you have to change your input keyboard to QWERTY for it to work properly.

## Future

- run on Element X iOS. This is already working but it need some change on the test to make it works. Could pass a PLATFORM parameter to have unique test and use conditional test.
- run specific test on both iOS and Android devices to make them communicate together. Could be possible to test room invite and join, verification, call, etc. To be done when Element X will be able to create account and create room. A main script would be able to detect the Android device and the iOS device, and run several maestro tests sequentially, using `--device` parameter to perform a global test.

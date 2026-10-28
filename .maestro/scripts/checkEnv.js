// Keep this list in sync with the environment variables required by the test suite.
// Report variable names only; never include credentials in validation errors.

function requireEnv(name, value) {
    if (value == null || String(value).trim() === "") {
        throw "Fatal: missing or empty env variable " + name
    }
}

requireEnv("MAESTRO_APP_ID", typeof MAESTRO_APP_ID === "undefined" ? null : MAESTRO_APP_ID)
requireEnv("MAESTRO_USERNAME", typeof MAESTRO_USERNAME === "undefined" ? null : MAESTRO_USERNAME)
requireEnv("MAESTRO_PASSWORD", typeof MAESTRO_PASSWORD === "undefined" ? null : MAESTRO_PASSWORD)
requireEnv("MAESTRO_RECOVERY_KEY", typeof MAESTRO_RECOVERY_KEY === "undefined" ? null : MAESTRO_RECOVERY_KEY)
requireEnv("MAESTRO_ROOM_NAME", typeof MAESTRO_ROOM_NAME === "undefined" ? null : MAESTRO_ROOM_NAME)
requireEnv("MAESTRO_INVITEE1_MXID", typeof MAESTRO_INVITEE1_MXID === "undefined" ? null : MAESTRO_INVITEE1_MXID)
requireEnv("MAESTRO_INVITEE2_MXID", typeof MAESTRO_INVITEE2_MXID === "undefined" ? null : MAESTRO_INVITEE2_MXID)

if (typeof MAESTRO_LOCATION_SHARING_ENABLED !== "undefined" &&
    MAESTRO_LOCATION_SHARING_ENABLED !== "true" && MAESTRO_LOCATION_SHARING_ENABLED !== "false") {
    throw "Fatal: MAESTRO_LOCATION_SHARING_ENABLED must be true or false"
}

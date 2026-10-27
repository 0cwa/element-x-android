#!/usr/bin/env bash

# Copyright (c) 2026 Element Creations Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only.
# Please see LICENSE in the repository root for full details.

# This file can also be sourced before a local run. Never print configuration values:
# the same settings include the account password and recovery key.
prepare_maestro_environment() {
    local name value invalid=0
    local required=(
        MAESTRO_APP_ID MAESTRO_USERNAME MAESTRO_PASSWORD MAESTRO_RECOVERY_KEY
        MAESTRO_ROOM_NAME MAESTRO_INVITEE1_MXID MAESTRO_INVITEE2_MXID
    )
    for name in "${required[@]}"; do
        value=${!name-}
        if [[ -z "${value//[[:space:]]/}" ]]; then
            printf 'Missing required Maestro setting: %s\n' "$name" >&2
            invalid=1
        fi
    done
    [[ "$invalid" == 0 ]] || return 1

    # MAS expects a localpart. Allow the more familiar complete Matrix ID as input,
    # deriving the account provider unless one has explicitly been configured.
    if [[ "$MAESTRO_USERNAME" == @* ]]; then
        if [[ "$MAESTRO_USERNAME" =~ ^@([^:]+):(.+)$ ]]; then
            MAESTRO_USERNAME=${BASH_REMATCH[1]}
            MAESTRO_HOMESERVER=${MAESTRO_HOMESERVER:-${BASH_REMATCH[2]}}
        else
            printf 'MAESTRO_USERNAME must be a localpart or a complete @user:server Matrix ID.\n' >&2
            return 1
        fi
    fi
    MAESTRO_HOMESERVER=${MAESTRO_HOMESERVER:-matrix.org}
    MAESTRO_CLEANUP_SESSIONS=${MAESTRO_CLEANUP_SESSIONS:-false}
    if [[ "$MAESTRO_CLEANUP_SESSIONS" != true && "$MAESTRO_CLEANUP_SESSIONS" != false ]]; then
        printf 'MAESTRO_CLEANUP_SESSIONS must be true or false.\n' >&2
        return 1
    fi

    # Reject multiline settings before writing anything to GITHUB_ENV. This prevents
    # a pasted value from becoming another environment assignment, and inputText
    # expects these credentials and names to be entered on one line.
    local settings=("${required[@]}" MAESTRO_HOMESERVER MAESTRO_CLEANUP_SESSIONS)
    for name in "${settings[@]}"; do
        value=${!name}
        if [[ "$value" == *$'\n'* || "$value" == *$'\r'* ]]; then
            printf 'Maestro setting must be a single line: %s\n' "$name" >&2
            return 1
        fi
        if [[ -z "${value//[[:space:]]/}" ]]; then
            printf 'Missing required Maestro setting: %s\n' "$name" >&2
            return 1
        fi
    done
    for name in "${settings[@]}"; do
        export "$name"
        if [[ -n "${GITHUB_ENV:-}" ]]; then
            printf '%s=%s\n' "$name" "${!name}" >> "$GITHUB_ENV" || return 1
        fi
    done
    printf 'Maestro configuration is complete.\n'
}

prepare_maestro_environment

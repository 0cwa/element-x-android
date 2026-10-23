/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.widget

import org.json.JSONObject

/** Installed at document start, before a generic widget can send its first Widget API request. */
internal fun widgetMessageBridgeScript(widgetOrigin: String): String = """
    (() => {
        if (window !== window.top) return;
        window.addEventListener('message', function(event) {
            if (event.source !== window || event.origin !== ${JSONObject.quote(widgetOrigin)}) return;
            const data = event.data;
            if (!data || typeof data !== 'object' || Array.isArray(data)) return;
            const isResponse = Object.prototype.hasOwnProperty.call(data, 'response');
            if (data.api === 'fromWidget' && !isResponse || data.api === 'toWidget' && isResponse) {
                ${WebViewWidgetMessageInterceptor.LISTENER_NAME}.postMessage(JSON.stringify(data));
            }
        });
    })();
""".trimIndent()

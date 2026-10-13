// SPDX-License-Identifier: GPL-3.0-or-later
package org.morlune.engine

/**
 * Supplies secrets that must not live in a provider file, for example a signed access token.
 * A provider refers to one as `{token.NAME}` in a URL or header; the app decides how it is made.
 * Throw [ProviderException] with a readable reason if the token cannot be made (missing credentials, bad key...).
 */
fun interface TokenSource {
    fun token(name: String): String
}

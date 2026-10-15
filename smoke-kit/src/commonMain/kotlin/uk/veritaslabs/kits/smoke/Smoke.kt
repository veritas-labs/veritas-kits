package uk.veritaslabs.kits.smoke

import kotlinx.coroutines.delay

/** Throwaway public API proving a kit publishes and a consumer resolves it. */
public object Smoke {
    public fun greet(name: String): String = "hello, $name"

    public suspend fun greetLater(name: String): String {
        delay(1)
        return greet(name)
    }
}

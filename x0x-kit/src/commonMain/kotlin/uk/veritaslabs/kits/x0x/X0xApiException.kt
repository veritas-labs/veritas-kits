package uk.veritaslabs.kits.x0x

/** An x0x daemon REST call returned an error status, or the daemon could not be reached at all (status 0). */
public class X0xApiException(public val status: Int, message: String) : Exception(message)

package uk.veritaslabs.kits

/**
 * Marks a declaration that is public only because another kit (or an app's compatibility tests) must reach it. Apps
 * must not use it: it can change or disappear in any release. Using it without opting in is a compile error.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is internal to the kits and may change without notice.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
public annotation class KitsInternalApi

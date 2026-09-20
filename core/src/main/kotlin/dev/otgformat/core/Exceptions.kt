package dev.otgformat.core

/**
 * Raised when a format cannot be performed *before* anything is written.
 *
 * Every geometry rule this project enforces produces one of these rather than
 * an invalid volume: refusing loudly is always better than writing something
 * that mounts on one machine and corrupts on another.
 */
class FormatException(message: String) : Exception(message)

/**
 * Raised when data read back from the device does not match what was written.
 *
 * This means the medium lied about a successful write — a counterfeit-capacity
 * stick, a failing controller, or a cable dropping transfers. The volume must
 * be treated as unusable.
 */
class VerificationException(message: String) : Exception(message)

/** Raised when a caller cancels via [Progress.isCancelled]. */
class FormatCancelledException : Exception("format cancelled")

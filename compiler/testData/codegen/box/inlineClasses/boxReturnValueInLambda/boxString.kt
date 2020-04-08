// IGNORE_BACKEND_FIR: JVM_IR
// IGNORE_BACKEND: JVM_IR

inline class X(val x: String)

fun useX(x: X): String = x.x

fun <T> call(fn: () -> T) = fn()

fun box() = useX(call { X("OK") })
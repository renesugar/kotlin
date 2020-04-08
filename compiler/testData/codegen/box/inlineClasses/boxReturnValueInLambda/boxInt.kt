// IGNORE_BACKEND_FIR: JVM_IR
// IGNORE_BACKEND: JVM_IR

inline class X(val x: Int)

fun useX(x: X): String = if (x.x == 42) "OK" else "fail: $x"

fun <T> call(fn: () -> T) = fn()

fun box() = useX(call { X(42) })